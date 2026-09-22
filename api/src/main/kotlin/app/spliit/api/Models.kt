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

// Money crosses the wire as an integer count of **minor units**, and minor units are not always
// hundredths: `amount == 1234` is 12.34 in a two-decimal currency and ¥1,234 in a group counted
// in yen. That holds for expense amounts, balances and reimbursements alike. Nothing in this
// file scales, rounds or divides, it carries the server's integers through untouched, and
// Part 5's MoneyFormatter is the only place that knows what a currency counts in.
//
// Every timestamp here is `@Contextual val x: Instant`. That is not a style choice: see
// InstantSerializer's own doc, and ExpenseFormValues, where getting it wrong is silent.

/** A category as the picker lists it. `id` 0 is "General", which the server treats as the default. */
@Serializable
public data class ExpenseCategory(
    public val id: Int,
    /** The heading this category sits under in the picker, e.g. "Food and Drink". */
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
    /** A free-text symbol such as "$" or "CHF", not an ISO code. See [currencyCode]. */
    public val currency: String,
    /**
     * ISO-4217, when the group has one. Older groups carry only a symbol, and a group that has
     * had its code cleared carries an empty string rather than a null, that is what the web app
     * writes. Both mean "we do not know what this counts in", which Part 5 reads as hundredths.
     */
    public val currencyCode: String? = null,
    @Contextual public val createdAt: Instant,
    public val participants: List<Participant>,
)

/**
 * A group as `groups.list` returns it: no participants, only how many.
 *
 * The count arrives inside Prisma's `_count` aggregate, there is no `participantCount` field on
 * the wire, so the aggregate is what the model decodes and [participantCount] is how the rest of
 * the app reads it. A model declaring a plain field decodes nothing, throws nothing, and every
 * group in the list claims to be empty.
 */
