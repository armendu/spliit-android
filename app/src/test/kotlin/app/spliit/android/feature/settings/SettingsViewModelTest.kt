package app.spliit.android.feature.settings

import app.spliit.core.AppSettings
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.android.feature.groups.FakeRecentGroupsStore
import app.spliit.core.RecentGroupsStore
import app.spliit.core.SettingsStore
import app.spliit.core.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val BUILD_DEFAULT = "https://spliit.app/"

private class FakeSettingsStore(private var settings: AppSettings = AppSettings()) : SettingsStore {
    override suspend fun load(): AppSettings = settings
    override suspend fun save(settings: AppSettings) {
        this.settings = settings
    }
}

class SettingsViewModelTest {
    // No-op applySettings: AppSettingsHolder is a Compose singleton JVM tests shouldn't touch.
    private fun viewModel(
        store: SettingsStore = FakeSettingsStore(),
        initial: AppSettings = AppSettings(),
    ): SettingsViewModel = SettingsViewModel(
        settingsStore = store,
        buildDefaultInstanceBaseUrl = BUILD_DEFAULT,
        initial = initial,
        versionName = "0.1.0",
        versionCode = 1,
        applySettings = {},
    )

    @Test
    fun `follow system is the default on a fresh store`() {
        val viewModel = viewModel(initial = AppSettings())

        assertEquals(ThemeMode.FOLLOW_SYSTEM, viewModel.state.value.themeMode)
        assertTrue(viewModel.state.value.isUsingBuildDefault)
        assertEquals(BUILD_DEFAULT, viewModel.state.value.instanceAddressText)
    }

    @Test
    fun `the theme choice persists and reloads`() = runBlocking {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store = store)

        viewModel.updateThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
        assertEquals(ThemeMode.DARK, store.load().themeMode, "the choice must reach the store, not just memory")

        val reloaded = viewModel(store = store, initial = store.load())
        assertEquals(ThemeMode.DARK, reloaded.state.value.themeMode)
    }

    @Test
    fun `an invalid instance URL is rejected`() = runBlocking {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store = store)

        viewModel.setInstanceAddressText("not a url")
        viewModel.commitInstanceAddress()

        assertFalse(viewModel.state.value.instanceIsValid)
        assertTrue(viewModel.state.value.hasAttemptedSaveInstance)
        assertNull(store.load().instanceBaseUrlOverride, "an invalid address must never reach the store")
        assertTrue(viewModel.state.value.isUsingBuildDefault)
    }

    @Test
    fun `resetting restores the build default`() = runBlocking {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store = store)
        viewModel.setInstanceAddressText("home.example.com")
        viewModel.commitInstanceAddress()
        assertFalse(viewModel.state.value.isUsingBuildDefault, "the override should have taken before resetting")

        viewModel.commitInstanceReset()

        assertEquals(BUILD_DEFAULT, viewModel.state.value.instanceAddressText)
        assertTrue(viewModel.state.value.isUsingBuildDefault)
        assertNull(store.load().instanceBaseUrlOverride)
    }

    @Test
    fun `existing recent-group rows keep their own instance when the default changes`() = runBlocking {
        val settingsStore = FakeSettingsStore()
        val recentGroupsStore = FakeRecentGroupsStore(
            RecentGroupsSnapshot(
                groups = listOf(
                    RecentGroup(groupId = "g1", instanceBaseUrl = BUILD_DEFAULT, groupName = "Weekend in Lisbon"),
                ),
            ),
        )
        val viewModel = viewModel(store = settingsStore)

        viewModel.setInstanceAddressText("https://home.example.com/")
        viewModel.commitInstanceAddress()

        val row = recentGroupsStore.load().groups.single()
        assertEquals(BUILD_DEFAULT, row.instanceBaseUrl, "an existing group must keep its own instance")
        assertEquals("https://home.example.com/", settingsStore.load().instanceBaseUrlOverride)
    }
}
