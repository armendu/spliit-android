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
data class ExpensesPage(
    val expenses: List<ExpenseListItem>,
    val hasMore: Boolean,
    val nextCursor: Int,
)

/**
 * The balances tab's whole answer, with [Balance]'s `paid`/`paidFor` already discarded.
 *
 * **`groups.balances.list` does not tell you what anyone paid.** Its `paid` and `paidFor` are
 * derived from the suggested payments rather than from the expenses, one is always zero and the
 * other is `abs(total)`. Only `total` means anything, so [balances] carries nothing else: there
 * is no field left for a later call site to misread.
 */
data class BalancesInfo(
    /** Participant ID to minor-units total. A participant with no activity is absent, not zero -
     *  see [SpliitEndpoints.BalancesResponse]. */
    val balances: Map<String, Long>,
    val reimbursements: List<Reimbursement>,
)

/**
 * The totals tab's answer, already reduced to what it draws.
 *
 * [yourShareMinorUnits] is rounded here rather than on the wire: `totalParticipantShare` is the
 * one amount in the API that is not an integer, and an instance older than the web app's *Shares*
 * change sends thirds of a cent. It stays a `Double` through [SpliitEndpoints.GroupStatsResponse]
 * and becomes whole minor units at exactly this boundary, never by dividing anything.
 *
 * [summary] and [categories] are absent on an instance still answering the removed
 * `groups.stats.get`, which carries the three figures and nothing else. The tab draws what it
 * has; it does not treat their absence as a failure.
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
 * The search field's state, kept beside the expense list rather than narrowing it.
 *
 * The server does the matching, `groups.expenses.list` takes a case-insensitive `filter` on the
 * title, so a search covers the whole group, not only the pages paged in. [results] is null
 * exactly while nothing has been typed: an empty result and an unasked question are different
 * sentences with different ways out of them.
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
 * [item] in [expenses], at the position its date gives it, the server orders a group's expenses
 * newest first, and an edit can move one.
 *
 * A row whose date did not change does not move at all, even to a place its date would also
 * allow. Expense dates are whole days, so several rows commonly share one, and their order
 * within that day is the server's (by creation) rather than anything this side can recompute -
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
 * The active participant's own balance, in minor units, what the "You" summary at the top of
 * the balances tab draws, and null exactly when that summary has nothing to lead with: either
 * nobody has said who they are yet, or the balances themselves have not loaded.
 *
 * A plain extension over [GroupDetailUiState] rather than a stored field, so there is exactly one
 * place this can disagree with [GroupDetailUiState.balances], nowhere, because it is read from
 * it directly instead of copied alongside it.
 */
fun GroupDetailUiState.yourBalanceMinorUnits(): Long? {
    val id = activeParticipantId ?: return null
    val loaded = balances as? LoadState.Loaded ?: return null
    // Absent means no activity, which is a real zero here, see BalancesInfo's own note.
    return loaded.value.balances[id] ?: 0L
}
