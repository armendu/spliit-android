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
import androidx.compose.material3.MaterialTheme
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

/**
 * Where this screen's tabs live.
 *
 * Material reserves `NavigationBar` for **top-level** destinations, and these four are views
 * within one destination, which is why they started at the top, as a `TabRow`. The question came
 * up twice anyway, so both layouts are built and this constant is the whole switch between them:
 * flip it, rebuild, and look at the two rather than argue about them.
 *
 * **The FAB conflict is resolved by removing the FAB, not by docking it.** M3 dropped the
 * docked-FAB pattern, and a floating FAB stacked over a navigation bar either covers the bar's
 * last item or floats above it with nothing holding it there. So in the bottom-bar layout "Add
 * expense" becomes a top-app-bar action, shown only on the Expenses tab, which is also what iOS
 * does, and it keeps the action in the same place the search and overflow actions already are.
 */
internal object GroupDetailLayout {
    /** True draws the four tabs as a bottom [NavigationBar]; false as a top [PrimaryScrollableTabRow]. */
    const val USE_BOTTOM_BAR: Boolean = false
}

/**
 * The tabs this group screen has.
 *
 * An enum with its own label, icon and tag, iterated over by whichever bar is drawing them, so
 * the two layouts cannot fall out of step about what the tabs are or what order they come in.
 * Totals sits beside Balances rather than beside Information because it answers the same question
 * from the other end: that tab says where the group will settle, this one what it has spent.
 */
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

