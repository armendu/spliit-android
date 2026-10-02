package app.spliit.android.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.android.AppSettingsHolder
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddGroupByUrlState(
    val urlText: String = "",
    val isChecking: Boolean = false,
    val problem: String? = null,
    val addedGroupId: String? = null,
    val defaultInstanceBaseUrl: String = DEFAULT_INSTANCE_BASE_URL,
)

class AddGroupByUrlViewModel(
    private val recentGroupsStore: RecentGroupsStore,
    private val defaultInstanceBaseUrl: () -> String = { AppSettingsHolder.defaultInstanceBaseUrl },
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(
        AddGroupByUrlState(defaultInstanceBaseUrl = defaultInstanceBaseUrl()),
    )
    val state: StateFlow<AddGroupByUrlState> = _state.asStateFlow()

    fun setUrlText(text: String) {
        _state.update { it.copy(urlText = text, problem = null) }
    }

    fun reset() {
        _state.value = AddGroupByUrlState(defaultInstanceBaseUrl = defaultInstanceBaseUrl())
    }

    fun add() {
        viewModelScope.launch { submit() }
    }

    suspend fun submit(): Boolean {
        if (_state.value.isChecking) return false

        val link = GroupLink.parse(_state.value.urlText)
        if (link == null) {
            _state.update { it.copy(problem = "That doesn't look like a Spliit group link.") }
            return false
        }
        val instanceBaseUrl = link.instanceBaseUrl ?: defaultInstanceBaseUrl()

        _state.update { it.copy(isChecking = true, problem = null) }
        try {
            val client = clientFactory(instanceBaseUrl)
            val response = client.call(SpliitEndpoints.groupsGet(link.groupId))
            val group = response.group
            if (group == null) {
                _state.update {
                    it.copy(
                        isChecking = false,
                        problem = "No group with that link exists on ${InstanceAddress.displayName(instanceBaseUrl)}.",
                    )
                }
                return false
            }

            val snapshot = recentGroupsStore.load()
            val stored = recentGroupsStore.save(
                snapshot.opening(
                    RecentGroup(groupId = group.id, instanceBaseUrl = instanceBaseUrl, groupName = group.name),
                ),
            )
            if (!stored) {
                _state.update {
                    it.copy(isChecking = false, problem = "Couldn't save this group to your list. Try again.")
                }
                return false
            }
            _state.update { it.copy(isChecking = false, addedGroupId = group.id) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(isChecking = false, problem = e.message) }
            return false
        }
    }
}
