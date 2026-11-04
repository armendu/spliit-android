package app.spliit.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.spliit.core.AppSettings
import app.spliit.core.DefaultSplit
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.SplitMode
import app.spliit.core.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class DataStoreAdaptersTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun stop() = scope.cancel()

    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }

    private val snapshot = RecentGroupsSnapshot(
        groups = listOf(
            RecentGroup(
                groupId = "g1",
                instanceBaseUrl = "https://spliit.app/",
                groupName = "Lisbon",
                isStarred = true,
                participantId = "p1",
                defaultSplit = DefaultSplit(SplitMode.BY_SHARES, mapOf("p1" to 200L, "p2" to 100L)),
                lastOpenedAt = Instant.parse("2026-10-01T10:00:00Z"),
                updatedAt = Instant.parse("2026-10-02T10:00:00Z"),
            ),
            RecentGroup(
                groupId = "g2",
                instanceBaseUrl = "http://10.0.2.2:3009/",
                groupName = "Flat",
                isArchived = true,
            ),
        ),
        tombstones = mapOf("gone" to Instant.parse("2026-09-30T08:00:00Z")),
    )

    @Test
    fun `a store that was never written loads empty`() = runBlocking {
        assertEquals(RecentGroupsSnapshot(), DataStoreRecentGroupsStore(dataStore("recent")).load())
        assertEquals(AppSettings(), DataStoreSettingsStore(dataStore("settings")).load())
    }

    @Test
    fun `every field of the group list survives a save and a fresh load`() = runBlocking {
        val store = dataStore("recent")

        assertTrue(DataStoreRecentGroupsStore(store).save(snapshot))

        assertEquals(snapshot, DataStoreRecentGroupsStore(store).load())
    }

    @Test
    fun `a list written by a newer version with extra fields still loads`() = runBlocking {
        val store = dataStore("recent")
        store.edit {
            it[stringPreferencesKey("snapshot")] =
                """{"groups":[{"groupId":"g1","instanceBaseUrl":"https://spliit.app/","groupName":"Lisbon",""" +
                """"colour":"teal"}],"tombstones":{},"syncedAt":"2026-10-01T00:00:00Z"}"""
        }

        val loaded = DataStoreRecentGroupsStore(store).load()

        assertEquals(listOf("g1"), loaded.groups.map { it.groupId })
        assertEquals(false, loaded.groups.single().isStarred)
    }

    @Test
    fun `an unreadable list loads empty instead of crashing`() = runBlocking {
        val store = dataStore("recent")
        store.edit { it[stringPreferencesKey("snapshot")] = "{not json" }

        assertEquals(RecentGroupsSnapshot(), DataStoreRecentGroupsStore(store).load())
    }

    @Test
    fun `settings survive a save and a fresh load`() = runBlocking {
        val store = dataStore("settings")
        val settings = AppSettings(themeMode = ThemeMode.DARK, instanceBaseUrlOverride = "https://home.example.com/")

        DataStoreSettingsStore(store).save(settings)

        assertEquals(settings, DataStoreSettingsStore(store).load())
    }

    @Test
    fun `an unknown theme from a newer version falls back without losing the server`() = runBlocking {
        val store = dataStore("settings")
        store.edit {
            it[stringPreferencesKey("settings")] =
                """{"themeMode":"HIGH_CONTRAST","instanceBaseUrlOverride":"https://home.example.com/"}"""
        }

        val loaded = DataStoreSettingsStore(store).load()

        assertEquals(ThemeMode.FOLLOW_SYSTEM, loaded.themeMode)
        assertEquals("https://home.example.com/", loaded.instanceBaseUrlOverride)
    }
}
