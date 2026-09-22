package app.spliit.android.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.ExpenseListItem
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcClientError
import app.spliit.api.TrpcException
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
 * One group's detail screen.
 *
 * The nav route is just `groups/{groupId}`, so [refresh] resolves the instance itself from the
 * stored [RecentGroup] row. A group with no row fails every section rather than guessing a
 * server.
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

        /** How long a pause in typing counts as "done typing". */
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
        client = { resolvedInstanceBaseUrl?.let(clientFactory) },
    )

    /** See [app.spliit.android.feature.groups.GroupsListViewModel.load] for why this exists
     *  alongside [refresh] rather than being called from `init`. */
    fun load() {
        viewModelScope.launch { refresh() }
    }

    /**
     * Loads the group, but only if it is not already here. The screen's `LaunchedEffect(Unit)`
     * re-runs on every return to it, and this ViewModel outlives all of that.
     *
     * Fresh numbers are asked for explicitly: [pullToRefresh], [retry], or
     * [reloadAfterExpenseChange]. Nothing is served from an HTTP cache.
     */
    fun loadIfNeeded() {
        if (_state.value.group is LoadState.Loaded) return
        load()
    }

    /** Also rate-limited: a button beside an error is the other thing people tap repeatedly. */
    fun retry() {
        if (!refreshLimiter.allow()) return
        load()
    }

    /**
     * Everything again without the skeletons. Not [refresh], which would blank the numbers a
     * user is looking at for the length of a round trip. Lazy sections refresh only if they
     * have been opened.
     */
    fun pullToRefresh() {
        viewModelScope.launch { refreshInPlace() }
    }

    /** The work behind [pullToRefresh], public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun refreshInPlace() {
        // The limit is on the work, not the launcher: `viewModelScope` dispatches on Main,
        // which the JVM suites do not install, so a check in the launcher is untestable.
        // `isRefreshing` must still clear, or PullToRefreshBox spins over nothing.
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

            // Opening stamps lastOpenedAt and keeps what this phone already knew (remembered
            // participant, default split) rather than letting the server row clobber it.
            val updated = snapshot.opening(
                RecentGroup(groupId = group.id, instanceBaseUrl = instanceBaseUrl, groupName = group.name),
            )
            recentGroupsStore.save(updated)
            val actorId = updated.actorId(groupId, group.participants.map { CoreParticipant(it.id, it.name) })

            val info = groupInfoOf(group, instanceBaseUrl)
            _state.update { it.copy(group = LoadState.Loaded(info), activeParticipantId = actorId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(group = LoadState.Failed(e.message)) }
        }
    }

    /**
     * Re-reads the group without touching the stored row or the active participant, unlike
     * [loadGroup], which is the *opening* of one. A failure keeps what is on screen.
     */
    private suspend fun reloadGroupInPlace(client: TrpcClient, instanceBaseUrl: String) {
        val group = try {
            client.call(SpliitEndpoints.groupsGet(groupId)).group ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            return
        }
        val info = groupInfoOf(group, instanceBaseUrl)
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
        } catch (e: TrpcException) {
            if (!keepOnFailure) _state.update { it.copy(expenses = LoadState.Failed(e.message)) }
        }
    }

    fun loadMoreExpenses() {
        viewModelScope.launch { loadNextExpensesPage() }
    }

    /** The next offset-cursor page. Public so tests can drive it without a Main dispatcher. */
    suspend fun loadNextExpensesPage(): Unit = loadNextPage(
        current = (_state.value.expenses as? LoadState.Loaded)?.value,
        isLoading = _state.value.isLoadingMoreExpenses,
        idOf = { it.id },
        setLoading = { loading -> _state.update { it.copy(isLoadingMoreExpenses = loading) } },
        fetch = { client, cursor ->
            val response = client.call(SpliitEndpoints.expensesList(groupId, cursor, PAGE_SIZE))
            ExpensesPage(response.expenses, response.hasMore, response.nextCursor)
        },
        onPage = { items, hasMore, cursor ->
            _state.update { it.copy(expenses = LoadState.Loaded(ExpensesPage(items, hasMore, cursor))) }
        },
    )

    /**
     * One page of a cursor-paged list, for both lists that have one.
     *
     * Two things here are easy to get wrong separately and were: the loading flag has to clear
     * on *every* way out, including the failures, or the footer spinner never stops; and a page
     * can repeat rows, because both lists can be written to between requests, the activity log
     * at the top and the expense list anywhere. A failure leaves what is on screen alone and
     * stops trying until something asks again.
     */
    private suspend fun <T> loadNextPage(
        current: CursorPage<T>?,
        isLoading: Boolean,
        idOf: (T) -> String,
        setLoading: (Boolean) -> Unit,
        fetch: suspend (TrpcClient, Int) -> CursorPage<T>,
        onPage: (items: List<T>, hasMore: Boolean, nextCursor: Int) -> Unit,
    ) {
        if (current == null || !current.hasMore || isLoading) return
        val baseUrl = resolvedInstanceBaseUrl ?: return

        setLoading(true)
        try {
            val fetched = fetch(clientFactory(baseUrl), current.nextCursor)
            val known = current.items.mapTo(HashSet(), idOf)
            val merged = current.items + fetched.items.filterNot { idOf(it) in known }
            onPage(merged, fetched.hasMore, fetched.nextCursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            // Deliberately silent: the rows already drawn are still correct.
        } finally {
            setLoading(false)
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
        } catch (e: TrpcException) {
            if (!keepOnFailure) _state.update { it.copy(balances = LoadState.Failed(e.message)) }
        }
    }

    // ---- what the edit sheet writes back ------------------------------------------------------
    //
    // Editing is a sheet over this screen, so nothing re-runs on its own and the three entry
    // points below put the result back. None of them calls `groups.expenses.list`: the list keeps
    // its scroll position and its paged-in rows, and only what changed is read again.

    /**
     * One expense was written: put the row back where it belongs and recompute the balances.
     * The balances are the server's sum to make; the row comes from one `groups.expenses.get`,
     * since the update answers with an ID and nothing else.
     */
    fun expenseSaved(expenseId: String) {
        viewModelScope.launch { applySavedExpense(expenseId) }
    }

    /** The work behind [expenseSaved], public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun applySavedExpense(expenseId: String) {
        refreshLimiter.reset()
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

    /** The expense is gone: drop the row and recompute the balances. Nothing to fetch. */
    fun expenseDeleted(expenseId: String) {
        viewModelScope.launch { applyDeletedExpense(expenseId) }
    }

    /** The work behind [expenseDeleted], public for the same reason as [applySavedExpense]. */
    suspend fun applyDeletedExpense(expenseId: String) {
        refreshLimiter.reset()
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
     * An expense was written somewhere this screen does not own the result of, so everything an
     * expense can move is read again. Not the group and not the categories: an expense cannot
     * change either.
     */
    fun reloadAfterExpenseChange() {
        viewModelScope.launch { applyExpenseChange() }
    }

    /** The work behind [reloadAfterExpenseChange], public for the same reason as
     *  [applySavedExpense]. */
    suspend fun applyExpenseChange() {
        // Never rate-limited, and clears the window, like every other write-follow-up: a pull
        // straight after writing is asking about the change, not repeating a gesture.
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

    /** The three things an expense change also moves, each only if it is being looked at: the
     *  open search, the totals, and the activity log. */
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
     * The group was edited, so read it again. Separate from [reloadAfterExpenseChange] because
     * the two invalidate opposite things. Balances come along, since removing a participant
     * takes their column out.
     */
    fun groupEdited() {
        refreshLimiter.reset()
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

    /** Reads one expense into the loaded page. Payee names come from the group already in
     *  memory, since the detail payload names them by ID only. */
    private suspend fun reloadExpenseRow(client: TrpcClient, expenseId: String) {
        val expense = try {
            client.call(SpliitEndpoints.expensesGet(groupId, expenseId)).expense
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            // The write went through; only the row failed to catch up.
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
            // The detail payload's order, not the group's. An update rewrites the payee rows
            // server-side, so this is the order the list endpoint reports from then on, and
            // re-sorting here would make an updated row read differently from a reloaded one.
            // Verified against a live instance.
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
     * Records who this phone is in this group, or clears it with a null [participantId]. Beyond
     * the "You" summary, this is what every future write carries, and the only thing the
     * activity log can name anybody with.
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

            // Two of the totals are this participant's, so the answer changes with them.
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            if (statsRequested) loadStats(clientFactory(baseUrl), actorId)
        }
    }

    // ---- the totals tab ----------------------------------------------------------------------
    //
    // Lazy: another request on a screen that already makes three, and no other tab needs it.
    // Nothing runs until the tab is opened, and `statsRequested` is what invalidations check.

    /** Whether the totals tab has ever been opened. Separate from [statsParticipantId], where
     *  null is a real answer. */
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
        } catch (e: TrpcException) {
            if (!keepOnFailure) _state.update { it.copy(activities = LoadState.Failed(e.message)) }
        }
    }

    fun loadMoreActivities() {
        viewModelScope.launch { loadNextActivitiesPage() }
    }

    /** Public for the same reason as [loadNextExpensesPage]. */
    suspend fun loadNextActivitiesPage(): Unit = loadNextPage(
        current = (_state.value.activities as? LoadState.Loaded)?.value,
        isLoading = _state.value.isLoadingMoreActivities,
        idOf = { it.id },
        setLoading = { loading -> _state.update { it.copy(isLoadingMoreActivities = loading) } },
        fetch = { client, cursor ->
            val response = client.call(SpliitEndpoints.activitiesList(groupId, cursor, PAGE_SIZE))
            ActivitiesPage(response.activities, response.hasMore, response.nextCursor)
        },
        onPage = { items, hasMore, cursor ->
            _state.update { it.copy(activities = LoadState.Loaded(ActivitiesPage(items, hasMore, cursor))) }
        },
    )

    // ---- search ------------------------------------------------------------------------------

    // The search field owns its own state slice and its own debounce job; see [ExpenseSearch].
    // These three stay as delegates because the screen and the tests both call them by name.

    fun setSearchActive(active: Boolean): Unit = searcher.setActive(active)

    fun search(text: String): Unit = searcher.onQueryChanged(text)

    suspend fun runSearch(query: String): Unit = searcher.run(query)

}
