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

// Decoding ignores meta.values (groups.list sends unannotated dates); encoding emits it,
// because the server rebuilds Dates before validating.
public object SuperJson {
    // Dates are written behind a per-call marker, then a second pass records their key paths.
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
                // A bare root value takes the type array directly, not a keyed map.
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

    public fun <T> decodeResponse(deserializer: DeserializationStrategy<T>, body: String): T {
        val root = try {
            DECODER.parseToJsonElement(body)
        } catch (cause: SerializationException) {
            throw SerializationException("The response is not JSON.", cause)
        }
        val result = (root as? JsonObject)?.get("result") as? JsonObject
        val data = result?.get("data") as? JsonObject
            ?: throw SerializationException("The response is not a tRPC result envelope.")

        return DECODER.decodeFromJsonElement(deserializer, data["json"] ?: JsonNull)
    }

    public fun decodeError(body: String): TrpcServerError? {
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
        explicitNulls = false
        encodeDefaults = false
    }

    private val DECODER = Json {
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

@Serializable(with = TrpcVoidSerializer::class)
public object TrpcVoid

internal object TrpcVoidSerializer : KSerializer<TrpcVoid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.TrpcVoid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TrpcVoid) {
        throw SerializationException("TrpcVoid is a response type; it is never sent.")
    }

    override fun deserialize(decoder: Decoder): TrpcVoid {
        (decoder as JsonDecoder).decodeJsonElement()
        return TrpcVoid
    }
}

// Writes milliseconds; reads with or without them (expenseDate can arrive without).
private val ISO_8601_WITH_MILLISECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

// Don't name this on a model field: it skips meta.values and the server rejects the write. Use @Contextual.
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

private class MarkedInstantSerializer(private val marker: String) : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(marker + ISO_8601_WITH_MILLISECONDS.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant =
        throw SerializationException("The marked date serializer only encodes.")
}
