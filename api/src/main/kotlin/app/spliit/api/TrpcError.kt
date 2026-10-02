package app.spliit.api

public sealed class TrpcException(message: String) : Exception(message)

public class TrpcServerError(
    public val code: String?,
    override val message: String,
    public val httpStatus: Int?,
    public val path: String?,
) : TrpcException(message) {
    // The message check separates a missing route from a missing resource ("Group not found." is also NOT_FOUND).
    public val isUnknownProcedure: Boolean
        get() = code == "NOT_FOUND" && message.contains("No procedure found", ignoreCase = true)
}

public sealed class TrpcClientError(message: String) : TrpcException(message) {
    public class InvalidBaseUrl(public val url: String) :
        TrpcClientError("\"$url\" isn't a valid Spliit address.")

    public class Network(public val reason: String) :
        TrpcClientError("Couldn't reach the server: $reason")

    public class Decoding(public val reason: String) :
        TrpcClientError("The server's response couldn't be read. It may be running a different version.")

    // An instance without S3 answers the document route with 500 and an empty body: no documents, not a retry.
    public class UnexpectedResponse(public val status: Int, public val bodyPrefix: String) :
        TrpcClientError("Unexpected response ($status)")

    internal companion object {
        internal const val BODY_PREFIX_LIMIT: Int = 512
    }
}
