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
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull

/**
 * One call to the Spliit API: which procedure, whether it reads or writes, and the input it
 * carries. [TrpcClient] is the only thing that reads these fields, everything about turning
 * them into bytes on the wire lives there, not here.
 */
public class TrpcProcedure<I, O> private constructor(
    internal val path: String,
    internal val kind: Kind,
    internal val input: I,
    internal val inputSerializer: SerializationStrategy<I>,
    internal val outputSerializer: DeserializationStrategy<O>,
) {

    public enum class Kind {
        /** Sent as `GET`, with the input in the query string. */
        Query,

        /** Sent as `POST`, with the input as the body. */
        Mutation,
    }

    public companion object {

        /** A query, sent as `GET`, that takes no input. */
        public fun <O> query(path: String, output: DeserializationStrategy<O>): TrpcProcedure<NoInput, O> =
            TrpcProcedure(path, Kind.Query, NoInput, NoInput.serializer(), output)

        /** A query, sent as `GET` with [input] in the query string. */
        public fun <I, O> query(
            path: String,
            input: I,
            inputSerializer: SerializationStrategy<I>,
            output: DeserializationStrategy<O>,
        ): TrpcProcedure<I, O> = TrpcProcedure(path, Kind.Query, input, inputSerializer, output)

        /** A mutation, sent as `POST`, that takes no input. */
        public fun <O> mutation(path: String, output: DeserializationStrategy<O>): TrpcProcedure<NoInput, O> =
            TrpcProcedure(path, Kind.Mutation, NoInput, NoInput.serializer(), output)

        /** A mutation, sent as `POST` with [input] as the body. */
        public fun <I, O> mutation(
            path: String,
            input: I,
            inputSerializer: SerializationStrategy<I>,
            output: DeserializationStrategy<O>,
        ): TrpcProcedure<I, O> = TrpcProcedure(path, Kind.Mutation, input, inputSerializer, output)
    }
}

/**
 * Input for a procedure that takes none.
 *
 * [TrpcClient] recognizes this singleton by reference and, for a [TrpcProcedure.Kind.Query],
 * omits the `input` query parameter entirely rather than sending an encoded null, the two are
 * different requests to a tRPC router. A [TrpcProcedure.Kind.Mutation] still needs a body, so it
 * sends this encoded as `{"json":null}` the same as any other input would be.
 */
@Serializable(with = NoInputSerializer::class)
public object NoInput

internal object NoInputSerializer : KSerializer<NoInput> {
    // Nominal, like TrpcVoidSerializer's: this type is never read back, only ever written (or,
    // for a query, not written at all, see NoInput's own doc).
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.NoInput", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NoInput) {
        // Mirrors SuperJson's own JsonDecoder cast: SuperJson.encodeEnvelope always drives this
        // through a Json encoder, so the cast is safe and lets a "no input" mutation encode to a
        // real `null` rather than to a placeholder string.
        (encoder as JsonEncoder).encodeJsonElement(JsonNull)
    }

    override fun deserialize(decoder: Decoder): NoInput {
        throw SerializationException("NoInput is a request type; it is never received.")
    }
}
