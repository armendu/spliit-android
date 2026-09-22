package app.spliit.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
// Named for what it is rather than after a model: Part 3's real GroupSummary now lives in
// the same package, and a file-private stub sharing its name shadows it for every other test.
private data class ListedGroup(val id: String, val name: String, @Contextual val createdAt: Instant)

@Serializable
private data class GroupsList(val groups: List<ListedGroup>)

@Serializable
private data class Timestamped(@Contextual val at: Instant)

@Serializable
private data class ExpenseInput(val title: String, @Contextual val expenseDate: Instant)

@Serializable
private data class Item(@Contextual val at: Instant)

@Serializable
private data class Nested(@Contextual val on: Instant)

@Serializable
private data class ArrayInput(val items: List<Item>, val nested: Nested)

@Serializable
private data class PlainInput(val groupId: String)

@Serializable
private data class OptionalInput(val name: String, val notes: String?)

@Serializable
private data class ClearingInput(val name: String, val originalCurrency: JsonElement)

@Serializable
private data class MapInput(val dates: Map<String, @Contextual Instant>)

private val DATE: Instant = utc(2023, 11, 14, 22, 13, 20)

/** Built without going through the parser under test, so the assertions are not circular. */
private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Instant =
    OffsetDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant()

private const val MARKER_PREFIX = "\u0001superjson-date:"

class SuperJsonTest {

