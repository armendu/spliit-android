package app.spliit.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant

// Timestamps are `@Contextual Instant`, see InstantSerializer. Amounts are minor units, never scaled here.

@Serializable
public data class ExpenseCategory(
    public val id: Int,
    public val grouping: String,
    public val name: String,
)

@Serializable
public data class Participant(
    public val id: String,
    public val name: String,
)

@Serializable
public data class Group(
    public val id: String,
    public val name: String,
    public val information: String? = null,
    public val currency: String,
    public val currencyCode: String? = null,
    @Contextual public val createdAt: Instant,
    public val participants: List<Participant>,
)

// The participant count arrives in Prisma's `_count`; a plain field would silently decode as empty.
@Serializable
public data class GroupSummary(
    public val id: String,
    public val name: String,
    public val currency: String,
    @Contextual public val createdAt: Instant,
    @SerialName("_count") private val counts: Counts,
) {
    public val participantCount: Int get() = counts.participants

    public constructor(
        id: String,
        name: String,
        currency: String,
        createdAt: Instant,
        participantCount: Int,
    ) : this(id, name, currency, createdAt, Counts(participantCount))

    @Serializable
    public data class Counts(public val participants: Int)
}

// Unknown enum values: SplitMode throws (it's money); RecurrenceRule and ActivityType degrade.

@Serializable
public enum class SplitMode {
    EVENLY,
    BY_SHARES,
    BY_PERCENTAGE,
    BY_AMOUNT,
}

@Serializable(with = RecurrenceRuleSerializer::class)
public sealed interface RecurrenceRule {
    public data object None : RecurrenceRule
    public data object Daily : RecurrenceRule
    public data object Weekly : RecurrenceRule
    public data object Monthly : RecurrenceRule

    public data class Unknown(public val raw: String) : RecurrenceRule

    public val isRecognised: Boolean get() = this !is Unknown

    public companion object {
        internal fun of(raw: String): RecurrenceRule = when (raw) {
            "NONE" -> None
            "DAILY" -> Daily
            "WEEKLY" -> Weekly
            "MONTHLY" -> Monthly
            else -> Unknown(raw)
        }
    }
}

internal object RecurrenceRuleSerializer : KSerializer<RecurrenceRule> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.RecurrenceRule", PrimitiveKind.STRING)

    // Round-trips on write too, so an unknown rule isn't reset to NONE.
    override fun serialize(encoder: Encoder, value: RecurrenceRule) {
        encoder.encodeString(
            when (value) {
                RecurrenceRule.None -> "NONE"
                RecurrenceRule.Daily -> "DAILY"
                RecurrenceRule.Weekly -> "WEEKLY"
                RecurrenceRule.Monthly -> "MONTHLY"
                is RecurrenceRule.Unknown -> value.raw
            },
        )
    }

    override fun deserialize(decoder: Decoder): RecurrenceRule = RecurrenceRule.of(decoder.decodeString())
}

@Serializable
public data class ExpenseDocument(
    public val id: String,
    public val url: String,
    public val width: Int,
    public val height: Int,
)

// Prisma.Decimal arrives as a string. BigDecimal equality is scale-sensitive: use compareTo.
@Serializable(with = LenientDecimalSerializer::class)
public data class LenientDecimal(public val value: BigDecimal) : Comparable<LenientDecimal> {
    override fun compareTo(other: LenientDecimal): Int = value.compareTo(other.value)

    override fun toString(): String = value.toPlainString()
}

internal object LenientDecimalSerializer : KSerializer<LenientDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.LenientDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LenientDecimal) {
        encoder.encodeString(value.value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): LenientDecimal {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Expected a decimal, found $element.")
        return try {
            LenientDecimal(BigDecimal(primitive.content))
        } catch (cause: NumberFormatException) {
            throw SerializationException("Expected a decimal, found \"${primitive.content}\".", cause)
        }
    }
}

