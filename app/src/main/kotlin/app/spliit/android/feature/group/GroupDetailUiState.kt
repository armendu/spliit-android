package app.spliit.android.feature.group

import app.spliit.api.Activity
import app.spliit.api.ExpenseListItem
import app.spliit.api.Participant
import app.spliit.api.Reimbursement
import app.spliit.api.SpliitEndpoints
import app.spliit.core.DateBucket
import app.spliit.core.LoadState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

data class GroupInfo(
    val id: String,
    val name: String,
    val information: String?,
    val currencySymbol: String,
    val currencyCode: String?,
    val createdAt: Instant,
    val participants: List<Participant>,
    val instanceBaseUrl: String,
)

fun groupInfoOf(group: app.spliit.api.Group, instanceBaseUrl: String): GroupInfo = GroupInfo(
    id = group.id,
    name = group.name,
    information = group.information,
    currencySymbol = group.currency,
    currencyCode = group.currencyCode,
    createdAt = group.createdAt,
    participants = group.participants,
    instanceBaseUrl = instanceBaseUrl,
)

interface CursorPage<T> {
    val items: List<T>
    val hasMore: Boolean
    val nextCursor: Int
}

data class ExpensesPage(
    val expenses: List<ExpenseListItem>,
    override val hasMore: Boolean,
    override val nextCursor: Int,
) : CursorPage<ExpenseListItem> {
    override val items: List<ExpenseListItem> get() = expenses
}

data class BalancesInfo(
    val balances: Map<String, Long>,
    val reimbursements: List<Reimbursement>,
)

data class StatsInfo(
    val totalGroupSpendings: Long,
    val yourSpendings: Long?,
    val yourShareMinorUnits: Long?,
    val summary: SpliitEndpoints.StatsSummary?,
    val categories: List<SpliitEndpoints.CategoryTotal>,
)

data class ActivitiesPage(
    val activities: List<Activity>,
    override val hasMore: Boolean,
    override val nextCursor: Int,
) : CursorPage<Activity> {
    override val items: List<Activity> get() = activities
}

data class SearchUiState(
    val isActive: Boolean = false,
    val query: String = "",
    val results: LoadState<ExpensesPage>? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

data class GroupDetailUiState(
    val group: LoadState<GroupInfo> = LoadState.Loading,
    val expenses: LoadState<ExpensesPage> = LoadState.Loading,
    val isLoadingMoreExpenses: Boolean = false,
    val loadMoreExpensesFailed: Boolean = false,
    val balances: LoadState<BalancesInfo> = LoadState.Loading,
    val activeParticipantId: String? = null,
    val stats: LoadState<StatsInfo> = LoadState.Loading,
    val statsUnavailable: Boolean = false,
    val activities: LoadState<ActivitiesPage> = LoadState.Loading,
    val isLoadingMoreActivities: Boolean = false,
    val loadMoreActivitiesFailed: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    val isRefreshing: Boolean = false,
)

data class ExpenseSection(val bucket: DateBucket, val expenses: List<ExpenseListItem>)

fun bucketExpenses(expenses: List<ExpenseListItem>, clock: Clock = Clock.systemDefaultZone()): List<ExpenseSection> {
    val byBucket = LinkedHashMap<DateBucket, MutableList<ExpenseListItem>>()
    for (expense in expenses) {
        // An expense date is a calendar day sent as UTC midnight; the device zone would move it a day west of UTC.
        val bucket = DateBucket.of(expense.expenseDate.atZone(ZoneOffset.UTC).toLocalDate(), clock)
        byBucket.getOrPut(bucket) { mutableListOf() }.add(expense)
    }
    return byBucket.entries.sortedBy { it.key.ordinal }.map { ExpenseSection(it.key, it.value) }
}

fun placed(expenses: List<ExpenseListItem>, item: ExpenseListItem): List<ExpenseListItem> {
    val current = expenses.indexOfFirst { it.id == item.id }
    if (current >= 0 && expenses[current].expenseDate == item.expenseDate) {
        return expenses.toMutableList().also { it[current] = item }
    }
    val without = expenses.filterNot { it.id == item.id }
    val index = without.indexOfFirst { it.expenseDate.isBefore(item.expenseDate) }
    return if (index < 0) without + item else without.take(index) + item + without.drop(index)
}

// nextCursor is an offset into the server's list, so every row added or removed locally moves it too.
fun ExpensesPage.withoutRow(id: String): ExpensesPage {
    if (expenses.none { it.id == id }) return this
    return copy(expenses = expenses.filterNot { it.id == id }, nextCursor = (nextCursor - 1).coerceAtLeast(0))
}

// A row that sorts past everything loaded belongs to a page not fetched yet; leave it for that page.
fun ExpensesPage.withRow(row: ExpenseListItem): ExpensesPage {
    val wasShown = expenses.any { it.id == row.id }
    val shown = placed(expenses, row)
    return if (hasMore && shown.last().id == row.id) {
        copy(expenses = shown.dropLast(1), nextCursor = if (wasShown) nextCursor - 1 else nextCursor)
    } else {
        copy(expenses = shown, nextCursor = if (wasShown) nextCursor else nextCursor + 1)
    }
}

fun <T> appendingNew(shown: List<T>, more: List<T>, idOf: (T) -> String): List<T> {
    val known = shown.mapTo(HashSet(), idOf)
    return shown + more.filterNot { idOf(it) in known }
}

fun GroupDetailUiState.yourBalanceMinorUnits(): Long? {
    val id = activeParticipantId ?: return null
    val loaded = balances as? LoadState.Loaded ?: return null
    return loaded.value.balances[id] ?: 0L
}
