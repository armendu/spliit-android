package app.spliit.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

// `paidFor[].shares` means different things per split mode. Don't "simplify" it:
//   EVENLY, BY_SHARES   share value x100 (one share is 100)   not currency-scaled
//   BY_PERCENTAGE       percentage x100 (30% is 3000)          not currency-scaled
//   BY_AMOUNT           raw minor units                        currency-scaled
// :core counts minor units in Long; MinorUnits is the only place they narrow to the wire's Int.

public object MinorUnits {
    public fun fromWire(amount: Int): Long = amount.toLong()

    public fun toWire(amount: Long): Int = Math.toIntExact(amount)
}

public enum class SplitMode {
    EVENLY,
    BY_SHARES,
    BY_PERCENTAGE,
    BY_AMOUNT,
}

public data class Participant(
    public val id: String,
    public val name: String,
)

public data class ParticipantShareDraft(
    public val id: String,
    public val name: String,
    public val isIncluded: Boolean = false,
    public val valueText: String = "1",
)

public data class ParticipantAmount(
    public val participantId: String,
    public val amount: Long,
)

public data class ExpenseFormDraft(
    public val title: String = "",
    public val expenseDate: Instant = Instant.now(),
    public val amountText: String = "",
    public val categoryId: Int = 0,
    public val paidById: String? = null,
    public val splitMode: SplitMode = SplitMode.EVENLY,
    public val participants: List<ParticipantShareDraft> = emptyList(),
    public val saveSplitAsDefault: Boolean = false,
    public val isReimbursement: Boolean = false,
    public val notes: String = "",
    public val recurrenceRule: String = NO_RECURRENCE,
    public val locale: Locale = Locale.getDefault(),
    public val groupCurrencyCode: String? = null,
    public val originalCurrencyCode: String? = null,
    public val originalAmountText: String = "",
    public val conversionRateText: String = "",
) {
    public val minorUnitDigits: Int get() = MoneyFormatter.minorUnitDigits(groupCurrencyCode)

    public val originalMinorUnitDigits: Int
        get() = MoneyFormatter.minorUnitDigits(originalCurrencyCode)

    public val amountMinorUnits: Long?
        get() = if (conversionRequired) {
            convertedAmountMinorUnits
        } else {
            MoneyFormatter.parseMinorUnits(amountText, locale, minorUnitDigits)
        }

    public val conversionRequired: Boolean
        get() {
            val group = isoCode(groupCurrencyCode) ?: return false
            val original = isoCode(originalCurrencyCode) ?: return false
            return group != original
        }

    public val originalAmountMinorUnits: Long?
        get() = MoneyFormatter.parseMinorUnits(originalAmountText, locale, originalMinorUnitDigits)

    public val conversionRate: BigDecimal?
        get() = MoneyFormatter.parseDecimal(conversionRateText, locale)

    public val convertedAmountMinorUnits: Long?
        get() {
            if (!conversionRequired) return null
            val paid = originalAmountMinorUnits ?: return null
            val rate = conversionRate ?: return null
            // BigDecimal, not double: the rate is a typed decimal.
            return try {
                BigDecimal.valueOf(paid)
                    .movePointLeft(originalMinorUnitDigits)
                    .multiply(rate)
                    .movePointRight(minorUnitDigits)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

    public val includedParticipants: List<ParticipantShareDraft>
        get() = participants.filter { it.isIncluded }

    public val allParticipantsIncluded: Boolean
        get() = participants.isNotEmpty() && participants.all { it.isIncluded }

    public val isSplitWorthRemembering: Boolean get() = !isReimbursement

    public fun shareValue(participant: ParticipantShareDraft): Long? = when (splitMode) {
        SplitMode.EVENLY -> EVEN_SHARE
        SplitMode.BY_SHARES,
        SplitMode.BY_PERCENTAGE,
        -> MoneyFormatter.parseMinorUnits(participant.valueText, locale)
        SplitMode.BY_AMOUNT ->
            MoneyFormatter.parseMinorUnits(participant.valueText, locale, minorUnitDigits)
    }

    public val unallocated: Long?
        get() {
            val allocated = includedParticipants.sumOf { shareValue(it) ?: 0L }
            return when (splitMode) {
                SplitMode.BY_AMOUNT -> (amountMinorUnits ?: return null) - allocated
                SplitMode.BY_PERCENTAGE -> WHOLE - allocated
                SplitMode.EVENLY, SplitMode.BY_SHARES -> null
            }
        }

    // Extra units go to the earliest participants in server order, so every device agrees.
    public fun splitAmounts(): List<ParticipantAmount> {
        val included = includedParticipants
        if (included.isEmpty()) return emptyList()

        if (splitMode == SplitMode.BY_AMOUNT) {
            return included.map {
                ParticipantAmount(it.id, shareValue(it) ?: return emptyList())
            }
        }

        val total = amountMinorUnits ?: return emptyList()
        val weights = when (splitMode) {
            SplitMode.EVENLY -> included.map { 1L }
            else -> included.map { participant ->
                shareValue(participant)?.takeIf { it > 0 } ?: return emptyList()
            }
        }
        return included.zip(apportion(total, weights)) { participant, amount ->
            ParticipantAmount(participant.id, amount)
        }
    }

    public fun withSplitMode(mode: SplitMode): ExpenseFormDraft =
        copy(splitMode = mode, participants = seededShares(mode))

    public fun withParticipantIncluded(id: String, isIncluded: Boolean): ExpenseFormDraft =
        copy(
            participants = participants.map {
                if (it.id == id) it.copy(isIncluded = isIncluded) else it
            },
        )

    public fun withAllParticipantsIncluded(isIncluded: Boolean): ExpenseFormDraft =
        copy(participants = participants.map { it.copy(isIncluded = isIncluded) })

    public fun withCurrency(code: String?): ExpenseFormDraft {
        val converted = convertedAmountMinorUnits
        val isADifferentCurrency = isoCode(code) != isoCode(originalCurrencyCode)

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

    private fun seededShares(mode: SplitMode): List<ParticipantShareDraft> {
        val seeds: Map<String, String> = when (mode) {
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

    public enum class Field {
        TITLE,
        AMOUNT,
        ORIGINAL_AMOUNT,
        CONVERSION_RATE,
        PAID_BY,
        PAID_FOR,
    }

    public sealed interface Problem {
        public val field: Field

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

        public data class AmountsDoNotSumToTotal(public val difference: Long) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }

        public data class PercentagesDoNotSumTo100(public val difference: Long) : Problem {
            override val field: Field get() = Field.PAID_FOR
        }
    }

    public val problems: List<Problem>
        get() {
            val problems = mutableListOf<Problem>()

            if (title.trim().length < MIN_TITLE_LENGTH) problems += Problem.TitleTooShort

            if (conversionRequired) {
                // Checked even though derived: a small rate can round a real payment to zero.
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

    public fun problems(field: Field): List<Problem> = problems.filter { it.field == field }

    public fun problems(participantId: String): List<Problem> =
        problems.filter { it.participantId == participantId }

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
            // originalCurrency is what marks a conversion; null reaches the wire as an explicit JSON null.
            originalCurrency = if (conversionRequired) isoCode(originalCurrencyCode) else null,
            originalAmount = if (conversionRequired) originalAmountMinorUnits else null,
            conversionRate = if (conversionRequired) conversionRate else null,
        )
    }

    public data class ExistingExpense(
        public val title: String,
        public val expenseDate: Instant,
        public val amount: Int,
        public val categoryId: Int,
        public val paidById: String,
        public val paidFor: List<PaidFor>,
        public val splitMode: SplitMode,
        public val isReimbursement: Boolean,
        public val notes: String? = null,
        public val recurrenceRule: String = NO_RECURRENCE,
        public val originalAmount: Int? = null,
        public val originalCurrency: String? = null,
        public val conversionRate: BigDecimal? = null,
    ) {
        public data class PaidFor(
            public val participantId: String,
            public val shares: Int,
        )
    }

    public companion object {
        public const val NO_RECURRENCE: String = "NONE"

        public const val MAX_AMOUNT_MINOR_UNITS: Long = 10_000_000_00L

        private const val MIN_TITLE_LENGTH = 2

        private const val PAYMENT_CATEGORY_ID = 1

        private const val EVEN_SHARE = 100L

        private const val WHOLE = 100_00L

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
                participants = split.apply(participants, locale),
                locale = locale,
                groupCurrencyCode = groupCurrencyCode,
                originalCurrencyCode = groupCurrencyCode,
            )
        }

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

        // No originalCurrency means not converted, whatever the stale amount and rate columns hold.
        public fun editing(
            expense: ExistingExpense,
            participants: List<Participant>,
            groupCurrencyCode: String?,
            locale: Locale = Locale.getDefault(),
        ): ExpenseFormDraft {
            val shares = expense.paidFor.associate { it.participantId to MinorUnits.fromWire(it.shares) }
            val wasConverted = expense.originalCurrency != null
            val originalCode = expense.originalCurrency ?: groupCurrencyCode

            return ExpenseFormDraft(
                title = expense.title,
                expenseDate = expense.expenseDate,
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
                        // The original currency's own precision, never the group's.
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

        internal fun minorUnitsText(minorUnits: Long, currencyCode: String?, locale: Locale): String =
            MoneyFormatter(currencyCode = currencyCode, locale = locale).formatPlain(minorUnits)

        public fun rateText(rate: BigDecimal, locale: Locale = Locale.getDefault()): String {
            val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
            format.isGroupingUsed = false
            format.minimumFractionDigits = 0
            format.maximumFractionDigits = 6
            format.roundingMode = RoundingMode.HALF_UP
            return format.format(rate)
        }

        internal fun hundredthsText(shares: Long, locale: Locale): String =
            if (shares % 100L == 0L) (shares / 100L).toString() else minorUnitsText(shares, null, locale)

        private fun shareText(
            shares: Long,
            splitMode: SplitMode,
            groupCurrencyCode: String?,
            locale: Locale,
        ): String = when (splitMode) {
            SplitMode.BY_AMOUNT -> minorUnitsText(shares, groupCurrencyCode, locale)
            SplitMode.EVENLY, SplitMode.BY_SHARES, SplitMode.BY_PERCENTAGE ->
                hundredthsText(shares, locale)
        }

        // Floor division so refunds apportion like expenses; BigInteger because total * weight overflows Long.
        private fun apportion(total: Long, weights: List<Long>): List<Long> {
            val divisor = weights.fold(BigInteger.ZERO) { sum, weight -> sum + weight.toBigInteger() }
            if (divisor.signum() <= 0) return weights.map { 0L }

            val amount = total.toBigInteger()
            val shares = ArrayList<Long>(weights.size)
            val remainders = ArrayList<BigInteger>(weights.size)
            for (weight in weights) {
                val product = amount * weight.toBigInteger()
                val remainder = product.mod(divisor)  // mod, not rem: never negative
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

        private fun isoCode(code: String?): String? = isoCurrency(code)?.currencyCode
    }
}

public data class ExpenseSubmission(
    public val title: String,
    public val expenseDate: Instant,
    public val amount: Long,
    public val category: Int,
    public val paidBy: String,
    public val paidFor: List<PaidFor>,
    public val splitMode: SplitMode,
    public val saveDefaultSplittingOptions: Boolean,
    public val isReimbursement: Boolean,
    public val notes: String?,
    public val recurrenceRule: String,
    public val originalCurrency: String?,
    public val originalAmount: Long?,
    public val conversionRate: BigDecimal?,
) {
    public data class PaidFor(
        public val participant: String,
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
        originalAmount = originalAmount?.let(MinorUnits::toWire),
        conversionRate = conversionRate,
    )

    // Conversion fields disagree on empty: a null originalCurrency is sent as JSON null (clears it);
    // null originalAmount and conversionRate are omitted, since both answer 400 to null.
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
        public val originalAmount: Int?,
        public val conversionRate: BigDecimal?,
    ) {
        public data class PaidFor(
            public val participant: String,
            public val shares: Int,
        )
    }
}
