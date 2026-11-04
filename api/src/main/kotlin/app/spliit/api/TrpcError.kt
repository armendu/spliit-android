package app.spliit.api

public sealed class TrpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

public class TrpcServerError(
    public val code: String?,
    override val message: String,
    public val httpStatus: Int?,
    public val path: String?,
) : TrpcException(message) {
    // The message check separates a missing route from a missing resource ("Group not found." is also NOT_FOUND).
    // tRPC words it two ways depending on which layer misses the route; older servers may send either.
    public val isUnknownProcedure: Boolean
        get() = code == "NOT_FOUND" &&
            (message.contains("No procedure found", ignoreCase = true) || CALL_PROCEDURE_MISS.containsMatchIn(message))

    private companion object {
        private val CALL_PROCEDURE_MISS = Regex("""No "\w+"-procedure on path""")
    }
}

public sealed class TrpcClientError(message: String, cause: Throwable? = null) : TrpcException(message, cause) {
    public class InvalidBaseUrl(public val url: String) :
        TrpcClientError("\"$url\" isn't a valid Spliit address.")

    public class Network(public val reason: String, cause: Throwable? = null) :
        TrpcClientError("Couldn't reach the server: $reason", cause)

    // Release builds allow https only; without this the platform's own wording reaches the screen.
    public class CleartextBlocked(public val host: String, cause: Throwable? = null) :
        TrpcClientError("$host only answers over http, and this app connects over https only.", cause)

    // A well-formed tRPC answer whose payload doesn't match the model. A page that isn't tRPC at all
    // (a captive portal, a proxy) is UnexpectedResponse instead.
    public class Decoding(public val reason: String, cause: Throwable? = null) :
        TrpcClientError("The server's response couldn't be read. It may be running a different version.", cause)

    // An instance without S3 answers the document route with 500 and an empty body: no documents, not a retry.
    public class UnexpectedResponse(public val status: Int, public val bodyPrefix: String) :
        TrpcClientError("Unexpected response ($status)")

    internal companion object {
        internal const val BODY_PREFIX_LIMIT: Int = 512
    }
}
