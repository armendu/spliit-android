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
 * How much the number matters on the screen it is on — DESIGN.md §3. Mapped onto M3's *named*
 * type styles rather than given point sizes of its own, and inheriting their `sp` units, so
 * every amount scales with the system font size the same way iOS's Dynamic Type does. Not `dp`:
 * an amount that ignores the reader's text size is an accessibility bug in the one element they
 * most need to read.
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
 * Whether an amount carries a direction, and — where it does — which half of the money axis it
 * is drawn in. DESIGN.md §3's sign rules: colour carries sign *only* where the amount has one.
 * An expense amount has none, so [NONE] takes the surrounding text colour rather than a tint of
 * its own. A balance has one, and [SETTLED] is deliberately [MaterialTheme.colorScheme]'s
 * `onSurfaceVariant` rather than a third money colour — zero is not an outcome worth tinting.
 *
 * Colour is never the only carrier: a caller drawing [POSITIVE] / [NEGATIVE] on a bare number,
 * with no sign character in `value` and no caption above stating the direction, has reintroduced
 * exactly what that section warns against — this enum only supplies the tint, not the guarantee.
 */
enum class MoneySign {
    /** An expense amount. It has no direction, so it takes the default text colour. */
    NONE,

    /** Owed to you. */
    POSITIVE,

    /** You owe. */
    NEGATIVE,

    /** A zero balance — the absence of the axis, not a third point on it. */
    SETTLED,
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
 * The ledger axis has two tiers, not one — DESIGN.md §1: the bright pair reads fine at large
 * sizes and on non-text marks but falls short of the 4.5:1 body-text threshold, so it is never
 * drawn under 24px. [MoneySize.HERO] is the only [MoneySize] guaranteed to clear that on every
 * supported system font scale (`displaySmall` is 32sp); every other size — including [MoneySize
 * .LEAD], which sits well under 24sp — takes the AA-safe text tier. A size threshold rather than
 * a per-size lookup table, so a future size added to [MoneySize] without an explicit colour rule
 * fails safe into the readable tier instead of the bright one.
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
    }
}

/**
 * Every amount in the app, in one treatment.
 *
 * Money is what Spliit is for, so it gets a typeface treatment of its own rather than being body
 * text that happens to contain digits: tabular figures always (a column of amounts must not
 * jitter as digits change — DESIGN.md §3), a fixed tight tracking, and semibold weight unless
 * [isReimbursement] softens it.
 *
 * `fontFeatureSettings = "tnum"` selects the tabular-figure variant Inter ships — DESIGN.md §2:
 * "Numbers are the wayfinding," required on every amount in the app, not only here. Inter itself
 * comes from whichever named [MoneySize] style this borrows ([SpliitTypography] sets it once,
 * globally); this composable only adds what a plain body style does not already carry — the
 * tabular feature, the tighter tracking, and the sign tint.
 *
 * @param value Preformatted by `MoneyFormatter`. Never assembled here, and never split the
 *   currency symbol into an element of its own: a screen reader should read exactly what the
 *   formatter produced as one label, not "dollar" then "20.00" as two.
 * @param isReimbursement Reimbursements read as an aside rather than a charge — regular weight
 *   and italic, matching how the iOS expense list has always drawn their titles.
 * @param testTag Applied to this composable's own `Text` — a leaf, never a wrapping container.
 *   See [app.spliit.android.ui.TestTags].
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
    // Tracking is deliberately NOT set here: size.baseStyle() already carries the per-style
    // value DESIGN.md §2's table specifies (e.g. -0.02em for a hero, -0.005em for a row amount),
    // and TextStyle.merge lets a non-null field here win — so overriding it with one flat
    // constant across all four sizes, as an earlier iOS-ported treatment did, would silently
    // discard that per-size table.
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
