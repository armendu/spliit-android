package app.spliit.api

/**
 * Anything a call can fail with: [TrpcServerError] is the server saying no, [TrpcClientError] is
 * never having got an answer. Catch this when the handling is the same, which is usually.
 */
public sealed class TrpcException(message: String) : Exception(message)

/**
 * An error the Spliit server itself reported, as opposed to a transport failure.
 *
 * Every field but [message] is optional because tRPC's own error shape makes them so: a handler
 * that throws before the router resolves sends a message and nothing else.
 */
public class TrpcServerError(
    /** tRPC's error code, e.g. `NOT_FOUND` or `BAD_REQUEST`, not the numeric JSON-RPC code. */
    public val code: String?,
    override val message: String,
    public val httpStatus: Int?,
    /** The procedure that failed, e.g. `groups.getDetails`. */
    public val path: String?,
) : TrpcException(message) {

    /**
     * True when the instance has never heard of this procedure. Degrade or fall back, do not
     * retry.
     *
     * tRPC answers an unresolvable path with `NOT_FOUND` and `No procedure found on path "..."`.
     * The message check is what separates a missing *route* from a missing *resource*, since
     * "Group not found." is also `NOT_FOUND`. The HTTP status is not checked: an error built
     * outside [TrpcClient] may carry none, which would make requiring it a false negative.
     */
    public val isUnknownProcedure: Boolean
        get() = code == "NOT_FOUND" && message.contains("No procedure found", ignoreCase = true)
}

/**
 * A failure that happened before the server could answer, or while turning its answer into
 * bytes, as opposed to [TrpcServerError], which is the server answering with a problem.
 */
public sealed class TrpcClientError(message: String) : TrpcException(message) {

    /** The base URL a group (or a user typing a self-hosted address) supplied isn't a URL at all. */
    public class InvalidBaseUrl(public val url: String) :
        TrpcClientError("\"$url\" isn't a valid Spliit address.")

    /** The request never completed: DNS, TLS, a dropped connection, a timeout. */
    public class Network(public val reason: String) :
        TrpcClientError("Couldn't reach the server: $reason")

    /**
     * A 2xx response whose body didn't decode into the expected model, most likely a self-hosted
     * instance running a server version this client's models don't match. [reason] is
     * [SuperJson]'s own failure message, kept for logs; the user-facing text is deliberately
     * version-agnostic rather than naming a field the person cannot act on.
     */
    public class Decoding(public val reason: String) :
        TrpcClientError("The server's response couldn't be read. It may be running a different version.")

    /**
     * A non-2xx response whose body was not a tRPC error envelope: a proxy's HTML page, a timeout
     * page, or nothing at all.
     *
     * [status] and [bodyPrefix] together mark a *meaningful* empty response. An instance with no
     * S3 bucket answers the document route with exactly 500 and an empty body, which means "this
     * instance keeps no documents" rather than "retry". Neither field alone identifies that.
     */
    public class UnexpectedResponse(public val status: Int, public val bodyPrefix: String) :
        TrpcClientError("Unexpected response ($status)")

    // Not public: explicit API mode would otherwise publish an empty companion object for a
    // constant nothing outside this module needs to see.
    internal companion object {
        internal const val BODY_PREFIX_LIMIT: Int = 512
    }
}
