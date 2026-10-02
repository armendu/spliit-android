package app.spliit.android.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.GroupFormValues
import app.spliit.api.Participant
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import app.spliit.core.Currencies
import app.spliit.core.GroupFormDraft
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.spliit.core.Participant as CoreParticipant

enum class GroupFormMode { CREATE, EDIT }

data class GroupFormUiState(
    val mode: GroupFormMode,
    val draft: GroupFormDraft = GroupFormDraft.creating(),
    val instanceAddressText: String = DEFAULT_INSTANCE_BASE_URL,
    val resolvedInstanceBaseUrl: String? = DEFAULT_INSTANCE_BASE_URL,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val hasAttemptedSave: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val blockedParticipantMessage: String? = null,
    val savedGroupId: String? = null,
) {
    val instanceIsValid: Boolean get() = mode == GroupFormMode.EDIT || resolvedInstanceBaseUrl != null
}

class GroupFormViewModel(
    mode: GroupFormMode,
    private val groupId: String?,
    instanceBaseUrl: String,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
) : ViewModel() {
    init {
        require((mode == GroupFormMode.EDIT) == (groupId != null)) {
            "groupId is required for EDIT and meaningless for CREATE"
        }
    }

    private val _state = MutableStateFlow(
        GroupFormUiState(
            mode = mode,
            instanceAddressText = instanceBaseUrl,
            resolvedInstanceBaseUrl = if (mode == GroupFormMode.EDIT) {
                instanceBaseUrl
            } else {
                InstanceAddress.normalize(instanceBaseUrl)
            },
            isLoading = mode == GroupFormMode.EDIT,
        ),
    )
    val state: StateFlow<GroupFormUiState> = _state.asStateFlow()

    private var knownParticipants: List<Participant> = emptyList()

    private var loadStarted = false

    fun resetForCreate(instanceBaseUrl: String) {
        _state.value = GroupFormUiState(
            mode = GroupFormMode.CREATE,
            instanceAddressText = instanceBaseUrl,
            resolvedInstanceBaseUrl = InstanceAddress.normalize(instanceBaseUrl),
        )
        knownParticipants = emptyList()
    }

    // Called by the screen, not from init: an init-time launch races tests that call refreshForEdit.
    fun load() {
        if (_state.value.mode != GroupFormMode.EDIT || loadStarted) return
        loadStarted = true
        viewModelScope.launch { refreshForEdit() }
    }

    suspend fun refreshForEdit() {
        val id = checkNotNull(groupId) { "refreshForEdit requires EDIT mode" }
        _state.update { it.copy(isLoading = true, loadError = null) }
        val instanceBaseUrl = try {
            recentGroupsStore.load().groups.firstOrNull { it.groupId == id }?.instanceBaseUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: run {
            _state.update { it.copy(isLoading = false, loadError = "This group isn't in your list anymore.") }
            return
        }
        _state.update { it.copy(resolvedInstanceBaseUrl = instanceBaseUrl) }
        try {
            val client = clientFactory(instanceBaseUrl)
            val response = client.call(SpliitEndpoints.groupsGetDetails(id))
            knownParticipants = response.group.participants
            val draft = GroupFormDraft.editing(
                name = response.group.name,
                information = response.group.information.orEmpty(),
                currency = response.group.currency,
                currencyCode = response.group.currencyCode,
                participants = response.group.participants.map { CoreParticipant(it.id, it.name) },
                participantsWithExpenses = response.participantsWithExpenses.toSet(),
            )
            _state.update { it.copy(isLoading = false, draft = draft) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(isLoading = false, loadError = e.message) }
        }
    }

    fun setName(name: String) = updateDraft { it.copy(name = name) }

    fun setInformation(information: String) = updateDraft { it.copy(information = information) }

    fun setCustomSymbol(symbol: String) = updateDraft { it.copy(currency = symbol) }

    fun setCurrency(code: String) = updateDraft { draft ->
        Currencies.named(code, draft.locale)?.let(draft::withCurrency) ?: draft
    }

    fun useCustomSymbol() = updateDraft { it.withCustomSymbol() }

    fun addParticipant(): String {
        updateDraft { it.withParticipantAdded() }
        return _state.value.draft.participants.last().id
    }

    fun renameParticipant(id: String, name: String) = updateDraft { it.withParticipantRenamed(id, name) }

    fun removeParticipant(id: String) {
        val draft = _state.value.draft
        val updated = draft.withParticipantRemoved(id)
        if (updated == null) {
            val name = draft.participants.firstOrNull { it.id == id }?.name.orEmpty()
            _state.update {
                it.copy(
                    blockedParticipantMessage = "$name appears on at least one expense, so they " +
                        "can't be removed. Delete or reassign those expenses first.",
                )
            }
        } else {
            _state.update { it.copy(draft = updated) }
        }
    }

    fun dismissBlockedParticipantMessage() = _state.update { it.copy(blockedParticipantMessage = null) }

    fun setInstanceAddressText(text: String) {
        _state.update {
            it.copy(instanceAddressText = text, resolvedInstanceBaseUrl = InstanceAddress.normalize(text))
        }
    }

    private inline fun updateDraft(transform: (GroupFormDraft) -> GroupFormDraft) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    fun save() {
        viewModelScope.launch { submit() }
    }

    suspend fun submit(): Boolean {
        _state.update { it.copy(hasAttemptedSave = true, saveError = null) }
        val current = _state.value
        val submission = current.draft.submission()
        val instanceBaseUrl = current.resolvedInstanceBaseUrl
        if (submission == null || !current.instanceIsValid || instanceBaseUrl == null) return false

        _state.update { it.copy(isSaving = true) }
        val values = GroupFormValues(
            name = submission.name,
            information = submission.information,
            currency = submission.currency,
            currencyCode = submission.currencyCode,
            participants = submission.participants.map { GroupFormValues.Participant(id = it.id, name = it.name) },
        )
        try {
            val client = clientFactory(instanceBaseUrl)
            when (current.mode) {
                GroupFormMode.CREATE -> {
                    val response = client.call(SpliitEndpoints.groupsCreate(values))
                    val snapshot = recentGroupsStore.load()
                    val stored = recentGroupsStore.save(
                        snapshot.opening(
                            RecentGroup(
                                groupId = response.groupId,
                                instanceBaseUrl = instanceBaseUrl,
                                groupName = submission.name,
                            ),
                        ),
                    )
                    if (!stored) {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveError = "The group was created but could not be saved to " +
                                    "this phone's list. Keep this link: " +
                                    "${instanceBaseUrl.trimEnd('/')}/groups/${response.groupId}",
                            )
                        }
                        return false
                    }
                    _state.update { it.copy(isSaving = false, savedGroupId = response.groupId) }
                }

                GroupFormMode.EDIT -> {
                    val id = checkNotNull(groupId)
                    val recentSnapshot = recentGroupsStore.load()
                    val actorId = recentSnapshot.actorId(id, knownParticipants.map { CoreParticipant(it.id, it.name) })
                    client.call(SpliitEndpoints.groupsUpdate(id, values, actorId))
                    _state.update { it.copy(isSaving = false, savedGroupId = id) }
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }
}
