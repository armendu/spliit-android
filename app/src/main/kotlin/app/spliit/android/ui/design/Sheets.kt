package app.spliit.android.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SheetHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    actionTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier,
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
fun DiscardChangesDialog(
    dialogTestTag: String,
    confirmTestTag: String,
    onDismiss: () -> Unit,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(dialogTestTag),
        title = { Text("Discard changes?") },
        text = { Text("What you've typed on this expense won't be kept.") },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag(confirmTestTag)) {
                Text("Discard")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text("Keep editing") }
        },
    )
}
