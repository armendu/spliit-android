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

val FabClearance: Dp = 88.dp

@Composable
fun fabAndNavigationBarPadding(): Dp =
    FabClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

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
