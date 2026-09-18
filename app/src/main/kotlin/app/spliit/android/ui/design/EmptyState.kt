package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.theme.SpliitTheme

/**
 * The screen with nothing on it — the shape `ContentUnavailableView` gives iOS for free: an icon
 * tile, a title and an optional line of description. DESIGN.md §2's icon tile is the only visual
 * rule Part 9 was asked to give this: `BrandAccentSoft` behind an accent-tinted glyph, both M3
 * everywhere else.
 *
 * @param icon A short, tintable glyph — see [CategoryIcon]'s note on why this app draws text
 *   rather than a pictogram or an emoji: an emoji's colour ignores the `tint` parameter Compose
 *   would otherwise apply, which is exactly what would need to change colour between light and
 *   dark or to prove the accent is being applied at all.
 * @param actions Left to the caller. Most placeholders in this part have none.
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    art: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            // Decorative either way: the title beside it carries what a screen reader needs.
            modifier = Modifier.clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                // The mark stands on its own and brings its own colour, so it takes no tile —
                // a tinted tile behind a logo reads as two brand marks arguing.
                art()
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(SpliitTheme.colors.brandAccentSoft, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = icon,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.EMPTY_STATE_TITLE),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TestTags.EMPTY_STATE_DESCRIPTION),
                )
            }
        }

        actions()
    }
}
