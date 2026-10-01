package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.spliit.android.R
import app.spliit.android.ui.theme.SpliitTheme
import androidx.compose.ui.unit.dp

/**
 * A category's glyph in the rounded slot that leads an expense row.
 *
 * **The glyph carries the hue; the tile does not.** The tile stays a neutral `surfaceVariant`, so
 * the amount is still the only solid saturated thing in a row. The colour comes from the theme
 * rather than a branch on the dark setting here, which is where a use site drifts.
 *
 * Authored here rather than pulled from `material-icons-extended`: seven groupings do not justify
 * a dependency, and they are stroked rather than filled, which is what lets `Icon` tint them.
 */
@Composable
fun CategoryIcon(
    grouping: String?,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    // A grouping this version has no colour for keeps the neutral tint rather than being given
    // one at random: an unhued glyph reads as "no category in particular", which is what it is.
    val tint = SpliitTheme.colors.categoryGlyphs[grouping] ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(size * 0.24f))
            // The row already names the category.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CategoryGlyphs.drawable(grouping)),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/** Keyed by a category's `grouping`, not its `name`, like the web and iOS apps. */
object CategoryGlyphs {
    private val byGrouping = mapOf(
        "Entertainment" to R.drawable.ic_cat_entertainment,
        "Food and Drink" to R.drawable.ic_cat_food,
        "Home" to R.drawable.ic_cat_home,
        "Life" to R.drawable.ic_cat_life,
        "Transportation" to R.drawable.ic_cat_transportation,
        "Utilities" to R.drawable.ic_cat_utilities,
    )

    /** Falls through to a banknote, same as the web app's own "Uncategorized" default. */
    fun drawable(grouping: String?): Int =
        byGrouping[grouping] ?: R.drawable.ic_cat_uncategorized
}
