package app.spliit.android.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags

/** DESIGN.md §3: text fields are 12dp radius, distinct from a card's 16dp. */
private val FieldShape = RoundedCornerShape(12.dp)

/** DESIGN.md §3: bottom sheets take 24dp top corners. */
private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * Adds a group someone shared, by pasting its link.
 *
 * Spliit has no accounts: a group URL *is* the invitation, so this is how a second device ever
 * learns about a group. The link says which server as well as which group, see [GroupLink] -
 * which is what lets somebody be handed a group on an instance this phone has never talked to.
 *
 * A modal sheet rather than a pushed screen, matching iOS. Pasting a link is a small, cancellable
 * errand that ends where it started; a push says "you are now somewhere else" and asks for a
 * navigation gesture to undo something that was never a journey. The dashboard stays visible
 * behind it, which is also where the added group appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupByUrlSheet(
    viewModel: AddGroupByUrlViewModel,
    sheetState: SheetState,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.addedGroupId) {
        state.addedGroupId?.let(onAdded)
    }
    // The one thing this sheet is for is receiving a paste, so the field is ready for one without
    // a second tap.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = SheetShape) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(
                text = "Add group",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Group link",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.urlText,
                onValueChange = viewModel::setUrlText,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(TestTags.ADD_GROUP_URL_FIELD),
                // The instance this phone would look a bare ID up on, not a hardcoded spliit.app:
                // somebody running their own server should be shown their own address here.
                placeholder = { Text("${state.defaultInstanceBaseUrl}groups/…") },
                singleLine = true,
                isError = state.problem != null,
                enabled = !state.isChecking,
                shape = FieldShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    // A link is never capitalised and rarely a dictionary word; a group ID that
                    // autocorrect has "fixed" names nothing at all.
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                // Submitting from the keyboard does what the button does, the hands are already
                // there, and the alternative is dismissing the keyboard to reach a button.
                keyboardActions = KeyboardActions(onGo = { viewModel.add() }),
            )
            if (state.problem != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.problem.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TestTags.ADD_GROUP_URL_ERROR),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Paste the link to a group that was shared with you, from spliit.app, " +
                    "or from any other Spliit server, and it will appear in your list. A group's " +
                    "ID on its own works too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(TestTags.ADD_GROUP_URL_CANCEL),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = viewModel::add,
                    // Nothing to check, or a check already in flight: a second tap would start a
                    // second lookup of the same link.
                    enabled = !state.isChecking && state.urlText.isNotBlank(),
                    modifier = Modifier.testTag(TestTags.ADD_GROUP_URL_SUBMIT),
                ) {
                    Text(if (state.isChecking) "Adding…" else "Add")
                }
            }
        }
    }
}
