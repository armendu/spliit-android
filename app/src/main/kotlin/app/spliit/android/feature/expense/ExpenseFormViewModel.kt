package app.spliit.android.feature.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.android.feature.group.GroupInfo
import app.spliit.android.feature.group.groupInfoOf
import app.spliit.api.ExchangeRateError
import app.spliit.api.ExchangeRates
import app.spliit.api.ExpenseDocument
import app.spliit.api.ExpenseFormValues
import app.spliit.api.LenientDecimal
import app.spliit.api.RecurrenceRule
import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import app.spliit.core.DefaultSplit
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.ExpenseSubmission
import app.spliit.core.RecentGroupsStore
import app.spliit.core.SplitMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.spliit.api.ExpenseCategory as ApiCategory
import app.spliit.api.SplitMode as ApiSplitMode
import app.spliit.core.Participant as CoreParticipant

sealed interface ExpenseFormMode {
    data object Create : ExpenseFormMode

    data class Edit(val expenseId: String) : ExpenseFormMode

    data class Settle(
        val fromParticipantId: String,
        val toParticipantId: String,
        val amountMinorUnits: Long,
    ) : ExpenseFormMode

    val editedExpenseId: String? get() = (this as? Edit)?.expenseId
}

data class DeletedExpense(
    val values: ExpenseFormValues,
    val title: String,
)

sealed interface RateLookup {
    data object Idle : RateLookup
    data object Loading : RateLookup
    data class Found(val date: LocalDate, val requestedDate: LocalDate) : RateLookup
    data object Unavailable : RateLookup
    data object Failed : RateLookup
}

data class ExpenseFormUiState(
    val mode: ExpenseFormMode,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val group: GroupInfo? = null,
    val categories: List<ApiCategory> = emptyList(),
    val draft: ExpenseFormDraft? = null,
    val hasAttemptedSave: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val isDirty: Boolean = false,
    val savedExpenseId: String? = null,
    val deleted: DeletedExpense? = null,
    val isFinished: Boolean = false,
    val rateLookup: RateLookup = RateLookup.Idle,
    val rateIsCustom: Boolean = false,
) {
    val isEditing: Boolean get() = mode is ExpenseFormMode.Edit

    fun problems(field: ExpenseFormDraft.Field): List<ExpenseFormDraft.Problem> =
        if (!hasAttemptedSave) emptyList() else draft?.problems(field).orEmpty()

    fun problems(participantId: String): List<ExpenseFormDraft.Problem> =
        if (!hasAttemptedSave) emptyList() else draft?.problems(participantId).orEmpty()
}

