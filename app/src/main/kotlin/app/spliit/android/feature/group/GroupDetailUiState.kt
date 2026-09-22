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

// What the group screen holds and the pure functions over it. Split from GroupDetailViewModel,
// which is the loading half: this file has no coroutines, no client and no ViewModel, so its
// contents can be read, and tested, without any of that.

/** The group screen's currency-bearing detail, everything the tabs draw from that only
 *  `groups.get` carries, plus the one thing it does not: which server answered. */
data class GroupInfo(
    val id: String,
    val name: String,
    /** The group's note, as the information tab shows it. Blank and absent mean the same thing
     *  here; the tab trims before deciding which it is. */
    val information: String?,
    /** Free-text, e.g. "$" or "CHF", see [app.spliit.core.MoneyFormatter]'s own note. */
    val currencySymbol: String,
    val currencyCode: String?,
    val createdAt: Instant,
    val participants: List<Participant>,
    /**
     * The instance this group is on, resolved from its stored row rather than from the app's
     * default, **the share link is built from this**. A self-hosted group shared as a
     * spliit.app link opens nothing, so the default must never stand in for it.
     */
    val instanceBaseUrl: String,
)

/** One page of the expense list, as `groups.expenses.list` answers it, carried whole rather
 *  than unpacked, since [loadMoreExpenses] needs [nextCursor] and [hasMore] verbatim. */
/**
 * A [GroupInfo] from what `groups.get` answered, plus the one thing it cannot know: which server
 * answered it. Transcribing these eight fields by hand existed in three places, in two feature
 * packages, so adding a field meant remembering all three.
 */
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


data class ExpensesPage(
    val expenses: List<ExpenseListItem>,
    val hasMore: Boolean,
    val nextCursor: Int,
)

/**
 * The balances tab's answer, with `paid`/`paidFor` discarded. They are derived from the
 * suggested payments, not the expenses: one is always zero and the other `abs(total)`. Only
 * `total` means anything, so nothing else is carried for a call site to misread.
 */
data class BalancesInfo(
    /** Participant ID to minor-units total. A participant with no activity is absent, not zero -
     *  see [SpliitEndpoints.BalancesResponse]. */
    val balances: Map<String, Long>,
    val reimbursements: List<Reimbursement>,
)

/**
 * The totals tab's answer, reduced to what it draws.
 *
 * [yourShareMinorUnits] rounds here, not on the wire: `totalParticipantShare` is not an integer
 * on older instances, so it stays a `Double` until this boundary and never by dividing.
 *
 * [summary] and [categories] are absent on an instance answering the removed
 * `groups.stats.get`. The tab draws what it has rather than failing.
 */
data class StatsInfo(
    val totalGroupSpendings: Long,
    val yourSpendings: Long?,
    val yourShareMinorUnits: Long?,
    val summary: SpliitEndpoints.StatsSummary?,
    val categories: List<SpliitEndpoints.CategoryTotal>,
)

/** One page of the activity log, newest first, the same offset-cursor shape as [ExpensesPage]. */
data class ActivitiesPage(
    val activities: List<Activity>,
    val hasMore: Boolean,
    val nextCursor: Int,
)

/**
 * The search field's state, kept beside the expense list rather than narrowing it. The server
 * matches, so a search covers the whole group and not only the pages loaded. [results] is null
 * exactly while nothing has been typed: no results and no question are different answers.
 */
data class SearchUiState(
    val isActive: Boolean = false,
    /** Exactly what is in the field, including whitespace, the field's own value, not the
     *  trimmed query that was sent. */
    val query: String = "",
    val results: LoadState<List<ExpenseListItem>>? = null,
)

data class GroupDetailUiState(
    val group: LoadState<GroupInfo> = LoadState.Loading,
    val expenses: LoadState<ExpensesPage> = LoadState.Loading,
    val isLoadingMoreExpenses: Boolean = false,
    val balances: LoadState<BalancesInfo> = LoadState.Loading,
    /** Who this phone is in this group, resolved via [app.spliit.core.RecentGroupsSnapshot.actorId]
     * , null both before anyone has answered and once a remembered answer has left the group. */
    val activeParticipantId: String? = null,
    /** [LoadState.Loading] both before the totals tab has been opened and while its request is
     *  out; [statsRequested] is what tells those apart, and only the tab itself needs to. */
    val stats: LoadState<StatsInfo> = LoadState.Loading,
    /**
     * This instance answers neither name the totals go by. Not a failure: there is nothing to
     * retry and nothing the user did wrong, so the tab says so once and offers no button.
     */
    val statsUnavailable: Boolean = false,
    val activities: LoadState<ActivitiesPage> = LoadState.Loading,
    val isLoadingMoreActivities: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    /** A pull-to-refresh is in flight. Distinct from [LoadState.Loading], which replaces what is
     *  on screen with a skeleton, a refresh leaves the numbers up until new ones arrive. */
    val isRefreshing: Boolean = false,
)

/** One bucket's worth of expenses, newest bucket first, see [bucketExpenses]. */
data class ExpenseSection(val bucket: DateBucket, val expenses: List<ExpenseListItem>)

/**
 * Groups [expenses] under the app's [DateBucket]s, newest first. A plain function so tests drive
 * it without a server. [DateBucket]'s ordinal order is already newest-first, and expenses within
 * a bucket keep the server's order.
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
 * [item] placed in [expenses] by date, newest first.
 *
 * A row whose date did not change does not move at all. Dates are whole days, so rows share
 * them, and their order within a day is the server's by creation: inserting by date alone
 * shuffled an edited row to the end of its own day, which looks like the list reloading.
 *
 * Otherwise the old copy goes and the new one is inserted before the first older row. An undone
 * delete comes back under a new ID and lands the same way.
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
 * The active participant's balance, which the "You" summary draws. Null when nobody has said who
 * they are, or the balances have not loaded. An extension rather than a stored field, so it
 * cannot disagree with [GroupDetailUiState.balances].
 */
fun GroupDetailUiState.yourBalanceMinorUnits(): Long? {
    val id = activeParticipantId ?: return null
    val loaded = balances as? LoadState.Loaded ?: return null
    // Absent means no activity, which is a real zero here, see BalancesInfo's own note.
    return loaded.value.balances[id] ?: 0L
}
