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

public class TrpcProcedure<I, O> private constructor(
    internal val path: String,
    internal val kind: Kind,
    internal val input: I,
    internal val inputSerializer: SerializationStrategy<I>,
    internal val outputSerializer: DeserializationStrategy<O>,
) {
    public enum class Kind {
        Query,

        Mutation,
    }

    public companion object {
        public fun <O> query(path: String, output: DeserializationStrategy<O>): TrpcProcedure<NoInput, O> =
            TrpcProcedure(path, Kind.Query, NoInput, NoInput.serializer(), output)

        public fun <I, O> query(
            path: String,
            input: I,
            inputSerializer: SerializationStrategy<I>,
            output: DeserializationStrategy<O>,
        ): TrpcProcedure<I, O> = TrpcProcedure(path, Kind.Query, input, inputSerializer, output)

        public fun <O> mutation(path: String, output: DeserializationStrategy<O>): TrpcProcedure<NoInput, O> =
            TrpcProcedure(path, Kind.Mutation, NoInput, NoInput.serializer(), output)

        public fun <I, O> mutation(
            path: String,
            input: I,
            inputSerializer: SerializationStrategy<I>,
            output: DeserializationStrategy<O>,
        ): TrpcProcedure<I, O> = TrpcProcedure(path, Kind.Mutation, input, inputSerializer, output)
    }
}

// Recognised by reference: a query sends no `input` at all; a mutation sends {"json":null}.
@Serializable(with = NoInputSerializer::class)
public object NoInput

internal object NoInputSerializer : KSerializer<NoInput> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.NoInput", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NoInput) {
        (encoder as JsonEncoder).encodeJsonElement(JsonNull)
    }

    override fun deserialize(decoder: Decoder): NoInput {
        throw SerializationException("NoInput is a request type; it is never received.")
    }
}
