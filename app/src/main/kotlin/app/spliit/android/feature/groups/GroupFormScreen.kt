package app.spliit.android.feature.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import java.util.Currency
import app.spliit.android.ui.design.CurrencyPickerSheet
import app.spliit.core.Currencies
import app.spliit.core.GroupFormDraft
import app.spliit.android.ui.design.FieldShape
import app.spliit.android.ui.design.FieldError
import app.spliit.android.ui.design.FormSectionHeader
import app.spliit.android.ui.design.SkeletonBlock


/**
 * The group editor as a full screen, "Group settings", reached from the group's own overflow
 * menu. Every rule about what makes a group valid comes from [GroupFormDraft.problems]; this
 * screen only renders it.
 *
 * **Creating a group does not come through here.** It is a bottom sheet, [CreateGroupSheet] -
 * over the dashboard, because creating one is short (a name and a participant or two) and a
 * full-screen form for that much is a trip rather than a task. Editing keeps the screen: by then
 * there are participants to add and remove, a note, a currency, and the errand is long enough to
 * deserve the room. Both draw the same [GroupFormBody].
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupFormScreen(
    viewModel: GroupFormViewModel,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.savedGroupId) {
        state.savedGroupId?.let(onSaved)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (state.mode == GroupFormMode.CREATE) "Create group" else "Group settings") },
                    // A modal task, not a destination: Material's full-screen dialog takes an ✕
                    // and a confirming action, which is also what the iOS Cancel/Save pair means.
                    // An up arrow here would promise a parent screen this form does not have.
                    navigationIcon = {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag(TestTags.GROUP_FORM_CANCEL_BUTTON),
                        ) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.isSaving && !state.isLoading,
                            modifier = Modifier.testTag(TestTags.GROUP_FORM_SAVE_BUTTON),
                        ) {
                            val label = if (state.mode == GroupFormMode.CREATE) "Create" else "Save"
                            Text(if (state.isSaving) "$label…" else label)
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (state.isLoading) {
                FormSkeleton(Modifier.padding(contentPadding).fillMaxSize())
                return@Scaffold
            }
            GroupFormBody(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
            )
        }

        // Informational, not a decision to make, never a protected-focus dialog. A refused
        // removal says why, near the list it happened in, and clears itself on the next tap.
        BlockedParticipantNotice(
            message = state.blockedParticipantMessage,
            onDismiss = viewModel::dismissBlockedParticipantMessage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The fields themselves, without any chrome, so the full screen and [CreateGroupSheet] draw the
 * same form rather than two forms that have to be kept in step.
 */