class ExpenseFormViewModel(
    private val groupId: String,
    mode: ExpenseFormMode,
    private val recentGroupsStore: RecentGroupsStore,
    private val clientFactory: (String) -> TrpcClient = { TrpcClient(it) },
    private val exchangeRates: ExchangeRates = ExchangeRates(),
    private val locale: Locale = Locale.getDefault(),
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    // Today here, as the UTC midnight the server stores a day as. Instant.now() is tomorrow after 8pm in New York.
    private val today: Instant = now.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    private val _state = MutableStateFlow(ExpenseFormUiState(mode = mode))
    val state: StateFlow<ExpenseFormUiState> = _state.asStateFlow()

    private var resolvedInstanceBaseUrl: String? = null

    private var initialDraft: ExpenseFormDraft? = null

    private var rateJob: Job? = null

    // Sent back on update; omitting them drops receipts attached on the web.
    private var documents: List<ExpenseDocument> = emptyList()

    private var started = false

    fun load() {
        if (started) return
        started = true
        viewModelScope.launch { refresh() }
    }

    fun retry() {
        viewModelScope.launch { refresh() }
    }

    // The sheet calls this every time it appears, rotation included; only an edit that ended starts over.
    fun reopen() {
        val current = _state.value
        if (started && !current.isFinished && current.savedExpenseId == null) return
        rearm()
        load()
    }

    fun rearm() {
        started = false
        rateJob?.cancel()
        initialDraft = null
        documents = emptyList()
        _state.value = ExpenseFormUiState(mode = _state.value.mode)
    }

    suspend fun refresh() {
        started = true
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
            val group = groupInfoOf(loadedGroup, instanceBaseUrl)
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
                    rateIsCustom = draft.conversionRequired,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(isLoading = false, loadError = e.message) }
        }
    }

    private suspend fun loadCategories(client: TrpcClient): List<ApiCategory> =
        try {
            client.call(SpliitEndpoints.categoriesList()).categories
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
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
            ).copy(expenseDate = today)

            ExpenseFormMode.Create -> ExpenseFormDraft.creating(
                participants = participants,
                groupCurrencyCode = group.currencyCode,
                paidBy = rememberedParticipantId,
                defaultSplit = defaultSplit,
                locale = locale,
            ).copy(expenseDate = today)
        }
    }

    fun setTitle(title: String) = edit { it.copy(title = title) }

    fun setAmountText(text: String) = edit { it.copy(amountText = text) }

    fun setExpenseDate(date: Instant) {
        applyExpenseDate(date)
        fetchRate()
    }

    fun applyExpenseDate(date: Instant) = edit { it.copy(expenseDate = date) }

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
        it.copy(isReimbursement = isReimbursement, saveSplitAsDefault = it.saveSplitAsDefault && !isReimbursement)
    }

    fun setRecurrenceRule(rule: String) = edit { it.copy(recurrenceRule = rule) }

    fun setSaveSplitAsDefault(save: Boolean) = edit { it.copy(saveSplitAsDefault = save) }

    fun setCurrency(code: String?) {
        applyCurrency(code)
        fetchRate()
    }

    fun applyCurrency(code: String?) {
        edit { it.withCurrency(code) }
        _state.update { it.copy(rateIsCustom = false, rateLookup = RateLookup.Idle) }
    }

    fun setOriginalAmountText(text: String) = edit { it.copy(originalAmountText = text) }

    fun setConversionRateText(text: String) {
        rateJob?.cancel()
        edit { it.copy(conversionRateText = text) }
        _state.update { it.copy(rateIsCustom = true, rateLookup = RateLookup.Idle) }
    }

    fun useFetchedRate() {
        _state.update { it.copy(rateIsCustom = false) }
        fetchRate()
    }

    private fun fetchRate() {
        rateJob?.cancel()
        rateJob = viewModelScope.launch { lookUpRate() }
    }

    suspend fun lookUpRate() {
        val current = _state.value
        val draft = current.draft ?: return
        if (!draft.conversionRequired || current.rateIsCustom) return
        val from = draft.originalCurrencyCode ?: return
        val to = draft.groupCurrencyCode ?: return
        val day = draft.rateDay()

        _state.update { it.copy(rateLookup = RateLookup.Loading) }
        val result = try {
            exchangeRates.rate(day, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExchangeRateError) {
            _state.update { it.copy(rateLookup = RateLookup.Failed) }
            return
        }

        val latest = _state.value
        val stillAsked = latest.draft?.let {
            it.originalCurrencyCode == from && it.groupCurrencyCode == to && it.rateDay() == day
        } == true
        if (!stillAsked || latest.rateIsCustom) return

        if (result == null) {
            _state.update { it.copy(rateLookup = RateLookup.Unavailable) }
        } else {
            edit { it.withRate(result.rate) }
            _state.update { it.copy(rateLookup = RateLookup.Found(result.date, day)) }
        }
    }

    private fun ExpenseFormDraft.rateDay(): LocalDate = expenseDate.atZone(ZoneOffset.UTC).toLocalDate()

    private inline fun edit(transform: (ExpenseFormDraft) -> ExpenseFormDraft) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            val updated = transform(draft)
            state.copy(draft = updated, isDirty = updated != initialDraft)
        }
    }

    fun save() {
        viewModelScope.launch { submit() }
    }

    suspend fun submit(): Boolean {
        if (_state.value.isSaving) return false
        _state.update { it.copy(hasAttemptedSave = true, saveError = null) }
        val current = _state.value
        val draft = current.draft ?: return false
        val group = current.group ?: return false
        val baseUrl = resolvedInstanceBaseUrl ?: return false
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
            if (draft.saveSplitAsDefault && draft.isSplitWorthRemembering) {
                rememberSplit(submission, group)
            }
            _state.update { it.copy(isSaving = false, savedExpenseId = expenseId) }
            return true
        } catch (e: CancellationException) {
            _state.update { it.copy(isSaving = false) }
            throw e
        } catch (e: TrpcException) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    fun delete() {
        viewModelScope.launch { submitDelete() }
    }

    suspend fun submitDelete(): Boolean {
        val current = _state.value
        if (current.isSaving) return false
        val mode = current.mode as? ExpenseFormMode.Edit ?: return false
        val group = current.group ?: return false
        val draft = current.draft ?: return false
        val baseUrl = resolvedInstanceBaseUrl ?: return false

        _state.update { it.copy(isSaving = true, saveError = null) }
        try {
            val client = clientFactory(baseUrl)
            client.call(SpliitEndpoints.expensesDelete(groupId, mode.expenseId, actorId(group)))
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
        } catch (e: TrpcException) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    fun undoDelete() {
        viewModelScope.launch { submitUndoDelete() }
    }

    suspend fun submitUndoDelete(): Boolean {
        val current = _state.value
        if (current.isSaving) return false
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
        } catch (e: TrpcException) {
            _state.update { it.copy(isSaving = false, saveError = e.message) }
            return false
        }
    }

    fun dismissDeleted() = _state.update { it.copy(deleted = null, isFinished = true) }

    fun dismissSaveError() = _state.update { it.copy(saveError = null) }

    fun close() = _state.update { it.copy(isFinished = true) }

    private suspend fun actorId(group: GroupInfo): String? = try {
        recentGroupsStore.load().actorId(groupId, group.participants.map { CoreParticipant(it.id, it.name) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun rememberSplit(submission: ExpenseSubmission, group: GroupInfo) {
        try {
            val split = DefaultSplit.remembering(
                submission = submission,
                participants = group.participants.map { CoreParticipant(it.id, it.name) },
            )
            recentGroupsStore.update { it.settingDefaultSplit(groupId, split) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

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