@Serializable
public data class GroupSummary(
    public val id: String,
    public val name: String,
    public val currency: String,
    @Contextual public val createdAt: Instant,
    @SerialName("_count") private val counts: Counts,
) {
    public val participantCount: Int get() = counts.participants

    /** For tests and for Part 8's recent-group rows, which build one without a server. */
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

// Three enumerations arrive from the server and they do *not* treat an unrecognised value the
// same way, on purpose. Instances are self-hosted and may run ahead of this client, so the
// question is never "can this happen" but "what does it cost when it does":
//
//   SplitMode       throws   , money. A mode we misread divides an expense wrongly and pays the
//                               wrong person a plausible-looking amount.
//   RecurrenceRule  degrades , display only in cycle 1. Nothing computes from it.
//   ActivityType    degrades , one line of prose in the log.
//
// Failing loudly is right exactly where being wrong is expensive, and wrong everywhere else.

/**
 * How an expense divides between the people it was paid for.
 *
 * Deliberately fails to decode a value it does not know, unlike [RecurrenceRule] and
 * [ActivityType]: a split mode this client cannot read is money it would divide wrongly, and a
 * wrong number on a balances screen is worse than a screen saying it could not load.
 */
@Serializable
public enum class SplitMode {
    EVENLY,
    BY_SHARES,
    BY_PERCENTAGE,
    BY_AMOUNT,
}

/**
 * How often an expense repeats.
 *
 * An unrecognised value decodes as [Unknown] rather than throwing, because the alternative is out
 * of all proportion: this is display-only metadata in cycle 1, nothing computes from it, so not
 * understanding a value costs one row a little of its detail, while throwing fails the decode of
 * **the entire expense list**, including every expense that has nothing to do with recurrence.
 * `ModelsTest` demonstrates both halves of that on a payload where one expense of three carries
 * an unknown rule.
 *
 * Nullability does not cover this. `RecurrenceRule? = null` absorbs an *absent* key, not an
 * unrecognised *value*.
 *
 * Where such a payload comes from is worth being precise about, because our own e2e image cannot
 * produce one: its zod enum is exactly these four values and it answers 400 to anything else
 * (checked, `YEARLY` and `FORTNIGHTLY` are both rejected). So this is not a case any server we
 * can currently point at will send. It is the self-hosted instance running *ahead* of us, which
 * is the ordinary case rather than the exotic one given upstream ships recurring expenses as a
 * beta feature, the same reasoning [ActivityType] already exists for.
 */
@Serializable(with = RecurrenceRuleSerializer::class)
public sealed interface RecurrenceRule {
    public data object None : RecurrenceRule
    public data object Daily : RecurrenceRule
    public data object Weekly : RecurrenceRule
    public data object Monthly : RecurrenceRule

    /** A cadence this version has no words for. [raw] is the server's, and round-trips intact. */
    public data class Unknown(public val raw: String) : RecurrenceRule

    /** False for a rule this version cannot describe, and so should not name on a row. */
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

    // Encoding matters here in a way it does not for ActivityType: this type is on the write side
    // too, and an expense edited on a screen that could not name its rule must be saved with the
    // rule it already had rather than silently reset to NONE.
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

/**
 * A `Prisma.Decimal` as it crosses superjson, a **string**, annotated
 * `[["custom","decimal.js"]]` rather than as a number.
 *
 * Decoding is lenient in case an instance sends a JSON number instead, which costs nothing here
 * and saves a screen there. [BigDecimal] rather than [Double] because a conversion rate typed as
 * 0.9241 should stay 0.9241 rather than become the nearest double, and because it is what the
 * server stores.
 *
 * Note equality is [BigDecimal]'s, so it is scale-sensitive: `0.9241` and `0.92410` are not
 * equal. Compare with [compareTo] when that matters.
 */
@Serializable(with = LenientDecimalSerializer::class)
public data class LenientDecimal(public val value: BigDecimal) : Comparable<LenientDecimal> {
    override fun compareTo(other: LenientDecimal): Int = value.compareTo(other.value)

    override fun toString(): String = value.toPlainString()
}

internal object LenientDecimalSerializer : KSerializer<LenientDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.spliit.api.LenientDecimal", PrimitiveKind.STRING)

    // Sent as a string: the write-side schema accepts a number or a numeric string, and text is
    // what keeps the digits the user typed rather than a double's idea of them.
    override fun serialize(encoder: Encoder, value: LenientDecimal) {
        encoder.encodeString(value.value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): LenientDecimal {
        // Reading the primitive rather than the declared kind is what makes this lenient: a JSON
        // number and a JSON string both arrive with the digits in `content`.
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

/**
 * An expense as it appears in a group's list. Carries only what a row needs, use
 * [ExpenseDetails] to edit one.
 */
@Serializable
public data class ExpenseListItem(
    public val id: String,
    public val title: String,
    /** Minor units. See the note at the top of this file. */
    public val amount: Int,
    @Contextual public val createdAt: Instant,
    @Contextual public val expenseDate: Instant,
    public val isReimbursement: Boolean,
    public val splitMode: SplitMode,
    public val recurrenceRule: RecurrenceRule? = null,
    public val category: ExpenseCategory? = null,
    public val paidBy: Participant,
    public val paidFor: List<PaidFor>,
    // Prisma's aggregate again, and the field that decides whether a row shows a paperclip.
    @SerialName("_count") private val counts: Counts,
) {
    public val documentCount: Int get() = counts.documents

    /**
     * For a row rebuilt on this side rather than decoded, the edit sheet writes over the group
     * screen, so one changed expense is put back into the loaded list instead of the whole list
     * being read again. Mirrors [GroupSummary]'s own secondary constructor, and exists for the
     * same reason: `_count` is Prisma's aggregate, not something a caller should have to name.
     */
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
        /**
         * **One field, two units, decided by a sibling.** For [SplitMode.EVENLY],
         * [SplitMode.BY_SHARES] and [SplitMode.BY_PERCENTAGE] this is the share value ×100 -
         * one share is `100`, 33.5% is `3350`, whatever the currency. For [SplitMode.BY_AMOUNT]
         * it is a raw minor-unit amount, which does scale with the currency, and the entries sum
         * to the expense's `amount`. Part 6 owns the arithmetic; this carries the value verbatim.
         */
        public val shares: Int,
    )

    @Serializable
    public data class Counts(public val documents: Int)
}

/** Everything needed to render and edit a single expense. */
@Serializable
public data class ExpenseDetails(
    public val id: String,
    public val groupId: String,
    public val title: String,
    /** Minor units, in the **group's** currency, see [originalAmount] for the other scale. */
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
    /**
     * What was actually paid, in [originalCurrency]'s **own** minor units, when the expense was
     * in a currency the group is not denominated in. Null when it was in the group's currency.
     *
     * A converted expense therefore carries two amounts on two different scales, and formatting
     * either with the other's currency is a bug that looks plausible.
     */
    public val originalAmount: Int? = null,
    /** ISO-4217 of what was actually paid, and the field that says an expense was converted. */
    public val originalCurrency: String? = null,
    /** [amount] ÷ [originalAmount]: one unit of [originalCurrency] in the group's currency. */
    public val conversionRate: LenientDecimal? = null,
) {
    /**
     * The detail payload names each payee by ID alone, where [ExpenseListItem.PaidFor] nests a
     * whole participant. The form screen wants the IDs, so this is the useful half.
     */
    @Serializable
    public data class PaidFor(
        public val participantId: String,
        /** Share value ×100, or minor units under [SplitMode.BY_AMOUNT], see [ExpenseListItem.PaidFor.shares]. */
        public val shares: Int,
    )
}

/**
 * One participant's standing, as `groups.balances.list` computes it.
 *
 * **Only [total] means anything.** The server derives [paid] and [paidFor] from the suggested
 * payments rather than from the expenses, so one of the two is always zero and the other is
 * `abs(total)`. Reading them as "what Ana spent" gives a number that is real, stable, and about
 * something else.
 */
@Serializable
public data class Balance(
    public val paid: Int,
    public val paidFor: Int,
    /** Minor units. Negative means this participant owes. */
    public val total: Int,
)

/** A payment that would settle part of a group, as the server suggests it. */
@Serializable
public data class Reimbursement(
    /** Participant ID of whoever owes. */
    public val from: String,
    /** Participant ID of whoever is owed. */
    public val to: String,
    /** Minor units. */
    public val amount: Int,
)

/**
 * What a recorded activity was.
 *
 * Unknown values decode instead of throwing, which [SplitMode] deliberately does not: a split
 * mode this client cannot read is money it would divide wrongly, while an activity it cannot
 * read is one line of prose. Instances are self-hosted and may be ahead of this client, so a
 * server that grows a fifth kind should cost the log a row, not the whole tab. [RecurrenceRule]
 * makes the same trade for the same reason.
 */
@Serializable(with = ActivityTypeSerializer::class)
public sealed interface ActivityType {
    public data object UpdateGroup : ActivityType
    public data object CreateExpense : ActivityType
    public data object UpdateExpense : ActivityType
    public data object DeleteExpense : ActivityType

    /** Something this version has no sentence for. [raw] is the server's word for it. */
    public data class Unknown(public val raw: String) : ActivityType

    /** False for a kind this version cannot describe, and so should not draw a row for. */
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

/** One thing that happened to a group, as `groups.activities.list` records it. */
@Serializable
public data class Activity(
    public val id: String,
    public val groupId: String,
    @Contextual public val time: Instant,
    public val activityType: ActivityType,
    /**
     * Who did it, but only when the client that did it said so. The four mutating procedures
     * take an optional `participantId` and none of them requires it, so this is null for
     * anything written before someone identified themselves. Nothing backfills it.
     */
    public val participantId: String? = null,
    public val expenseId: String? = null,
    /**
     * The expense's title **as it was** when this was recorded, which is the point: renaming an
     * expense leaves the old name on the line describing its creation. The server calls this
     * column `data`, and it has never held anything else.
     */
    @SerialName("data") public val title: String? = null,
    /**
     * Whether the expense this refers to is still in the group.
     *
     * The server sends the whole expense alongside each row; all a log row does with it is decide
     * whether it can be opened, and decoding a second copy of a model we already have would only
     * be one more thing to keep in step with the schema.
     */
    @SerialName("expense") private val expenseReference: JsonObject? = null,
) {
    public val expenseStillExists: Boolean get() = expenseReference != null
}
