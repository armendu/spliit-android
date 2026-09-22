package app.spliit.android.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.ExpenseListItem
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcClientError
import app.spliit.api.TrpcServerError
import app.spliit.api.groupStats
import app.spliit.core.MoneyFormatter
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsStore
import app.spliit.core.RefreshLimiter
import app.spliit.core.LoadState
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.spliit.core.Participant as CoreParticipant

/**
 * One group's detail screen: the expenses tab and the balances tab, loaded together.
 *
 * Unlike [app.spliit.android.feature.groups.GroupsListViewModel], this class is not handed the
 * instance a group lives on, the nav route is just `groups/{groupId}`, so [refresh] resolves it
 * itself from the stored [RecentGroup] row for [groupId]. A group with no such row (reached by a
 * route this app does not currently produce) fails every section rather than guessing a server.
 */
class GroupDetailViewModel(
    private val groupId: String,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Injected so the tests can drive its clock, see RefreshLimiter. */
    private val refreshLimiter: RefreshLimiter = RefreshLimiter(),
) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 20

        /**
         * How long a pause in typing counts as "done typing".
         *
         * Every keystroke cancels the job before it, so this delay is only ever survived by the
         * last one, which is what makes it a debounce rather than a lag. See [search].
         */
    }

    private val _state = MutableStateFlow(GroupDetailUiState())
    val state: StateFlow<GroupDetailUiState> = _state.asStateFlow()

    /** Set once [refresh] has resolved which instance [groupId] lives on, reused by
     *  [loadNextExpensesPage] and [selectActiveParticipant] so neither re-derives it. */
    private var resolvedInstanceBaseUrl: String? = null

    private val searcher = ExpenseSearch(
        groupId = groupId,
        pageSize = PAGE_SIZE,
        state = _state,
        scope = viewModelScope,
        client = { resolvedInstanceBaseUrl?.let(::client) },
    )

    /** See [app.spliit.android.feature.groups.GroupsListViewModel.load] for why this exists
     *  alongside [refresh] rather than being called from `init`. */
    fun load() {
        viewModelScope.launch { refresh() }
    }

    /**
     * Loads the group, but only if it is not already here.
     *
     * The screen's `LaunchedEffect(Unit)` runs again every time this composable is freshly
     * composed, coming back from the expense form, from the group editor, from anywhere this
     * screen was left rather than popped. The ViewModel is scoped to the nav entry and survives
     * all of that, so re-entering a loaded group used to re-read the group, the whole first page
     * of expenses and the balances for nothing. iOS guards the same call with
     * `guard group == nil else { return }`; this is that guard.
     *
     * Whoever wants fresh numbers asks for them: pull-to-refresh calls [pullToRefresh], a failure
     * offers [retry], and a write calls [reloadAfterExpenseChange]. Nothing is served from an
     * HTTP cache, see [TrpcClient]'s own no-store note; a cached GET would quietly serve stale
     * balances.
     */
    fun loadIfNeeded() {
        if (_state.value.group is LoadState.Loaded) return
        load()
    }

    /** Also limited: an error puts a button on screen, and a button beside an error message is
     *  the other thing people tap repeatedly. */
    fun retry() {
        if (!refreshLimiter.allow()) return
        load()
    }

    /**
     * Everything on this screen again, without the skeletons, the pull-to-refresh gesture.
     *
     * Deliberately not [refresh], which resets the three sections to [LoadState.Loading] and so
     * replaces the numbers a user is looking at with a skeleton for the length of a round trip.
     * A refresh that blanks the screen is indistinguishable from a reload, and the spinner
     * already says work is happening.
     *
     * The lazy sections are refreshed only if they have been asked for once, see
     * [refreshStatsIfRequested] and [refreshActivitiesIfLoaded].
     */
    fun pullToRefresh() {
        viewModelScope.launch { refreshInPlace() }
    }

    /** The work behind [pullToRefresh], public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun refreshInPlace() {
        // The rate limit lives on the work rather than on [pullToRefresh], for two reasons. It
        // covers every caller instead of the one launcher, and it is reachable from a test:
        // `viewModelScope` dispatches on Main, which the JVM suites deliberately do not install
        //, a check inside the launcher is a check nothing can assert on.
        //
        // `isRefreshing` is cleared on the way out because PullToRefreshBox keeps its indicator
        // up until the callback returns; dropping the work without this leaves a spinner
        // turning over nothing.
        if (!refreshLimiter.allow()) {
            _state.update { it.copy(isRefreshing = false) }
            return
        }
        val baseUrl = resolvedInstanceBaseUrl ?: return refresh()
        _state.update { it.copy(isRefreshing = true) }
        try {
            val client = clientFactory(baseUrl)
            coroutineScope {
                val group = async { reloadGroupInPlace(client, baseUrl) }
                val expenses = async { loadFirstExpensesPage(client, keepOnFailure = true) }
                val balances = async { loadBalances(client, keepOnFailure = true) }
                val stats = async { refreshStatsIfRequested(client) }
                val activities = async { refreshActivitiesIfLoaded(client) }
                val search = async { searcher.reload(client) }
                group.await(); expenses.await(); balances.await(); stats.await(); activities.await(); search.await()
            }
        } finally {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /** The actual load, see [app.spliit.android.feature.groups.GroupsListViewModel.refresh]
     *  for why tests call this directly. */
    suspend fun refresh() {
        _state.update {
            it.copy(group = LoadState.Loading, expenses = LoadState.Loading, balances = LoadState.Loading)
        }

        val snapshot = try {
            recentGroupsStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "Couldn't read the stored group list."
            _state.update {
                it.copy(
                    group = LoadState.Failed(message),
                    expenses = LoadState.Failed(message),
                    balances = LoadState.Failed(message),
                )
            }
            return
        }

        val instanceBaseUrl = snapshot.groups.firstOrNull { it.groupId == groupId }?.instanceBaseUrl
        if (instanceBaseUrl == null) {
            val message = "This group isn't in your list anymore."
            _state.update {
                it.copy(
                    group = LoadState.Failed(message),
                    expenses = LoadState.Failed(message),
                    balances = LoadState.Failed(message),
                )
            }
            return
        }
        resolvedInstanceBaseUrl = instanceBaseUrl

        val client = clientFactory(instanceBaseUrl)
        coroutineScope {
            val groupJob = async { loadGroup(client, snapshot, instanceBaseUrl) }
            val expensesJob = async { loadFirstExpensesPage(client) }
            val balancesJob = async { loadBalances(client) }
            groupJob.await()
            expensesJob.await()
            balancesJob.await()
        }
    }

    private suspend fun loadGroup(client: TrpcClient, snapshot: app.spliit.core.RecentGroupsSnapshot, instanceBaseUrl: String) {
        try {
            val response = client.call(SpliitEndpoints.groupsGet(groupId))
            val group = response.group
            if (group == null) {
                _state.update { it.copy(group = LoadState.Failed("This group no longer exists on this server.")) }
                return
            }

            // Opening a group stamps lastOpenedAt (so it sorts to the top of the list) and keeps
            // whatever this phone already knew, its remembered participant, its default split -
            // rather than the fresh row from the server clobbering either. See
            // RecentGroupsSnapshot.opening's own doc.
            val updated = snapshot.opening(
                RecentGroup(groupId = group.id, instanceBaseUrl = instanceBaseUrl, groupName = group.name),
            )
            recentGroupsStore.save(updated)
            val actorId = updated.actorId(groupId, group.participants.map { CoreParticipant(it.id, it.name) })

            val info = GroupInfo(
                id = group.id,
                name = group.name,
                information = group.information,
                currencySymbol = group.currency,
                currencyCode = group.currencyCode,
                createdAt = group.createdAt,
                participants = group.participants,
                instanceBaseUrl = instanceBaseUrl,
            )
            _state.update { it.copy(group = LoadState.Loaded(info), activeParticipantId = actorId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(group = LoadState.Failed(e.message)) }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(group = LoadState.Failed(e.message)) }
        }
    }

    /**
     * Re-reads the group without touching the stored row or the active participant.
     *
     * [loadGroup] is the *opening* of a group: it stamps `lastOpenedAt` and re-resolves who this
     * phone is. A refresh is neither, the group is already open and the answer to "who are
     * you?" has not moved, so this reads the one thing that can have changed on the server and
     * leaves the rest alone. A failure keeps what is on screen: the name in the title bar being
     * a moment old beats it being replaced by an error panel.
     */
    private suspend fun reloadGroupInPlace(client: TrpcClient, instanceBaseUrl: String) {
        val group = try {
            client.call(SpliitEndpoints.groupsGet(groupId)).group ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            return
        } catch (e: TrpcClientError) {
            return
        }
        val info = GroupInfo(
            id = group.id,
            name = group.name,
            information = group.information,
            currencySymbol = group.currency,
            currencyCode = group.currencyCode,
            createdAt = group.createdAt,
            participants = group.participants,
            instanceBaseUrl = instanceBaseUrl,
        )
        _state.update { it.copy(group = LoadState.Loaded(info)) }
    }

    /** @param keepOnFailure see [loadBalances]. */
    private suspend fun loadFirstExpensesPage(client: TrpcClient, keepOnFailure: Boolean = false) {
        try {
            val response = client.call(SpliitEndpoints.expensesList(groupId, cursor = 0, limit = PAGE_SIZE))
            val page = ExpensesPage(response.expenses, response.hasMore, response.nextCursor)
            _state.update { it.copy(expenses = LoadState.Loaded(page)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            if (!keepOnFailure) _state.update { it.copy(expenses = LoadState.Failed(e.message)) }
        } catch (e: TrpcClientError) {
            if (!keepOnFailure) _state.update { it.copy(expenses = LoadState.Failed(e.message)) }
        }
    }

    fun loadMoreExpenses() {
        viewModelScope.launch { loadNextExpensesPage() }
    }

    /** The offset-cursor page after whatever is already loaded, `groups.expenses.list`'s own
     *  `cursor`/`limit`/`nextCursor`/`hasMore`, not a key-based cursor. Public, like [refresh],
     *  so tests can drive it without a Main-dispatcher rule. */
    suspend fun loadNextExpensesPage() {
        val current = (_state.value.expenses as? LoadState.Loaded)?.value ?: return
        if (!current.hasMore || _state.value.isLoadingMoreExpenses) return
        val baseUrl = resolvedInstanceBaseUrl ?: return

        _state.update { it.copy(isLoadingMoreExpenses = true) }
        try {
            val client = clientFactory(baseUrl)
            val response = client.call(
                SpliitEndpoints.expensesList(groupId, cursor = current.nextCursor, limit = PAGE_SIZE),
            )
            // Guards a duplicate page if an expense was added while paging, same as the iOS list.
            val known = current.expenses.mapTo(HashSet()) { it.id }
            val merged = current.expenses + response.expenses.filterNot { it.id in known }
            _state.update {
                it.copy(
                    expenses = LoadState.Loaded(ExpensesPage(merged, response.hasMore, response.nextCursor)),
                    isLoadingMoreExpenses = false,
                )
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(isLoadingMoreExpenses = false) }
            throw e
        } catch (e: TrpcServerError) {
            // A paging failure shouldn't replace what is already on screen, the row simply stops
            // trying until a full retry.
            _state.update { it.copy(isLoadingMoreExpenses = false) }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isLoadingMoreExpenses = false) }
        }
    }

    /**
     * @param keepOnFailure leave whatever is on screen alone if the read fails. True for
     *   [refreshBalances], where the balances are already drawn and a failed recompute should
     *   not replace a correct-a-moment-ago figure with an error panel; false for the initial
     *   load, where there is nothing to keep.
     */
    private suspend fun loadBalances(client: TrpcClient, keepOnFailure: Boolean = false) {
        try {
            val response = client.call(SpliitEndpoints.balancesList(groupId))
            // Only `total` is read, see BalancesInfo's own doc for why `paid`/`paidFor` never
            // make it past this line.
            val totals = response.balances.mapValues { (_, balance) -> balance.total.toLong() }
            _state.update { it.copy(balances = LoadState.Loaded(BalancesInfo(totals, response.reimbursements))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            if (!keepOnFailure) _state.update { it.copy(balances = LoadState.Failed(e.message)) }
        } catch (e: TrpcClientError) {
            if (!keepOnFailure) _state.update { it.copy(balances = LoadState.Failed(e.message)) }
        }
    }

    // ---- what the edit sheet writes back ------------------------------------------------------
    //
    // The expense form used to be a destination: the NavHost dropped this screen while it was up,
    // and `LaunchedEffect(Unit) { load() }` re-read the group, the whole first page of expenses
    // and the balances on the way back. Editing is now a sheet drawn *over* this screen, which
    // stays composed, so nothing re-runs on its own, and the three entry points below are what
    // put the result back. None of them calls `groups.expenses.list`: the list keeps its scroll
    // position, its paged-in rows and its skeleton-free state, and only what actually changed is
    // read again.

    /**
     * One expense has been written; put it back into the list where it belongs, and recompute
     * the balances.
     *
     * The balances genuinely have to be asked for again, an amount, a payer or a split that
     * changed moves what everybody owes, and that sum is the server's to make, not ours. The
     * *row*, on the other hand, is one expense, and `groups.expenses.update` answers with an ID
     * and nothing else, so the row's new contents come from a single `groups.expenses.get`.
     */
    fun expenseSaved(expenseId: String) {
        viewModelScope.launch { applySavedExpense(expenseId) }
    }

    /** The work behind [expenseSaved], public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun applySavedExpense(expenseId: String) {
        val baseUrl = resolvedInstanceBaseUrl ?: return
        val client = clientFactory(baseUrl)
        coroutineScope {
            val row = async { reloadExpenseRow(client, expenseId) }
            val balances = async { loadBalances(client, keepOnFailure = true) }
            val derived = async { reloadDerivedAfterExpenseChange(client) }
            row.await()
            balances.await()
            derived.await()
        }
    }

    /**
     * The expense is gone from the server; take the row out and recompute the balances.
     *
     * No read at all for the row, a delete that succeeded leaves nothing to fetch, and the list
     * already knows which row it was.
     */
    fun expenseDeleted(expenseId: String) {
        viewModelScope.launch { applyDeletedExpense(expenseId) }
    }

    /** The work behind [expenseDeleted], public for the same reason as [applySavedExpense]. */
    suspend fun applyDeletedExpense(expenseId: String) {
        _state.update { state ->
            val page = (state.expenses as? LoadState.Loaded)?.value ?: return@update state
            state.copy(
                expenses = LoadState.Loaded(page.copy(expenses = page.expenses.filterNot { it.id == expenseId })),
            )
        }
        val baseUrl = resolvedInstanceBaseUrl ?: return
        val client = clientFactory(baseUrl)
        coroutineScope {
            val balances = async { loadBalances(client, keepOnFailure = true) }
            val derived = async { reloadDerivedAfterExpenseChange(client) }
            balances.await()
            derived.await()
        }
    }

    /**
     * An expense was written from somewhere this screen does not own the result of, the
     * full-screen create form, or a settle-up, so everything an expense can move is read again.
     *
     * iOS's `reloadAfterExpenseChange`, with the same list and the same two deliberate omissions:
     * **not** the group and **not** the categories. An expense cannot rename a group, change its
     * currency, add a participant or invent a category, so re-reading either would be two
     * requests that can only ever answer what we already have.
     */
    fun reloadAfterExpenseChange() {
        viewModelScope.launch { applyExpenseChange() }
    }

    /** The work behind [reloadAfterExpenseChange], public for the same reason as
     *  [applySavedExpense]. */
    suspend fun applyExpenseChange() {
        // Never rate-limited, and it clears the window as well: something was just written, so
        // what is on screen is known to be stale, and a pull immediately afterwards is asking
        // about the change rather than repeating a gesture.
        refreshLimiter.reset()
        val baseUrl = resolvedInstanceBaseUrl ?: return
        val client = clientFactory(baseUrl)
        coroutineScope {
            val expenses = async { loadFirstExpensesPage(client, keepOnFailure = true) }
            val balances = async { loadBalances(client, keepOnFailure = true) }
            val derived = async { reloadDerivedAfterExpenseChange(client) }
            expenses.await()
            balances.await()
            derived.await()
        }
    }

    /**
     * The three things an expense change also moves, each only if somebody is looking at it: the
     * open search (whose results are a view of the same expenses and would otherwise still show
     * the old title), the totals, and the activity log, which has just gained the line
     * describing the change.
     */
    private suspend fun reloadDerivedAfterExpenseChange(client: TrpcClient) {
        coroutineScope {
            val search = async { searcher.reload(client) }
            val stats = async { refreshStatsIfRequested(client) }
            val activities = async { refreshActivitiesIfLoaded(client) }
            search.await()
            stats.await()
            activities.await()
        }
    }

    /**
     * The group itself was edited, its name, its note, its participants, so read it again.
     *
     * Its own entry point rather than a flag on [reloadAfterExpenseChange], because the two
     * invalidate opposite things: an expense cannot change the group, and a group edit does not
     * move a single amount. The balances do come along, since removing a participant takes their
     * column out of them.
     */
    fun groupEdited() {
        viewModelScope.launch {
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            val client = clientFactory(baseUrl)
            coroutineScope {
                val group = async { reloadGroupInPlace(client, baseUrl) }
                val balances = async { loadBalances(client, keepOnFailure = true) }
                val activities = async { refreshActivitiesIfLoaded(client) }
                group.await()
                balances.await()
                activities.await()
            }
        }
    }

    /**
     * Reads one expense and places it in the loaded page.
     *
     * `ExpenseDetails` names its payees by ID where a list row nests a whole participant, so the
     * names come from the group that is already loaded rather than from a second read, the one
     * thing this function is here to avoid.
     */
    private suspend fun reloadExpenseRow(client: TrpcClient, expenseId: String) {
        val expense = try {
            client.call(SpliitEndpoints.expensesGet(groupId, expenseId)).expense
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            // The write itself went through, this is only the row failing to catch up, and a
            // stale row is better than an error panel over a list that is otherwise correct.
            return
        } catch (e: TrpcClientError) {
            return
        }
        val participants = (_state.value.group as? LoadState.Loaded)?.value?.participants.orEmpty()
        val byId = participants.associateBy { it.id }
        val row = ExpenseListItem(
            id = expense.id,
            title = expense.title,
            amount = expense.amount,
            createdAt = expense.createdAt,
            expenseDate = expense.expenseDate,
            isReimbursement = expense.isReimbursement,
            splitMode = expense.splitMode,
            recurrenceRule = expense.recurrenceRule,
            category = expense.category,
            paidBy = expense.paidBy,
            // In the order the detail payload gives them, not the group's. The row prints these
            // names, "Paid by Ana for Bruno and Chidi", and re-sorting them here would make an
            // updated row read differently from the same row after a reload: an update rewrites
            // the payee rows server-side, so the order that comes back afterwards is the order
            // the list endpoint will report from then on. Verified against a live instance,
            // because the guess went the other way first.
            paidFor = expense.paidFor.mapNotNull { paidFor ->
                byId[paidFor.participantId]?.let { ExpenseListItem.PaidFor(it, paidFor.shares) }
            },
            documentCount = expense.documents.size,
        )
        _state.update { state ->
            val page = (state.expenses as? LoadState.Loaded)?.value ?: return@update state
            state.copy(expenses = LoadState.Loaded(page.copy(expenses = placed(page.expenses, row))))
        }
    }

    /**
     * Records who this phone is in this group, or clears that answer with a null
     * [participantId], the active-user picker's whole job.
     *
     * This matters beyond the "You" summary: [participantId] is what every future
     * `groups.expenses.*` write from this screen will carry, and it is the only thing the
     * activity log can name anybody with (CLAUDE.md).
     */
    fun selectActiveParticipant(participantId: String?) {
        viewModelScope.launch {
            val snapshot = try {
                recentGroupsStore.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            val updated = snapshot.settingParticipantId(groupId, participantId)
            recentGroupsStore.save(updated)

            val participants = (_state.value.group as? LoadState.Loaded)?.value?.participants.orEmpty()
            val actorId = updated.actorId(groupId, participants.map { CoreParticipant(it.id, it.name) })
            _state.update { it.copy(activeParticipantId = actorId) }

            // Two of the totals are this participant's, so answering the question changes the
            // answer, but only for a tab somebody has actually opened.
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            if (statsRequested) loadStats(clientFactory(baseUrl), actorId)
        }
    }

    // ---- the totals tab ----------------------------------------------------------------------
    //
    // Lazy, and deliberately so: this is another request on a screen that already makes three,
    // and no other tab needs it. iOS words the rule as "three tabs' worth of editing should not
    // pay for an answer nobody has asked to see", so nothing here runs until the tab is opened,
    // and `statsRequested` is what every later invalidation checks.

    /** Whether the totals tab has ever been opened. Separate from [statsParticipantId], which has
     *  null as a real answer, the group's total with nobody's share beside it. */
    private var statsRequested = false
    private var statsParticipantId: String? = null

    /**
     * The totals, unless they are already the answer to this exact question.
     *
     * Keyed on the participant as well as on having been asked once, because two of the three
     * figures are theirs: re-answering "who are you?" from this screen has to ask again.
     */
    fun loadStatsIfNeeded() {
        val state = _state.value
        if (state.group !is LoadState.Loaded) return
        if (statsRequested && statsParticipantId == state.activeParticipantId) return
        refreshStats()
    }

    /** The same, unconditionally, "Try again", and the tab's own pull-to-refresh. */
    fun refreshStats() {
        viewModelScope.launch {
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            loadStats(clientFactory(baseUrl), _state.value.activeParticipantId)
        }
    }

    /** Re-runs the totals for whoever they were last run for, and does nothing until the tab has
     *  been opened once. */
    private suspend fun refreshStatsIfRequested(client: TrpcClient) {
        if (!statsRequested) return
        loadStats(client, statsParticipantId)
    }

    /** The work behind both entry points, public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun loadStats(client: TrpcClient, participantId: String?) {
        statsRequested = true
        statsParticipantId = participantId
        _state.update { it.copy(stats = LoadState.Loading, statsUnavailable = false) }
        try {
            // Through the `groupStats` extension, never a bare procedure: an instance that
            // predates the rename answers `groups.stats.overview` with NOT_FOUND, and asking only
            // the current name is what shipped a wrong "this server has no totals".
            val response = client.groupStats(groupId, participantId)
            // The picker may have moved on while this was in flight; somebody else's share must
            // not become the answer under your name.
            if (statsParticipantId != participantId) return
            _state.update {
                it.copy(
                    stats = LoadState.Loaded(
                        StatsInfo(
                            totalGroupSpendings = response.totalGroupSpendings.toLong(),
                            yourSpendings = response.totalParticipantSpendings?.toLong(),
                            // Rounded here, on the way to the display, and never divided, see
                            // StatsInfo's own note on why this arrives as a Double at all.
                            yourShareMinorUnits = response.totalParticipantShare
                                ?.takeIf { share -> share.isFinite() }
                                ?.let { share -> MoneyFormatter.roundMinorUnits(share) },
                            summary = response.summary,
                            categories = response.categories.orEmpty(),
                        ),
                    ),
                    statsUnavailable = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            if (e.isUnknownProcedure) {
                // Neither name. Not a failure, there is nothing to retry and nothing the user
                // did wrong, so the tab says so once and offers no button.
                _state.update { it.copy(stats = LoadState.Failed(null), statsUnavailable = true) }
            } else {
                _state.update { it.copy(stats = LoadState.Failed(e.message)) }
            }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(stats = LoadState.Failed(e.message)) }
        }
    }

    // ---- the activity log --------------------------------------------------------------------

    /**
     * The log, the first time it is looked at.
     *
     * Deliberately not part of [refresh]: it is the one thing on this screen nothing else needs,
     * so loading it with the rest would put a request every group open pays for behind the three
     * that actually draw the screen. A failure is not retried from here, the log offers a button
     * for that, and an empty log that loaded is left alone.
     */
    fun loadActivitiesIfNeeded() {
        if (activitiesRequested) return
        retryActivities()
    }

    fun retryActivities() {
        viewModelScope.launch {
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            loadFirstActivitiesPage(clientFactory(baseUrl))
        }
    }

    private var activitiesRequested = false

    /** Re-reads the log after a change that wrote to it, but only once it has been read once:
     *  fetching a log for a screen nobody has opened is a request for something nobody is
     *  looking at, and it will fetch itself the moment they do. */
    private suspend fun refreshActivitiesIfLoaded(client: TrpcClient) {
        if (!activitiesRequested) return
        loadFirstActivitiesPage(client, keepOnFailure = true)
    }

    /** Public, like [refresh], so tests drive it without a Main-dispatcher rule. */
    suspend fun loadFirstActivitiesPage(client: TrpcClient, keepOnFailure: Boolean = false) {
        activitiesRequested = true
        if (!keepOnFailure) _state.update { it.copy(activities = LoadState.Loading) }
        try {
            val response = client.call(SpliitEndpoints.activitiesList(groupId, cursor = 0, limit = PAGE_SIZE))
            _state.update {
                it.copy(
                    activities = LoadState.Loaded(
                        ActivitiesPage(response.activities, response.hasMore, response.nextCursor),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            if (!keepOnFailure) _state.update { it.copy(activities = LoadState.Failed(e.message)) }
        } catch (e: TrpcClientError) {
            if (!keepOnFailure) _state.update { it.copy(activities = LoadState.Failed(e.message)) }
        }
    }

    fun loadMoreActivities() {
        viewModelScope.launch { loadNextActivitiesPage() }
    }

    /** Public for the same reason as [loadNextExpensesPage]. */
    suspend fun loadNextActivitiesPage() {
        val current = (_state.value.activities as? LoadState.Loaded)?.value ?: return
        if (!current.hasMore || _state.value.isLoadingMoreActivities) return
        val baseUrl = resolvedInstanceBaseUrl ?: return

        _state.update { it.copy(isLoadingMoreActivities = true) }
        try {
            val response = client(baseUrl).call(
                SpliitEndpoints.activitiesList(groupId, cursor = current.nextCursor, limit = PAGE_SIZE),
            )
            // The log grows at the *top*, so a page fetched after something new was recorded
            // repeats a row rather than skipping one. Same guard as the expense list.
            val known = current.activities.mapTo(HashSet()) { it.id }
            val merged = current.activities + response.activities.filterNot { it.id in known }
            _state.update {
                it.copy(
                    activities = LoadState.Loaded(
                        ActivitiesPage(merged, response.hasMore, response.nextCursor),
                    ),
                    isLoadingMoreActivities = false,
                )
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(isLoadingMoreActivities = false) }
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(isLoadingMoreActivities = false) }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isLoadingMoreActivities = false) }
        }
    }

    // ---- search ------------------------------------------------------------------------------

    /** The in-flight search, cancelled by the next keystroke. Holding the job is what makes the
     *  delay in [runSearch] a debounce: only the last one ever survives it. */

    // The search field owns its own state slice and its own debounce job; see [ExpenseSearch].
    // These three stay as delegates because the screen and the tests both call them by name.

    fun setSearchActive(active: Boolean): Unit = searcher.setActive(active)

    fun search(text: String): Unit = searcher.onQueryChanged(text)

    suspend fun runSearch(query: String): Unit = searcher.run(query)

    private fun client(baseUrl: String): TrpcClient = clientFactory(baseUrl)
}
