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
