package app.spliit.api

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
) : Exception(message) {

    /**
     * True when the instance has never heard of this procedure, e.g. an older deployment without
     * `groups.stats.overview`. Degrade the screen or try a fallback rather than retrying.
     *
     * tRPC answers an unresolvable path with code `NOT_FOUND` and a message like
     * `No procedure found on path "..."`. The code check drops anything that was never about
     * routing (a validation failure is `BAD_REQUEST`); the message check separates a missing
     * *route* from a missing *resource*, since "Group not found." is also `NOT_FOUND`.
     *
     * The HTTP status is deliberately not checked: `NOT_FOUND` already implies 404, and a
     * `TrpcServerError` built outside [TrpcClient] may carry a null status, which would make
     * requiring it a false negative.
     */
    public val isUnknownProcedure: Boolean
        get() = code == "NOT_FOUND" && message.contains("No procedure found", ignoreCase = true)
}

/**
 * A failure that happened before the server could answer, or while turning its answer into
 * bytes, as opposed to [TrpcServerError], which is the server answering with a problem.
 */
public sealed class TrpcClientError(message: String) : Exception(message) {

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
     * A non-2xx response whose body was not a tRPC error envelope, a proxy's HTML error page, an
     * upstream timeout page, or a body that is simply empty.
     *
     * [status] and [bodyPrefix] together are what let a caller distinguish a *meaningful* empty
     * response from a generic failure: a self-hosted Spliit instance with no S3 bucket configured
     * answers its document-signing route with exactly HTTP 500 and an empty body, which a caller
     * should read as "this instance keeps no documents", not as something worth retrying. That
     * reading needs both fields (`status == 500 && bodyPrefix.isEmpty()`); neither one alone marks
     * the case, since a 500 can carry a real error page and an empty body can arrive with a
     * different status from a different route.
     *
     * [bodyPrefix] is truncated to [BODY_PREFIX_LIMIT] characters, an error message should not
     * be a whole HTML page.
     */
    public class UnexpectedResponse(public val status: Int, public val bodyPrefix: String) :
        TrpcClientError("Unexpected response ($status)")

    // Not public: explicit API mode would otherwise publish an empty companion object for a
    // constant nothing outside this module needs to see.
    internal companion object {
        internal const val BODY_PREFIX_LIMIT: Int = 512
    }
}
