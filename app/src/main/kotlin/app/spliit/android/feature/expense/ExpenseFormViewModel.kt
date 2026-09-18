package app.spliit.android.feature.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.api.ExpenseDocument
import app.spliit.api.ExpenseFormValues
import app.spliit.api.LenientDecimal
import app.spliit.api.RecurrenceRule
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcClientError
import app.spliit.api.TrpcServerError
import app.spliit.android.feature.group.GroupInfo
import app.spliit.core.DefaultSplit
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.ExpenseSubmission
import app.spliit.core.RecentGroupsStore
import app.spliit.core.SplitMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import app.spliit.api.ExpenseCategory as ApiCategory
import app.spliit.api.SplitMode as ApiSplitMode
import app.spliit.core.Participant as CoreParticipant

/**
 * What this screen was opened to do — iOS's `ExpenseFormView.Mode` plus the settle-up case its
 * group screen expresses by handing the form a prefilled draft.
 *
 * [Settle] is a *create*, not a mode of its own on the wire: Spliit has no "mark as paid", so a
 * debt is settled by writing an ordinary expense with [ExpenseFormDraft.isReimbursement] set.
 * It is a separate case here only because what the form starts from differs.
 */
sealed interface ExpenseFormMode {
    data object Create : ExpenseFormMode

    data class Edit(val expenseId: String) : ExpenseFormMode

    /** @param amountMinorUnits the suggested payment, in the group's minor units. */
    data class Settle(
        val fromParticipantId: String,
        val toParticipantId: String,
        val amountMinorUnits: Long,
    ) : ExpenseFormMode

    val editedExpenseId: String? get() = (this as? Edit)?.expenseId
}

/**
 * An expense that has just been deleted, kept for exactly as long as the undo is on offer.
 *
 * **The server has no undelete.** `groups.expenses.delete` removes the row, and nothing brings
 * that row back — so undo re-creates the expense from the values it was deleted with and it
 * comes back under a new ID. Everything a reader sees is restored; the activity log, honestly,
 * records a delete and a create.
 */
data class DeletedExpense(
    val values: ExpenseFormValues,
    val title: String,
)

data class ExpenseFormUiState(
    val mode: ExpenseFormMode,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val group: GroupInfo? = null,
    val categories: List<ApiCategory> = emptyList(),
    /** Null until the group — and, when editing, the expense — have arrived. */
    val draft: ExpenseFormDraft? = null,
    /**
     * Whether a save has been attempted. Problems are drawn only after one: a form that turns
     * red before anybody has finished typing the title is scolding rather than helping.
     */
    val hasAttemptedSave: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** Whether anything has been changed since the form opened — what makes a back gesture ask. */
    val isDirty: Boolean = false,
    /** Set once the write has actually gone through — the screen's cue to leave. */
    val savedExpenseId: String? = null,
    /** Set between a delete and the moment its undo stops being on offer. */
    val deleted: DeletedExpense? = null,
    /** Set when the screen should close without having saved anything. */
    val isFinished: Boolean = false,
) {
    val isEditing: Boolean get() = mode is ExpenseFormMode.Edit

    /** The problems one field should draw — [ExpenseFormDraft]'s, never a second opinion. */
    fun problems(field: ExpenseFormDraft.Field): List<ExpenseFormDraft.Problem> =
        if (!hasAttemptedSave) emptyList() else draft?.problems(field).orEmpty()

    /** The problems one participant's row should draw. */
    fun problems(participantId: String): List<ExpenseFormDraft.Problem> =
        if (!hasAttemptedSave) emptyList() else draft?.problems(participantId).orEmpty()
}

/**
 * The expense form: create, edit, settle up, delete.
 *
 * **Every rule about what an expense is worth and how it divides lives in [ExpenseFormDraft].**
 * This class holds one, drives the calls around it, and resolves the two things `:core` cannot
 * know on its own — which instance the group is on, and which participant is making the write,
 * for the activity log. There is no arithmetic here, and no second validator: the screen renders
 * `draft.problems(field)`.
 */
