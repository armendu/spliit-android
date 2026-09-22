package app.spliit.android.feature.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.SheetShape
import app.spliit.android.ui.design.SheetHeader


/**
 * Creating a group, as a drawer over the dashboard rather than a screen of its own.
 *
 * It is the same errand as adding a group by link, which is already a sheet beside it, a name,
 * a couple of participants, done, and a full-screen form for that much reads as a trip rather
 * than a task. **Editing** a group keeps the full screen: by then there are participants to add
 * and remove, a note and a currency, and the form has earned the room. Both draw the same
 * [GroupFormBody], so there is one form here, presented two ways.
 *
 * **It opens fully expanded** (`skipPartiallyExpanded`), unlike the expense editor next door.
 * That sheet opens at a partial anchor because the two fields most edits touch are at the top of
 * a long form; this one *is* the whole form, and an anchor that showed only the name would put a
 * drag between somebody and the participant they came to add.
 *
 * **The server address is not asked for.** It sits behind the "Advanced" disclosure inside
 * [GroupFormBody], prefilled from the default in Settings, see that function's own note for why
 * it is hidden rather than removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupSheet(
    viewModel: GroupFormViewModel,
    sheetState: SheetState,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.savedGroupId) {
        state.savedGroupId?.let(onCreated)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        // Handed to the content instead, where one modifier pads for the navigation bar and the
        // keyboard together, the sheet consuming the inset leaves the last field under the
        // gesture handle. Same reasoning as ExpenseEditSheet.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag(TestTags.GROUP_FORM_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SheetHeader(
                title = "Create group",
                actionLabel = if (state.isSaving) "Creating…" else "Create",
                onAction = viewModel::save,
                actionEnabled = !state.isSaving,
                actionTestTag = TestTags.GROUP_FORM_SAVE_BUTTON,
            )

            Box(modifier = Modifier.weight(1f)) {
                GroupFormBody(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
                BlockedParticipantNotice(
                    message = state.blockedParticipantMessage,
                    onDismiss = viewModel::dismissBlockedParticipantMessage,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
