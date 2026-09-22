package app.spliit.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

@Serializable
private data class GroupInput(val groupId: String)

@Serializable
private data class GroupOutput(val id: String, val name: String)

/** A tRPC success envelope carrying [json] as its payload, unparsed so tests can inline it. */
private fun okBody(json: String): String = """{"result":{"data":{"json":$json}}}"""

private fun RequestBody.readUtf8(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}

class TrpcClientTest {

    private val server = MockWebServer()
    private lateinit var client: TrpcClient

    @BeforeEach
    fun startServer() {
        server.start()
        client = TrpcClient(server.url("/").toString())
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    // ---- request building --------------------------------------------------------------

    @Test
    fun `a query goes as GET with the envelope in input`() {
        val procedure = TrpcProcedure.query(
            "groups.get",
            GroupInput("g1"),
            GroupInput.serializer(),
            GroupOutput.serializer(),
        )

        val request = client.buildRequest(procedure)

        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals(
            SuperJson.encodeEnvelope(GroupInput.serializer(), GroupInput("g1")),
            request.url.queryParameter("input"),
        )
    }

    @Test
    fun `a mutation goes as POST with the envelope as the body`() {
        val procedure = TrpcProcedure.mutation(
            "groups.update",
            GroupInput("g1"),
            GroupInput.serializer(),
            GroupOutput.serializer(),
        )

        val request = client.buildRequest(procedure)

        assertEquals("POST", request.method)
        assertNull(request.url.queryParameter("input"))
        assertEquals(
            SuperJson.encodeEnvelope(GroupInput.serializer(), GroupInput("g1")),
            checkNotNull(request.body).readUtf8(),
        )
    }

    /**
     * The trap this whole part warns about: `URLComponents`-style query builders leave `+` and
     * `&` alone inside a value, and Spliit's Next.js server decodes query strings the
     * `URLSearchParams` way, where a literal `+` means a space. Round-tripping through the actual
     * request line, not just through our own encoder, is what proves the escaping survives.
     */
    @Test
    fun `percent-encodes a payload containing plus and ampersand so the server receives them intact`() {
        val input = GroupInput("a+b&c d")
        val procedure = TrpcProcedure.query("groups.get", input, GroupInput.serializer(), GroupOutput.serializer())

        val request = client.buildRequest(procedure)
        val rawTarget = request.url.encodedQuery.orEmpty()

        // The wire form must carry no literal + or & inside the value, only inside the escapes.
        assertTrue(rawTarget.contains("%2B"), "expected an escaped + in $rawTarget")
        assertTrue(rawTarget.contains("%26"), "expected an escaped & in $rawTarget")
        // Strip the legitimate escapes back out first, so a real literal + left over is what
        // this actually catches, asserting against the un-stripped string can never fail, since
        // the %2B check above already guarantees a "+" substring is present inside it.
        assertFalse(
            rawTarget.removePrefix("input=").replace("%2B", "").contains("+"),
            "a literal + reached the wire: $rawTarget",
        )
        // And decoding the escapes back out reproduces the exact envelope we intended to send.
        assertEquals(
            SuperJson.encodeEnvelope(GroupInput.serializer(), input),
            request.url.queryParameter("input"),
        )
    }

    /** The same round-trip, but over an actual socket, proving nothing decodes early in transit. */
    @Test
    fun `a real request over the wire carries the escaped payload intact`() {
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"id":"g1","name":"A"}""")).build())
        val input = GroupInput("a+b&c")
        val procedure = TrpcProcedure.query("groups.get", input, GroupInput.serializer(), GroupOutput.serializer())

        runBlocking { client.call(procedure) }

        val recorded = server.takeRequest()
        assertTrue(recorded.target.contains("%2B"), "raw request target lost the +: ${recorded.target}")
        assertTrue(recorded.target.contains("%26"), "raw request target lost the &: ${recorded.target}")
        assertEquals(
            SuperJson.encodeEnvelope(GroupInput.serializer(), input),
            recorded.url.queryParameter("input"),
        )
    }

    /**
     * The byte-wise encoder in [percentEncodeQueryValue] walks UTF-8 bytes, not chars, a
     * char-wise refactor (e.g. checking `Character.isLetterOrDigit`) would silently mis-encode
     * anything outside ASCII, one multi-byte sequence at a time, with no test noticing until a
     * real accented name or currency symbol hit it.
     */
    @Test
    fun `percent-encodes a non-ascii payload so it round-trips intact`() {
        val input = GroupInput("Café éè ¥100 🎉")
        val procedure = TrpcProcedure.query("groups.get", input, GroupInput.serializer(), GroupOutput.serializer())

        val request = client.buildRequest(procedure)

        assertEquals(
            SuperJson.encodeEnvelope(GroupInput.serializer(), input),
            request.url.queryParameter("input"),
        )
    }

    @Test
    fun `base URL with a trailing slash produces exactly one slash before api trpc`() {
        val trailing = TrpcClient(server.url("/").toString())
        val request = trailing.buildRequest(TrpcProcedure.query("groups.list", GroupOutput.serializer()))

        assertTrue(request.url.encodedPath.endsWith("/api/trpc/groups.list"))
        assertFalse(request.url.encodedPath.contains("//api/trpc"))
    }

    /**
     * `server.url("/")` is already `http://localhost:PORT/`, its path is bare `/` either way a
     * trailing slash is added or removed from the string, so a root-URL test alone never actually
     * exercises the `endsWith("/")` branch in [TrpcClient.buildRequest]; deleting that check
     * entirely would still leave a root-URL test green. A path prefix is what forces the branch:
     * `/spliit` (no trailing slash) must gain one before `api/trpc`, and `/spliit/` must not gain
     * a second.
     */
    @Test
    fun `a base URL with a path prefix keeps the prefix and gains one slash`() {
        for (base in listOf("https://host.example/spliit", "https://host.example/spliit/")) {
            val request = TrpcClient(base).buildRequest(
                TrpcProcedure.query("groups.list", GroupOutput.serializer()),
            )
            assertEquals("https://host.example/spliit/api/trpc/groups.list", request.url.toString())
        }
    }

    @Test
    fun `a query with no input sends no input parameter`() {
        val request = client.buildRequest(TrpcProcedure.query("groups.list", GroupOutput.serializer()))

        assertNull(request.url.query)
        assertNull(request.url.queryParameter("input"))
    }

    @Test
    fun `the procedure path lands in the url path not the query`() {
        val procedure = TrpcProcedure.query(
            "groups.getDetails",
            GroupInput("g1"),
            GroupInput.serializer(),
            GroupOutput.serializer(),
        )

        val request = client.buildRequest(procedure)

        assertTrue(request.url.encodedPath.endsWith("/api/trpc/groups.getDetails"))
        assertFalse(request.url.encodedQuery.orEmpty().contains("groups.getDetails"))
    }

    // ---- responses ------------------------------------------------------------------------

    @Test
    fun `a 200 decodes the payload`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"id":"g1","name":"Flat 3B"}""")).build())

        val result = client.call(TrpcProcedure.query("groups.get", GroupOutput.serializer()))

        assertEquals(GroupOutput("g1", "Flat 3B"), result)
    }

    @Test
    fun `a 404 with a trpc error body throws TrpcServerError with code message and path`() {
        val body = """{"error":{"json":{"message":"Group not found.","code":-32004,""" +
            """"data":{"code":"NOT_FOUND","httpStatus":404,"path":"groups.getDetails"}}}}"""
        server.enqueue(MockResponse.Builder().code(404).body(body).build())

        val error = assertThrows<TrpcServerError> {
            runBlocking { client.call(TrpcProcedure.query("groups.getDetails", GroupOutput.serializer())) }
        }

        assertEquals("NOT_FOUND", error.code)
        assertEquals("Group not found.", error.message)
        assertEquals("groups.getDetails", error.path)
        assertEquals(404, error.httpStatus)
        assertFalse(error.isUnknownProcedure)
    }

    @Test
    fun `isUnknownProcedure is true for a missing procedure and false for an ordinary NOT_FOUND`() {
        val missingProcedure = TrpcServerError(
            code = "NOT_FOUND",
            message = """No procedure found on path "groups.stats.overview".""",
            httpStatus = 404,
            path = "groups.stats.overview",
        )
        val missingResource = TrpcServerError(
            code = "NOT_FOUND",
            message = "Group not found.",
            httpStatus = 404,
            path = "groups.getDetails",
        )

        assertTrue(missingProcedure.isUnknownProcedure)
        assertFalse(missingResource.isUnknownProcedure)
    }

    /**
     * The specific case a Spliit instance with no S3 bucket answers its document-signing route
     * with: HTTP 500, nothing in the body. It has to come out as something a caller can recognize
     * without mistaking it for a tRPC error (there's no error envelope to parse) or for a generic
     * unexpected-response failure indistinguishable from a broken proxy.
     */
    @Test
    fun `a 500 with an empty body is distinguishable from a trpc error`() {
        server.enqueue(MockResponse.Builder().code(500).body("").build())

        val error = assertThrows<TrpcClientError.UnexpectedResponse> {
            runBlocking { client.call(TrpcProcedure.query("groups.expenses.documents.sign", GroupOutput.serializer())) }
        }

        assertEquals(500, error.status)
        assertEquals("", error.bodyPrefix)
    }

    @Test
    fun `an html error page throws the non-trpc error with a truncated body`() {
        val html = "<html>" + "x".repeat(1000) + "</html>"
        server.enqueue(MockResponse.Builder().code(502).body(html).build())

        val error = assertThrows<TrpcClientError.UnexpectedResponse> {
            runBlocking { client.call(TrpcProcedure.query("groups.list", GroupOutput.serializer())) }
        }

        assertEquals(502, error.status)
        assertEquals(TrpcClientError.BODY_PREFIX_LIMIT, error.bodyPrefix.length)
        assertTrue(error.bodyPrefix.startsWith("<html>"))
        // Not the same shape as the meaningful empty-500 case above.
        assertFalse(error.status == 500 && error.bodyPrefix.isEmpty())
    }

    /**
     * A 2xx whose payload doesn't match the model, most likely a self-hosted instance running a
     * different server version. This must not leak a raw `SerializationException` out of `call`:
     * `:app` catches `TrpcServerError | TrpcClientError`, and an uncaught kotlinx.serialization
     * exception would crash instead of showing the version-skew message.
     */
    @Test
    fun `a 200 whose payload does not match the model throws Decoding rather than crashing`() {
        // Missing the required "name" field.
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"id":"g1"}""")).build())

        assertThrows<TrpcClientError.Decoding> {
            runBlocking { client.call(TrpcProcedure.query("groups.get", GroupOutput.serializer())) }
        }
    }

    @Test
    fun `an invalid base URL throws InvalidBaseUrl rather than crashing`() {
        val badClient = TrpcClient("not a url at all")

        val error = assertThrows<TrpcClientError.InvalidBaseUrl> {
            runBlocking { badClient.call(TrpcProcedure.query("groups.list", GroupOutput.serializer())) }
        }

        assertEquals("not a url at all", error.url)
    }

    /**
     * Pinned, not fixed: OkHttp, like `URLSession`, follows a 301/302 by re-issuing the request
     * as `GET`, silently dropping a mutation's body. A redirecting proxy can therefore turn a
     * write into a no-op that still reports success, with nothing in the response to say the
     * mutation itself never reached the server. This is inherited from the HTTP client's default
     * behaviour, not something this part introduced, and changing it is a deliberate decision
     * (disabling redirects entirely, or rejecting a redirected mutation) deferred until a real
     * deployment needs it, this test exists so that decision is made on purpose, not discovered
     * by accident.
     */
    @Test
    fun `a redirected mutation silently becomes a GET that still reports success`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/api/trpc/groups.update")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(okBody("null")).build())

        val procedure = TrpcProcedure.mutation(
            "groups.update",
            GroupInput("g1"),
            GroupInput.serializer(),
            TrpcVoid.serializer(),
        )

        client.call(procedure) // does not throw, that is precisely the danger being pinned here

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("GET", second.method) // the mutation's body never reached the server
    }

    @Test
    fun `a network failure throws the network error type`() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.close() // nothing is listening on this port anymore

        val deadClient = TrpcClient(deadUrl)

        assertThrows<TrpcClientError.Network> {
            runBlocking { deadClient.call(TrpcProcedure.query("groups.list", GroupOutput.serializer())) }
        }
    }

    /**
     * The bug this whole rule exists to prevent: on iOS, reporting a cancelled request as a
     * network error told people their server was unreachable when all they had done was type
     * another character into a search field. `CancellationException` must reach the caller
     * unmolested.
     */
    @Test
    fun `a cancelled call propagates cancellation rather than a network error`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(5, TimeUnit.SECONDS)
                .body(okBody("""{"id":"g1","name":"Flat"}"""))
                .build(),
        )

        var caught: Throwable? = null
        val job = launch {
            try {
                client.call(TrpcProcedure.query("groups.get", GroupOutput.serializer()))
            } catch (e: Throwable) {
                caught = e
            }
        }
        delay(200) // give the request time to actually reach the (slow-to-answer) server
        job.cancelAndJoin()

        assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
    }

    /**
     * The half of cancellation that the previous test can't see: that cancelling the coroutine
     * also cancels OkHttp's own in-flight [okhttp3.Call], instead of leaving it to run to
     * completion (and hold a connection) in the background. Removing `invokeOnCancellation` in
     * [TrpcClient.execute] still throws `CancellationException` at the suspension point, the
     * previous test alone stays green, but the underlying call leaks. A dedicated
     * [OkHttpClient] with its own dispatcher is what makes that leak observable.
     */
    @Test
    fun `cancelling a call also cancels the underlying OkHttp call`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(5, TimeUnit.SECONDS)
                .body(okBody("""{"id":"g1","name":"Flat"}"""))
                .build(),
        )
        val dedicatedHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val dedicatedClient = TrpcClient(server.url("/").toString(), dedicatedHttpClient)

        val job = launch {
            dedicatedClient.call(TrpcProcedure.query("groups.get", GroupOutput.serializer()))
        }
        withTimeout(2_000) {
            while (dedicatedHttpClient.dispatcher.runningCallsCount() == 0) delay(10)
        }
        job.cancelAndJoin()

        withTimeout(2_000) {
            while (dedicatedHttpClient.dispatcher.runningCallsCount() != 0) delay(10)
        }
        assertEquals(0, dedicatedHttpClient.dispatcher.runningCallsCount())
    }
}
