package app.spliit.android.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.GroupSummary
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcClientError
import app.spliit.api.TrpcServerError
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

/** One row on the groups screen — a remembered group, enriched with what the server said about it. */
data class GroupListItem(
    val groupId: String,
    val name: String,
    val instanceBaseUrl: String,
    /**
     * Null when the instance this group lives on has not answered — it did not respond, or has
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
 * The three sections the dashboard draws, and the note about what could not be reached.
 *
 * Sections rather than one list, because starring and archiving are about *where* a group sits
 * rather than about a badge on it — which is also why no row carries a star icon: the header it
 * sits under already says so, and saying it twice is how a list starts looking like a form.
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

/** A removal waiting out its undo window — see [GroupsListViewModel.removeGroup]. */
data class PendingRemoval(val groupId: String, val groupName: String)

/**
 * The dashboard's state: which groups this phone remembers, in their three sections, enriched
 * with server-side detail.
 *
 * Three rules from the spec shape this class, and each one fails the opposite way a careless
 * implementation would:
 *
 *  - **One request per instance.** `groups.list` takes a list of IDs but only ever answers for
 *    the server it was sent to, so a list spanning two instances is two requests — issued
 *    together, so the slow one does not hold up the other.
 *  - **An unreachable instance costs its own groups their detail, never the screen.** Its rows
 *    still appear, under the names this phone stored, with no participant count; the sections
 *    say what could not be reached. [LoadState.Failed] is reserved for the one failure that
 *    leaves nothing to draw at all — the stored list itself being unreadable.
 *  - **A group the server no longer has drops out silently.** `groups.list` simply omits an ID it
 *    does not recognise — that is how a group deleted server-side shows up here — so a row
 *    missing from an answer that *did* arrive is left out rather than treated as a failure.
 */
class GroupsListViewModel(
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    /** Injected so the tests can drive its clock — see RefreshLimiter. */
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
     *  more" from "nobody asked this server, or it did not reply" — two situations with the same
     *  missing summary and opposite right answers. */
    private var answeredInstances: Set<String> = emptySet()
    private var unreachableInstances: List<String> = emptyList()
    private var undoJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * Kicks [refresh] off on this ViewModel's own scope. Deliberately not called from `init` —
     * this ViewModel outlives the screen's composition (it is scoped to the "groups" nav-graph
     * entry), and a group just added or created needs the list to reload when the screen becomes
     * visible again, not only the first time it was ever constructed. The screen calls this from
     * a `LaunchedEffect`, which reruns exactly when that recomposition happens; a retry action
     * calls it again for the same reason. Tests call [refresh] directly: see its own doc.
     */
    fun load() {
        // One load at a time. Two of them share [snapshot], [summaries] and [answeredInstances],
        // so an earlier one finishing late publishes over a newer one's result — which is how a
        // group added while the first load was still on the wire came back to an empty list,
        // watched happen on a device. The newer load is always the one that is still wanted, so
        // the older is cancelled rather than waited for.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refresh() }
    }

    /**
     * Rate-limited, unlike [load].
     *
     * This screen has no pull gesture, so the button beside a failure is the only thing a user
     * can repeat — and it is the most expensive thing in the app when they do, because it fans
     * out one request per stored group. [load] itself is left alone: it runs once per composition
     * and is not something anybody can hammer.
     */
    fun retry() {
        if (!refreshLimiter.allow()) return
        load()
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
     * Takes [groupId] off the list — after an undo window, not now.
     *
     * A group is reachable only by its link, so a removal somebody did not mean is a group gone
     * for good unless they still have the link. The row disappears immediately, which is what
     * makes the screen feel like it did the thing; the `forget` that is actually irreversible
     * waits out [UNDO_WINDOW_MILLIS], and [undoRemoval] cancels it. A second removal commits the
     * first straight away rather than queueing two — one snackbar can only offer to undo one
     * thing, and the thing anybody means by "undo" is the last one.
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

    /** Hides [groupId] and starts offering the undo — the half of [removeGroup] that is only a
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

    /**
     * Writes the `forget` the undo window was holding back, if there is one.
     *
     * Reads the store again rather than writing [snapshot] straight out: an edit made while the
     * window was open — from this screen or from a group screen — would otherwise be overwritten
     * by a snapshot taken before it.
     */
    internal suspend fun commitPendingRemoval() {
        val pending = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        snapshot = recentGroupsStore.load().forget(pending.groupId)
        recentGroupsStore.save(snapshot)
        summaries.remove(pending.groupId)
        publish()
    }

    /** Applies one snapshot edit, saves it, and redraws — see [snapshot] for why this is local. */
    private suspend fun edit(change: (RecentGroupsSnapshot) -> RecentGroupsSnapshot) {
        snapshot = change(recentGroupsStore.load())
        recentGroupsStore.save(snapshot)
        publish()
    }

    /**
     * The actual load, as a plain suspend function — called through [load] in production (which
     * runs it on [viewModelScope], the Main dispatcher), and called directly in tests, which
     * sidesteps needing a Main-dispatcher test rule for what is otherwise ordinary, deterministic
     * suspending code.
     */
    suspend fun refresh() {
        // The skeleton is for a screen with nothing on it yet. Every later load — coming back
        // from a group, adding one, pulling the list again — keeps the rows that are already
        // drawn: they are read from local storage and are not what the request is for, and
        // replacing them with a skeleton for a second would make returning to this screen look
        // like it lost the list.
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
            // Not a server that is down — the screen went away, or another load started. Catching
            // this alongside the two below would report "unreachable" for a load nobody is
            // waiting on any more.
            throw e
        } catch (e: TrpcServerError) {
            instanceBaseUrl to null
        } catch (e: TrpcClientError) {
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
         * route — the group is reachable only by a link the person may no longer have.
         */
        const val UNDO_WINDOW_MILLIS: Long = 8_000
    }
}