/**
 * A single group: its expenses, its balances, its totals and what it is.
 *
 * Four routes lead to the expense form: the add action creates one, a row opens that expense, a
 * suggested payment opens a prefilled settle-up, and an activity row opens the expense it
 * describes if there still is one.
 *
 * Editing is a sheet; creating and settling up are destinations. An edit is usually one field on
 * an expense already on screen, so it happens over the list and the list is not re-read when it
 * closes. A create has no row to preserve.
 *
 * Re-entering a loaded group costs nothing: the effect below calls `loadIfNeeded`, not `load`.
 * Whatever did change is asked for specifically, by the edit sheet, by [onExpensesChanged]'s
 * caller, or by a pull to refresh.
 *
 * @param search whether the top bar is currently a search field. Hoisted so the back handler and
 *   the tab bar can both see it.
 */
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
    // Which expense is being edited, if any, the sheet is state on this screen rather than a
    // destination of its own, which is what keeps this screen composed underneath it and its
    // expense list unread. Deliberately not `rememberSaveable`: a sheet restored across process
    // death would come back over a group screen that had reloaded anyway.
    var editingExpenseId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    // Lazy, both of them: nothing asks the server for the totals until somebody opens the tab
    // that draws them, and nothing asks for the log until the sheet is opened. Keyed on what the
    // answer depends on, so re-answering "who are you?" re-asks for the totals and nothing else.
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
                        // Built from *this group's* instance, never the app default: a
                        // self-hosted group shared as a spliit.app link opens nothing. The same
                        // link the web app shares, so it opens for anyone regardless of platform.
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
            // Only once the group is in: an expense cannot be written without its participants
            // and its currency, and a FAB that opens a form with nothing in it is worse than one
            // that arrives a moment later. Suppressed entirely in the bottom-bar layout, where
            // the same action lives in the top bar, see GroupDetailLayout.
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
        // Top and bottom are treated differently on purpose. The bottom of `contentPadding` is
        // the navigation-bar inset (plus the bottom bar, where there is one); consuming it for
        // the lists would stop them short of the bar instead of letting them scroll under it, so
        // each tab adds that inset to its own content padding. The bottom bar itself is the
        // exception, it is opaque, so its height has to be reserved rather than scrolled under.
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

            // Explicit, because nothing on this screen refreshes itself any more and nothing is
            // served from an HTTP cache, a cached GET would quietly serve stale balances. This
            // is how somebody who wants fresh numbers asks for them.
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

    // Composed for as long as the edit is in flight, which outlasts the sheet itself, because
    // the undo offered after a delete belongs to the same ViewModel.
    editingExpenseId?.let { expenseId ->
        ExpenseEditSheet(
            expenseId = expenseId,
            viewModel = editViewModelFor(expenseId),
            snackbarHostState = snackbarHostState,
            // One row read and the balances recomputed, never `groups.expenses.list`. The
            // balances have to be asked for again because the amount may have moved; the list
            // does not, because only one row of it did. See GroupDetailViewModel.expenseSaved.
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
                // The log closes on the way to the editor: two stacked sheets is one more layer
                // than the back gesture reads cleanly, and the log is where you were rather than
                // what you are doing.
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

/**
 * The web app's own share link, `{instance}/groups/{id}`, which is what makes it open for
 * anyone regardless of platform.
 *
 * [instanceBaseUrl] is stored with a trailing slash in some rows and without in others, so the
 * separator is normalised here rather than assumed. Nothing is escaped: Spliit's IDs are nanoids
 * over `A-Za-z0-9_-`, every character of which is already URL-safe.
 */
internal fun groupShareLink(instanceBaseUrl: String, groupId: String): String =
    "${instanceBaseUrl.trimEnd('/')}/groups/$groupId"

/**
 * The top bar, in its two states.
 *
 * Search is an **app-bar action that expands into a field**, not a fifth tab. iOS's search tab is
 * an iOS idiom, the search role puts a magnifying glass in its own capsule beside the tab bar -
 * and Android's equivalent has always been the bar. A fifth tab here would also squeeze four
 * labels for the destination people open least.
 */
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
                // The field replaces the title rather than sitting under it: the bar is one row
                // tall and a second row would push every tab down for as long as a search lasts.
                TextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text("Search expenses") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // The results are already live as you type; the action key just puts the
                    // keyboard away so more of them are visible.
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
                // A destination navigated *into*, so an up arrow, not the ✕ a modal task takes,
                // and not a text button: Android's leading navigation slot is an icon, and the
                // drawable is autoMirrored so it points the other way in a right-to-left layout.
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

            // One overflow for the two things that act on the *group* rather than on what is in
            // it, which is what keeps "Add expense" out of it, same split as iOS's toolbar.
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

/**
 * Material's own tab row, not a hand-drawn one.
 *
 * Navigating between sibling views is what tabs are *for*, and the component brings what a row of
 * pills cannot: the sliding selection indicator, `selectableGroup()` semantics so TalkBack
 * announces "tab 1 of 4", proper touch targets and keyboard traversal.
 *
 * `PrimaryScrollableTabRow` rather than the fixed variant now that there are four: the fixed
 * row divides the width equally and clipped "Information" to "Informatio" at the *default* font
 * size on a Pixel 8, seen on the emulator, not predicted, and DESIGN.md §2 is explicit that
 * anything which would clip must wrap or scroll rather than truncate.
 *
 * Colours are the theme's. DESIGN.md names no override for tabs, and passing one here is how two
 * screens end up subtly different greens.
 */
@Composable
private fun GroupDetailTabs(selected: GroupDetailTab, onSelect: (GroupDetailTab) -> Unit) {
    // edgePadding 0 so the first tab starts flush with the 16dp content margin rather than
    // inset by the component's own default.
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

/**
 * The same four tabs at the bottom, the layout [GroupDetailLayout] exists to let somebody
 * compare.
 *
 * Icons are not optional here the way they are in a `TabRow`: a `NavigationBar` item is drawn
 * icon-above-label, and the component reserves the icon slot whether or not one is supplied.
 *
 * **Transparent, not `surfaceContainer`.** M3's default container is a tone lighter than this
 * app's background, which drew the bar as a pale band with the system navigation area below it
 * in a third shade, three horizontal stripes at the bottom of a screen whose whole point is the
 * list above them. Transparent lets the one background run from the last expense row to the
 * bottom of the display, which is also what the status bar already does at the other end (see
 * MainActivity's note on why both system bars are transparent).
 *
 * Nothing is lost to legibility, because nothing scrolls underneath: `Scaffold` measures this bar
 * and hands its height to the content as padding, so the list stops above it rather than running
 * beneath. The selected item keeps its own `secondaryContainer` pill, which is what marks the
 * current tab once the bar itself has no edge.
 */
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