@Composable
internal fun GroupFormBody(
    state: GroupFormUiState,
    viewModel: GroupFormViewModel,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    // The picker is a sheet the form opens over itself rather than a route it navigates to.
    // Navigating was what the full screen used to do, and it left the create *sheet* with no way
    // to reach a currency at all, a sheet has no NavController to push onto, so the row there
    // was wired to `{}` and silently did nothing. Owning it here is what makes one row behave
    // the same in both presentations.
    var showCurrencyPicker by rememberSaveable { mutableStateOf(false) }
    // Collapsed by default and remembered across a rotation but not across the sheet closing:
    // somebody who opened Advanced for one group has not said anything about the next.
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val advancedChevronRotation by animateFloatAsState(
        targetValue = if (showAdvanced) 90f else 0f,
        label = "group_form_advanced_chevron",
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val loadError = state.loadError
        if (loadError != null) {
            FieldError(loadError)
            Spacer(Modifier.height(16.dp))
        }

        FormSectionHeader("Group information", topSpace = 0.dp)

        OutlinedTextField(
            value = draft.name,
            onValueChange = viewModel::setName,
            label = { Text("Group name") },
            singleLine = true,
            isError = state.hasAttemptedSave && draft.problems(GroupFormDraft.Field.NAME).isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_FORM_NAME_FIELD),
            shape = FieldShape,
        )
        if (state.hasAttemptedSave && draft.problems(GroupFormDraft.Field.NAME).isNotEmpty()) {
            FieldError("A group needs a name.", testTag = TestTags.GROUP_FORM_NAME_ERROR)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCurrencyPicker = true }
                .testTag(TestTags.GROUP_FORM_CURRENCY_ROW)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Currency", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = currencySummary(draft),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.usesCustomSymbol) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.currency,
                onValueChange = viewModel::setCustomSymbol,
                label = { Text("Currency symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_FORM_CUSTOM_SYMBOL_FIELD),
                shape = FieldShape,
            )
        }

        // Creating a group no longer asks for a server up front. The default is
        // configurable in Settings, and picking one is the advanced half of a decision
        // most people never make, somebody creating a group on spliit.app should never
        // see this field. It is *hidden, not removed*: self-hosting is a first-class
        // Spliit use case, the group's server is what a link resolves against, and there
        // has to be a way to reach it without a detour through Settings.
        //
        // Only when creating. By the time you are editing a group the whole form is
        // advanced, the address is a fact about the group rather than a choice, and a
        // group cannot move servers anyway, so the ViewModel does not offer the field
        // there at all.
        if (state.mode == GroupFormMode.CREATE) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .testTag(TestTags.GROUP_FORM_ADVANCED_TOGGLE)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        // The same glyph in both states, turned, one drawable, and the
                        // rotation animates where a swap between two would pop.
                        .rotate(advancedChevronRotation),
                )
                Text(
                    text = "Advanced",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    OutlinedTextField(
                        value = state.instanceAddressText,
                        onValueChange = viewModel::setInstanceAddressText,
                        label = { Text("Server address") },
                        singleLine = true,
                        isError = state.hasAttemptedSave && !state.instanceIsValid,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_FORM_SERVER_FIELD),
                        shape = FieldShape,
                    )
                    if (state.hasAttemptedSave && !state.instanceIsValid) {
                        FieldError(
                            "That doesn't look like a web address. Try something like " +
                                "spliit.example.com.",
                            testTag = TestTags.GROUP_FORM_SERVER_ERROR,
                        )
                    }
                    Text(
                        text = "Where this group is created, your default from Settings " +
                            "unless you change it. A group stays on the server it was made on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        FormSectionHeader("Notes")
        OutlinedTextField(
            value = draft.information,
            onValueChange = viewModel::setInformation,
            label = { Text("What should participants know?") },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_FORM_INFORMATION_FIELD),
            shape = FieldShape,
        )

        FormSectionHeader("Participants")
        draft.sortedParticipants.forEachIndexed { index, participant ->
            val canRemove = draft.canRemoveParticipant(participant.id)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = participant.name,
                    onValueChange = { viewModel.renameParticipant(participant.id, it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(TestTags.groupFormParticipantField(index)),
                    shape = FieldShape,
                )
                TextButton(
                    onClick = { viewModel.removeParticipant(participant.id) },
                    enabled = canRemove,
                    modifier = Modifier.testTag(TestTags.groupFormParticipantRemove(index)),
                ) {
                    Text("Remove")
                }
            }
            if (state.hasAttemptedSave && draft.problems(participant.id).isNotEmpty()) {
                FieldError("This participant needs a name.", testTag = TestTags.groupFormParticipantError(index))
            }
        }
        TextButton(
            onClick = viewModel::addParticipant,
            modifier = Modifier.testTag(TestTags.GROUP_FORM_ADD_PARTICIPANT_BUTTON),
        ) {
            Text("Add participant")
        }
        if (state.hasAttemptedSave &&
            draft.problems(GroupFormDraft.Field.PARTICIPANTS).contains(GroupFormDraft.Problem.NoParticipants)
        ) {
            FieldError("A group needs at least one participant.", testTag = TestTags.GROUP_FORM_PARTICIPANTS_ERROR)
        } else if (state.mode != GroupFormMode.CREATE) {
            // Only worth saying while editing. A group being created has no expenses for
            // anyone to appear on, so on the create sheet this was a rule about a
            // situation that cannot exist yet.
            Text(
                text = "Anyone who already appears on an expense can't be removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val saveError = state.saveError
        if (saveError != null) {
            Spacer(Modifier.height(16.dp))
            FieldError(saveError)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showCurrencyPicker) {
        CurrencyPickerSheet(
            title = "Currency",
            selectedCode = draft.currencyCode,
            // The phone's own currency, which is the likeliest answer for a group somebody is
            // creating on it. `Currency.getInstance` throws for a locale with no country behind
            // it, which a stripped-down emulator image really does have, hence the catch rather
            // than a check on the locale.
            promotedCode = runCatching { Currency.getInstance(draft.locale).currencyCode }.getOrNull(),
            promotedSuffix = ", this phone's own",
            onSelect = {
                viewModel.setCurrency(it)
                showCurrencyPicker = false
            },
            onUseCustomSymbol = {
                viewModel.useCustomSymbol()
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
            tag = TestTags::groupFormCurrencyOption,
            searchFieldTag = TestTags.GROUP_FORM_CURRENCY_SEARCH_FIELD,
        )
    }
}

/**
 * Why a participant could not be removed, said near the list it happened in.
 *
 * Informational, not a decision to make, never a protected-focus dialog. It clears itself on
 * the next tap. [GroupFormDraft.withParticipantRemoved] returns null rather than quietly doing
 * nothing precisely so there is something to say here.
 */
@Composable
internal fun BlockedParticipantNotice(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = message != null, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** What the currency row shows on the right, the currency and the symbol it puts beside every
 *  amount, or just the symbol when that is all the group has. */
private fun currencySummary(draft: GroupFormDraft): String {
    val code = draft.currencyCode
    if (!code.isNullOrBlank()) {
        val currency = Currencies.named(code, draft.locale)
        if (currency != null) return "${currency.name} (${currency.symbol})"
        return code
    }
    return draft.currency
}

@Composable
private fun FormSkeleton(modifier: Modifier) {
    Column(modifier = modifier.padding(16.dp)) {
        repeat(5) { index ->
            SkeletonBlock(Modifier.fillMaxWidth().height(if (index == 0) 24.dp else 56.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}
