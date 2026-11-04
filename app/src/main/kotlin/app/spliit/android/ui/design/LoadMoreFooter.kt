package app.spliit.android.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Keyed on the cursor: a footer still on screen after a page lands has to ask again, not sit there.
@Composable
fun LoadMoreFooter(
    cursor: Int,
    isLoading: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
    testTag: String,
) {
    if (!failed) LaunchedEffect(cursor) { onLoadMore() }
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            failed -> TextButton(onClick = onLoadMore) { Text("Couldn't load more. Try again") }
        }
    }
}
