package app.spliit.android.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.GroupSummary
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import app.spliit.core.LoadState
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RecentGroupsStore
import app.spliit.core.RefreshLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** One row on the groups screen, a remembered group, enriched with what the server said about it. */
data class GroupListItem(
    val groupId: String,
    val name: String,
    val instanceBaseUrl: String,
    /**
     * Null when the instance this group lives on has not answered, it did not respond, or has
     * not been asked yet. A row is drawn either way: the name is local, and a server that is down
     * costs its own groups their detail rather than costing somebody the only route back to them.
     */
    val participantCount: Int?,
    /** Null for the same reason [participantCount] is: it comes from the instance's own answer,
     *  and a row whose instance hasn't answered still draws with what this phone already knew. */
    val createdAt: Instant?,
    val isStarred: Boolean,
    val isArchived: Boolean,
)

/**
 * The three sections the dashboard draws, and the note about what could not be reached. Sections
 * rather than badges: the header a row sits under already says it is starred.
 */
data class GroupsDashboard(
    val starred: List<GroupListItem> = emptyList(),
    val recent: List<GroupListItem> = emptyList(),
    val archived: List<GroupListItem> = emptyList(),
    /**
     * Display names of the instances that did not answer this load, if any. Named rather than
     * counted: with a list spanning more than one server, "the server" is no longer something
     * anybody can act on.
     */
    val unreachableInstances: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = starred.isEmpty() && recent.isEmpty() && archived.isEmpty()
}

/** A removal waiting out its undo window, see [GroupsListViewModel.removeGroup]. */
data class PendingRemoval(val groupId: String, val groupName: String)

/**
 * The dashboard's state: the groups this phone remembers, in three sections, enriched with
 * server-side detail. Three rules shape it:
 *
 *  - One request per instance. `groups.list` only answers for the server it was sent to, so two
 *    instances are two requests, issued together.
 *  - An unreachable instance costs its groups their detail, never the screen. Rows still appear
 *    under stored names; [LoadState.Failed] is only for the stored list itself being unreadable.
 *  - A group the server no longer has drops out silently. `groups.list` omits an ID it does not
 *    recognise, which is how a server-side deletion arrives.
 */
