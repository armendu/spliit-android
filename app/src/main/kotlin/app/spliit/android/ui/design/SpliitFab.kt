package app.spliit.android.ui.design

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes

/**
 * The app's floating action button — one definition, so a third one cannot drift into a
 * different green.
 *
 * **Colour.** DESIGN.md §1 names `primary` on `on-primary` for the FAB, and M3's own default is
 * neither: `FloatingActionButton` falls back to `primaryContainer`/`onPrimaryContainer`, a
 * baseline tone this theme never tunes (only `primary` is seeded from the accent — see
 * `Color.kt`). The dashboard passed the pair explicitly and the group screen did not, so the two
 * FABs were visibly different greens for the same kind of action. Both now come through here and
 * there is one place left to get it wrong.
 *
 * **No label.** Circular and icon-only, not M3's extended pill: both of this app's FABs say
 * "add the obvious thing to the list you are looking at", and on the one screen where that was
 * ambiguous the top-app-bar menu is what disambiguates. Dropping the label costs two things that
 * are put back deliberately rather than optionally — [contentDescription], which is all a screen
 * reader now has, and a long-press tooltip carrying the same words for a sighted first-time
 * user. Neither is a nicety once the words are off the screen.
 *
 * @param contentDescription what the button does, as a sentence a screen reader can read out and
 *   the tooltip can show: "Add expense", not "plus".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpliitFab(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    TooltipBox(
        // Explicitly above: a FAB sits at the bottom of the screen, so the default
        // positioning (which M3 now asks callers to state) has nowhere below to go.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
    ) {
        FloatingActionButton(
            onClick = onClick,
            // Circular, per DESIGN.md §4. M3's own default is a 16dp rounded square, which next
            // to this app's 16dp cards reads as one more card rather than as the screen's single
            // action — verified on the emulator before this line was added.
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
