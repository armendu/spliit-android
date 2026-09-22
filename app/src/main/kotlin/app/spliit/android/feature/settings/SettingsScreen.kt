package app.spliit.android.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import app.spliit.core.ThemeMode
import app.spliit.android.ui.design.FieldShape
import app.spliit.android.ui.design.CardShape
import app.spliit.android.ui.design.FormSectionHeader
import app.spliit.android.ui.design.FieldError



/**
 * Settings: appearance, the default instance, and the static About/Feedback/Version rows that
 * mirror the iOS app's own SettingsView.
 *
 * **The theme picker is not on iOS**, and that is deliberate rather than a gap the port left open.
 * iOS's SettingsView carries a comment saying so directly: an app there follows the system by
 * convention, so there is nothing to pick. Android has no equivalent convention, and the person
 * who asked for this screen asked for the choice by name, "specify the theme", so offering one
 * here is a documented divergence from the source of truth, not an oversight to reconcile later.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(TestTags.SETTINGS_BACK_BUTTON),
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FormSectionHeader("Appearance", topSpace = 0.dp)
            AppearanceCard(themeMode = state.themeMode, onSelect = viewModel::setThemeMode)

            FormSectionHeader("Default instance")
            InstanceCard(
                state = state,
                onTextChange = viewModel::setInstanceAddressText,
                onSave = viewModel::saveInstanceAddress,
                onReset = viewModel::resetInstanceAddress,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Where a group you create lives, and what a link naming no server is " +
                    "looked up against. A pasted link that names its own server always wins, " +
                    "and a group already on your list stays on the server it was made on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FormSectionHeader("About")
            AboutCard(
                onVisit = { openUrl(context, "https://spliit.app/?ref=android-app") },
                onGitHub = { openUrl(context, "https://github.com/spliit-app") },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Spliit is an open source project by Sebastien Castiel, with help from " +
                    "many contributors.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FormSectionHeader("Feedback")
            FeedbackCard(
                // Points at the org page, not a repo, there is no spliit-android repository yet.
                // See this part's own report for the follow-up once one exists.
                onReport = { openUrl(context, "https://github.com/spliit-app") },
            )

            FormSectionHeader("Version")
            VersionCard(name = viewModel.versionName, code = viewModel.versionCode)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearanceCard(themeMode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            // Groups the three rows for accessibility services as one radio group, the same
            // relationship RadioButton implies visually, a real Material selection control, not
            // three independently-focusable rows that happen to look related.
            .selectableGroup(),
    ) {
        ThemeOption("Follow system", ThemeMode.FOLLOW_SYSTEM, themeMode, onSelect)
        ThemeOption("Light", ThemeMode.LIGHT, themeMode, onSelect)
        ThemeOption("Dark", ThemeMode.DARK, themeMode, onSelect)
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val isSelected = mode == selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row is the tap target, per Material's own radio-list-item pattern; the
            // RadioButton below is `onClick = null` so the click isn't handled twice.
            .selectable(selected = isSelected, onClick = { onSelect(mode) }, role = Role.RadioButton)
            .testTag(TestTags.settingsThemeOption(mode.name))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InstanceCard(
    state: SettingsUiState,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.instanceAddressText,
            onValueChange = onTextChange,
            label = { Text("Server address") },
            singleLine = true,
            isError = state.hasAttemptedSaveInstance && !state.instanceIsValid,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_INSTANCE_FIELD),
            shape = FieldShape,
        )
        if (state.hasAttemptedSaveInstance && !state.instanceIsValid) {
            Spacer(Modifier.height(4.dp))
            FieldError(
                message = "That doesn't look like a web address. Try something like spliit.example.com.",
                testTag = TestTags.SETTINGS_INSTANCE_ERROR,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onSave,
                modifier = Modifier.testTag(TestTags.SETTINGS_INSTANCE_SAVE_BUTTON),
            ) {
                Text("Save")
            }
            // Disabled rather than hidden while already on the build default: a control that
            // disappears the moment it would do nothing is harder to find the one time it's
            // needed again than one that is simply greyed out.
            TextButton(
                onClick = onReset,
                enabled = !state.isUsingBuildDefault,
                modifier = Modifier.testTag(TestTags.SETTINGS_INSTANCE_RESET_BUTTON),
            ) {
                Text("Reset to default")
            }
        }
    }
}

@Composable
private fun AboutCard(onVisit: () -> Unit, onGitHub: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape),
    ) {
        LinkRow("Visit spliit.app", onVisit, TestTags.SETTINGS_ABOUT_VISIT_LINK)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LinkRow("View on GitHub", onGitHub, TestTags.SETTINGS_ABOUT_GITHUB_LINK)
    }
}

@Composable
private fun FeedbackCard(onReport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape),
    ) {
        LinkRow("Report a problem", onReport, TestTags.SETTINGS_FEEDBACK_REPORT_LINK)
    }
}

@Composable
private fun VersionCard(name: String, code: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Version", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "$name ($code)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.SETTINGS_VERSION_VALUE),
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Best-effort: a device with literally no browser would otherwise crash the app for tapping a
 *  link, and there is nothing more useful to do about that from here. */
private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
