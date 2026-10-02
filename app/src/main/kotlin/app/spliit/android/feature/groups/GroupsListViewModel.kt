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

data class GroupListItem(
    val groupId: String,
    val name: String,
    val instanceBaseUrl: String,
    val participantCount: Int?,
    val createdAt: Instant?,
    val isStarred: Boolean,
    val isArchived: Boolean,
)

data class GroupsDashboard(
    val starred: List<GroupListItem> = emptyList(),
    val recent: List<GroupListItem> = emptyList(),
    val archived: List<GroupListItem> = emptyList(),
    val unreachableInstances: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = starred.isEmpty() && recent.isEmpty() && archived.isEmpty()
}

data class PendingRemoval(val groupId: String, val groupName: String)

class GroupsListViewModel(
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val refreshLimiter: RefreshLimiter = RefreshLimiter(),
) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<GroupsDashboard>>(LoadState.Loading)
    val state: StateFlow<LoadState<GroupsDashboard>> = _state.asStateFlow()

    private val _pendingRemoval = MutableStateFlow<PendingRemoval?>(null)

    val pendingRemoval: StateFlow<PendingRemoval?> = _pendingRemoval.asStateFlow()

    private var snapshot = RecentGroupsSnapshot()
    private val summaries = HashMap<String, GroupSummary>()

    private var answeredInstances: Set<String> = emptySet()
    private var unreachableInstances: List<String> = emptyList()
    private var undoJob: Job? = null
    private var refreshJob: Job? = null

    fun load() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refresh() }
    }

    fun retry() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { retryNow() }
    }

    suspend fun retryNow() {
        if (!refreshLimiter.allow()) return
        refresh()
    }

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

    fun removeGroup(groupId: String) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            commitPendingRemoval()
            beginRemoval(groupId)
            delay(UNDO_WINDOW_MILLIS)
            commitPendingRemoval()
        }
    }

    internal fun beginRemoval(groupId: String) {
        val group = snapshot.groups.firstOrNull { it.groupId == groupId } ?: return
        _pendingRemoval.value = PendingRemoval(groupId, group.groupName)
        publish()
    }

    fun undoRemoval() {
        undoJob?.cancel()
        undoJob = null
        _pendingRemoval.value = null
        publish()
    }

    internal suspend fun commitPendingRemoval() {
        val pending = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        snapshot = recentGroupsStore.load().forget(pending.groupId)
        recentGroupsStore.save(snapshot)
        summaries.remove(pending.groupId)
        publish()
    }

    private suspend fun edit(change: (RecentGroupsSnapshot) -> RecentGroupsSnapshot) {
        snapshot = change(recentGroupsStore.load())
        recentGroupsStore.save(snapshot)
        publish()
    }

    suspend fun refresh() {
        if (_state.value !is LoadState.Loaded) _state.value = LoadState.Loading

        snapshot = try {
            recentGroupsStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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

    private suspend fun fetch(instanceBaseUrl: String, groupIds: List<String>): Pair<String, List<GroupSummary>?> =
        try {
            val response = clientFactory(instanceBaseUrl).call(SpliitEndpoints.groupsList(groupIds))
            instanceBaseUrl to response.groups
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            instanceBaseUrl to null
        }

    private fun publish() {
        val removing = _pendingRemoval.value?.groupId
        fun section(groups: List<RecentGroup>): List<GroupListItem> = groups
            .filter { it.groupId != removing }
            .mapNotNull { group ->
                val summary = summaries[group.groupId]
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
        const val UNDO_WINDOW_MILLIS: Long = 8_000
    }
}
