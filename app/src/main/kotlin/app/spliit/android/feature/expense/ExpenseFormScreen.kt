package app.spliit.android.feature.expense

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.feature.groups.SkeletonBlock
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.EmptyState
import app.spliit.core.Currencies
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.MoneyFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val FieldShape = RoundedCornerShape(12.dp)

/**
 * Creating and editing an expense — who paid, how much, and how it divides.
 *
 * **A modal task, not a destination.** It takes an ✕ and a confirming action rather than an up
 * arrow (Material's full-screen dialog), because what it is doing can be abandoned; the system
 * back gesture goes the same way the ✕ does, and asks first when there is something to lose.
 *
 * Every number on this screen comes from [ExpenseFormDraft] and every complaint from
 * `draft.problems(field)`. Nothing here parses an amount or divides one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseFormScreen(
    viewModel: ExpenseFormViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    // One exit, whichever way it was reached: saved, undone, deleted-and-let-go, or closed.
    LaunchedEffect(state.savedExpenseId, state.isFinished) {
        if (state.savedExpenseId != null || state.isFinished) onClose()
    }

    val deleted = state.deleted
    LaunchedEffect(deleted) {
        if (deleted == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Deleted \"${deleted.title}\".",
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        when (result) {
            // The server has no undelete, so this writes the expense back — under a new ID.
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissDeleted()
        }
    }

    state.saveError?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissSaveError()
        }
    }

    val close = {
        if (state.isDirty) showDiscardDialog = true else viewModel.close()
    }
    // Back is primarily a gesture on Android, so the confirmation has to hang off the gesture and
    // not off the ✕ alone — a swipe that silently threw away a half-written expense would be the
    // same bug with better manners. Disabled once there is nothing to lose, so the gesture then
    // does the ordinary thing without a dialog in the way.
    BackHandler(enabled = state.deleted == null) { close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit expense" else "New expense") },
                navigationIcon = {
                    IconButton(
                        onClick = close,
                        modifier = Modifier.testTag(TestTags.EXPENSE_FORM_CLOSE_BUTTON),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !state.isSaving && state.draft != null && state.deleted == null,
                        modifier = Modifier.testTag(TestTags.EXPENSE_FORM_SAVE_BUTTON),
                    ) {
                        Text(if (state.isSaving) "Saving…" else "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        val draft = state.draft
        val group = state.group
        when {
            state.isLoading -> ExpenseFormSkeleton(Modifier.padding(contentPadding))

            draft == null || group == null -> Box(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = "!",
                    title = "Couldn't load the expense",
                    description = state.loadError ?: "Check your connection and try again.",
                ) {
                    Button(
                        onClick = viewModel::retry,
                        modifier = Modifier.testTag(TestTags.EXPENSE_FORM_RETRY_BUTTON),
                    ) {
                        Text("Retry")
                    }
                }
            }

            else -> ExpenseFormBody(
                state = state,
                draft = draft,
                viewModel = viewModel,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            modifier = Modifier.testTag(TestTags.EXPENSE_FORM_DISCARD_DIALOG),
            title = { Text("Discard changes?") },
            text = { Text("What you've typed on this expense won't be kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.close()
                    },
                    modifier = Modifier.testTag(TestTags.EXPENSE_FORM_DISCARD_CONFIRM),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

/**
 * The form itself — every field, in order, with the amount and the title first.
 *
 * `internal` because two containers draw it: the full-screen create form above, and the edit
 * sheet in ExpenseEditSheet.kt. The order of the fields is load-bearing for the second one — the
 * sheet opens partially expanded, and what is at the top of this column is what a collapsed
 * sheet shows.
 */
