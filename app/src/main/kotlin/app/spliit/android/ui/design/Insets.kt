package app.spliit.android.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A FAB plus the margin it floats on. Named because it appeared as a bare `88.dp` nine times. */
val FabClearance: Dp = 88.dp

/**
 * What a scrolling list on a screen with a FAB needs below its last row.
 *
 * DESIGN.md §6: the navigation-bar inset goes on top of the FAB clearance, not instead of it.
 */
@Composable
fun fabAndNavigationBarPadding(): Dp =
    FabClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * A single centred thing on a scrolling screen: an empty state, an error panel, a spinner.
 *
 * One definition because there were five, and they had drifted into four different behaviours.
 * Two of them cleared the FAB but not the navigation bar, and two cleared neither, so the same
 * empty state sat under the FAB on one tab of a screen and under the gesture bar on another.
 * They are sibling tabs sharing one FAB and one navigation bar; nobody chose that.
 *
 * Scrollable rather than a plain `Box` so the content can still be reached at the largest font
 * sizes, where an error panel and its button are taller than the viewport.
 */
@Composable
fun CenteredScroll(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = fabAndNavigationBarPadding()),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
