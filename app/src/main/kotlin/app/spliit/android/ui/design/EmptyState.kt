package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
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
 * The screen with nothing on it: an icon tile, a title, an optional line of description.
 *
 * @param icon A short, tintable glyph. Text rather than an emoji, whose colour ignores `tint`.
 * @param iconRes One of the app's own glyphs, preferred where a drawing exists.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    @DrawableRes iconRes: Int? = null,
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
                // The mark stands on its own and brings its own colour, so it takes no tile -
                // a tinted tile behind a logo reads as two brand marks arguing.
                art()
            } else if (iconRes != null || !icon.isNullOrBlank()) {
                // Drawn only when there is something to put in it. Unconditionally, a call site
                // passing `icon = ""` produced a 64dp green smudge above the title, which is what
                // the "No expenses yet" screen showed.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(SpliitTheme.colors.brandAccentSoft, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    } else {
                        Text(
                            text = icon.orEmpty(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
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
