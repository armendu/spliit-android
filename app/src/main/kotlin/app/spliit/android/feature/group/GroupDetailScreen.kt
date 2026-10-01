package app.spliit.android.feature.group

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.feature.expense.ExpenseEditSheet
import app.spliit.android.feature.expense.ExpenseFormViewModel
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.SpliitFab
import app.spliit.api.Reimbursement
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter

internal object GroupDetailLayout {
    const val USE_BOTTOM_BAR: Boolean = false
}

private enum class GroupDetailTab(
    val label: String,
    @param:DrawableRes val icon: Int,
    val testTag: String,
) {
    EXPENSES("Expenses", R.drawable.ic_tab_expenses, TestTags.GROUP_DETAIL_TAB_EXPENSES),
    BALANCES("Balances", R.drawable.ic_tab_balances, TestTags.GROUP_DETAIL_TAB_BALANCES),
    TOTALS("Totals", R.drawable.ic_tab_totals, TestTags.GROUP_DETAIL_TAB_TOTALS),
    INFORMATION("Information", R.drawable.ic_tab_information, TestTags.GROUP_DETAIL_TAB_INFORMATION),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    viewModel: GroupDetailViewModel,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onEditGroup: () -> Unit,
    onSettle: (Reimbursement) -> Unit,
    editViewModelFor: @Composable (expenseId: String) -> ExpenseFormViewModel,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(GroupDetailTab.EXPENSES) }
    var showActiveUserPicker by rememberSaveable { mutableStateOf(false) }
    var showActivityLog by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var editingExpenseId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    LaunchedEffect(tab, state.group is LoadState.Loaded, state.activeParticipantId) {
        if (tab == GroupDetailTab.TOTALS) viewModel.loadStatsIfNeeded()
    }
    LaunchedEffect(showActivityLog) {
        if (showActivityLog) viewModel.loadActivitiesIfNeeded()
    }

    val groupInfo = (state.group as? LoadState.Loaded)?.value
    val title = groupInfo?.name.orEmpty()
    val formatter = remember(groupInfo?.currencySymbol, groupInfo?.currencyCode) {
        MoneyFormatter(currencySymbol = groupInfo?.currencySymbol.orEmpty(), currencyCode = groupInfo?.currencyCode)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GroupTopBar(
                title = title,
                search = state.search,
                showAddAction = GroupDetailLayout.USE_BOTTOM_BAR &&
                    tab == GroupDetailTab.EXPENSES &&
                    groupInfo != null,
                showOverflow = showOverflow,
                onBack = onBack,
                onAddExpense = onAddExpense,
                onSearchOpen = { viewModel.setSearchActive(true) },
                onSearchClose = { viewModel.setSearchActive(false) },
                onQueryChange = viewModel::search,
                onOverflowOpen = { showOverflow = true },
                onOverflowDismiss = { showOverflow = false },
                onEditGroup = {
                    showOverflow = false
                    onEditGroup()
                },
                onShare = {
                    showOverflow = false
                    groupInfo?.let { info ->
                        val link = groupShareLink(info.instanceBaseUrl, info.id)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, info.name)
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
            )
        },
        bottomBar = {
            if (GroupDetailLayout.USE_BOTTOM_BAR && !state.search.isActive) {
                GroupBottomBar(selected = tab, onSelect = { tab = it })
            }
        },
        floatingActionButton = {
            if (!GroupDetailLayout.USE_BOTTOM_BAR && groupInfo != null && !state.search.isActive) {
                SpliitFab(
                    icon = R.drawable.ic_plus,
                    contentDescription = "Add expense",
                    onClick = onAddExpense,
                    testTag = TestTags.GROUP_DETAIL_ADD_EXPENSE_FAB,
                )
            }
        },
    ) { contentPadding ->
        val bottomInset = if (GroupDetailLayout.USE_BOTTOM_BAR && !state.search.isActive) {
            contentPadding.calculateBottomPadding()
        } else {
            0.dp
        }
        Column(
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding(), bottom = bottomInset)
                .fillMaxSize(),
        ) {
            if (!GroupDetailLayout.USE_BOTTOM_BAR && !state.search.isActive) {
                GroupDetailTabs(selected = tab, onSelect = { tab = it })
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::pullToRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.search.isActive) {
                    SearchResults(
                        search = state.search,
                        formatter = formatter,
                        onExpenseClick = { editingExpenseId = it },
                    )
                } else {
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

                            GroupDetailTab.TOTALS -> TotalsTab(
                                state = state,
                                formatter = formatter,
                                onRetry = viewModel::retry,
                                onRetryStats = viewModel::refreshStats,
                                onIdentify = { showActiveUserPicker = true },
                            )

                            GroupDetailTab.INFORMATION -> InformationTab(
                                groupState = state.group,
                                activeParticipantId = state.activeParticipantId,
                                onRetry = viewModel::retry,
                                onEditGroup = onEditGroup,
                                onOpenActivity = { showActivityLog = true },
                                onIdentify = { showActiveUserPicker = true },
                            )
                        }
                    }
                }
            }
        }
    }

    editingExpenseId?.let { expenseId ->
        ExpenseEditSheet(
            expenseId = expenseId,
            viewModel = editViewModelFor(expenseId),
            snackbarHostState = snackbarHostState,
            onSaved = viewModel::expenseSaved,
            onDeleted = viewModel::expenseDeleted,
            onDone = { editingExpenseId = null },
        )
    }

    if (showActivityLog) {
        ActivityLogSheet(
            state = state,
            onLoadMore = viewModel::loadMoreActivities,
            onRetry = viewModel::retryActivities,
            onOpenExpense = { expenseId ->
                showActivityLog = false
                editingExpenseId = expenseId
            },
            onDismiss = { showActivityLog = false },
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

internal fun groupShareLink(instanceBaseUrl: String, groupId: String): String =
    "${instanceBaseUrl.trimEnd('/')}/groups/$groupId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTopBar(
    title: String,
    search: SearchUiState,
    showAddAction: Boolean,
    showOverflow: Boolean,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOverflowOpen: () -> Unit,
    onOverflowDismiss: () -> Unit,
    onEditGroup: () -> Unit,
    onShare: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    TopAppBar(
        title = {
            if (search.isActive) {
                TextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text("Search expenses") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(TestTags.GROUP_DETAIL_SEARCH_FIELD),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (search.isActive) {
                IconButton(
                    onClick = onSearchClose,
                    modifier = Modifier.testTag(TestTags.GROUP_DETAIL_SEARCH_CLOSE),
                ) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close search")
                }
            } else {
                IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.GROUP_DETAIL_BACK_BUTTON)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
            }
        },
        actions = {
            if (search.isActive) return@TopAppBar

            IconButton(
                onClick = onSearchOpen,
                modifier = Modifier.testTag(TestTags.GROUP_DETAIL_SEARCH_BUTTON),
            ) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = "Search expenses")
            }

            if (showAddAction) {
                IconButton(
                    onClick = onAddExpense,
                    modifier = Modifier.testTag(TestTags.GROUP_DETAIL_ADD_EXPENSE_ACTION),
                ) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = "Add expense")
                }
            }

            IconButton(
                onClick = onOverflowOpen,
                modifier = Modifier.testTag(TestTags.GROUP_DETAIL_MENU_BUTTON),
            ) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Group actions")
            }
            DropdownMenu(expanded = showOverflow, onDismissRequest = onOverflowDismiss) {
                DropdownMenuItem(
                    text = { Text("Edit group") },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = onEditGroup,
                    modifier = Modifier.testTag(TestTags.GROUP_DETAIL_MENU_EDIT_GROUP),
                )
                DropdownMenuItem(
                    text = { Text("Share group") },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_share),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = onShare,
                    modifier = Modifier.testTag(TestTags.GROUP_DETAIL_MENU_SHARE_GROUP),
                )
            }
        },
    )
}

@Composable
private fun GroupDetailTabs(selected: GroupDetailTab, onSelect: (GroupDetailTab) -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 0.dp) {
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

@Composable
private fun GroupBottomBar(selected: GroupDetailTab, onSelect: (GroupDetailTab) -> Unit) {
    NavigationBar(
        containerColor = Color.Transparent,
        modifier = Modifier.testTag(TestTags.GROUP_DETAIL_BOTTOM_BAR),
    ) {
        for (tab in GroupDetailTab.entries) {
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag(tab.testTag),
            )
        }
    }
}
