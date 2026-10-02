package app.spliit.android.ui.design

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

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

const val GENERIC_FAILURE: String = "Check your connection and try again."

@Composable
fun FieldError(message: String, modifier: Modifier = Modifier, testTag: String? = null) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
    )
}
