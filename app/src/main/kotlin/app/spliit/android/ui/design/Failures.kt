package app.spliit.android.ui.design

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * What a screen says when a load failed: the exclamation tile, a title naming what failed, the
 * server's own words if it gave any, and a Retry button when retrying is possible.
 *
 * There were nine copies of this, and the fallback sentence was written out nine times. A
 * screen that cannot retry (search results, which re-run on the next keystroke) passes
 * `onRetry = null`.
 */
@Composable
fun LoadFailure(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    retryTestTag: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        icon = "!",
        title = title,
        description = message ?: GENERIC_FAILURE,
        modifier = modifier,
    ) {
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = if (retryTestTag != null) Modifier.testTag(retryTestTag) else Modifier,
            ) {
                Text("Retry")
            }
        }
    }
}

/** What to say when the server gave no reason of its own. */
const val GENERIC_FAILURE: String = "Check your connection and try again."

/**
 * One field's validation message, in the error colour.
 *
 * Six near-copies of this `Text` existed, differing only in a tag or a padding. `:core` produces
 * the sentence; this draws it.
 */
@Composable
fun FieldError(message: String, modifier: Modifier = Modifier, testTag: String? = null) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
    )
}
