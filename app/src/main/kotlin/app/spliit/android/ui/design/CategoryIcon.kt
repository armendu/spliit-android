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
 * A category's glyph in the rounded slot that will lead an expense row — Part 11's vocabulary,
 * built here because the design system is what this part exists to define.
 *
 * **The glyph carries the hue; the tile does not.** DESIGN.md §5 gives each grouping its own
 * colour, and §3's rule that saturated colour belongs to the amount survives it intact: the tile
 * stays a neutral M3 fill (`surfaceVariant`) and nothing is filled with a block of colour, so
 * the amount is still the only solid saturated thing in a row while the row gains something to
 * scan by.
 *
 * The colour comes from the theme — [SpliitTheme.colors] — rather than from a branch on the
 * system dark setting here. Light and dark are not the same colour for a given hue (see
 * `Color.kt`), and a use site choosing between them is a use site that will drift.
 *
 * The glyphs are authored here rather than pulled from `material-icons-extended`: seven
 * groupings do not justify a dependency the house rule would have to make an exception for, and
 * a set drawn to one stroke weight reads as one family in a way a library's assorted metrics do
 * not. They are stroked, not filled, which is what lets `Icon` tint them at all — an emoji would
 * ignore the tint entirely, and a filled glyph would become the coloured block this avoids.
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
            // The row this leads names the category in its own title (Part 11) — this would
            // just say it again.
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
