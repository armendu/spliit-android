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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    var isSheetVisible by remember { mutableStateOf(true) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(expenseId) { viewModel.reopen() }

    val deleted = state.deleted
    LaunchedEffect(deleted) {
        if (deleted == null) return@LaunchedEffect
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
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.testTag(TestTags.EXPENSE_EDIT_SHEET),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
