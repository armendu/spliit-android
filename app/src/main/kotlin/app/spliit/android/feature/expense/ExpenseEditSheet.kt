package app.spliit.android.feature.expense

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import kotlinx.coroutines.launch
import app.spliit.android.ui.design.SheetShape
import app.spliit.android.ui.design.LoadFailure
import app.spliit.android.ui.design.SheetHeader
import app.spliit.android.ui.design.DiscardChangesDialog


/**
 * Editing an expense, as a sheet over the group screen rather than a screen of its own.
 *
 * It opens partially expanded on the title and amount, the two fields nearly every edit touches,
 * with save pinned above them. Dragging up reveals the rest of [ExpenseFormBody].
 *
 * The point is what does not happen: the group screen stays composed underneath, so its
 * `LaunchedEffect(Unit) { load() }` does not re-run, the list keeps its scroll and paged-in rows,
 * and no skeleton flashes.
 *
 * It outlives the sheet slightly: after a delete the sheet goes but the undo is still on offer.
 * [onDone] signals the whole errand is over.
 *
 * @param snackbarHostState the *group screen's* host; a snackbar inside a closing sheet has
 *   nowhere to be.
 * @param onSaved a write landed. Also fires for an undone delete, which returns under a **new**
 *   ID (see [DeletedExpense]).
 * @param onDeleted the expense is gone from the server.
 * @param onDone the sheet and its undo window are both finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditSheet(
    expenseId: String,
    viewModel: ExpenseFormViewModel,
    snackbarHostState: SnackbarHostState,
    onSaved: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    // skipPartiallyExpanded = false is the whole design: the partially-expanded anchor is what
    // the sheet opens at, and what the user drags up from.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    var isSheetVisible by remember { mutableStateOf(true) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Keyed on the expense rather than Unit: the same ViewModel instance is handed back for the
    // same row, so a second open has to start from the expense again rather than from wherever
    // the first one stopped. See ExpenseFormViewModel.reopen.
    LaunchedEffect(expenseId) { viewModel.reopen() }

    val deleted = state.deleted
    LaunchedEffect(deleted) {
        if (deleted == null) return@LaunchedEffect
        // The row goes as soon as the server says it has: leaving it under an "Undo" snackbar
        // would be showing an expense that no longer exists.
        onDeleted(expenseId)
        sheetState.hide()
        isSheetVisible = false
        val result = snackbarHostState.showSnackbar(
            message = "Deleted \"${deleted.title}\".",
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        when (result) {
            // The server has no undelete, so this writes the expense back, under a new ID.
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissDeleted()
        }
    }

    LaunchedEffect(state.savedExpenseId) {
        val savedId = state.savedExpenseId ?: return@LaunchedEffect
        onSaved(savedId)
        if (isSheetVisible) {
            sheetState.hide()
            isSheetVisible = false
        }
        onDone()
    }

    LaunchedEffect(state.isFinished) {
        if (!state.isFinished) return@LaunchedEffect
        if (isSheetVisible) {
            sheetState.hide()
            isSheetVisible = false
        }
        onDone()
    }

    state.saveError?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissSaveError()
        }
    }

    if (isSheetVisible) {
        ModalBottomSheet(
            // A drag down, a tap on the scrim and the back gesture all arrive here, the sheet's
            // own back handling calls this too, so the unsaved-changes question is asked once,
            // for all three, rather than hung off a close button that a sheet does not have.
            onDismissRequest = {
                if (state.isDirty) {
                    showDiscardDialog = true
                } else {
                    isSheetVisible = false
                    viewModel.close()
                }
            },
            sheetState = sheetState,
            shape = SheetShape,
            // The sheet consumes the navigation-bar inset by default, which leaves the
            // `navigationBarsPadding()` below with nothing to apply, so the last row of the form
            // drew underneath the gesture bar. Insets are handed to the content instead, where
            // one modifier can pad for the bar and the keyboard together.
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.testTag(TestTags.EXPENSE_EDIT_SHEET),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fills the sheet whatever it currently holds, so the partially-expanded
                    // anchor does not move between the skeleton and the loaded form.
                    .fillMaxHeight()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                SheetHeader(
                    title = "Edit expense",
                    actionLabel = if (state.isSaving) "Saving…" else "Save",
                    onAction = viewModel::save,
                    actionEnabled = !state.isSaving && state.draft != null && state.deleted == null,
                    actionTestTag = TestTags.EXPENSE_FORM_SAVE_BUTTON,
                )

                val draft = state.draft
                val group = state.group
                when {
                    state.isLoading -> ExpenseFormSkeleton(Modifier.weight(1f))

                    draft == null || group == null -> Box(
                        modifier = Modifier.weight(1f).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadFailure(
                            title = "Couldn't load the expense",
                            message = state.loadError,
                            retryTestTag = TestTags.EXPENSE_FORM_RETRY_BUTTON,
                            onRetry = viewModel::retry,
                        )
                    }

                    else -> ExpenseFormBody(
                        state = state,
                        draft = draft,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        // Dismissing the question is not answering it, and the sheet has already animated away
        // underneath, so both ways out of the dialog bring it back.
        val keepEditing = {
            showDiscardDialog = false
            scope.launch { sheetState.show() }
            Unit
        }
        DiscardChangesDialog(
            dialogTestTag = TestTags.EXPENSE_FORM_DISCARD_DIALOG,
            confirmTestTag = TestTags.EXPENSE_FORM_DISCARD_CONFIRM,
            onDismiss = keepEditing,
            onKeepEditing = keepEditing,
            onDiscard = {
                showDiscardDialog = false
                isSheetVisible = false
                viewModel.close()
            },
        )
    }
}

