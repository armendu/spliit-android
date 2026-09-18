package app.spliit.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.spliit.core.AppSettings
import app.spliit.core.SettingsStore
import app.spliit.core.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A separate file from `recentGroupsDataStore` — one row of app-wide settings has nothing to do
 *  with the list of groups, and giving it its own file means a corrupt or downgraded settings blob
 *  (see [DataStoreSettingsStore.load]) can never take the groups list down with it, or the other
 *  way around. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

private val SETTINGS_KEY = stringPreferencesKey("settings")

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The AndroidX DataStore–backed [SettingsStore] — a thin JSON adapter, the same shape as
 * [DataStoreRecentGroupsStore] and for the same reasons documented there: `ignoreUnknownKeys` plus
 * defaults on every field of [StoredSettings] is what lets a later part grow [AppSettings] without
 * breaking an install that already has a file on disk, and this class's only job is turning that
 * shape into bytes and back.
 *
 * One difference from [DataStoreRecentGroupsStore]: a row here that fails to decode falls back to
 * per-field defaults inside [StoredSettings.toCore] rather than degrading the *whole* blob to
 * [AppSettings()][AppSettings] the way an unreadable groups snapshot does. There is no compound
 * structure to protect — an unrecognised [ThemeMode] name has no reason to also discard a
 * perfectly good [AppSettings.instanceBaseUrlOverride] sitting right next to it in the same row.
 *
 * @param dataStore Usually `context.settingsDataStore`; taken as a parameter rather than a
 *   [Context] directly so this class touches nothing Android beyond the DataStore type itself.
 */
public class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    public constructor(context: Context) : this(context.settingsDataStore)

    override suspend fun load(): AppSettings {
        val json = dataStore.data.first()[SETTINGS_KEY] ?: return AppSettings()
        return try {
            JSON.decodeFromString(StoredSettings.serializer(), json).toCore()
        } catch (_: Exception) {
            // A blob this device itself wrote but can no longer parse. Falling back to the
            // defaults — follow the system, use the build's own instance — is the same choice
            // DataStoreRecentGroupsStore makes for the same reason: a settings row nobody can read
            // is not worth crashing launch over.
            AppSettings()
        }
    }

    override suspend fun save(settings: AppSettings) {
        val json = JSON.encodeToString(StoredSettings.serializer(), StoredSettings.from(settings))
        dataStore.edit { prefs -> prefs[SETTINGS_KEY] = json }
    }
}

@Serializable
private data class StoredSettings(
    val themeMode: String = ThemeMode.FOLLOW_SYSTEM.name,
    val instanceBaseUrlOverride: String? = null,
) {
    fun toCore(): AppSettings = AppSettings(
        // A name this build has never heard of — written by a newer version — degrades to the
        // default rather than throwing: see the class doc for why this field, unlike
        // DataStoreRecentGroupsStore's SplitMode, does not take the rest of the row down with it.
        themeMode = ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.FOLLOW_SYSTEM,
        instanceBaseUrlOverride = instanceBaseUrlOverride,
    )

    companion object {
        fun from(settings: AppSettings): StoredSettings = StoredSettings(
            themeMode = settings.themeMode.name,
            instanceBaseUrlOverride = settings.instanceBaseUrlOverride,
        )
    }
}
