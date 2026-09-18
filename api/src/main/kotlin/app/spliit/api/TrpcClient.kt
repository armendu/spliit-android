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
     * A group stores its instance as e.g. `https://spliit.app/`, but a user may type one without
     * the trailing slash. [HttpUrl] normalizes either into a root path of `/`, so both end up
     * producing exactly one slash before `api/trpc` without this class having to special-case it.
     *
     * Parsed lazily rather than in `init`: a malformed [baseUrl] is user input (someone typing a
     * self-hosted address, not a programmer error), so it surfaces as [TrpcClientError] from a
     * call — the same place every other failure in this client shows up — rather than as a crash
     * at construction time.
     */
    private val baseHttpUrl: HttpUrl? by lazy { baseUrl.toHttpUrlOrNull() }

    public suspend fun <I, O> call(procedure: TrpcProcedure<I, O>): O = withContext(Dispatchers.IO) {
        val request = buildRequest(procedure)

        // IOException here is a real network failure (DNS, TLS, a dropped connection, a
        // timeout) — deliberately caught as IOException rather than a broader Exception, because
        // kotlinx.coroutines signals cancellation as a CancellationException that does NOT
        // extend IOException. A wider catch would swallow a cancelled search-field keystroke and
        // report it as the server being unreachable, which is exactly the bug this client must
        // not repeat.
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
                    // self-hosted instance, not a bug worth crashing over — the same reasoning as
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
     * Builds the request without sending it, so a test can assert on the URL and body directly —
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
                // A cancellation races OkHttp's own callback: invokeOnCancellation above already
                // moved the continuation to a cancelled state, so resuming it here (even with a
                // failure) would be a no-op at best and a crash at worst. Letting it fall through
                // is what makes the coroutine throw CancellationException at the suspension
                // point instead of us reporting the cancellation as a network error.
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
        // per call — the same reasoning as URLSession.shared on the iOS side.
        private val SHARED_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            // URLSession's 60s default read to a person as the app having hung; failing sooner
            // lets the screen offer a retry while the attempt is still something they remember
            // making.
            .callTimeout(20, TimeUnit.SECONDS)
            // A cached GET would quietly serve stale balances.
            .cache(null)
            // followRedirects is left at OkHttp's default (true), inherited rather than chosen:
            // a 301/302 turns a POST into a GET per HTTP semantics (URLSession does the same), so
            // a redirecting proxy can silently drop a mutation's body and still answer 200 — the
            // caller sees success for a write that never happened. See
            // `TrpcClientTest`'s pinning test for the exact behaviour. Left alone until a real
            // deployment needs disabling it, rather than guessed at here.
            .build()
    }
}

/**
 * Percent-encodes [value] for use as a query parameter's value, escaping everything outside the
 * unreserved set `A-Za-z0-9-._~`.
 *
 * [HttpUrl.Builder.encodedQuery] is deliberately not trusted to do this itself: the envelope is
 * JSON, and JSON is full of characters — `+`, `&`, `{`, `"` — that are meaningful in a query
 * string. `+` is the sharp edge: left alone, a downstream `URLSearchParams`-style decoder (which
 * is what Spliit's Next.js server uses) reads it as a space, silently corrupting the envelope
 * with no error the server can even blame it for.
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
