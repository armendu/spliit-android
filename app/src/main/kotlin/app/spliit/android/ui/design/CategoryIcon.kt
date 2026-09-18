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
import androidx.compose.ui.unit.dp

/**
 * A category's glyph in the rounded slot that will lead an expense row — Part 11's vocabulary,
 * built here because the design system is what this part exists to define.
 *
 * The tile is a neutral M3 fill (`surfaceVariant`), deliberately: DESIGN.md §3 reserves
 * saturated colour for the amount, and a tinted tile beside it would compete for the glance the
 * amount is supposed to get — the same reasoning as the iOS `CategoryIcon`'s `.secondary` glyph
 * on `.tertiarySystemFill`.
 *
 * The glyphs are authored here rather than pulled from `material-icons-extended`: seven
 * groupings do not justify a dependency the house rule would have to make an exception for, and
 * a set drawn to one stroke weight reads as one family in a way a library's assorted metrics do
 * not. They are stroked, not filled, so `Icon` tints them with the row's own colour — an emoji
 * would ignore that tint and defeat the neutral-fill rule above the moment a row greyed out.
 */
@Composable
fun CategoryIcon(
    grouping: String?,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(size * 0.24f))
            // The row this leads names the category in its own title (Part 11) — this would
            // just say it again.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CategoryGlyphs.drawable(grouping)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/**
 * Keyed by `grouping`, the same top-level key the web app's `category-icon.tsx` and the iOS
 * `ExpenseCategoryIcon` both use before falling back — a category's *grouping* decides this
 * cycle's glyph, not its more specific `name`, so e.g. "Taxi" and "Car" read the same until
 * Part 11 gives per-name icons their own row treatment.
 */
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

    /** Every grouping the server sends today has its own glyph. */
    fun groupingsWithOwnGlyph(): Set<String> = byGrouping.keys
}
