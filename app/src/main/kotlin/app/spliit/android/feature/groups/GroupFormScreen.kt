package app.spliit.android.feature.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import app.spliit.core.Currencies
import app.spliit.core.GroupFormDraft

/** DESIGN.md §3: text fields are 12dp radius, distinct from a card's 16dp. */
private val FieldShape = RoundedCornerShape(12.dp)

/**
 * The group editor, shared by "Create group" and (once Part 11 gives it somewhere to be reached
 * from) "Group settings" — see [GroupFormViewModel]. Every rule about what makes a group valid
 * comes from [GroupFormDraft.problems]; this screen only renders it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupFormScreen(
    viewModel: GroupFormViewModel,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    onPickCurrency: () -> Unit,
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

            val draft = state.draft
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.loadError != null) {
                    Text(
                        text = state.loadError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                    FieldError("A group needs a name.", TestTags.GROUP_FORM_NAME_ERROR)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickCurrency)
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

                if (state.mode == GroupFormMode.CREATE) {
                    FormSectionHeader("Server")
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
                            "That doesn't look like a web address. Try something like spliit.example.com.",
                            TestTags.GROUP_FORM_SERVER_ERROR,
                        )
                    }
                    Text(
                        text = "Where this group is created. A group stays on the server it was made on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        FieldError("This participant needs a name.", TestTags.groupFormParticipantError(index))
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
                    FieldError("A group needs at least one participant.", TestTags.GROUP_FORM_PARTICIPANTS_ERROR)
                } else {
                    Text(
                        text = "Anyone who already appears on an expense can't be removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.saveError != null) {
                    Spacer(Modifier.height(16.dp))
                    FieldError(state.saveError.orEmpty(), testTag = null)
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // Informational, not a decision to make — never a protected-focus dialog. A refused
        // removal says why, near the list it happened in, and clears itself on the next tap.
        AnimatedVisibility(
            visible = state.blockedParticipantMessage != null,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.blockedParticipantMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissBlockedParticipantMessage) { Text("OK") }
            }
        }
    }
}

@Composable
private fun FieldError(message: String, testTag: String?) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    )
}

/** More space above a heading than below it — a heading belongs to what follows it. */
@Composable
private fun FormSectionHeader(text: String, topSpace: Dp = 24.dp) {
    Spacer(Modifier.height(topSpace))
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** What the currency row shows on the right — the currency and the symbol it puts beside every
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