class GroupsListViewModel(
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    /** Injected so the tests can drive its clock, see RefreshLimiter. */
    private val refreshLimiter: RefreshLimiter = RefreshLimiter(),
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<GroupsDashboard>>(LoadState.Loading)
    val state: StateFlow<LoadState<GroupsDashboard>> = _state.asStateFlow()

    private val _pendingRemoval = MutableStateFlow<PendingRemoval?>(null)

    /** The removal the undo snackbar is currently offering to take back, or null. */
    val pendingRemoval: StateFlow<PendingRemoval?> = _pendingRemoval.asStateFlow()

    /**
     * What the screen is drawn from, kept here rather than re-read on every edit: starring a
     * group changes which section it is in and nothing a server knows, so it must redraw
     * instantly and without a round trip.
     */
    private var snapshot = RecentGroupsSnapshot()
    private val summaries = HashMap<String, GroupSummary>()

    /** Base URLs that answered the last load. What separates "this server has no such group any
     *  more" from "nobody asked this server, or it did not reply", two situations with the same
     *  missing summary and opposite right answers. */
    private var answeredInstances: Set<String> = emptySet()
    private var unreachableInstances: List<String> = emptyList()
    private var undoJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * Kicks [refresh] off on this ViewModel's scope. Not from `init`: this ViewModel outlives the
     * composition, and a group just created needs the list to reload when the screen returns.
     */
    fun load() {
        // One load at a time: two share the same fields, so an earlier one finishing late
        // publishes over a newer one's result. That is how a group added mid-load came back to
        // an empty list, watched happen on a device.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refresh() }
    }

    /**
     * Rate-limited, unlike [load]: this screen has no pull gesture, so the button beside a
     * failure is the only repeatable thing on it, and costs one request per stored group.
     */
    fun retry() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { retryNow() }
    }

    /**
     * The work behind [retry], where the rate limit lives, rather than on [retry] itself:
     * `viewModelScope` dispatches on Main, which the JVM suites do not install, so a check inside
     * a launcher is one nothing can assert on.
     */
    suspend fun retryNow() {
        if (!refreshLimiter.allow()) return
        refresh()
    }

    /** Stamps [groupId] as just-opened, so it sorts to the top next time the list loads. */
    fun onGroupOpened(groupId: String) {
        viewModelScope.launch { markOpened(groupId) }
    }

    internal suspend fun markOpened(groupId: String) {
        val stored = recentGroupsStore.load()
        val group = stored.groups.firstOrNull { it.groupId == groupId } ?: return
        snapshot = stored.opening(group)
        recentGroupsStore.save(snapshot)
        publish()
    }

    fun setStarred(groupId: String, isStarred: Boolean) {
        viewModelScope.launch { markStarred(groupId, isStarred) }
    }

    fun setArchived(groupId: String, isArchived: Boolean) {
        viewModelScope.launch { markArchived(groupId, isArchived) }
    }

    internal suspend fun markStarred(groupId: String, isStarred: Boolean) {
        edit { it.settingStarred(groupId, isStarred) }
    }

    internal suspend fun markArchived(groupId: String, isArchived: Boolean) {
        edit { it.settingArchived(groupId, isArchived) }
    }

    /**
     * Takes [groupId] off the list after an undo window, not now. A group is reachable only by
     * its link, so an unintended removal loses it for good. The row goes immediately; the
     * irreversible `forget` waits out [UNDO_WINDOW_MILLIS]. A second removal commits the first,
     * since one snackbar can only undo one thing.
     */
    fun removeGroup(groupId: String) {
        // Cancelling here does *not* skip the earlier removal's write: the job's first act below
        // is to commit whatever was still pending, and the pending row is held in a flow that
        // cancellation does not touch.
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            commitPendingRemoval()
            beginRemoval(groupId)
            delay(UNDO_WINDOW_MILLIS)
            commitPendingRemoval()
        }
    }

    /** Hides [groupId] and starts offering the undo, the half of [removeGroup] that is only a
     *  change of mind away, split out so tests can drive the two halves without a clock. */
    internal fun beginRemoval(groupId: String) {
        val group = snapshot.groups.firstOrNull { it.groupId == groupId } ?: return
        _pendingRemoval.value = PendingRemoval(groupId, group.groupName)
        publish()
    }

    /** Puts back the group [removeGroup] was about to forget. */
    fun undoRemoval() {
        undoJob?.cancel()
        undoJob = null
        _pendingRemoval.value = null
        publish()
    }

    /** Writes the `forget` the undo window held back. Re-reads the store, so an edit made while
     *  the window was open is not overwritten by a stale snapshot. */
    internal suspend fun commitPendingRemoval() {
        val pending = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        snapshot = recentGroupsStore.load().forget(pending.groupId)
        recentGroupsStore.save(snapshot)
        summaries.remove(pending.groupId)
        publish()
    }

    /** Applies one snapshot edit, saves it, and redraws, see [snapshot] for why this is local. */
    private suspend fun edit(change: (RecentGroupsSnapshot) -> RecentGroupsSnapshot) {
        snapshot = change(recentGroupsStore.load())
        recentGroupsStore.save(snapshot)
        publish()
    }

    /** The actual load. Called through [load] in production and directly in tests, which
     *  sidesteps needing a Main-dispatcher rule. */
    suspend fun refresh() {
        // The skeleton is for a screen with nothing on it yet. Later loads keep the drawn rows,
        // which come from local storage anyway; blanking them would look like a lost list.
        if (_state.value !is LoadState.Loaded) _state.value = LoadState.Loading

        snapshot = try {
            recentGroupsStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The one failure with nothing left to draw: without the stored list there are no
            // names, no IDs, and no screen to degrade to.
            _state.value = LoadState.Failed("Couldn't read the stored group list.")
            return
        }

        val byInstance = snapshot.groups.groupBy { it.instanceBaseUrl }
        val answers = coroutineScope {
            byInstance.map { (instanceBaseUrl, groupsOnInstance) ->
                async { fetch(instanceBaseUrl, groupsOnInstance.map { it.groupId }) }
            }.awaitAll()
        }

        summaries.clear()
        val unreachable = mutableListOf<String>()
        val answered = mutableSetOf<String>()
        for ((instanceBaseUrl, groups) in answers) {
            if (groups == null) {
                unreachable += InstanceAddress.displayName(instanceBaseUrl)
                continue
            }
            answered += instanceBaseUrl
            for (summary in groups) summaries[summary.id] = summary
        }
        answeredInstances = answered
        unreachableInstances = unreachable.sorted()
        publish()
    }

    /** One instance's answer, or null when it could not be reached. */
    private suspend fun fetch(instanceBaseUrl: String, groupIds: List<String>): Pair<String, List<GroupSummary>?> =
        try {
            val response = clientFactory(instanceBaseUrl).call(SpliitEndpoints.groupsList(groupIds))
            instanceBaseUrl to response.groups
        } catch (e: CancellationException) {
            // Not a server that is down, the screen went away, or another load started. Catching
            // this alongside the two below would report "unreachable" for a load nobody is
            // waiting on any more.
            throw e
        } catch (e: TrpcException) {
            instanceBaseUrl to null
        }

    /** Rebuilds the three sections from what is currently known. Synchronous on purpose: every
     *  edit above redraws through it, and a redraw that suspended would let the screen show the
     *  old sections for a frame after the user tapped. */
    private fun publish() {
        val removing = _pendingRemoval.value?.groupId
        fun section(groups: List<RecentGroup>): List<GroupListItem> = groups
            .filter { it.groupId != removing }
            .mapNotNull { group ->
                val summary = summaries[group.groupId]
                // A row the instance answered about but did not name is one the server has
                // deleted; a row whose instance never answered is one we simply have no detail
                // for. Only the first kind drops out.
                if (summary == null && group.instanceBaseUrl in answeredInstances) return@mapNotNull null
                GroupListItem(
                    groupId = group.groupId,
                    name = summary?.name ?: group.groupName,
                    instanceBaseUrl = group.instanceBaseUrl,
                    participantCount = summary?.participantCount,
                    createdAt = summary?.createdAt,
                    isStarred = group.isStarred,
                    isArchived = group.isArchived,
                )
            }

        _state.value = LoadState.Loaded(
            GroupsDashboard(
                starred = section(snapshot.starred),
                recent = section(snapshot.recent),
                archived = section(snapshot.archived),
                unreachableInstances = unreachableInstances,
            ),
        )
    }

    companion object {
        /**
         * How long a removed group can still be brought back. Longer than a snackbar's usual few
         * seconds, because the thing being undone is not reversible afterwards by any other
         * route, the group is reachable only by a link the person may no longer have.
         */
        const val UNDO_WINDOW_MILLIS: Long = 8_000
    }
}
