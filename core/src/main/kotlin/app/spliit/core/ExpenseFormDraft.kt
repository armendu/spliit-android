package app.spliit.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

// The expense form's arithmetic: what an expense divides into, and what the server would refuse.
//
// Do not "simplify" the shares rule. `paidFor[].shares` is one field carrying four things,
// decided by the sibling `splitMode`:
//
//   | mode          | what `shares` carries                  | scales with currency? |
//   |---------------|----------------------------------------|-----------------------|
//   | EVENLY        | share value x100, always exactly 100    | no                    |
//   | BY_SHARES     | share value x100, 2 shares are 200      | no                    |
//   | BY_PERCENTAGE | percentage x100, 30% is 3000            | no                    |
//   | BY_AMOUNT     | a raw minor-unit amount                 | yes                   |
//
// Only BY_AMOUNT is money. An even split in yen still sends 100 a head. Get it wrong either way
// and the numbers look plausible: scale the x100 modes and a yen group splits `1` a head; scale
// BY_AMOUNT too and every by-amount expense is 100x the money. `ExpenseFormDraftTest` pins all
// four in three currencies so a tidying-up pass has to notice.
//
// `:core` counts minor units in [Long]; `:api` uses [Int] because that is what the wire carries.
// A sum overflows 32 bits in a zero-decimal currency well before the figure is unreasonable
// (2.1 billion VND is about $85,000), and an overflow is a wrong number, not an error.
// [MinorUnits] is the only place the two widths meet.

/**
 * The one place minor units change width between `:core` and `:api`.
 *
 * `:api` owns [Int], it is the wire's type, and the server's. `:core` owns [Long] for the
 * reason given at the top of this file. Everything that crosses does so here: the draft takes
 * wire-width values in [ExpenseFormDraft.ExistingExpense] and hands them back in
 * [ExpenseSubmission.Wire], and `:app` copies those straight into `ExpenseFormValues`.
 */
public object MinorUnits {
    /** Widening, which never fails. */
    public fun fromWire(amount: Int): Long = amount.toLong()

    /**
     * Narrowing, which can. It throws rather than wrapping: a silently negative amount is the
     * kind of bug this whole module exists to avoid, and validation already caps an expense at
     * [ExpenseFormDraft.MAX_AMOUNT_MINOR_UNITS], well inside an [Int], so reaching the throw
     * means something upstream is already wrong.
     */
    public fun toWire(amount: Long): Int = Math.toIntExact(amount)
}

/**
 * How an expense divides between the people it was paid for.
 *
 * `:core`'s own, deliberately not `:api`'s: this module does not depend on the wire models, see
 * CLAUDE.md, and the split maths is about amounts and participants. `:app` maps the two, which
 * is a `when` over four entries that the compiler checks.
 */
public enum class SplitMode {
    EVENLY,
    BY_SHARES,
    BY_PERCENTAGE,
    BY_AMOUNT,
}

/** A group member as the split maths needs one. `:app` maps `api.Participant` onto it. */
public data class Participant(
    public val id: String,
    public val name: String,
)

/** One participant's row in the "Paid for" list. */
public data class ParticipantShareDraft(
    /** The server-side participant ID. */
    public val id: String,
    public val name: String,
    public val isIncluded: Boolean = false,
    /**
     * What the user typed: a share count, a percentage, or an amount, depending on the split
     * mode. Unused when splitting evenly.
     */
    public val valueText: String = "1",
)

/** What one participant ends up owing, in the group's minor units. */
public data class ParticipantAmount(
    public val participantId: String,
    public val amount: Long,
)

/**
 * The expense form's state, plus the validation the server would apply. Mirrors the web app's
 * `expenseFormSchema`, including the split-mode sum rules. Immutable.
 *
 * Documents live in `:app`: they are a wire model and nothing here computes from them.
 */
