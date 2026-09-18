package app.spliit.android.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.ExpenseListItem
import app.spliit.api.Participant
import app.spliit.api.Reimbursement
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcClientError
import app.spliit.api.TrpcServerError
import app.spliit.core.DateBucket
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsStore
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

/** The group screen's currency-bearing detail — everything both tabs draw from that only
 *  `groups.get` carries. */
data class GroupInfo(
    val id: String,
    val name: String,
    /** Free-text, e.g. "$" or "CHF" — see [app.spliit.core.MoneyFormatter]'s own note. */
    val currencySymbol: String,
    val currencyCode: String?,
    val participants: List<Participant>,
)

/** One page of the expense list, as `groups.expenses.list` answers it — carried whole rather
 *  than unpacked, since [loadMoreExpenses] needs [nextCursor] and [hasMore] verbatim. */
data class ExpensesPage(
    val expenses: List<ExpenseListItem>,
    val hasMore: Boolean,
    val nextCursor: Int,
)

/**
 * The balances tab's whole answer, with [Balance]'s `paid`/`paidFor` already discarded.
 *
 * **`groups.balances.list` does not tell you what anyone paid.** Its `paid` and `paidFor` are
 * derived from the suggested payments rather than from the expenses — one is always zero and the
 * other is `abs(total)`. Only `total` means anything, so [balances] carries nothing else: there
 * is no field left for a later call site to misread.
 */
data class BalancesInfo(
    /** Participant ID to minor-units total. A participant with no activity is absent, not zero —
     *  see [SpliitEndpoints.BalancesResponse]. */
    val balances: Map<String, Long>,
    val reimbursements: List<Reimbursement>,
)

data class GroupDetailUiState(
    val group: LoadState<GroupInfo> = LoadState.Loading,
    val expenses: LoadState<ExpensesPage> = LoadState.Loading,
    val isLoadingMoreExpenses: Boolean = false,
    val balances: LoadState<BalancesInfo> = LoadState.Loading,
    /** Who this phone is in this group, resolved via [app.spliit.core.RecentGroupsSnapshot.actorId]
     *  — null both before anyone has answered and once a remembered answer has left the group. */
    val activeParticipantId: String? = null,
)

/** One bucket's worth of expenses, newest bucket first — see [bucketExpenses]. */
data class ExpenseSection(val bucket: DateBucket, val expenses: List<ExpenseListItem>)

/**
 * Groups [expenses] under the same [DateBucket]s the app uses elsewhere, newest first.
 *
 * A plain function rather than a method on the ViewModel: it needs nothing the ViewModel holds
 * beyond the list and a clock, so it is exercised directly in tests with neither a server nor a
 * `RecentGroupsStore` in the way. [DateBucket]'s own ordinal order is already newest-to-oldest,
 * which is what sorting the buckets by it relies on; the expenses within one bucket keep the
 * order the server sent them in.
 */
fun bucketExpenses(expenses: List<ExpenseListItem>, clock: Clock = Clock.systemDefaultZone()): List<ExpenseSection> {
    val byBucket = LinkedHashMap<DateBucket, MutableList<ExpenseListItem>>()
    for (expense in expenses) {
        val bucket = DateBucket.of(expense.expenseDate, clock)
        byBucket.getOrPut(bucket) { mutableListOf() }.add(expense)
    }
    return byBucket.entries.sortedBy { it.key.ordinal }.map { ExpenseSection(it.key, it.value) }
}

/**
 * [item] in [expenses], at the position its date gives it — the server orders a group's expenses
 * newest first, and an edit can move one.
 *
 * A row whose date did not change does not move at all, even to a place its date would also
 * allow. Expense dates are whole days, so several rows commonly share one, and their order
 * within that day is the server's (by creation) rather than anything this side can recompute —
 * inserting by date alone shuffled an edited row to the end of its own day, which looks like the
 * list reloading and is the thing the sheet exists to avoid.
 *
 * Otherwise the old copy goes and the new one is inserted before the first row that is older,
 * leaving every other row where it was. An undone delete comes back under a new ID and lands the
 * same way.
 */
fun placed(expenses: List<ExpenseListItem>, item: ExpenseListItem): List<ExpenseListItem> {
    val current = expenses.indexOfFirst { it.id == item.id }
    if (current >= 0 && expenses[current].expenseDate == item.expenseDate) {
        return expenses.toMutableList().also { it[current] = item }
    }
    val without = expenses.filterNot { it.id == item.id }
    val index = without.indexOfFirst { it.expenseDate.isBefore(item.expenseDate) }
    return if (index < 0) without + item else without.take(index) + item + without.drop(index)
}

/**
 * The active participant's own balance, in minor units — what the "You" summary at the top of
 * the balances tab draws, and null exactly when that summary has nothing to lead with: either
 * nobody has said who they are yet, or the balances themselves have not loaded.
 *
 * A plain extension over [GroupDetailUiState] rather than a stored field, so there is exactly one
 * place this can disagree with [GroupDetailUiState.balances] — nowhere, because it is read from
 * it directly instead of copied alongside it.
 */
