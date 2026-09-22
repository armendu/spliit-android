package app.spliit.android.ui.design

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.spliit.android.ui.theme.SpliitTheme

/**
 * How much the number matters on the screen it is on. Mapped onto M3's named type styles, so it
 * inherits their `sp` and scales with the system font size; `dp` here would be an accessibility
 * bug in the one element a reader most needs.
 */
enum class MoneySize {
    /** A balance headline or a group total. */
    HERO,

    /** A suggested payment. */
    LEAD,

    /** A list row. The default. */
    ROW,

    /** An inline aside. */
    SUPPORT,
}

@Composable
private fun MoneySize.baseStyle(): TextStyle = when (this) {
    MoneySize.HERO -> MaterialTheme.typography.displaySmall
    MoneySize.LEAD -> MaterialTheme.typography.headlineSmall
    MoneySize.ROW -> MaterialTheme.typography.bodyLarge
    MoneySize.SUPPORT -> MaterialTheme.typography.bodySmall
}

/**
 * Whether an amount carries a direction, and which half of the money axis it is drawn in.
 * Colour carries sign only where the amount has one: an expense has none, so [NONE] takes the
 * surrounding text colour.
 *
 * Colour is never the only carrier. This supplies the tint, not the guarantee: a caller drawing
 * [POSITIVE]/[NEGATIVE] on a bare number with no sign and no caption has undone the rule.
 */
enum class MoneySign {
    /** An expense amount. It has no direction, so it takes the default text colour. */
    NONE,

    /** Owed to you. */
    POSITIVE,

    /** You owe. */
    NEGATIVE,

    /** A zero balance, the absence of the axis, not a third point on it. */
    SETTLED,

    /**
     * An expense amount in a list, the brand green. **Not a direction and not on the ledger
     * axis**: an expense has no sign, and `on-surface` would make it the same colour as the
     * title beside it. This holds only because the two never share a screen, so **an expense
     * amount and a balance in one view is the case it does not cover**.
     *
     * One tier, unlike the ledger pair: 6.74:1 in light and the lightened `PrimaryDark` in dark
     * both clear the body-text threshold, so there is no size at which this has to darken.
     */
    EXPENSE,
    ;

    companion object {
        /** @param balanceMinorUnits negative means this participant owes. */
        fun forBalance(balanceMinorUnits: Long): MoneySign = when {
            balanceMinorUnits > 0 -> POSITIVE
            balanceMinorUnits < 0 -> NEGATIVE
            else -> SETTLED
        }
    }
}

/**
 * The ledger axis has two tiers: the bright pair falls short of 4.5:1 and is never drawn under
 * 24px, so only [MoneySize.HERO] takes it. A threshold rather than a lookup table, so a size
 * added later without a colour rule fails safe into the readable tier.
 */
@Composable
private fun MoneySign.tint(size: MoneySize): Color {
    val colors = SpliitTheme.colors
    val useLargeTier = size == MoneySize.HERO
    return when (this) {
        MoneySign.NONE -> LocalContentColor.current
        MoneySign.POSITIVE -> if (useLargeTier) colors.moneyPositiveLarge else colors.moneyPositiveText
        MoneySign.NEGATIVE -> if (useLargeTier) colors.moneyNegativeLarge else colors.moneyNegativeText
        MoneySign.SETTLED -> MaterialTheme.colorScheme.onSurfaceVariant
        MoneySign.EXPENSE -> MaterialTheme.colorScheme.primary
    }
}

/**
 * Every amount in the app, in one treatment: tabular figures always, so a column does not jitter
 * as digits change, tight tracking, semibold unless [isReimbursement] softens it.
 *
 * @param value Preformatted by `MoneyFormatter`. Never assembled here and never split from its
 *   symbol: a screen reader should read one label, not "dollar" then "20.00".
 * @param isReimbursement Drawn regular and italic, as an aside rather than a charge.
 */
@Composable
fun Money(
    value: String,
    modifier: Modifier = Modifier,
    size: MoneySize = MoneySize.ROW,
    sign: MoneySign = MoneySign.NONE,
    isReimbursement: Boolean = false,
    testTag: String? = null,
) {
    // Tracking is deliberately not set: baseStyle() carries the per-size value, and merge lets a
    // non-null field here win, so one flat constant would silently discard that table.
    val style = size.baseStyle().merge(
        TextStyle(
            fontFeatureSettings = "tnum",
            fontWeight = if (isReimbursement) FontWeight.Normal else FontWeight.SemiBold,
            fontStyle = if (isReimbursement) FontStyle.Italic else FontStyle.Normal,
        ),
    )
    Text(
        text = value,
        style = style,
        color = sign.tint(size),
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
    )
}