    private fun envelope(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun annotations(body: String): JsonObject =
        envelope(body).getValue("meta").jsonObject.getValue("values").jsonObject

    private fun JsonElement.at(index: Int): JsonObject = (this as JsonArray)[index].jsonObject

    // ---- decoding ---------------------------------------------------------------------

    @Test
    fun `unwraps the result data json envelope into the payload type`() {
        val body = """{"result":{"data":{"json":{"groups":[]}}}}"""

        val payload = SuperJson.decodeResponse(GroupsList.serializer(), body)

        assertEquals(emptyList<ListedGroup>(), payload.groups)
    }

    @Test
    fun `decodes a procedure that returns nothing as the void value`() {
        val body = """{"result":{"data":{"json":null,"meta":{"values":["undefined"]}}}}"""

        assertSame(TrpcVoid, SuperJson.decodeResponse(TrpcVoid.serializer(), body))
    }

    @Test
    fun `decodes a procedure that answers with an empty object as the void value`() {
        // `expenses.delete` answers `{}` rather than null.
        val body = """{"result":{"data":{"json":{}}}}"""

        assertSame(TrpcVoid, SuperJson.decodeResponse(TrpcVoid.serializer(), body))
    }

    /**
     * The test this whole part exists to make pass. `groups.list` builds `createdAt` with
     * `.toISOString()`, so unlike every other endpoint it sends the timestamp with *no* entry in
     * `meta.values` at all. Decoding has to work anyway, which is exactly what justifies
     * ignoring the metadata rather than consulting it.
     */
    @Test
    fun `decodes a date the server did not annotate`() {
        val body = """
            {"result":{"data":{"json":{"groups":[
              {"id":"xvT","name":"Flat 3B","createdAt":"2026-08-29T13:12:42.661Z"}
            ]}}}}
        """.trimIndent()

        val payload = SuperJson.decodeResponse(GroupsList.serializer(), body)

        assertEquals(
            utc(2026, 8, 29, 13, 12, 42).plusMillis(661),
            payload.groups.single().createdAt,
        )
    }

    @Test
    fun `accepts a timestamp without fractional seconds`() {
        val body = """{"result":{"data":{"json":{"at":"2026-08-15T16:24:15Z"}}}}"""

        val payload = SuperJson.decodeResponse(Timestamped.serializer(), body)

        assertEquals(utc(2026, 8, 15, 16, 24, 15), payload.at)
    }

    @Test
    fun `accepts a timestamp with milliseconds`() {
        val body = """{"result":{"data":{"json":{"at":"2026-08-15T16:24:15.600Z"}}}}"""

        val payload = SuperJson.decodeResponse(Timestamped.serializer(), body)

        assertEquals(utc(2026, 8, 15, 16, 24, 15).plusMillis(600), payload.at)
    }

    @Test
    fun `rejects a timestamp it cannot parse rather than defaulting`() {
        val body = """{"result":{"data":{"json":{"at":"last Tuesday"}}}}"""

        assertThrows<SerializationException> {
            SuperJson.decodeResponse(Timestamped.serializer(), body)
        }
    }

    @Test
    fun `a success body that is not a trpc envelope fails loudly`() {
        assertThrows<SerializationException> {
            SuperJson.decodeResponse(GroupsList.serializer(), """{"groups":[]}""")
        }
    }

    @Test
    fun `reads a trpc error body into a structured error`() {
        // Recorded from a real instance. Note the numeric JSON-RPC `code` beside `message`: it is
        // a different field from the string `code` inside `data`, and reading the wrong one gives
        // you a number where the tRPC code belongs.
        val body = """{"error":{"json":{"message":"Group not found.","code":-32004,""" +
            """"data":{"code":"NOT_FOUND","httpStatus":404,"path":"groups.getDetails"}}}}"""

        val error = checkNotNull(SuperJson.decodeError(body))

        assertEquals("NOT_FOUND", error.code)
        assertEquals("Group not found.", error.message)
        assertEquals(404, error.httpStatus)
        assertEquals("groups.getDetails", error.path)
    }

    @Test
    fun `reads an error that carries no data block`() {
        val body = """{"error":{"json":{"message":"Something broke."}}}"""

        val error = checkNotNull(SuperJson.decodeError(body))

        assertEquals("Something broke.", error.message)
        assertNull(error.code)
        assertNull(error.httpStatus)
        assertNull(error.path)
    }

    @Test
    fun `a body that is not a trpc error yields null rather than throwing`() {
        assertNull(SuperJson.decodeError("<html>502 Bad Gateway</html>"))
        assertNull(SuperJson.decodeError("""{"result":{"data":{"json":{"groups":[]}}}}"""))
        assertNull(SuperJson.decodeError(""))
    }

    // ---- encoding ---------------------------------------------------------------------

    @Test
    fun `annotates every date it encodes at its key path`() {
        val body = SuperJson.encodeEnvelope(ExpenseInput.serializer(), ExpenseInput("Taxi", DATE))

        val json = envelope(body).getValue("json").jsonObject
        assertEquals("Taxi", json.getValue("title").jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20.000Z", json.getValue("expenseDate").jsonPrimitive.content)

        val values = annotations(body)
        assertEquals(JsonArray(listOf(JsonPrimitive("Date"))), values["expenseDate"])
        assertEquals(1, values.size)
    }

    /**
     * superjson addresses nested values by dot-separated path and array elements by index, so a
     * date inside a list is `items.0.at` and never `items.at`.
     */
    @Test
    fun `annotates dates nested in arrays and objects by their index path`() {
        val input = ArrayInput(items = listOf(Item(DATE), Item(DATE)), nested = Nested(DATE))

        val values = annotations(SuperJson.encodeEnvelope(ArrayInput.serializer(), input))

        assertEquals(setOf("items.0.at", "items.1.at", "nested.on"), values.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("Date"))), values["items.1.at"])
    }

    @Test
    fun `writes no meta key when there are no dates`() {
        val body = SuperJson.encodeEnvelope(PlainInput.serializer(), PlainInput("abc"))

        assertFalse(envelope(body).containsKey("meta"))
        assertEquals(
            "abc",
            envelope(body).getValue("json").jsonObject.getValue("groupId").jsonPrimitive.content,
        )
    }