@Serializable
public data class ExpenseListItem(
    public val id: String,
    public val title: String,
    public val amount: Int,
    @Contextual public val createdAt: Instant,
    @Contextual public val expenseDate: Instant,
    public val isReimbursement: Boolean,
    public val splitMode: SplitMode,
    public val recurrenceRule: RecurrenceRule? = null,
    public val category: ExpenseCategory? = null,
    public val paidBy: Participant,
    public val paidFor: List<PaidFor>,
    @SerialName("_count") private val counts: Counts,
) {
    public val documentCount: Int get() = counts.documents

    @Suppress("LongParameterList")
    public constructor(
        id: String,
        title: String,
        amount: Int,
        createdAt: Instant,
        expenseDate: Instant,
        isReimbursement: Boolean,
        splitMode: SplitMode,
        recurrenceRule: RecurrenceRule?,
        category: ExpenseCategory?,
        paidBy: Participant,
        paidFor: List<PaidFor>,
        documentCount: Int,
    ) : this(
        id = id,
        title = title,
        amount = amount,
        createdAt = createdAt,
        expenseDate = expenseDate,
        isReimbursement = isReimbursement,
        splitMode = splitMode,
        recurrenceRule = recurrenceRule,
        category = category,
        paidBy = paidBy,
        paidFor = paidFor,
        counts = Counts(documentCount),
    )

    @Serializable
    public data class PaidFor(
        public val participant: Participant,
        // Share value x100, or raw minor units under BY_AMOUNT.
        public val shares: Int,
    )

    @Serializable
    public data class Counts(public val documents: Int)
}

@Serializable
public data class ExpenseDetails(
    public val id: String,
    public val groupId: String,
    public val title: String,
    public val amount: Int,
    public val categoryId: Int,
    public val category: ExpenseCategory? = null,
    @Contextual public val expenseDate: Instant,
    @Contextual public val createdAt: Instant,
    public val paidById: String,
    public val paidBy: Participant,
    public val paidFor: List<PaidFor>,
    public val isReimbursement: Boolean,
    public val splitMode: SplitMode,
    public val notes: String? = null,
    public val documents: List<ExpenseDocument> = emptyList(),
    public val recurrenceRule: RecurrenceRule? = null,
    // In originalCurrency's own minor units, not the group's.
    public val originalAmount: Int? = null,
    public val originalCurrency: String? = null,
    public val conversionRate: LenientDecimal? = null,
) {
    @Serializable
    public data class PaidFor(
        public val participantId: String,
        public val shares: Int,
    )
}

// Only total is meaningful: paid and paidFor come from suggested payments, not expenses.
@Serializable
public data class Balance(
    public val paid: Int,
    public val paidFor: Int,
    public val total: Int,
)

@Serializable
public data class Reimbursement(
    public val from: String,
    public val to: String,
    public val amount: Int,
)

@Serializable(with = ActivityTypeSerializer::class)
public sealed interface ActivityType {
    public data object UpdateGroup : ActivityType
    public data object CreateExpense : ActivityType
    public data object UpdateExpense : ActivityType
    public data object DeleteExpense : ActivityType

    public data class Unknown(public val raw: String) : ActivityType

    public val isRecognised: Boolean get() = this !is Unknown

    public companion object {
        internal fun of(raw: String): ActivityType = when (raw) {
            "UPDATE_GROUP" -> UpdateGroup
            "CREATE_EXPENSE" -> CreateExpense
            "UPDATE_EXPENSE" -> UpdateExpense
            "DELETE_EXPENSE" -> DeleteExpense
            else -> Unknown(raw)
        }
    }
}

internal object ActivityTypeSerializer : KSerializer<ActivityType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.ActivityType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ActivityType) {
        encoder.encodeString(
            when (value) {
                ActivityType.UpdateGroup -> "UPDATE_GROUP"
                ActivityType.CreateExpense -> "CREATE_EXPENSE"
                ActivityType.UpdateExpense -> "UPDATE_EXPENSE"
                ActivityType.DeleteExpense -> "DELETE_EXPENSE"
                is ActivityType.Unknown -> value.raw
            },
        )
    }

    override fun deserialize(decoder: Decoder): ActivityType = ActivityType.of(decoder.decodeString())
}

@Serializable
public data class Activity(
    public val id: String,
    public val groupId: String,
    @Contextual public val time: Instant,
    public val activityType: ActivityType,
    public val participantId: String? = null,
    public val expenseId: String? = null,
    @SerialName("data") public val title: String? = null,
    @SerialName("expense") private val expenseReference: JsonObject? = null,
) {
    public val expenseStillExists: Boolean get() = expenseReference != null
}
