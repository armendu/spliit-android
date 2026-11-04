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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.DiscardChangesDialog
import app.spliit.android.ui.design.SheetShape
import app.spliit.android.ui.design.SheetHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupSheet(
    viewModel: GroupFormViewModel,
    sheetState: SheetState,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.savedGroupId) {
        state.savedGroupId?.let(onCreated)
    }

    ModalBottomSheet(
        onDismissRequest = { if (state.isDirty) showDiscardDialog = true else onDismiss() },
        sheetState = sheetState,
        shape = SheetShape,
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

    if (showDiscardDialog) {
        // A swipe has already hidden the sheet by the time it asks; keeping the edit brings it back.
        val keepEditing = {
            showDiscardDialog = false
            scope.launch { sheetState.show() }
            Unit
        }
        DiscardChangesDialog(
            subject = "this group",
            dialogTestTag = TestTags.GROUP_FORM_DISCARD_DIALOG,
            confirmTestTag = TestTags.GROUP_FORM_DISCARD_CONFIRM,
            onDismiss = keepEditing,
            onKeepEditing = keepEditing,
            onDiscard = {
                showDiscardDialog = false
                onDismiss()
            },
        )
    }
}
