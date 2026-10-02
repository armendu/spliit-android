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

public class TrpcClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = SHARED_HTTP_CLIENT,
) {
    // Parsed lazily so a malformed user-entered address is a TrpcClientError, not a crash.
    private val baseHttpUrl: HttpUrl? by lazy { baseUrl.toHttpUrlOrNull() }

    public suspend fun <I, O> call(procedure: TrpcProcedure<I, O>): O = withContext(Dispatchers.IO) {
        val request = buildRequest(procedure)

        // IOException only: catching Exception would report a cancellation as "unreachable".
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

    internal fun <I, O> buildRequest(procedure: TrpcProcedure<I, O>): Request {
        val base = baseHttpUrl ?: throw TrpcClientError.InvalidBaseUrl(baseUrl)
        val basePath = base.encodedPath
        val trailingSlashedPath = if (basePath.endsWith("/")) basePath else "$basePath/"
        val urlBuilder = base.newBuilder()
            .encodedPath(trailingSlashedPath + "api/trpc/" + procedure.path)

        val requestBuilder = Request.Builder().header("Accept", "application/json")

        return when (procedure.kind) {
            TrpcProcedure.Kind.Query -> {
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

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Already cancelled: falling through makes the coroutine throw CancellationException.
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

        // Shared so switching instances doesn't leak a connection pool per call.
        private val SHARED_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            // A cached GET would quietly serve stale balances.
            .cache(null)
            // Redirects are followed (OkHttp default): a 301/302 turns a POST into a GET. Pinned by TrpcClientTest.
            .build()
    }
}

// Escapes everything outside A-Za-z0-9-._~: a literal `+` would be read as a space and corrupt the envelope.
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