fun GroupDetailUiState.yourBalanceMinorUnits(): Long? {
    val id = activeParticipantId ?: return null
    val loaded = balances as? LoadState.Loaded ?: return null
    // Absent means no activity, which is a real zero here — see BalancesInfo's own note.
    return loaded.value.balances[id] ?: 0L
}

/**
 * One group's detail screen: the expenses tab and the balances tab, loaded together.
 *
 * Unlike [app.spliit.android.feature.groups.GroupsListViewModel], this class is not handed the
 * instance a group lives on — the nav route is just `groups/{groupId}` — so [refresh] resolves it
 * itself from the stored [RecentGroup] row for [groupId]. A group with no such row (reached by a
 * route this app does not currently produce) fails every section rather than guessing a server.
 */
class GroupDetailViewModel(
    private val groupId: String,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 20
    }

    private val _state = MutableStateFlow(GroupDetailUiState())
    val state: StateFlow<GroupDetailUiState> = _state.asStateFlow()

    /** Set once [refresh] has resolved which instance [groupId] lives on — reused by
     *  [loadNextExpensesPage] and [selectActiveParticipant] so neither re-derives it. */
    private var resolvedInstanceBaseUrl: String? = null

    /** See [app.spliit.android.feature.groups.GroupsListViewModel.load] for why this exists
     *  alongside [refresh] rather than being called from `init`. */
    fun load() {
        viewModelScope.launch { refresh() }
    }

    fun retry() = load()

    /** The actual load — see [app.spliit.android.feature.groups.GroupsListViewModel.refresh]
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
            // whatever this phone already knew — its remembered participant, its default split —
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
                currencySymbol = group.currency,
                currencyCode = group.currencyCode,
                participants = group.participants,
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

    private suspend fun loadFirstExpensesPage(client: TrpcClient) {
        try {
            val response = client.call(SpliitEndpoints.expensesList(groupId, cursor = 0, limit = PAGE_SIZE))
            val page = ExpensesPage(response.expenses, response.hasMore, response.nextCursor)
            _state.update { it.copy(expenses = LoadState.Loaded(page)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(expenses = LoadState.Failed(e.message)) }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(expenses = LoadState.Failed(e.message)) }
        }
    }

    fun loadMoreExpenses() {
        viewModelScope.launch { loadNextExpensesPage() }
    }

    /** The offset-cursor page after whatever is already loaded — `groups.expenses.list`'s own
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
            // A paging failure shouldn't replace what is already on screen — the row simply stops
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
            // Only `total` is read — see BalancesInfo's own doc for why `paid`/`paidFor` never
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
    // stays composed — so nothing re-runs on its own, and the three entry points below are what
    // put the result back. None of them calls `groups.expenses.list`: the list keeps its scroll
    // position, its paged-in rows and its skeleton-free state, and only what actually changed is
    // read again.

    /**
     * One expense has been written; put it back into the list where it belongs, and recompute
     * the balances.
     *
     * The balances genuinely have to be asked for again — an amount, a payer or a split that
     * changed moves what everybody owes, and that sum is the server's to make, not ours. The
     * *row*, on the other hand, is one expense, and `groups.expenses.update` answers with an ID
     * and nothing else, so the row's new contents come from a single `groups.expenses.get`.
     */
    fun expenseSaved(expenseId: String) {
        viewModelScope.launch { applySavedExpense(expenseId) }
    }

    /** The work behind [expenseSaved] — public, like [refresh], so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun applySavedExpense(expenseId: String) {
        val baseUrl = resolvedInstanceBaseUrl ?: return
        val client = clientFactory(baseUrl)
        coroutineScope {
            val row = async { reloadExpenseRow(client, expenseId) }
            val balances = async { loadBalances(client, keepOnFailure = true) }
            row.await()
            balances.await()
        }
    }

    /**
     * The expense is gone from the server; take the row out and recompute the balances.
     *
     * No read at all for the row — a delete that succeeded leaves nothing to fetch, and the list
     * already knows which row it was.
     */
    fun expenseDeleted(expenseId: String) {
        viewModelScope.launch { applyDeletedExpense(expenseId) }
    }

    /** The work behind [expenseDeleted] — public for the same reason as [applySavedExpense]. */
    suspend fun applyDeletedExpense(expenseId: String) {
        _state.update { state ->
            val page = (state.expenses as? LoadState.Loaded)?.value ?: return@update state
            state.copy(
                expenses = LoadState.Loaded(page.copy(expenses = page.expenses.filterNot { it.id == expenseId })),
            )
        }
        val baseUrl = resolvedInstanceBaseUrl ?: return
        loadBalances(clientFactory(baseUrl), keepOnFailure = true)
    }

    /**
     * Reads one expense and places it in the loaded page.
     *
     * `ExpenseDetails` names its payees by ID where a list row nests a whole participant, so the
     * names come from the group that is already loaded rather than from a second read — the one
     * thing this function is here to avoid.
     */
    private suspend fun reloadExpenseRow(client: TrpcClient, expenseId: String) {
        val expense = try {
            client.call(SpliitEndpoints.expensesGet(groupId, expenseId)).expense
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            // The write itself went through — this is only the row failing to catch up, and a
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
            // names — "Paid by Ana for Bruno and Chidi" — and re-sorting them here would make an
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
     * [participantId] — the active-user picker's whole job.
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
        }
    }
}
