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

enum class MoneySize {
    HERO,

    LEAD,

    ROW,

    SUPPORT,
}

@Composable
private fun MoneySize.baseStyle(): TextStyle = when (this) {
    MoneySize.HERO -> MaterialTheme.typography.displaySmall
    MoneySize.LEAD -> MaterialTheme.typography.headlineSmall
    MoneySize.ROW -> MaterialTheme.typography.bodyLarge
    MoneySize.SUPPORT -> MaterialTheme.typography.bodySmall
}

enum class MoneySign {
    NONE,

    POSITIVE,

    NEGATIVE,

    SETTLED,

    EXPENSE,
    ;

    companion object {
        fun forBalance(balanceMinorUnits: Long): MoneySign = when {
            balanceMinorUnits > 0 -> POSITIVE
            balanceMinorUnits < 0 -> NEGATIVE
            else -> SETTLED
        }
    }
}

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

@Composable
fun Money(
    value: String,
    modifier: Modifier = Modifier,
    size: MoneySize = MoneySize.ROW,
    sign: MoneySign = MoneySign.NONE,
    isReimbursement: Boolean = false,
    testTag: String? = null,
) {
    // No letterSpacing here: it would override the per-size tracking from baseStyle().
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
