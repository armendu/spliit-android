package app.spliit.android.feature.group

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.feature.expense.ExpenseEditSheet
import app.spliit.android.feature.expense.ExpenseFormViewModel
import app.spliit.android.ui.TestTags
import app.spliit.api.Reimbursement
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter

/**
 * The tabs this group screen has.
 *
 * An enum with its own label and tag, iterated over by the tab row, so the Totals and
 * Information tabs arrive as entries here rather than as another pair of hand-placed controls.
 * They are **not** listed yet: the totals tab is a real Spliit feature deferred to a later cycle
 * (CLAUDE.md), and DESIGN.md is explicit that drawing a tab which does nothing is worse than not
 * drawing it. The shape is the hook; the entry is the one-line change.
 */
private enum class GroupDetailTab(val label: String, val testTag: String) {
    EXPENSES("Expenses", TestTags.GROUP_DETAIL_TAB_EXPENSES),
    BALANCES("Balances", TestTags.GROUP_DETAIL_TAB_BALANCES),
}

/**
 * A single group: its expenses and its balances. The totals tab the design's mockups show is a
 * real Spliit feature — deferred to a later cycle, per CLAUDE.md — so this draws exactly two
 * tabs, not the three the mockups have room for; drawing a tab that does nothing is worse than
 * not drawing it.
 *
 * Three ways lead to the expense form from here, matching iOS's three sheets: the FAB creates
 * one, an expense row opens that expense, and a suggested payment opens a settle-up prefilled
 * from it. The group-settings entry is still Part 13's.
 *
 * **Editing is a sheet; creating and settling up are still destinations.** An edit is usually
 * one field on an expense the user is already looking at, so it happens over the list — see
 * [ExpenseEditSheet] — and the list is not read again when it closes. A create has no row to
 * preserve and starts from an empty form, so it keeps the full-screen route: the NavHost drops
 * this composable while that form is up, the `LaunchedEffect(Unit)` below runs again on the way
 * back, and the new expense is in the reloaded list. Two behaviours, because the two cases cost
 * different things.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    viewModel: GroupDetailViewModel,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onSettle: (Reimbursement) -> Unit,
    editViewModelFor: @Composable (expenseId: String) -> ExpenseFormViewModel,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(GroupDetailTab.EXPENSES) }
    var showActiveUserPicker by rememberSaveable { mutableStateOf(false) }
    // Which expense is being edited, if any — the sheet is state on this screen rather than a
    // destination of its own, which is what keeps this screen composed underneath it and its
    // expense list unread. Deliberately not `rememberSaveable`: a sheet restored across process
    // death would come back over a group screen that had reloaded anyway.
    var editingExpenseId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Reruns whenever this screen is freshly composed, same reasoning as GroupsListScreen's own
    // LaunchedEffect(Unit): the ViewModel is scoped to the nav entry and survives a trip to and
    // from a screen this cycle doesn't have yet, so "load on first composition" is not the same
    // as "load once ever".
    LaunchedEffect(Unit) { viewModel.load() }

    val groupInfo = (state.group as? LoadState.Loaded)?.value
    val title = groupInfo?.name.orEmpty()
    val formatter = remember(groupInfo?.currencySymbol, groupInfo?.currencyCode) {
        MoneyFormatter(currencySymbol = groupInfo?.currencySymbol.orEmpty(), currencyCode = groupInfo?.currencyCode)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                // A destination navigated *into*, so an up arrow — not the ✕ a modal task takes,
                // and not a text button: Android's leading navigation slot is an icon, and the
                // drawable is autoMirrored so it points the other way in a right-to-left layout.
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.GROUP_DETAIL_BACK_BUTTON)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            // Only once the group is in: an expense cannot be written without its participants
            // and its currency, and a FAB that opens a form with nothing in it is worse than one
            // that arrives a moment later.
            if (groupInfo != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddExpense,
                    modifier = Modifier.testTag(TestTags.GROUP_DETAIL_ADD_EXPENSE_FAB),
                ) {
                    Text("Add expense")
                }
            }
        },
    ) { contentPadding ->
        // Top only. The bottom of `contentPadding` is the navigation-bar inset, and consuming it
        // here would stop the lists short of the bar instead of letting them scroll under it —
        // each tab adds that inset to its own content padding, on top of the FAB clearance.
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding()).fillMaxSize()) {
            GroupDetailTabs(selected = tab, onSelect = { tab = it })

            Crossfade(
                targetState = tab,
                animationSpec = tween(durationMillis = 200, easing = EaseInOut),
                modifier = Modifier.fillMaxSize(),
                label = "group_detail_tab",
            ) { current ->
                when (current) {
                    GroupDetailTab.EXPENSES -> ExpensesTab(
                        groupState = state.group,
                        expensesState = state.expenses,
                        isLoadingMore = state.isLoadingMoreExpenses,
                        formatter = formatter,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMoreExpenses,
                        onExpenseClick = { editingExpenseId = it },
                    )

                    GroupDetailTab.BALANCES -> BalancesTab(
                        state = state,
                        formatter = formatter,
                        onRetry = viewModel::retry,
                        onIdentify = { showActiveUserPicker = true },
                        onSettle = onSettle,
                    )
                }
            }
        }
    }

    // Composed for as long as the edit is in flight — which outlasts the sheet itself, because
    // the undo offered after a delete belongs to the same ViewModel.
    editingExpenseId?.let { expenseId ->
        ExpenseEditSheet(
            expenseId = expenseId,
            viewModel = editViewModelFor(expenseId),
            snackbarHostState = snackbarHostState,
            // One row read and the balances recomputed — never `groups.expenses.list`. The
            // balances have to be asked for again because the amount may have moved; the list
            // does not, because only one row of it did. See GroupDetailViewModel.expenseSaved.
            onSaved = viewModel::expenseSaved,
            onDeleted = viewModel::expenseDeleted,
            onDone = { editingExpenseId = null },
        )
    }

    if (showActiveUserPicker) {
        ActiveUserPickerSheet(
            participants = groupInfo?.participants.orEmpty(),
            selectedParticipantId = state.activeParticipantId,
            onSelect = viewModel::selectActiveParticipant,
            onDismiss = { showActiveUserPicker = false },
        )
    }
}

/**
 * Material's own tab row, not a hand-drawn one.
 *
 * Navigating between sibling views is what tabs are *for*, and the component brings what a row of
 * pills cannot: the sliding selection indicator, `selectableGroup()` semantics so TalkBack
 * announces "tab 1 of 2", proper touch targets and keyboard traversal. The earlier pill row here
 * was an iOS segmented control transliterated — the expense form's split picker is the control
 * that job actually belongs to, and it uses `SegmentedButton`.
 *
 * `PrimaryTabRow` rather than the scrollable variant because the tab set is small and fixed;
 * labels are never truncated, so a set that stops fitting at the largest font size becomes
 * `PrimaryScrollableTabRow` rather than an ellipsis.
 *
 * Colours are the theme's. DESIGN.md names no override for tabs, and passing one here is how two
 * screens end up subtly different greens.
 */
@Composable
private fun GroupDetailTabs(selected: GroupDetailTab, onSelect: (GroupDetailTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        for (tab in GroupDetailTab.entries) {
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag(tab.testTag),
            )
        }
    }
}
