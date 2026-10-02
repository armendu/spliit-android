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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.spliit.core.Participant as CoreParticipant

class GroupDetailViewModel(
    private val groupId: String,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val refreshLimiter: RefreshLimiter = RefreshLimiter(),
) : ViewModel() {
    private companion object {
        const val PAGE_SIZE = 20
    }

    private val _state = MutableStateFlow(GroupDetailUiState())
    val state: StateFlow<GroupDetailUiState> = _state.asStateFlow()

    private var resolvedInstanceBaseUrl: String? = null

    private val searcher = ExpenseSearch(
        groupId = groupId,
        pageSize = PAGE_SIZE,
        state = _state,
        scope = viewModelScope,
        client = { resolvedInstanceBaseUrl?.let(clientFactory) },
    )

    fun load() {
        viewModelScope.launch { refresh() }
    }

    fun loadIfNeeded() {
        if (_state.value.group is LoadState.Loaded) return
        load()
    }

    fun retry() {
        if (!refreshLimiter.allow()) return
        load()
    }

    fun pullToRefresh() {
        viewModelScope.launch { refreshInPlace() }
    }

    suspend fun refreshInPlace() {
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
        } finally {
            setLoading(false)
        }
    }

    private suspend fun loadBalances(client: TrpcClient, keepOnFailure: Boolean = false) {
        try {
            val response = client.call(SpliitEndpoints.balancesList(groupId))
            val totals = response.balances.mapValues { (_, balance) -> balance.total.toLong() }
            _state.update { it.copy(balances = LoadState.Loaded(BalancesInfo(totals, response.reimbursements))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            if (!keepOnFailure) _state.update { it.copy(balances = LoadState.Failed(e.message)) }
        }
    }

    fun expenseSaved(expenseId: String) {
        viewModelScope.launch { applySavedExpense(expenseId) }
    }

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

    fun expenseDeleted(expenseId: String) {
        viewModelScope.launch { applyDeletedExpense(expenseId) }
    }

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

    fun reloadAfterExpenseChange() {
        viewModelScope.launch { applyExpenseChange() }
    }

    suspend fun applyExpenseChange() {
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

    private suspend fun reloadExpenseRow(client: TrpcClient, expenseId: String) {
        val expense = try {
            client.call(SpliitEndpoints.expensesGet(groupId, expenseId)).expense
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
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

    fun selectActiveParticipant(participantId: String?) {
        viewModelScope.launch { applyActiveParticipant(participantId) }
    }

    suspend fun applyActiveParticipant(participantId: String?) {
        val snapshot = try {
            recentGroupsStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val updated = snapshot.settingParticipantId(groupId, participantId)
        recentGroupsStore.save(updated)

        val participants = (_state.value.group as? LoadState.Loaded)?.value?.participants.orEmpty()
        val actorId = updated.actorId(groupId, participants.map { CoreParticipant(it.id, it.name) })
        _state.update { it.copy(activeParticipantId = actorId) }

        val baseUrl = resolvedInstanceBaseUrl ?: return
        if (statsRequested) loadStats(clientFactory(baseUrl), actorId)
    }

    private var statsRequested = false
    private var statsParticipantId: String? = null

    fun loadStatsIfNeeded() {
        val state = _state.value
        if (state.group !is LoadState.Loaded) return
        if (statsRequested && statsParticipantId == state.activeParticipantId) return
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            val baseUrl = resolvedInstanceBaseUrl ?: return@launch
            loadStats(clientFactory(baseUrl), _state.value.activeParticipantId)
        }
    }

    private suspend fun refreshStatsIfRequested(client: TrpcClient) {
        if (!statsRequested) return
        loadStats(client, statsParticipantId)
    }

    suspend fun loadStats(client: TrpcClient, participantId: String?) {
        statsRequested = true
        statsParticipantId = participantId
        _state.update { it.copy(stats = LoadState.Loading, statsUnavailable = false) }
        try {
            val response = client.groupStats(groupId, participantId)
            if (statsParticipantId != participantId) return
            _state.update {
                it.copy(
                    stats = LoadState.Loaded(
                        StatsInfo(
                            totalGroupSpendings = response.totalGroupSpendings.toLong(),
                            yourSpendings = response.totalParticipantSpendings?.toLong(),
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
                _state.update { it.copy(stats = LoadState.Failed(null), statsUnavailable = true) }
            } else {
                _state.update { it.copy(stats = LoadState.Failed(e.message)) }
            }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(stats = LoadState.Failed(e.message)) }
        }
    }

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

    private suspend fun refreshActivitiesIfLoaded(client: TrpcClient) {
        if (!activitiesRequested) return
        loadFirstActivitiesPage(client, keepOnFailure = true)
    }

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

    fun setSearchActive(active: Boolean): Unit = searcher.setActive(active)

    fun search(text: String): Unit = searcher.onQueryChanged(text)

    suspend fun runSearch(query: String): Unit = searcher.run(query)
}