    @Test
    fun `the internal marker never leaks into the encoded value`() {
        val input = ArrayInput(items = listOf(Item(DATE)), nested = Nested(DATE))

        val body = SuperJson.encodeEnvelope(ArrayInput.serializer(), input)

        // Against the literal text, not against the raw control character: a leaked marker is
        // JSON-escaped on the way out, so searching for the character itself could never fire.
        assertFalse(body.contains("superjson-date"), "marker leaked into $body")
        val json = envelope(body).getValue("json").jsonObject
        assertEquals(
            "2023-11-14T22:13:20.000Z",
            json.getValue("items").at(0).getValue("at").jsonPrimitive.content,
        )
        assertEquals("2023-11-14T22:13:20.000Z", json.getValue("nested").jsonObject.getValue("on").jsonPrimitive.content)
    }

    @Test
    fun `two encodings of the same value agree, so no per-call marker survives`() {
        val a = SuperJson.encodeEnvelope(ExpenseInput.serializer(), ExpenseInput("Taxi", DATE))
        val b = SuperJson.encodeEnvelope(ExpenseInput.serializer(), ExpenseInput("Taxi", DATE))

        assertEquals(a, b)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `a bare root date is annotated with the array form`() {
        val body = SuperJson.encodeEnvelope(ContextualSerializer(Instant::class), DATE)

        assertEquals(
            "2023-11-14T22:13:20.000Z",
            envelope(body).getValue("json").jsonPrimitive.content,
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Date"))),
            envelope(body).getValue("meta").jsonObject.getValue("values"),
        )
    }

    @Test
    fun `a payload string that merely looks like the marker is left alone`() {
        // The marker carries a per-call UUID so no payload can actually collide with it, but a
        // value shaped like one must not be mistaken for a date either.
        val suspicious = MARKER_PREFIX + "00000000-0000-0000-0000-000000000000:nope"

        val body = SuperJson.encodeEnvelope(PlainInput.serializer(), PlainInput(suspicious))

        assertFalse(envelope(body).containsKey("meta"))
        assertEquals(
            suspicious,
            envelope(body).getValue("json").jsonObject.getValue("groupId").jsonPrimitive.content,
        )
    }

    /**
     * The other half of the rule below, and the mechanism `originalCurrency` depends on: that
     * field's zod schema is the one that accepts an explicit null, and since `explicitNulls` is
     * off a Kotlin null can no longer say so. A `JsonNull` has to survive to the wire instead -
     * flipping the setting back would break clearing a currency with every other test still green.
     */
    @Test
    fun `an explicit JsonNull does reach the wire`() {
        val body = SuperJson.encodeEnvelope(
            ClearingInput.serializer(),
            ClearingInput("Flat", JsonNull),
        )

        val json = envelope(body).getValue("json").jsonObject
        assertTrue(json.containsKey("originalCurrency"))
        assertEquals(JsonNull, json.getValue("originalCurrency"))
    }

    /** Part 3's payloads nest dates under map-shaped values, not only under declared properties. */
    @Test
    fun `annotates a date that is a map value`() {
        val input = MapInput(dates = mapOf("due" to DATE, "settled" to DATE))

        val body = SuperJson.encodeEnvelope(MapInput.serializer(), input)

        assertEquals(setOf("dates.due", "dates.settled"), annotations(body).keys)
        assertEquals(
            "2023-11-14T22:13:20.000Z",
            envelope(body).getValue("json").jsonObject
                .getValue("dates").jsonObject.getValue("due").jsonPrimitive.content,
        )
    }

    @Test
    fun `null optionals are omitted rather than sent as null`() {
        // The server's zod schemas mark these `.optional()`: an absent key is accepted, and an
        // explicit null is not accepted by every one of them.
        val body = SuperJson.encodeEnvelope(OptionalInput.serializer(), OptionalInput("Flat", null))

        val json = envelope(body).getValue("json").jsonObject
        assertFalse(json.containsKey("notes"))
        assertEquals("Flat", json.getValue("name").jsonPrimitive.content)
    }
}