class ExpenseFormViewModel(
    private val groupId: String,
    mode: ExpenseFormMode,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val locale: Locale = Locale.getDefault(),
    /** Fixed once, so a form open across midnight does not change the expense's date under it. */
    private val now: Instant = Instant.now(),
) : ViewModel() {

    private val _state = MutableStateFlow(ExpenseFormUiState(mode = mode))
    val state: StateFlow<ExpenseFormUiState> = _state.asStateFlow()

    /** Resolved by [refresh]; reused by every write so none of them re-derives it. */
    private var resolvedInstanceBaseUrl: String? = null

    /** What the form opened with, so [ExpenseFormUiState.isDirty] is a comparison rather than a
     *  flag every setter has to remember to raise. */
    private var initialDraft: ExpenseFormDraft? = null

    /** The documents the expense already had. Nothing on this screen edits them in cycle 1, and
     *  omitting them from an update would drop every receipt attached from the web app. */
    private var documents: List<ExpenseDocument> = emptyList()

    /**
     * Loads the group and, when editing, the expense — **once**.
     *
     * Deliberately not the group screen's "reload on every composition": that screen is a read,
     * and re-reading it is how it catches up after this form has written something. This one
     * holds what the user is typing, and the composition it is called from runs again on every
     * configuration change — a rotation, or the system flipping to dark. Reloading there would
     * rebuild the draft from the server and throw away the half-written expense, which is
     * exactly what happened before this guard existed.
     *
     * [retry] is the way back in after a failure, and is not guarded.
     */
    fun load() {
        if (_state.value.draft != null) return
        viewModelScope.launch { refresh() }
    }

    fun retry() {
        viewModelScope.launch { refresh() }
    }

    /**
     * Re-arms the form for another open of the same expense.
     *
     * The edit sheet's ViewModel is keyed on the expense and scoped to the group screen's nav
     * entry, so it outlives the sheet — which is the whole point: the undo offered after a
     * delete is still this instance's to make once the sheet has gone. The cost is that a second
     * open would otherwise inherit the first one's ending, and there are two ways that goes
     * wrong: a [savedExpenseId] left set closes the sheet the instant it opens, and a draft left
     * holding edits somebody *discarded* comes back as if they had not been.
     *
     * So the state goes back to the shape it had before [load] and the expense is read again.
     * That read is one `groups.expenses.get` — the form needs the notes, the shares and the
     * conversion, none of which a list row carries. What it deliberately does not do is make the
     * group screen behind it reload: see [app.spliit.android.feature.group.GroupDetailViewModel].
     */
    fun reopen() {
        rearm()
        viewModelScope.launch { refresh() }
    }

    /** [reopen]'s reset, without the read — public, like [refresh], so a test can drive the two
     *  in sequence without a Main-dispatcher rule. */
    fun rearm() {
        initialDraft = null
        documents = emptyList()
        _state.value = ExpenseFormUiState(mode = _state.value.mode)
    }

    /** The actual load — public, like the other ViewModels', so tests drive it without a
     *  Main-dispatcher rule. */
    suspend fun refresh() {
        _state.update { it.copy(isLoading = true, loadError = null) }

        val snapshot = try {
            recentGroupsStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, loadError = "Couldn't read the stored group list.") }
            return
        }

        val row = snapshot.groups.firstOrNull { it.groupId == groupId }
        val instanceBaseUrl = row?.instanceBaseUrl
        if (instanceBaseUrl == null) {
            _state.update { it.copy(isLoading = false, loadError = "This group isn't in your list anymore.") }
            return
        }
        resolvedInstanceBaseUrl = instanceBaseUrl
        val client = clientFactory(instanceBaseUrl)

        try {
            // The three reads are independent, and the form needs all of them before it can draw
            // anything, so they go together rather than one after another.
            val loaded = coroutineScope {
                val groupJob = async { client.call(SpliitEndpoints.groupsGet(groupId)).group }
                val categoriesJob = async { loadCategories(client) }
                val expenseJob = async {
                    val id = _state.value.mode.editedExpenseId ?: return@async null
                    client.call(SpliitEndpoints.expensesGet(groupId, id)).expense
                }
                Triple(groupJob.await(), categoriesJob.await(), expenseJob.await())
            }
            val (loadedGroup, categories, expense) = loaded
            if (loadedGroup == null) {
                _state.update {
                    it.copy(isLoading = false, loadError = "This group no longer exists on this server.")
                }
                return
            }
            val group = GroupInfo(
                id = loadedGroup.id,
                name = loadedGroup.name,
                currencySymbol = loadedGroup.currency,
                currencyCode = loadedGroup.currencyCode,
                participants = loadedGroup.participants,
            )
            val draft = buildDraft(group, expense, row.defaultSplit, row.participantId)
            initialDraft = draft
            documents = expense?.documents.orEmpty()
            _state.update {
                it.copy(
                    isLoading = false,
                    group = group,
                    categories = categories,
                    draft = draft,
                    isDirty = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(isLoading = false, loadError = e.message) }
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isLoading = false, loadError = e.message) }
        }
    }

    /**
     * The category list, or none at all.
     *
     * Failing to read the catalogue costs the form its category chips and nothing else, so it
     * must not cost it the whole screen — an instance that answers `categories.list` with
     * anything unexpected would otherwise make every expense on it unopenable.
     */
    private suspend fun loadCategories(client: TrpcClient): List<ApiCategory> =
        try {
            client.call(SpliitEndpoints.categoriesList()).categories
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcServerError) {
            emptyList()
        } catch (e: TrpcClientError) {
            emptyList()
        }

    private fun buildDraft(
        group: GroupInfo,
        expense: app.spliit.api.ExpenseDetails?,
        defaultSplit: DefaultSplit?,
        rememberedParticipantId: String?,
    ): ExpenseFormDraft {
        val participants = group.participants.map { CoreParticipant(it.id, it.name) }
        return when (val mode = _state.value.mode) {
            is ExpenseFormMode.Edit -> ExpenseFormDraft.editing(
                expense = existing(checkNotNull(expense) { "an edit with no expense loaded" }),
                participants = participants,
                groupCurrencyCode = group.currencyCode,
                locale = locale,
            )

            is ExpenseFormMode.Settle -> ExpenseFormDraft.settling(
                fromParticipantId = mode.fromParticipantId,
                toParticipantId = mode.toParticipantId,
                amountMinorUnits = mode.amountMinorUnits,
                title = "Reimbursement",
                participants = participants,
                groupCurrencyCode = group.currencyCode,
                locale = locale,
            )

            ExpenseFormMode.Create -> ExpenseFormDraft.creating(
                participants = participants,
                groupCurrencyCode = group.currencyCode,
                // A remembered participant who has since left falls back to the first, rather
                // than prefilling the form as somebody who is gone — creating()'s own rule.
                paidBy = rememberedParticipantId,
                defaultSplit = defaultSplit,
                locale = locale,
            ).copy(expenseDate = now)
        }
    }

    // ---- editing the draft ------------------------------------------------------------------

    fun setTitle(title: String) = edit { it.copy(title = title) }

    fun setAmountText(text: String) = edit { it.copy(amountText = text) }

    fun setExpenseDate(date: Instant) = edit { it.copy(expenseDate = date) }

    fun setCategory(categoryId: Int) = edit { it.copy(categoryId = categoryId) }

    fun setPaidBy(participantId: String) = edit { it.copy(paidById = participantId) }

    fun setSplitMode(mode: SplitMode) = edit { it.withSplitMode(mode) }

    fun setParticipantIncluded(participantId: String, isIncluded: Boolean) =
        edit { it.withParticipantIncluded(participantId, isIncluded) }

    fun setAllParticipantsIncluded(isIncluded: Boolean) =
        edit { it.withAllParticipantsIncluded(isIncluded) }

    fun setShareText(participantId: String, text: String) = edit { draft ->
        draft.copy(
            participants = draft.participants.map {
                if (it.id == participantId) it.copy(valueText = text) else it
            },
        )
    }

    fun setNotes(notes: String) = edit { it.copy(notes = notes) }

    fun setReimbursement(isReimbursement: Boolean) = edit {
        // Nothing about a reimbursement is worth remembering as the group's usual split, so the
        // offer to remember goes away with the box rather than staying ticked behind it.
        it.copy(isReimbursement = isReimbursement, saveSplitAsDefault = it.saveSplitAsDefault && !isReimbursement)
    }

    fun setRecurrenceRule(rule: String) = edit { it.copy(recurrenceRule = rule) }

    fun setSaveSplitAsDefault(save: Boolean) = edit { it.copy(saveSplitAsDefault = save) }

    /** What the expense was paid in. [ExpenseFormDraft.withCurrency] is what decides whether that
     *  starts a conversion and what happens to the rate. */
    fun setCurrency(code: String?) = edit { it.withCurrency(code) }

    fun setOriginalAmountText(text: String) = edit { it.copy(originalAmountText = text) }

    fun setConversionRateText(text: String) = edit { it.copy(conversionRateText = text) }

    fun useRate(rate: BigDecimal) = edit { it.withRate(rate) }

    private inline fun edit(transform: (ExpenseFormDraft) -> ExpenseFormDraft) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            val updated = transform(draft)
            state.copy(draft = updated, isDirty = updated != initialDraft)
        }
    }

    // ---- saving ----------------------------------------------------------------------------

    fun save() {
        viewModelScope.launch { submit() }
    }

    /** The actual write. Returns whether anything was sent. */
    suspend fun submit(): Boolean {
        _state.update { it.copy(hasAttemptedSave = true, saveError = null) }
        val current = _state.value
        val draft = current.draft ?: return false
        val group = current.group ?: return false
        val baseUrl = resolvedInstanceBaseUrl ?: return false
        // Refused before anything was sent; the problems are already beside their fields.
        val submission = draft.submission() ?: return false

        _state.update { it.copy(isSaving = true) }
        val values = formValues(submission)
        try {
            val client = clientFactory(baseUrl)
            val actorId = actorId(group)
            val expenseId = when (val mode = current.mode) {
                is ExpenseFormMode.Edit ->
                    client.call(SpliitEndpoints.expensesUpdate(groupId, mode.expenseId, values, actorId)).expenseId

                ExpenseFormMode.Create, is ExpenseFormMode.Settle ->
                    client.call(SpliitEndpoints.expensesCreate(groupId, values, actorId)).expenseId
            }
            // Only once the expense is actually written: a split remembered from a save the
            // server refused would go on prefilling expenses that never happened.
            if (draft.saveSplitAsDefault && draft.isSplitWorthRemembering) {
                rememberSplit(submission, group)
            }
            _state.update { it.copy(isSaving = false, savedExpenseId = expenseId) }
            return true
        } catch (e: CancellationException) {
            // Not a failure to report: the scope went away, or another load replaced this one.
            // Catching Exception here instead would tell the user the server was unreachable.
            _state.update { it.copy(isSaving = false) }
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    // ---- deleting, and taking it back -------------------------------------------------------

    fun delete() {
        viewModelScope.launch { submitDelete() }
    }

    /** Deletes the expense being edited, keeping what it would take to put it back. */
    suspend fun submitDelete(): Boolean {
        val current = _state.value
        val mode = current.mode as? ExpenseFormMode.Edit ?: return false
        val group = current.group ?: return false
        val draft = current.draft ?: return false
        val baseUrl = resolvedInstanceBaseUrl ?: return false

        _state.update { it.copy(isSaving = true, saveError = null) }
        try {
            val client = clientFactory(baseUrl)
            client.call(SpliitEndpoints.expensesDelete(groupId, mode.expenseId, actorId(group)))
            // Built from the draft as it stands rather than from what was loaded: an edit the
            // user made and then deleted should come back the way they left it. A draft too
            // incomplete to submit leaves nothing to undo with, which the screen reads as a
            // delete with no undo rather than as a failure.
            val restorable = draft.submission()?.let { formValues(it) }
            _state.update {
                it.copy(
                    isSaving = false,
                    deleted = restorable?.let { values -> DeletedExpense(values, draft.title) },
                    isFinished = restorable == null,
                )
            }
            return true
        } catch (e: CancellationException) {
            _state.update { it.copy(isSaving = false) }
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    fun undoDelete() {
        viewModelScope.launch { submitUndoDelete() }
    }

    /** Writes the deleted expense back. It returns under a new ID — see [DeletedExpense]. */
    suspend fun submitUndoDelete(): Boolean {
        val current = _state.value
        val deleted = current.deleted ?: return false
        val group = current.group ?: return false
        val baseUrl = resolvedInstanceBaseUrl ?: return false

        _state.update { it.copy(isSaving = true, saveError = null) }
        try {
            val client = clientFactory(baseUrl)
            val response = client.call(
                SpliitEndpoints.expensesCreate(groupId, deleted.values, actorId(group)),
            )
            _state.update { it.copy(isSaving = false, deleted = null, savedExpenseId = response.expenseId) }
            return true
        } catch (e: CancellationException) {
            _state.update { it.copy(isSaving = false) }
            throw e
        } catch (e: TrpcServerError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        } catch (e: TrpcClientError) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    /** The undo has gone by — the delete stands and the screen can close. */
    fun dismissDeleted() = _state.update { it.copy(deleted = null, isFinished = true) }

    fun dismissSaveError() = _state.update { it.copy(saveError = null) }

    /** Leaving without saving. Whether to ask first is [ExpenseFormUiState.isDirty]'s business. */
    fun close() = _state.update { it.copy(isFinished = true) }

    // ---- the pieces `:core` cannot know -----------------------------------------------------

    /**
     * Who the activity log should credit for this write.
     *
     * Read fresh from the store rather than cached at load: the active-user picker sits one
     * screen away and can have been answered since. Null both before anybody has answered and
     * once the remembered answer has left the group — [RecentGroupsSnapshot.actorId]'s own rule,
     * because a write must never claim to be someone who is gone.
     */
    private suspend fun actorId(group: GroupInfo): String? = try {
        recentGroupsStore.load().actorId(groupId, group.participants.map { CoreParticipant(it.id, it.name) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The write is still worth making; the log will read "Someone", which is then accurate.
        null
    }

    private suspend fun rememberSplit(submission: ExpenseSubmission, group: GroupInfo) {
        try {
            val snapshot = recentGroupsStore.load()
            val split = DefaultSplit.remembering(
                submission = submission,
                participants = group.participants.map { CoreParticipant(it.id, it.name) },
            )
            recentGroupsStore.save(snapshot.settingDefaultSplit(groupId, split))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The expense is written; failing to remember how it divided is not worth undoing it
            // or telling the user about.
        }
    }

    /**
     * `:core`'s submission as the wire wants it.
     *
     * Everything here is a copy — [ExpenseSubmission.Wire] has already settled the widths and the
     * three spellings of "empty", including the explicit JSON null that drops a conversion.
     * `notes` stays a Kotlin `String?`, which `explicitNulls = false` **omits**: the schema
     * answers 400 to a literal null (verified against a live server), so this must not be
     * "fixed" into one.
     */
    private fun formValues(submission: ExpenseSubmission): ExpenseFormValues {
        val wire = submission.toWire()
        return ExpenseFormValues(
            title = wire.title,
            expenseDate = wire.expenseDate,
            amount = wire.amount,
            category = wire.category,
            paidBy = wire.paidBy,
            paidFor = wire.paidFor.map { ExpenseFormValues.PaidFor(it.participant, it.shares) },
            splitMode = wire.splitMode.toApi(),
            saveDefaultSplittingOptions = wire.saveDefaultSplittingOptions,
            isReimbursement = wire.isReimbursement,
            documents = documents,
            notes = wire.notes,
            recurrenceRule = recurrenceRuleOf(wire.recurrenceRule),
            originalAmount = wire.originalAmount,
            originalCurrency = ExpenseFormValues.conversionCurrency(wire.originalCurrency),
            conversionRate = wire.conversionRate?.let(::LenientDecimal),
        )
    }

    private fun existing(expense: app.spliit.api.ExpenseDetails): ExpenseFormDraft.ExistingExpense =
        ExpenseFormDraft.ExistingExpense(
            title = expense.title,
            expenseDate = expense.expenseDate,
            amount = expense.amount,
            categoryId = expense.categoryId,
            paidById = expense.paidById,
            paidFor = expense.paidFor.map {
                ExpenseFormDraft.ExistingExpense.PaidFor(it.participantId, it.shares)
            },
            splitMode = expense.splitMode.toCore(),
            isReimbursement = expense.isReimbursement,
            notes = expense.notes,
            recurrenceRule = nameOf(expense.recurrenceRule),
            originalAmount = expense.originalAmount,
            originalCurrency = expense.originalCurrency,
            conversionRate = expense.conversionRate?.value,
        )

}

// ---- the two enumerations `:core` and `:api` each have their own of ---------------------------
//
// `:core` deliberately does not depend on `:api` (CLAUDE.md), so the split mode exists twice and
// `:app` is where they meet. A `when` over four entries the compiler checks, rather than a name
// comparison that would go quietly wrong if either side ever renamed one.

internal fun SplitMode.toApi(): ApiSplitMode = when (this) {
    SplitMode.EVENLY -> ApiSplitMode.EVENLY
    SplitMode.BY_SHARES -> ApiSplitMode.BY_SHARES
    SplitMode.BY_PERCENTAGE -> ApiSplitMode.BY_PERCENTAGE
    SplitMode.BY_AMOUNT -> ApiSplitMode.BY_AMOUNT
}

internal fun ApiSplitMode.toCore(): SplitMode = when (this) {
    ApiSplitMode.EVENLY -> SplitMode.EVENLY
    ApiSplitMode.BY_SHARES -> SplitMode.BY_SHARES
    ApiSplitMode.BY_PERCENTAGE -> SplitMode.BY_PERCENTAGE
    ApiSplitMode.BY_AMOUNT -> SplitMode.BY_AMOUNT
}

/**
 * The server's own word for a cadence, which is what [ExpenseFormDraft.recurrenceRule] carries.
 *
 * An expense whose rule this version has no words for keeps it: [RecurrenceRule.Unknown] round
 * trips the server's raw string, so an expense edited on a screen that cannot name its cadence
 * is saved with the cadence it had rather than silently reset to NONE.
 */
internal fun nameOf(rule: RecurrenceRule?): String = when (rule) {
    null, RecurrenceRule.None -> ExpenseFormDraft.NO_RECURRENCE
    RecurrenceRule.Daily -> "DAILY"
    RecurrenceRule.Weekly -> "WEEKLY"
    RecurrenceRule.Monthly -> "MONTHLY"
    is RecurrenceRule.Unknown -> rule.raw
}

internal fun recurrenceRuleOf(name: String): RecurrenceRule = when (name) {
    ExpenseFormDraft.NO_RECURRENCE -> RecurrenceRule.None
    "DAILY" -> RecurrenceRule.Daily
    "WEEKLY" -> RecurrenceRule.Weekly
    "MONTHLY" -> RecurrenceRule.Monthly
    else -> RecurrenceRule.Unknown(name)
}
