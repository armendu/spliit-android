package app.spliit.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to a Spliit instance's tRPC endpoint at `{baseUrl}api/trpc/{procedure.path}`.
 *
 * Requests are sent unbatched: a [TrpcProcedure.Kind.Query] as `GET`, with the superjson
 * envelope in an `input` query parameter; a [TrpcProcedure.Kind.Mutation] as `POST`, with the
 * envelope as the body. The server accepts both unbatched, so there is no need to implement
 * tRPC's batching protocol.
 */
public class TrpcClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = SHARED_HTTP_CLIENT,
) {

    /**
     * [HttpUrl] normalizes an address with or without its trailing slash into a root path of `/`,
     * so both produce one slash before `api/trpc`. Parsed lazily, not in `init`: a malformed
     * address is user input, so it surfaces as a [TrpcClientError] rather than a crash.
     */
    private val baseHttpUrl: HttpUrl? by lazy { baseUrl.toHttpUrlOrNull() }

    public suspend fun <I, O> call(procedure: TrpcProcedure<I, O>): O = withContext(Dispatchers.IO) {
        val request = buildRequest(procedure)

        // IOException, not a broader Exception: coroutines signal cancellation with a
        // CancellationException that does not extend it, and a wider catch would report a
        // cancelled search keystroke as the server being unreachable.
        val response = try {
            execute(request)
        } catch (cause: IOException) {
            throw TrpcClientError.Network(cause.message ?: cause::class.simpleName ?: "unknown error")
        }

        response.use { resp ->
            val body = try {
                resp.body.string()
            } catch (cause: IOException) {
                throw TrpcClientError.Network(cause.message ?: "failed reading the response body")
            }

            if (resp.isSuccessful) {
                return@withContext try {
                    SuperJson.decodeResponse(procedure.outputSerializer, body)
                } catch (cause: SerializationException) {
                    // A 2xx that doesn't decode is most likely a version mismatch against a
                    // self-hosted instance, not a bug worth crashing over, the same reasoning as
                    // the iOS client's `.decoding` case.
                    throw TrpcClientError.Decoding(cause.message ?: "malformed response body")
                }
            }

            val serverError = SuperJson.decodeError(body)
            if (serverError != null) {
                throw TrpcServerError(
                    code = serverError.code,
                    message = serverError.message,
                    httpStatus = serverError.httpStatus ?: resp.code,
                    path = serverError.path,
                )
            }

            throw TrpcClientError.UnexpectedResponse(
                status = resp.code,
                bodyPrefix = body.take(TrpcClientError.BODY_PREFIX_LIMIT),
            )
        }
    }

    /**
     * Builds the request without sending it, so a test can assert on the URL and body directly -
     * no server, no coroutine, no parsing a response back out.
     */
    internal fun <I, O> buildRequest(procedure: TrpcProcedure<I, O>): Request {
        val base = baseHttpUrl ?: throw TrpcClientError.InvalidBaseUrl(baseUrl)
        val basePath = base.encodedPath
        val trailingSlashedPath = if (basePath.endsWith("/")) basePath else "$basePath/"
        val urlBuilder = base.newBuilder()
            .encodedPath(trailingSlashedPath + "api/trpc/" + procedure.path)

        val requestBuilder = Request.Builder().header("Accept", "application/json")

        return when (procedure.kind) {
            TrpcProcedure.Kind.Query -> {
                // NoInput is recognized by reference: a procedure taking no input sends no
                // `input` parameter at all, rather than an encoded null.
                if (procedure.input !== NoInput) {
                    val envelope = SuperJson.encodeEnvelope(procedure.inputSerializer, procedure.input)
                    urlBuilder.encodedQuery("input=" + percentEncodeQueryValue(envelope))
                }
                requestBuilder.url(urlBuilder.build()).get().build()
            }

            TrpcProcedure.Kind.Mutation -> {
                val envelope = SuperJson.encodeEnvelope(procedure.inputSerializer, procedure.input)
                requestBuilder
                    .url(urlBuilder.build())
                    .header("Content-Type", "application/json")
                    .post(envelope.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
        }
    }

    /**
     * Suspends until OkHttp's own async call finishes, cancelling the underlying [Call] when the
     * coroutine is cancelled rather than leaving it to run to completion in the background.
     */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // invokeOnCancellation already moved the continuation to a cancelled state, so
                // resuming here is a no-op at best and a crash at worst. Falling through is what
                // makes the coroutine throw CancellationException rather than a network error.
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }

    public companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Shared so switching Spliit instances doesn't leak a connection pool (and its threads)
        // per call, the same reasoning as URLSession.shared on the iOS side.
        private val SHARED_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            // URLSession's 60s default read to a person as the app having hung; failing sooner
            // lets the screen offer a retry while the attempt is still something they remember
            // making.
            .callTimeout(20, TimeUnit.SECONDS)
            // A cached GET would quietly serve stale balances.
            .cache(null)
            // followRedirects is OkHttp's default, inherited rather than chosen: a 301/302 turns
            // a POST into a GET, so a redirecting proxy can drop a mutation's body and still
            // answer 200. TrpcClientTest pins the behaviour; left alone until a deployment needs it.
            .build()
    }
}

/**
 * Percent-encodes [value], escaping everything outside the unreserved set `A-Za-z0-9-._~`.
 *
 * The envelope is JSON, which is full of characters meaningful in a query string. `+` is the
 * sharp edge: left alone, the `URLSearchParams`-style decoder Next.js uses reads it as a space
 * and corrupts the envelope, with no error the server can blame on it.
 */
internal fun percentEncodeQueryValue(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val unsigned = byte.toInt() and 0xFF
        val char = unsigned.toChar()
        val isUnreserved = unsigned < 128 &&
            (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '.' || char == '_' || char == '~')
        if (isUnreserved) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(HEX_DIGITS[(unsigned shr 4) and 0xF])
            builder.append(HEX_DIGITS[unsigned and 0xF])
        }
    }
    return builder.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"
