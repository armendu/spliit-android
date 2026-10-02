package app.spliit.android.feature.expense

import app.spliit.core.ExpenseFormDraft
import app.spliit.core.MoneyFormatter
import app.spliit.core.SplitMode
import java.math.BigDecimal

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

internal fun SplitMode.unitLabel(currencySymbol: String): String = when (this) {
    SplitMode.EVENLY -> ""
    SplitMode.BY_SHARES -> "shares"
    SplitMode.BY_PERCENTAGE -> "%"
    SplitMode.BY_AMOUNT -> currencySymbol
}

private fun percentText(hundredths: Long): String =
    BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString()