@Composable
internal fun ExpenseFormBody(
    state: ExpenseFormUiState,
    draft: ExpenseFormDraft,
    viewModel: ExpenseFormViewModel,
    modifier: Modifier = Modifier,
) {
    val group = checkNotNull(state.group)
    val formatter = remember(group.currencySymbol, group.currencyCode) {
        MoneyFormatter(currencySymbol = group.currencySymbol, currencyCode = group.currencyCode)
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showCurrencyPicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        AmountEntry(
            draft = draft,
            formatter = formatter,
            onAmountChange = viewModel::setAmountText,
        )
        FieldProblems(state, ExpenseFormDraft.Field.AMOUNT, formatter, Alignment.CenterHorizontally)

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = draft.title,
            onValueChange = viewModel::setTitle,
            label = { Text("What was it for?") },
            singleLine = true,
            shape = FieldShape,
            isError = state.problems(ExpenseFormDraft.Field.TITLE).isNotEmpty(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.EXPENSE_FORM_TITLE_FIELD),
        )
        FieldProblems(state, ExpenseFormDraft.Field.TITLE, formatter)

        Spacer(Modifier.height(16.dp))
        DateRow(
            date = draft.expenseDate,
            onClick = { showDatePicker = true },
        )

        SectionHeader("Paid by")
        PaidByChips(
            participants = group.participants,
            selectedId = draft.paidById,
            onSelect = viewModel::setPaidBy,
        )
        FieldProblems(state, ExpenseFormDraft.Field.PAID_BY, formatter)

        if (state.categories.isNotEmpty()) {
            SectionHeader("Category")
            CategoryChips(
                categories = state.categories,
                selectedId = draft.categoryId,
                onSelect = viewModel::setCategory,
            )
        }

        SplitSection(
            state = state,
            draft = draft,
            formatter = formatter,
            currencySymbol = group.currencySymbol,
            onSplitMode = viewModel::setSplitMode,
            onIncluded = viewModel::setParticipantIncluded,
            onAllIncluded = viewModel::setAllParticipantsIncluded,
            onShareText = viewModel::setShareText,
            onSaveSplitAsDefault = viewModel::setSaveSplitAsDefault,
        )

        // Only where a conversion is possible at all: it needs an ISO code on both sides, and a
        // group carrying only a free-text symbol has nothing to convert *to*. A row that could
        // never do anything is worse than no row — the same call the iOS form makes.
        if (Currencies.named(group.currencyCode.orEmpty()) != null) {
            CurrencySection(
                state = state,
                draft = draft,
                formatter = formatter,
                onPickCurrency = { showCurrencyPicker = true },
                onOriginalAmount = viewModel::setOriginalAmountText,
                onRate = viewModel::setConversionRateText,
            )
        }

        SectionHeader("More")
        SwitchRow(
            label = "This is a reimbursement",
            description = "Somebody settling up, rather than a new expense.",
            checked = draft.isReimbursement,
            onCheckedChange = viewModel::setReimbursement,
            testTag = TestTags.EXPENSE_FORM_REIMBURSEMENT_TOGGLE,
        )
        Spacer(Modifier.height(8.dp))
        RecurrenceRow(rule = draft.recurrenceRule, onSelect = viewModel::setRecurrenceRule)

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = draft.notes,
            onValueChange = viewModel::setNotes,
            label = { Text("Notes") },
            placeholder = { Text("Anything worth remembering?") },
            minLines = 2,
            maxLines = 5,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.EXPENSE_FORM_NOTES_FIELD),
        )

        if (state.isEditing) {
            // Well below the last field and behind a rule of its own: deleting is the one action
            // here that cannot be taken back the way a mistyped amount can, and the save action
            // is at the top of the sheet rather than beside this, so the two are never adjacent.
            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = viewModel::delete,
                enabled = !state.isSaving && state.deleted == null,
                // The tonal error pair, not a solid `error` fill — see Color.kt for both
                // measurements. A filled red bar is how an app shouts; this is an action that
                // belongs to the form, offered at the end of it.
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.EXPENSE_FORM_DELETE_BUTTON),
            ) {
                Text("Delete expense")
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    if (showDatePicker) {
        ExpenseDatePicker(
            date = draft.expenseDate,
            onPick = {
                viewModel.setExpenseDate(it)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showCurrencyPicker) {
        CurrencyPickerSheet(
            selectedCode = draft.originalCurrencyCode,
            groupCurrencyCode = group.currencyCode,
            onSelect = {
                viewModel.setCurrency(it)
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
        )
    }
}

// ---- date ------------------------------------------------------------------------------------

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
private fun DateRow(date: Instant, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .testTag(TestTags.EXPENSE_FORM_DATE_BUTTON)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Date", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = date.atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The date, picked in UTC on purpose.
 *
 * An expense date is a *day*, and the server stores it as midnight UTC — which is what
 * `groups.expenses.list` sends back. Reading the picker's millis in the phone's own zone would
 * move the day by one west of Greenwich on every round trip, silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDatePicker(date: Instant, onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) onPick(Instant.ofEpochMilli(millis).truncatedToUtcDay())
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun Instant.truncatedToUtcDay(): Instant =
    atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

// ---- shared chrome ---------------------------------------------------------------------------

@Composable
internal fun SectionHeader(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Whatever the draft says is wrong with one field, under that field.
 *
 * Reads [ExpenseFormUiState.problems], which is [ExpenseFormDraft.problems] filtered by field and
 * gated on a save having been attempted. There is no rule here.
 */
@Composable
internal fun FieldProblems(
    state: ExpenseFormUiState,
    field: ExpenseFormDraft.Field,
    formatter: MoneyFormatter,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    val problems = state.problems(field)
    if (problems.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Spacer(Modifier.height(4.dp))
        for (problem in problems) {
            Text(
                text = problem.message(formatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.expenseFormError(field.name)),
            )
        }
    }
}

/** Shared with the edit sheet, which shows the same shape while it reads the expense. */
@Composable
internal fun ExpenseFormSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.EXPENSE_FORM_SKELETON),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(Modifier.width(200.dp).height(44.dp))
        Spacer(Modifier.height(32.dp))
        repeat(4) {
            SkeletonBlock(Modifier.fillMaxWidth().height(56.dp))
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { SkeletonBlock(Modifier.size(72.dp, 32.dp)) }
        }
    }
}
