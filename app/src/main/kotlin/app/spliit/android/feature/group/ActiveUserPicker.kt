package app.spliit.android.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.Monogram
import app.spliit.api.Participant

/**
 * "Who are you in this group?", offered beside the balance it unlocks rather than gating the way
 * into a group somebody just tapped.
 *
 * [app.spliit.core.RecentGroup.participantId] is a plain nullable ID, so "none of these" and
 * "never answered" are one value here, and this offers exactly what it can hold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveUserPickerSheet(
    participants: List<Participant>,
    selectedParticipantId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.ACTIVE_USER_PICKER_SHEET),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "Who are you?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "This stays on this phone. Spliit has no accounts, so nobody else in the " +
                    "group can see which participant you picked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            for (participant in participants) {
                val isSelected = participant.id == selectedParticipantId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        )
                        .clickable { onSelect(participant.id); onDismiss() }
                        .testTag(TestTags.activeUserPickerOption(participant.id))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Monogram(name = participant.name, participantId = participant.id, size = 32.dp)
                    Text(
                        text = participant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (selectedParticipantId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onSelect(null); onDismiss() },
                    modifier = Modifier.testTag(TestTags.ACTIVE_USER_PICKER_CLEAR),
                ) {
                    Text("I'm not sure yet")
                }
            }
        }
    }
}
