package app.spliit.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The superjson envelope the Spliit API wraps every tRPC payload in.
 *
 * superjson sends `{"json": <value>, "meta": {"values": …}}`, where `meta.values` maps
 * dot-separated key paths to annotations for values plain JSON cannot express — `Date`,
 * `undefined`, `Decimal`.
 *
 * Decoding deliberately ignores `meta.values`. Our models are statically typed, so a field the
 * server annotated as a date is already declared [Instant] here and parses straight from its
 * ISO-8601 string — and `groups.list` builds `createdAt` with `.toISOString()` and sends it with
 * no annotation at all, so trusting the metadata would break exactly that one endpoint. Encoding
 * *does* emit annotations, because the server rebuilds real `Date` instances before its own zod
 * validation runs, and a write that arrives without them is rejected.
 *
 * Timestamps in models are declared `@Contextual val x: Instant`: the serializer for them is
 * supplied per call, which is what lets encoding slip a marker into every date it writes (see
 * [encodeEnvelope]) while decoding uses a plain parser.
 */
public object SuperJson {

    /**
     * Wraps [value] in a superjson envelope, annotating every [Instant] it contains.
     *
     * kotlinx-serialization offers no way to learn, after the fact, which strings in the tree came
     * from timestamps. So dates are written behind a per-call random prefix and the prefix is
     * stripped on a second pass over the tree, recording the key path of each one it finds. The
     * prefix carries a UUID, so no real payload string can collide with it.
     */
    public fun <T> encodeEnvelope(serializer: SerializationStrategy<T>, value: T): String {
        val marker = "\u0001superjson-date:${UUID.randomUUID()}:"
        val encoder = Json(ENCODER) {
            serializersModule = SerializersModule {
                contextual(Instant::class, MarkedInstantSerializer(marker))
            }
        }

        val datePaths = mutableListOf<String>()
        val cleaned = stripMarkers(
            encoder.encodeToJsonElement(serializer, value),
            marker,
            emptyList(),
            datePaths,
        )

        val envelope = buildJsonObject {
            put("json", cleaned)
            when {
                // A bare root value is annotated with the type array directly, not as a keyed map.
                // Decided on the tree rather than on the path, because the empty path is also what
                // a date stored under the empty key would produce.
                cleaned is JsonPrimitive && datePaths.size == 1 ->
                    put("meta", metaValues(DATE_ANNOTATION))
                datePaths.isNotEmpty() -> put(
                    "meta",
                    metaValues(JsonObject(datePaths.associateWith { DATE_ANNOTATION })),
                )
            }
        }
        return ENCODER.encodeToString(JsonElement.serializer(), envelope)
    }

    /** Unwraps `{"result":{"data":{"json": …}}}` into the payload type. */
    public fun <T> decodeResponse(deserializer: DeserializationStrategy<T>, body: String): T {
        val root = try {
            DECODER.parseToJsonElement(body)
        } catch (cause: SerializationException) {
            throw SerializationException("The response is not JSON.", cause)
        }
        val result = (root as? JsonObject)?.get("result") as? JsonObject
        val data = result?.get("data") as? JsonObject
            ?: throw SerializationException("The response is not a tRPC result envelope.")

        // A procedure returning `undefined` sends `{"json": null}`; TrpcVoid is the payload type
        // that accepts it, and anything else rightly fails to decode from null.
        return DECODER.decodeFromJsonElement(deserializer, data["json"] ?: JsonNull)
    }

    /** Reads `{"error":{"json": …}}`, or null when the body is not a tRPC error at all. */
    public fun decodeError(body: String): TrpcServerError? {
        // A body that isn't a tRPC error is the normal case here — a proxy's HTML 502, a plain
        // 404 page — so it answers null rather than throwing over it.
        val envelope = try {
            DECODER.decodeFromString(FailureEnvelope.serializer(), body)
        } catch (_: SerializationException) {
            return null
        }

        val payload = envelope.error.json
        return TrpcServerError(
            code = payload.data?.code,
            message = payload.message,
            httpStatus = payload.data?.httpStatus,
            path = payload.data?.path,
        )
    }