public data class ExpenseFormDraft(
    public val title: String = "",
    public val expenseDate: Instant = Instant.now(),
    /**
     * The total, as typed. Stale whenever [conversionRequired] is true, where the total is
     * derived from the rate instead. Use [amountMinorUnits], which is authoritative in both
     * cases.
     */
    public val amountText: String = "",
    public val categoryId: Int = 0,
    public val paidById: String? = null,
    public val splitMode: SplitMode = SplitMode.EVENLY,
    public val participants: List<ParticipantShareDraft> = emptyList(),
    /** Whether this split becomes the group's default. Always starts off, editing included. */
    public val saveSplitAsDefault: Boolean = false,
    public val isReimbursement: Boolean = false,
    public val notes: String = "",
    /**
     * The server's word for how often this repeats. Text, not an enum, so an instance ahead of
     * this client keeps its cadence instead of being reset to NONE on save.
     */
    public val recurrenceRule: String = NO_RECURRENCE,
    /** Used to read typed numbers; a comma is the decimal separator in much of the world. */
    public val locale: Locale = Locale.getDefault(),
    /** The group's ISO code, or null for a free-text symbol. Conversion needs one. */
    public val groupCurrencyCode: String? = null,
    /** What this expense was paid in. Only means anything once it differs from the group's. */
    public val originalCurrencyCode: String? = null,
    /** What was paid, as typed, in [originalCurrencyCode], **not** in the group's currency. */
    public val originalAmountText: String = "",
    /** One unit of [originalCurrencyCode] in the group's currency. A rate, never scaled by
     *  minor units. */
    public val conversionRateText: String = "",
) {

    /**
     * Minor-unit digits of the *group's* currency. Only the total and BY_AMOUNT shares scale
     * with it; counts and percentages are x100 regardless.
     */
    public val minorUnitDigits: Int get() = MoneyFormatter.minorUnitDigits(groupCurrencyCode)

    /** Minor-unit digits of what was paid, which is not the group's: a €40.00 dinner in a yen
     *  group is 4000 in one field and 6540 in the other. */
    public val originalMinorUnitDigits: Int
        get() = MoneyFormatter.minorUnitDigits(originalCurrencyCode)

    // ---- derived amounts --------------------------------------------------------------

    /**
     * The total in the group's minor units, or null if what was typed is not a number. Derived
     * from the rate under a conversion, so a stored expense's amount always matches its rate.
     */
    public val amountMinorUnits: Long?
        get() = if (conversionRequired) {
            convertedAmountMinorUnits
        } else {
            MoneyFormatter.parseMinorUnits(amountText, locale, minorUnitDigits)
        }

    /**
     * Whether this was paid in a currency the group is not denominated in. Both sides need ISO
     * codes, so a group with only a free-text symbol cannot convert at all.
     */
    public val conversionRequired: Boolean
        get() {
            val group = isoCode(groupCurrencyCode) ?: return false
            val original = isoCode(originalCurrencyCode) ?: return false
            return group != original
        }

    /** What was paid, in the minor units of the currency it was paid in. */
    public val originalAmountMinorUnits: Long?
        get() = MoneyFormatter.parseMinorUnits(originalAmountText, locale, originalMinorUnitDigits)

    public val conversionRate: BigDecimal?
        get() = MoneyFormatter.parseDecimal(conversionRateText, locale)

    /** What the amount paid comes to in the group's currency, in its minor units. */
    public val convertedAmountMinorUnits: Long?
        get() {
            if (!conversionRequired) return null
            val paid = originalAmountMinorUnits ?: return null
            val rate = conversionRate ?: return null
            // BigDecimal, not double: a rate is a decimal the user typed, and a double would
            // round it before the money got involved.
            return try {
                BigDecimal.valueOf(paid)
                    .movePointLeft(originalMinorUnitDigits)
                    .multiply(rate)
                    .movePointRight(minorUnitDigits)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                // Larger than an amount can hold. An input field takes anything.
                null
            }
        }

    // ---- the split --------------------------------------------------------------------

    public val includedParticipants: List<ParticipantShareDraft>
        get() = participants.filter { it.isIncluded }

    /** Whether the split covers the whole group, what decides "select all" from "select none". */
    public val allParticipantsIncluded: Boolean
        get() = participants.isNotEmpty() && participants.all { it.isIncluded }

    /** Whether keeping this split as the default is worth offering. Never for a reimbursement,
     *  which is one person paying one other and not the shape of ordinary expenses. */
    public val isSplitWorthRemembering: Boolean get() = !isReimbursement

    /** One participant's `shares` value. Meaning changes with [splitMode]: see the table at
     *  the top of this file. */
    public fun shareValue(participant: ParticipantShareDraft): Long? = when (splitMode) {
        // Scaled by the protocol, not the currency: two shares are 200 even in a yen group,
        // hence no minorUnitDigits here.
        SplitMode.EVENLY -> EVEN_SHARE
        SplitMode.BY_SHARES,
        SplitMode.BY_PERCENTAGE,
        -> MoneyFormatter.parseMinorUnits(participant.valueText, locale)
        // The one mode whose shares are money, and so the one that follows the group's currency.
        SplitMode.BY_AMOUNT ->
            MoneyFormatter.parseMinorUnits(participant.valueText, locale, minorUnitDigits)
    }

    /**
     * For [SplitMode.BY_AMOUNT], how far the shares are from the total; for
     * [SplitMode.BY_PERCENTAGE], from 100%, in hundredths of a percent, since that is the unit
     * the protocol counts percentages in. Positive means there is more to allocate.
     */
    public val unallocated: Long?
        get() {
            val allocated = includedParticipants.sumOf { shareValue(it) ?: 0L }
            return when (splitMode) {
                SplitMode.BY_AMOUNT -> (amountMinorUnits ?: return null) - allocated
                SplitMode.BY_PERCENTAGE -> WHOLE - allocated
                SplitMode.EVENLY, SplitMode.BY_SHARES -> null
            }
        }

    /**
     * What each included participant owes, in the group's minor units.
     *
     * Apportioned in whole minor units: a third of 10.00 is 334/333/333, and the sum is exact.
     * The extra unit goes to the participants earliest in this list, which is the server's
     * participant order, so every device and both apps apportion identically. That ordering is
     * why this is a list and not a Map.
     *
     * Empty while the amount or any share is not yet a usable number.
     */
    public fun splitAmounts(): List<ParticipantAmount> {
        val included = includedParticipants
        if (included.isEmpty()) return emptyList()

        // By-amount is not apportioned at all: the amounts *are* what was typed, and validation
        // is what makes them add up.
        if (splitMode == SplitMode.BY_AMOUNT) {
            return included.map {
                ParticipantAmount(it.id, shareValue(it) ?: return emptyList())
            }
        }

        val total = amountMinorUnits ?: return emptyList()
        val weights = when (splitMode) {
            // Equal weights, so largest-remainder degenerates into "the first few pay extra".
            SplitMode.EVENLY -> included.map { 1L }
            else -> included.map { participant ->
                shareValue(participant)?.takeIf { it > 0 } ?: return emptyList()
            }
        }
        return included.zip(apportion(total, weights)) { participant, amount ->
            ParticipantAmount(participant.id, amount)
        }
    }

    // ---- editing the draft ------------------------------------------------------------

    /**
     * Changing how the expense divides. Keeps who it was paid for, and reseeds the share values
     * so the new mode already adds up. Rows outside the split keep what they had.
     */
    public fun withSplitMode(mode: SplitMode): ExpenseFormDraft =
        copy(splitMode = mode, participants = seededShares(mode))

    public fun withParticipantIncluded(id: String, isIncluded: Boolean): ExpenseFormDraft =
        copy(
            participants = participants.map {
                if (it.id == id) it.copy(isIncluded = isIncluded) else it
            },
        )

    /** Puts the whole group in the split, or takes it all out. Everyone keeps the share they
     *  were last given, so an accidental "select none" costs no typing. */
    public fun withAllParticipantsIncluded(isIncluded: Boolean): ExpenseFormDraft =
        copy(participants = participants.map { it.copy(isIncluded = isIncluded) })

    /** Changing what the expense was paid in. Returning to the group's currency carries the
     *  converted total across, so the shown value does not snap back. */
    public fun withCurrency(code: String?): ExpenseFormDraft {
        val converted = convertedAmountMinorUnits
        val isADifferentCurrency = isoCode(code) != isoCode(originalCurrencyCode)

        // A rate belongs to a pair of currencies and cannot follow to another. What was paid
        // stays: it is what the receipt says.
        val moved = copy(
            originalCurrencyCode = code,
            conversionRateText = if (isADifferentCurrency) "" else conversionRateText,
        )
        return if (!moved.conversionRequired && converted != null) {
            moved.copy(amountText = minorUnitsText(converted, groupCurrencyCode, locale))
        } else {
            moved
        }
    }

    /** Takes a looked-up rate as the one to use. */
    public fun withRate(rate: BigDecimal): ExpenseFormDraft =
        copy(conversionRateText = rateText(rate, locale))

    private fun seededShares(mode: SplitMode): List<ParticipantShareDraft> {
        val seeds: Map<String, String> = when (mode) {
            // Evenly divides without a per-row value, so there is nothing to seed.
            SplitMode.EVENLY -> return participants
            SplitMode.BY_SHARES -> includedParticipants.associate { it.id to "1" }
            SplitMode.BY_PERCENTAGE -> {
                val included = includedParticipants
                if (included.isEmpty()) return participants
                included.map { it.id }
                    .zip(apportion(WHOLE, included.map { 1L }))
                    .associate { (id, hundredths) -> id to hundredthsText(hundredths, locale) }
            }
            SplitMode.BY_AMOUNT -> {
                val amounts = copy(splitMode = SplitMode.EVENLY).splitAmounts()
                if (amounts.isEmpty()) return participants
                amounts.associate {
                    it.participantId to minorUnitsText(it.amount, groupCurrencyCode, locale)
                }
            }
        }
        return participants.map { participant ->
            seeds[participant.id]?.let { participant.copy(valueText = it) } ?: participant
        }
    }

    // ---- validation -------------------------------------------------------------------

    /** Which box on the form a [Problem] belongs against. */
    public enum class Field {
        TITLE,
        AMOUNT,
        ORIGINAL_AMOUNT,
        CONVERSION_RATE,
        PAID_BY,
        PAID_FOR,
    }

    /**
     * One thing the server would refuse, and which field it belongs to, so a screen can label
     * the box that is wrong.
     *
     * The rules follow the web app's `expenseFormSchema` so that the two cannot drift; the
     * sentences a reader sees are `:app`'s, since `:core` has no resources and no locale for
     * prose.
     */
    public sealed interface Problem {
        public val field: Field

        /** Set for the problems that are one participant's rather than the split's. */
        public val participantId: String? get() = null

        public data object TitleTooShort : Problem {
            override val field: Field get() = Field.TITLE
        }

        public data object AmountMissing : Problem {
            override val field: Field get() = Field.AMOUNT
        }

        public data object AmountNotANumber : Problem {
            override val field: Field get() = Field.AMOUNT
        }

        public data object AmountZero : Problem {
            override val field: Field get() = Field.AMOUNT
        }

        /**
         * An expense of minus ten euros is not a correction, it is a balances screen with the
         * signs inverted for everybody it was paid for. Worth its own problem because
         * [MoneyFormatter.parseMinorUnits] reads a leading minus deliberately, so this is a
         * value a field can really produce rather than one only a bug could.
         */
        public data object AmountNegative : Problem {
            override val field: Field get() = Field.AMOUNT
        }

        public data object AmountTooLarge : Problem {
            override val field: Field get() = Field.AMOUNT
        }

        public data object OriginalAmountMissing : Problem {
            override val field: Field get() = Field.ORIGINAL_AMOUNT
        }

        public data object OriginalAmountNotANumber : Problem {
            override val field: Field get() = Field.ORIGINAL_AMOUNT
        }

        public data object OriginalAmountZero : Problem {
            override val field: Field get() = Field.ORIGINAL_AMOUNT
        }

        /** As [AmountNegative], for what was actually paid. */
        public data object OriginalAmountNegative : Problem {
            override val field: Field get() = Field.ORIGINAL_AMOUNT
        }

        public data object OriginalAmountTooLarge : Problem {
            override val field: Field get() = Field.ORIGINAL_AMOUNT
        }

        public data object ConversionRateMissing : Problem {
            override val field: Field get() = Field.CONVERSION_RATE
        }

        public data object ConversionRateNotANumber : Problem {
            override val field: Field get() = Field.CONVERSION_RATE
        }

        public data object ConversionRateNotPositive : Problem {
            override val field: Field get() = Field.CONVERSION_RATE
        }

        public data object PayerMissing : Problem {
            override val field: Field get() = Field.PAID_BY
        }

        public data object NoParticipantsSelected : Problem {
            override val field: Field get() = Field.PAID_FOR
        }

        public data class ShareNotANumber(override val participantId: String) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }

        public data class ShareNotPositive(override val participantId: String) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }

        /** Shares must add up to the expense total. [difference] is what is left to allocate. */
        public data class AmountsDoNotSumToTotal(public val difference: Long) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }

        /** Percentages must add up to 100. [difference] is in hundredths of a percent. */
        public data class PercentagesDoNotSumTo100(public val difference: Long) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }
    }

    public val problems: List<Problem>
        get() {
            val problems = mutableListOf<Problem>()

            if (title.trim().length < MIN_TITLE_LENGTH) problems += Problem.TitleTooShort

            if (conversionRequired) {
                // The total derives from these two, but is still checked after: a small
                // enough rate rounds a real payment down to nothing.
                problems += conversionProblems()
                amountMinorUnits?.let { problems += amountProblems(it) }
            } else if (amountText.isBlank()) {
                problems += Problem.AmountMissing
            } else {
                val amount = amountMinorUnits
                if (amount == null) {
                    problems += Problem.AmountNotANumber
                } else {
                    problems += amountProblems(amount)
                }
            }

            if (paidById == null) problems += Problem.PayerMissing

            val included = includedParticipants
            if (included.isEmpty()) problems += Problem.NoParticipantsSelected

            if (splitMode != SplitMode.EVENLY) {
                for (participant in included) {
                    val value = shareValue(participant)
                    when {
                        value == null -> problems += Problem.ShareNotANumber(participant.id)
                        value <= 0 -> problems += Problem.ShareNotPositive(participant.id)
                    }
                }
            }

            // Only worth checking the totals once every individual share is a usable number.
            val sharesAreUsable = problems.none {
                it is Problem.ShareNotANumber || it is Problem.ShareNotPositive
            }
            val difference = unallocated
            if (sharesAreUsable && included.isNotEmpty() && difference != null && difference != 0L) {
                when (splitMode) {
                    SplitMode.BY_AMOUNT ->
                        if (amountMinorUnits != null) {
                            problems += Problem.AmountsDoNotSumToTotal(difference)
                        }
                    SplitMode.BY_PERCENTAGE -> problems += Problem.PercentagesDoNotSumTo100(difference)
                    SplitMode.EVENLY, SplitMode.BY_SHARES -> Unit
                }
            }

            return problems
        }

    public val isValid: Boolean get() = problems.isEmpty()

    /** The problems a given field should draw, which is how a screen labels the right box. */
    public fun problems(field: Field): List<Problem> = problems.filter { it.field == field }

    /** The problems belonging to one participant's row. */
    public fun problems(participantId: String): List<Problem> =
        problems.filter { it.participantId == participantId }

    // Sign, then zero, then ceiling: mutually exclusive, most precise name first.
    private fun amountProblems(amount: Long): List<Problem> = buildList {
        when {
            amount < 0L -> add(Problem.AmountNegative)
            amount == 0L -> add(Problem.AmountZero)
            amount > MAX_AMOUNT_MINOR_UNITS -> add(Problem.AmountTooLarge)
        }
    }

    private fun conversionProblems(): List<Problem> = buildList {
        if (originalAmountText.isBlank()) {
            add(Problem.OriginalAmountMissing)
        } else {
            val paid = originalAmountMinorUnits
            when {
                paid == null -> add(Problem.OriginalAmountNotANumber)
                paid < 0L -> add(Problem.OriginalAmountNegative)
                paid == 0L -> add(Problem.OriginalAmountZero)
                paid > MAX_AMOUNT_MINOR_UNITS -> add(Problem.OriginalAmountTooLarge)
            }
        }

        if (conversionRateText.isBlank()) {
            add(Problem.ConversionRateMissing)
        } else {
            val rate = conversionRate
            when {
                rate == null -> add(Problem.ConversionRateNotANumber)
                rate.signum() <= 0 -> add(Problem.ConversionRateNotPositive)
            }
        }
    }

    // ---- submission -------------------------------------------------------------------

    /** Everything needed to build an `ExpenseFormValues`, or null if the draft is not valid. */
    public fun submission(): ExpenseSubmission? {
        if (!isValid) return null
        val amount = amountMinorUnits ?: return null
        val payer = paidById ?: return null

        val paidFor = includedParticipants.map { participant ->
            ExpenseSubmission.PaidFor(
                participant = participant.id,
                shares = shareValue(participant) ?: return null,
            )
        }
        val trimmedNotes = notes.trim()

        return ExpenseSubmission(
            title = title.trim(),
            expenseDate = expenseDate,
            amount = amount,
            category = categoryId,
            paidBy = payer,
            paidFor = paidFor,
            splitMode = splitMode,
            saveDefaultSplittingOptions = saveSplitAsDefault,
            isReimbursement = isReimbursement,
            notes = trimmedNotes.ifEmpty { null },
            recurrenceRule = recurrenceRule,
            // The currency is what says an expense is converted, so clearing it is what stops
            // that. Reaches the wire as an explicit JSON null.
            originalCurrency = if (conversionRequired) isoCode(originalCurrencyCode) else null,
            originalAmount = if (conversionRequired) originalAmountMinorUnits else null,
            conversionRate = if (conversionRequired) conversionRate else null,
        )
    }

    /** An expense as the server returns it for editing, in the wire's widths. The inbound
     *  half of the boundary [MinorUnits] documents. */
    public data class ExistingExpense(
        public val title: String,
        public val expenseDate: Instant,
        /** Minor units in the **group's** currency, see [originalAmount] for the other scale. */
        public val amount: Int,
        public val categoryId: Int,
        public val paidById: String,
        public val paidFor: List<PaidFor>,
        public val splitMode: SplitMode,
        public val isReimbursement: Boolean,
        public val notes: String? = null,
        public val recurrenceRule: String = NO_RECURRENCE,
        /** In [originalCurrency]'s own minor units, which are not the group's. */
        public val originalAmount: Int? = null,
        /** ISO-4217, and **the field that says the expense was converted**. */
        public val originalCurrency: String? = null,
        public val conversionRate: BigDecimal? = null,
    ) {
        public data class PaidFor(
            public val participantId: String,
            /** As stored: ×100, or minor units under [SplitMode.BY_AMOUNT]. */
            public val shares: Int,
        )
    }

    public companion object {
        /** What [recurrenceRule] holds for an expense that does not repeat. */
        public const val NO_RECURRENCE: String = "NONE"

        /** The web app's ceiling, in minor units, which also keeps amounts inside the wire's
         *  [Int]. */
        public const val MAX_AMOUNT_MINOR_UNITS: Long = 10_000_000_00L

        /** The web app asks for two characters, so a one-letter title would be refused anyway. */
        private const val MIN_TITLE_LENGTH = 2

        /** "Payment" in the server's fixed category table, where settling-up expenses are
         *  filed. The table is compiled into every instance, so this is a constant. */
        private const val PAYMENT_CATEGORY_ID = 1

        /** One share, ×100. What every participant in an even split is sent as. */
        private const val EVEN_SHARE = 100L

        /** 100%, in the hundredths of a percent the protocol counts percentages in. */
        private const val WHOLE = 100_00L

        /**
         * A blank expense for a group: everyone included, split evenly.
         *
         * @param paidBy who the user says they are here. An ID the group no longer has falls
         *   back to the first participant rather than naming a stranger.
         * @param defaultSplit the group's remembered split, if any. One naming somebody who has
         *   left is dropped whole rather than trimmed, so it cannot silently exclude anyone.
         */
        public fun creating(
            participants: List<Participant>,
            groupCurrencyCode: String?,
            paidBy: String? = null,
            defaultSplit: DefaultSplit? = null,
            locale: Locale = Locale.getDefault(),
        ): ExpenseFormDraft {
            val payer = participants.firstOrNull { it.id == paidBy } ?: participants.firstOrNull()
            val split = defaultSplit?.orDefaultFor(participants) ?: DefaultSplit.DEFAULT
            return ExpenseFormDraft(
                paidById = payer?.id,
                splitMode = split.splitMode,
                // DefaultSplit.apply decides who is in and what each row starts at, including
                // "nothing remembered means everybody".
                participants = split.apply(participants, locale),
                locale = locale,
                groupCurrencyCode = groupCurrencyCode,
                originalCurrencyCode = groupCurrencyCode,
            )
        }

        /**
         * A reimbursement prefilled from a suggested payment. Spliit has no "mark as paid": a
         * debt is settled by recording an expense the payer paid for the payee alone. The split
         * is therefore one-sided, and never worth remembering as a default.
         *
         * @param amountMinorUnits the suggested payment, in the group's minor units.
         */
        public fun settling(
            fromParticipantId: String,
            toParticipantId: String,
            amountMinorUnits: Long,
            title: String,
            participants: List<Participant>,
            groupCurrencyCode: String?,
            locale: Locale = Locale.getDefault(),
        ): ExpenseFormDraft = ExpenseFormDraft(
            title = title,
            amountText = minorUnitsText(amountMinorUnits, groupCurrencyCode, locale),
            categoryId = PAYMENT_CATEGORY_ID,
            paidById = fromParticipantId,
            splitMode = SplitMode.EVENLY,
            participants = participants.map {
                ParticipantShareDraft(
                    id = it.id,
                    name = it.name,
                    isIncluded = it.id == toParticipantId,
                )
            },
            isReimbursement = true,
            locale = locale,
            groupCurrencyCode = groupCurrencyCode,
            originalCurrencyCode = groupCurrencyCode,
        )

        /**
         * An expense loaded for editing. No [ExistingExpense.originalCurrency] means not
         * converted, whatever the other two fields hold: the server cannot clear those columns,
         * so reading them back would resurrect a conversion the user removed.
         */
        public fun editing(
            expense: ExistingExpense,
            participants: List<Participant>,
            groupCurrencyCode: String?,
            locale: Locale = Locale.getDefault(),
        ): ExpenseFormDraft {
            val shares = expense.paidFor.associate { it.participantId to MinorUnits.fromWire(it.shares) }
            // The currency, and nothing else, is what says this expense was converted.
            val wasConverted = expense.originalCurrency != null
            val originalCode = expense.originalCurrency ?: groupCurrencyCode

            return ExpenseFormDraft(
                title = expense.title,
                expenseDate = expense.expenseDate,
                // Filled even under a conversion, where it is not what gets saved: it is what
                // the field shows if the conversion is dropped. See [amountText].
                amountText = minorUnitsText(MinorUnits.fromWire(expense.amount), groupCurrencyCode, locale),
                categoryId = expense.categoryId,
                paidById = expense.paidById,
                splitMode = expense.splitMode,
                participants = participants.map { participant ->
                    val share = shares[participant.id]
                    ParticipantShareDraft(
                        id = participant.id,
                        name = participant.name,
                        isIncluded = share != null,
                        valueText = shareText(
                            shares = share ?: EVEN_SHARE,
                            splitMode = expense.splitMode,
                            groupCurrencyCode = groupCurrencyCode,
                            locale = locale,
                        ),
                    )
                },
                isReimbursement = expense.isReimbursement,
                notes = expense.notes.orEmpty(),
                recurrenceRule = expense.recurrenceRule,
                locale = locale,
                groupCurrencyCode = groupCurrencyCode,
                originalCurrencyCode = originalCode,
                originalAmountText = if (wasConverted) {
                    expense.originalAmount?.let {
                        // Its own precision, never the group's: ¥6,540 read with two digits
                        // gives 65.40 yen.
                        minorUnitsText(MinorUnits.fromWire(it), expense.originalCurrency, locale)
                    }.orEmpty()
                } else {
                    ""
                },
                conversionRateText = if (wasConverted) {
                    expense.conversionRate?.let { rateText(it, locale) }.orEmpty()
                } else {
                    ""
                },
            )
        }

        /** An amount without grouping or a symbol, at [currencyCode]'s own precision. */
        internal fun minorUnitsText(minorUnits: Long, currencyCode: String?, locale: Locale): String =
            MoneyFormatter(currencyCode = currencyCode, locale = locale).formatPlain(minorUnits)

        /** A rate at the precision it arrived with, up to six places, never grouped. */
        public fun rateText(rate: BigDecimal, locale: Locale = Locale.getDefault()): String {
            val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
            format.isGroupingUsed = false
            format.minimumFractionDigits = 0
            format.maximumFractionDigits = 6
            format.roundingMode = RoundingMode.HALF_UP
            return format.format(rate)
        }

        /** A ×100 share or percentage as a field shows it: whole numbers without a `.00`. */
        private fun hundredthsText(shares: Long, locale: Locale): String =
            if (shares % 100L == 0L) (shares / 100L).toString() else minorUnitsText(shares, null, locale)

        /** A stored `shares` value back in the field it came from, see the table up top. */
        private fun shareText(
            shares: Long,
            splitMode: SplitMode,
            groupCurrencyCode: String?,
            locale: Locale,
        ): String = when (splitMode) {
            // Already minor units in the group's currency, so the group's precision. The
            // other three modes are hundredths whatever the currency.
            SplitMode.BY_AMOUNT -> minorUnitsText(shares, groupCurrencyCode, locale)
            SplitMode.EVENLY, SplitMode.BY_SHARES, SplitMode.BY_PERCENTAGE ->
                hundredthsText(shares, locale)
        }

        /**
         * Apportions [total] across [weights] in whole minor units, largest remainder first,
         * ties broken by position (see [splitAmounts]).
         *
         * Floor division, not truncation, so the leftover is never negative and a refund
         * apportions like an expense. Products go through [BigInteger] because `total * weight`
         * overflows a Long well before either factor looks unreasonable.
         */
        private fun apportion(total: Long, weights: List<Long>): List<Long> {
            val divisor = weights.fold(BigInteger.ZERO) { sum, weight -> sum + weight.toBigInteger() }
            if (divisor.signum() <= 0) return weights.map { 0L }

            val amount = total.toBigInteger()
            val shares = ArrayList<Long>(weights.size)
            val remainders = ArrayList<BigInteger>(weights.size)
            for (weight in weights) {
                val product = amount * weight.toBigInteger()
                val remainder = product.mod(divisor) // mod, not rem: never negative.
                shares += ((product - remainder) / divisor).toLong()
                remainders += remainder
            }

            var leftover = total - shares.sum()
            val order = weights.indices.sortedWith(
                compareByDescending<Int> { remainders[it] }.thenBy { it },
            )
            for (index in order) {
                if (leftover <= 0) break
                shares[index]++
                leftover--
            }
            return shares
        }

        /**
         * A canonical upper-case currency code, or null if the platform does not know it.
         * Uppercased with `Locale.ROOT`: under a Turkish default, `"iqd".uppercase()` is `İQD`.
         */
        private fun isoCode(code: String?): String? = isoCurrency(code)?.currencyCode
    }
}

