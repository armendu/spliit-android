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
    /** Set once the group has actually been stored, the screen's cue to leave. */
    val addedGroupId: String? = null,
    /**
     * Where a link that names no server is looked up, and what the field's placeholder is built
     * from, so somebody running their own instance is shown their own address rather than
     * spliit.app's. Carried on the state rather than read from a constant by the screen: once
     * Settings (Part 13) can change it, the screen is already reading the right thing.
     */
    val defaultInstanceBaseUrl: String = DEFAULT_INSTANCE_BASE_URL,
)

/**
 * Adds a group someone shared, by pasting its URL. Spliit has no accounts, so a group URL *is*
 * the invitation, and it names the server as well as the group.
 */
class AddGroupByUrlViewModel(
    private val recentGroupsStore: RecentGroupsStore,
    /**
     * A provider, not a plain `String`: this ViewModel lives for the whole session, so a value
     * captured at construction goes stale the instant Settings changes the default. Read fresh
     * on every [reset] and [submit].
     */
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

    /**
     * Empties the field, the error and the "added" flag. One ViewModel serves every open of the
     * sheet, so without this a reopen shows the last paste and the last error, and a still-set
     * `addedGroupId` closes the sheet the moment it appears.
     */
    fun reset() {
        _state.value = AddGroupByUrlState(defaultInstanceBaseUrl = defaultInstanceBaseUrl())
    }

    fun add() {
        viewModelScope.launch { submit() }
    }

    /** The actual work, as a plain suspend function, see [GroupsListViewModel.refresh] for why
     *  tests call this directly rather than [add]. */
    suspend fun submit(): Boolean {
        if (_state.value.isChecking) return false

        val link = GroupLink.parse(_state.value.urlText)
        if (link == null) {
            _state.update { it.copy(problem = "That doesn't look like a Spliit group link.") }
            return false
        }
        // A bare ID names no server, and the one this phone would otherwise create a group on is
        // the only reasonable guess, read fresh, not from the state's own snapshot, so a Settings
        // change made after this sheet was last opened still lands correctly.
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
            // Less costly than the create path, the user still has the link they pasted, but
            // reporting success for a row that did not store would send them back to a list the
            // group is not in.
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