    private val DATE_ANNOTATION = JsonArray(listOf(JsonPrimitive("Date")))

    private fun metaValues(values: JsonElement): JsonObject =
        buildJsonObject { put("values", values) }

    private val ENCODER = Json {
        // Omitted is not cleared: a key absent from the request is `undefined` to tRPC and Prisma
        // skips the column, while an explicit null is rejected outright by most of the zod
        // schemas. Fields that genuinely want a null send one as a JsonNull rather than as a
        // Kotlin null.
        explicitNulls = false
        encodeDefaults = false
    }

    private val DECODER = Json {
        // The server sends more than we model, and adds fields between releases. An unknown key is
        // not a reason to fail a screen.
        ignoreUnknownKeys = true
        serializersModule = SerializersModule { contextual(Instant::class, InstantSerializer) }
    }

    private fun stripMarkers(
        value: JsonElement,
        marker: String,
        path: List<String>,
        paths: MutableList<String>,
    ): JsonElement = when {
        value is JsonPrimitive && value.isString && value.content.startsWith(marker) -> {
            paths += path.joinToString(".")
            JsonPrimitive(value.content.removePrefix(marker))
        }

        value is JsonObject ->
            JsonObject(value.mapValues { (key, nested) -> stripMarkers(nested, marker, path + key, paths) })

        value is JsonArray ->
            JsonArray(
                value.mapIndexed { index, nested ->
                    stripMarkers(nested, marker, path + index.toString(), paths)
                },
            )

        else -> value
    }

    @Serializable
    private data class FailureEnvelope(val error: Wrapped) {
        @Serializable
        data class Wrapped(val json: Payload)

        @Serializable
        data class Payload(val message: String, val data: Details? = null)

        @Serializable
        data class Details(
            val code: String? = null,
            val httpStatus: Int? = null,
            val path: String? = null,
        )
    }
}

/**
 * The payload type of a procedure that returns nothing meaningful — `groups.update` answers
 * `undefined`, `expenses.delete` answers `{}`. It accepts either.
 */
@Serializable(with = TrpcVoidSerializer::class)
public object TrpcVoid

internal object TrpcVoidSerializer : KSerializer<TrpcVoid> {
    // The kind is nominal: this serializer only ever appears as a whole response payload, and it
    // reads the element itself rather than letting the descriptor drive the decode.
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.TrpcVoid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TrpcVoid) {
        throw SerializationException("TrpcVoid is a response type; it is never sent.")
    }

    override fun deserialize(decoder: Decoder): TrpcVoid {
        // Whatever shape the procedure answered with, it carries nothing we want.
        (decoder as JsonDecoder).decodeJsonElement()
        return TrpcVoid
    }
}

/**
 * Encoding writes milliseconds unconditionally; decoding takes them or leaves them, because the
 * API mixes the two. `expenseDate` is a bare Postgres `date` and can arrive without a fractional
 * part, while `createdAt` is a full timestamp that always has one.
 */
private val ISO_8601_WITH_MILLISECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Decode-only, despite implementing both halves.
 *
 * **Do not name this on a model field.** A property declared
 * `@Serializable(with = InstantSerializer::class) val expenseDate: Instant` encodes to a correct
 * ISO-8601 string with *no* `meta` block at all, because the marking pass in
 * [SuperJson.encodeEnvelope] never sees it — the decoding is fine and the unit tests are green,
 * and the write is rejected only by a live server. Declare `@Contextual val expenseDate: Instant`
 * instead and both directions are supplied per call.
 */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(ISO_8601_WITH_MILLISECONDS.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (cause: DateTimeParseException) {
            throw SerializationException("Expected an ISO-8601 timestamp, found \"$text\".", cause)
        }
    }
}

/**
 * Writes each timestamp behind [marker] so [SuperJson.encodeEnvelope]'s second pass can tell which
 * strings in the finished tree were dates, and at what key path.
 */
private class MarkedInstantSerializer(private val marker: String) : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(marker + ISO_8601_WITH_MILLISECONDS.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant =
        throw SerializationException("The marked date serializer only encodes.")
}
