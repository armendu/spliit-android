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

/** Whether the form is creating a group or editing one already on a server. */
enum class GroupFormMode { CREATE, EDIT }

/**
 * The group form's whole state, the draft itself, plus what only this screen cares about
 * (whether it is still loading, whether a save is in flight, and what went wrong).
 *
 * @param instanceAddressText What the (create-only) server field shows, exactly as typed.
 * @param resolvedInstanceBaseUrl [instanceAddressText] normalised into a base URL, or null while
 *   it doesn't name a usable one, the save button's guard, not merely its footnote.
 */
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
    /** Set when [GroupFormDraft.withParticipantRemoved] refused a removal, who, and why. */
    val blockedParticipantMessage: String? = null,
    /** Set once the save has actually gone through, the screen's cue to leave. */
    val savedGroupId: String? = null,
) {
    /** Whether the server address is usable, always true once editing, since a group cannot
     *  move servers and there is nothing here left to type. */
    val instanceIsValid: Boolean get() = mode == GroupFormMode.EDIT || resolvedInstanceBaseUrl != null
}

/**
 * The group editor, shared by "Create group" and "Group settings". Every rule about what makes a
 * group valid lives in [GroupFormDraft]; this carries the draft, drives the calls, and resolves
 * the one thing `:core` cannot know: which participant is making the change, for the log.
 */
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
        ),
    )
    val state: StateFlow<GroupFormUiState> = _state.asStateFlow()

    /** Who was in the group when it loaded, kept only to resolve [submit]'s actor, since the
     *  draft's own participant list is what is being edited. */
    private var knownParticipants: List<Participant> = emptyList()

    init {
        if (mode == GroupFormMode.EDIT) load()
    }

    /**
     * Back to a blank group, on [instanceBaseUrl]. One ViewModel serves every create, so without
     * this a second one opens holding the first's participants, name, and `savedGroupId`, which
     * closes the sheet the instant it appears. The instance is re-read, since Settings can change.
     */
    fun resetForCreate(instanceBaseUrl: String) {
        _state.value = GroupFormUiState(
            mode = GroupFormMode.CREATE,
            instanceAddressText = instanceBaseUrl,
            resolvedInstanceBaseUrl = InstanceAddress.normalize(instanceBaseUrl),
        )
        knownParticipants = emptyList()
    }

    fun load() {
        viewModelScope.launch { refreshForEdit() }
    }

    suspend fun refreshForEdit() {
        val id = checkNotNull(groupId) { "refreshForEdit requires EDIT mode" }
        _state.update { it.copy(isLoading = true, loadError = null) }
        // Which server the group is on is a fact about *the group*, resolved from its stored row
        //, never the app's current default, which a self-hosted group is not on and which
        // Settings can have changed since the group was added. The constructor's value is only a
        // fallback for a group with no row, which this app's routes cannot currently produce.
        val instanceBaseUrl = try {
            recentGroupsStore.load().groups.firstOrNull { it.groupId == id }?.instanceBaseUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: _state.value.resolvedInstanceBaseUrl ?: run {
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

    // ---- editing --------------------------------------------------------------------------

    fun setName(name: String) = updateDraft { it.copy(name = name) }

    fun setInformation(information: String) = updateDraft { it.copy(information = information) }

    fun setCustomSymbol(symbol: String) = updateDraft { it.copy(currency = symbol) }

    /** Picking a real currency, which sets the symbol with it, see
     *  [GroupFormDraft.withCurrency]. A code the platform does not know leaves the draft alone
     *  rather than clearing the currency it already had. */
    fun setCurrency(code: String) = updateDraft { draft ->
        Currencies.named(code, draft.locale)?.let(draft::withCurrency) ?: draft
    }

    /** Dropping the ISO code and keeping the symbol as free text. A Spliit group is allowed to
     *  be counted in a bare "$" with nothing behind it, the web app's own default, so this is
     *  a state to reach deliberately, not a failure to pick properly. */
    fun useCustomSymbol() = updateDraft { it.withCustomSymbol() }

    fun addParticipant() = updateDraft { it.withParticipantAdded() }

    fun renameParticipant(id: String, name: String) = updateDraft { it.withParticipantRenamed(id, name) }

    /** Removes the participant, or, per [GroupFormDraft.withParticipantRemoved]'s nullable
     *  return, explains why it would not: never a silent no-op. */
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

    /** CREATE only, the server this group will be made on. */
    fun setInstanceAddressText(text: String) {
        _state.update {
            it.copy(instanceAddressText = text, resolvedInstanceBaseUrl = InstanceAddress.normalize(text))
        }
    }

    private inline fun updateDraft(transform: (GroupFormDraft) -> GroupFormDraft) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    // ---- saving ---------------------------------------------------------------------------

    fun save() {
        viewModelScope.launch { submit() }
    }

    /** The actual work, as a plain suspend function, see [GroupsListViewModel.refresh] for why
     *  tests call this directly rather than [save]. */
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
                    // The only way :core exposes to add a row, see RecentGroupsSnapshot.opening.
                    // Freshly created is freshly relevant, so it belongs at the top of the list.
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
                    // The group now exists on the server. If the row did not store, nothing on
                    // this phone can reach it again: there is no account and no server-side list
                    // of what this device has seen. So the link goes on screen rather than the
                    // form quietly reporting success.
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
                    // groups.update's participantId is the only thing the activity log can name
                    // anybody with, resolved here, not omitted, per CLAUDE.md.
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
