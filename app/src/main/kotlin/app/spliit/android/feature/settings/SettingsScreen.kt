package app.spliit.android.feature.settings

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import app.spliit.core.ThemeMode
import app.spliit.android.ui.design.FieldShape
import app.spliit.android.ui.design.CardDivider
import app.spliit.android.ui.design.FormSectionHeader
import app.spliit.android.ui.design.FieldError
import app.spliit.android.ui.design.outlinedCard
import app.spliit.android.ui.design.Footnote

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            Footnote(
                "Where a group you create lives, and what a link naming no server is looked up " +
                    "against. A pasted link that names its own server always wins, and a group " +
                    "already on your list stays on the server it was made on.",
            )

            FormSectionHeader("About")
            AboutCard(
                onVisit = { openUrl(context, "https://spliit.app/?ref=android-app") },
                onSpliitGitHub = { openUrl(context, "https://github.com/spliit-app/spliit") },
                onAppGitHub = { openUrl(context, "https://github.com/armendu/spliit-android") },
            )
            Footnote(
                "Spliit is an open source project by Sebastien Castiel, with help from many " +
                    "contributors. This Android app is a separate open source project by " +
                    "Armend Ukëhaxhaj, ported from Spliit for iOS and released under the MIT licence.",
            )

            FormSectionHeader("Feedback")
            FeedbackCard(
                onReport = { openUrl(context, "https://github.com/armendu/spliit-android/issues") },
            )

            FormSectionHeader("Version")
            VersionCard(
                name = viewModel.versionName,
                code = viewModel.versionCode,
                testedSpliit = viewModel.testedSpliitVersion,
            )
            Footnote(
                "Spliit servers don't say which release they run, so this is the one the app was " +
                    "tested against, not necessarily the one your server is on.",
            )

            FormSectionHeader("Open source libraries")
            LibrariesCard(onOpen = { openUrl(context, it) })
            Footnote("Each is used under the Apache License 2.0.")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearanceCard(themeMode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .outlinedCard()
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
            .outlinedCard()
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
private fun AboutCard(onVisit: () -> Unit, onSpliitGitHub: () -> Unit, onAppGitHub: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .outlinedCard(),
    ) {
        LinkRow("Visit spliit.app", onVisit, TestTags.SETTINGS_ABOUT_VISIT_LINK)
        CardDivider()
        LinkRow("Spliit on GitHub", onSpliitGitHub, TestTags.SETTINGS_ABOUT_GITHUB_LINK)
        CardDivider()
        LinkRow("This app on GitHub", onAppGitHub, TestTags.SETTINGS_ABOUT_APP_GITHUB_LINK)
    }
}

@Composable
private fun FeedbackCard(onReport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .outlinedCard(),
    ) {
        LinkRow("Report a problem", onReport, TestTags.SETTINGS_FEEDBACK_REPORT_LINK)
    }
}

@Composable
private fun VersionCard(name: String, code: Int, testedSpliit: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .outlinedCard(),
    ) {
        ValueRow("Version", "$name ($code)", TestTags.SETTINGS_VERSION_VALUE)
        CardDivider()
        ValueRow("Tested with Spliit", testedSpliit, TestTags.SETTINGS_TESTED_SPLIIT_VALUE)
    }
}

@Composable
private fun ValueRow(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun LibrariesCard(onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .outlinedCard(),
    ) {
        openSourceLibraries.forEach { library ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(library.url) }
                    .testTag(TestTags.settingsLibraryLink(library.name))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(library.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    library.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardDivider()
        }
        LinkRow("Apache License 2.0", { onOpen(APACHE_LICENSE_URL) }, TestTags.SETTINGS_LICENSE_LINK)
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

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