/**
 * Everything an `ExpenseFormValues` needs, in `:core`'s own types.
 *
 * `:app` builds the request from [toWire], which is where the widths and the three spellings of
 * "empty" are settled.
 */
public data class ExpenseSubmission(
    public val title: String,
    public val expenseDate: Instant,
    /** The group's minor units. */
    public val amount: Long,
    public val category: Int,
    public val paidBy: String,
    public val paidFor: List<PaidFor>,
    public val splitMode: SplitMode,
    public val saveDefaultSplittingOptions: Boolean,
    public val isReimbursement: Boolean,
    /** Null when there is nothing to say, which leaves whatever note the expense had. */
    public val notes: String?,
    public val recurrenceRule: String,
    /** Null means **not converted**, and clears the conversion. */
    public val originalCurrency: String?,
    /** [originalCurrency]'s minor units, which are not [amount]'s. Null when not converted. */
    public val originalAmount: Long?,
    public val conversionRate: BigDecimal?,
) {
    public data class PaidFor(
        public val participant: String,
        /** ×100, or minor units under [SplitMode.BY_AMOUNT], see `ExpenseFormDraft`. */
        public val shares: Long,
    )

    public fun toWire(): Wire = Wire(
        title = title,
        expenseDate = expenseDate,
        amount = MinorUnits.toWire(amount),
        category = category,
        paidBy = paidBy,
        paidFor = paidFor.map { Wire.PaidFor(it.participant, MinorUnits.toWire(it.shares)) },
        splitMode = splitMode,
        saveDefaultSplittingOptions = saveDefaultSplittingOptions,
        isReimbursement = isReimbursement,
        notes = notes,
        recurrenceRule = recurrenceRule,
        originalCurrency = originalCurrency,
        // Null here means "leave the column alone", not "clear it", see [Wire]. Dropping a
        // conversion is [originalCurrency] going null and these two going quiet.
        originalAmount = originalAmount?.let(MinorUnits::toWire),
        conversionRate = conversionRate,
    )

    /**
     * The same values in the wire's own widths, ready to be copied into an `ExpenseFormValues`
     * field for field.
     *
     * **The three conversion fields do not share one notion of empty**, and the differences were
     * established against a live instance rather than read off the schema:
     *
     * - [originalCurrency], a **null**, which `:api` spells as an explicit JSON null. It is the
     *   only conversion field whose schema accepts one, and so the only way to stop an expense
     *   being converted.
     * - [originalAmount] and [conversionRate], a **null here means omitted**. Both answer 400
     *   to a JSON null and clear with `''`, but neither is cleared: they land on `:api`'s own
     *   defaults, and `encodeDefaults = false` leaves the keys out, so the stored figures stay
     *   in the database untouched and inert. Nothing reads either without [originalCurrency],
     *   which is the field that says an expense was converted at all.
     *
     * Leaving them behind rather than blanking them is a decision the three clients share. The
     * web app, iOS and this app write to **one database**, so the same action has to leave the
     * same state whichever of them performed it: an expense whose conversion was dropped on a
     * phone must look to the browser exactly like one dropped in the browser. It is also the
     * only spelling `:api` can express, `ExpenseFormValues.originalAmount` is an `Int?` and its
     * `conversionRate` a `LenientDecimal?`, and neither can carry a JSON empty string.
     */
    public data class Wire(
        public val title: String,
        public val expenseDate: Instant,
        public val amount: Int,
        public val category: Int,
        public val paidBy: String,
        public val paidFor: List<PaidFor>,
        public val splitMode: SplitMode,
        public val saveDefaultSplittingOptions: Boolean,
        public val isReimbursement: Boolean,
        public val notes: String?,
        public val recurrenceRule: String,
        public val originalCurrency: String?,
        /** [originalCurrency]'s minor units. Null is an omitted key, not a cleared column. */
        public val originalAmount: Int?,
        public val conversionRate: BigDecimal?,
    ) {
        public data class PaidFor(
            public val participant: String,
            public val shares: Int,
        )
    }
}
