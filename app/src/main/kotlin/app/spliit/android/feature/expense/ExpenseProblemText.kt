package app.spliit.android.feature.expense

import app.spliit.core.ExpenseFormDraft
import app.spliit.core.MoneyFormatter
import app.spliit.core.SplitMode
import java.math.BigDecimal

/**
 * The sentences `:core`'s [ExpenseFormDraft.Problem]s are read as: the rules live there, the
 * wording here. **This is not a second validator** — nothing below decides whether something is
 * wrong, it only spells out a problem the draft already produced.
 *
 * @param formatter the **group's**, so a remainder is drawn in the expense's own currency.
 */
internal fun ExpenseFormDraft.Problem.message(formatter: MoneyFormatter): String = when (this) {
    ExpenseFormDraft.Problem.TitleTooShort -> "Give the expense a name of at least two letters."
    ExpenseFormDraft.Problem.AmountMissing -> "How much was it?"
    ExpenseFormDraft.Problem.AmountNotANumber -> "That isn't a number."
    ExpenseFormDraft.Problem.AmountZero -> "An expense has to be for more than nothing."
    ExpenseFormDraft.Problem.AmountNegative ->
        "An expense can't be negative. Record money coming back as a reimbursement instead."
    ExpenseFormDraft.Problem.AmountTooLarge -> "That's larger than an expense can be."
    ExpenseFormDraft.Problem.OriginalAmountMissing -> "How much was paid?"
    ExpenseFormDraft.Problem.OriginalAmountNotANumber -> "That isn't a number."
    ExpenseFormDraft.Problem.OriginalAmountZero -> "An expense has to be for more than nothing."
    ExpenseFormDraft.Problem.OriginalAmountNegative -> "What was paid can't be negative."
    ExpenseFormDraft.Problem.OriginalAmountTooLarge -> "That's larger than an expense can be."
    ExpenseFormDraft.Problem.ConversionRateMissing -> "What rate was it converted at?"
    ExpenseFormDraft.Problem.ConversionRateNotANumber -> "That isn't a number."
    ExpenseFormDraft.Problem.ConversionRateNotPositive -> "A rate has to be more than zero."
    ExpenseFormDraft.Problem.PayerMissing -> "Say who paid."
    ExpenseFormDraft.Problem.NoParticipantsSelected -> "Pick at least one person this was paid for."
    is ExpenseFormDraft.Problem.ShareNotANumber -> "That isn't a number."
    is ExpenseFormDraft.Problem.ShareNotPositive -> "A share has to be more than zero."
    is ExpenseFormDraft.Problem.AmountsDoNotSumToTotal -> if (difference > 0) {
        "${formatter.format(difference)} still to allocate."
    } else {
        "${formatter.format(-difference)} over the total."
    }
    is ExpenseFormDraft.Problem.PercentagesDoNotSumTo100 -> if (difference > 0) {
        "${percentText(difference)}% still to allocate."
    } else {
        "${percentText(-difference)}% over 100%."
    }
}

/**
 * What is left to allocate, as a running total rather than as a refusal, drawn under the split
 * before a save has been attempted, which is what turns a rejected save into a live tally.
 *
 * Null for the two modes that cannot be short: an even split and a share split always add up to
 * whatever they add up to. [ExpenseFormDraft.unallocated] is what decides that, not this.
 */
internal fun ExpenseFormDraft.remainderText(formatter: MoneyFormatter): String? {
    val remainder = unallocated ?: return null
    if (remainder == 0L) return null
    return when (splitMode) {
        SplitMode.BY_AMOUNT -> if (remainder > 0) {
            "${formatter.format(remainder)} still to allocate."
        } else {
            "${formatter.format(-remainder)} over the total."
        }
        SplitMode.BY_PERCENTAGE -> if (remainder > 0) {
            "${percentText(remainder)}% still to allocate."
        } else {
            "${percentText(-remainder)}% over 100%."
        }
        SplitMode.EVENLY, SplitMode.BY_SHARES -> null
    }
}

/** What the footnote under the split says when there is nothing left to allocate. */
internal fun SplitMode.explanation(): String = when (this) {
    SplitMode.EVENLY -> "Everyone selected pays an equal part."
    SplitMode.BY_SHARES -> "Give anyone paying a larger part more shares."
    SplitMode.BY_PERCENTAGE -> "Percentages must add up to 100."
    SplitMode.BY_AMOUNT -> "Amounts must add up to the expense total."
}

internal fun SplitMode.title(): String = when (this) {
    SplitMode.EVENLY -> "Equally"
    SplitMode.BY_SHARES -> "By shares"
    SplitMode.BY_PERCENTAGE -> "By %"
    SplitMode.BY_AMOUNT -> "Exact"
}

/** What sits beside a share field: what the number in it means. */
internal fun SplitMode.unitLabel(currencySymbol: String): String = when (this) {
    SplitMode.EVENLY -> ""
    SplitMode.BY_SHARES -> "shares"
    SplitMode.BY_PERCENTAGE -> "%"
    SplitMode.BY_AMOUNT -> currencySymbol
}

/**
 * Hundredths of a percent as a percentage, the unit the protocol counts percentages in, and
 * **never** the currency's, which is why this divides by a literal 100 where money never may.
 * `stripTrailingZeros` so a whole 30% does not read as "30.00%".
 */
private fun percentText(hundredths: Long): String =
    BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString()
