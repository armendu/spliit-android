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
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** A separate file from `recentGroupsDataStore`, one row of app-wide settings has nothing to do
 *  with the list of groups, and giving it its own file means a corrupt or downgraded settings blob
 *  (see [DataStoreSettingsStore.load]) can never take the groups list down with it, or the other
 *  way around. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

private val SETTINGS_KEY = stringPreferencesKey("settings")

/**
 * The AndroidX DataStore–backed [SettingsStore].
 *
 * Unlike [DataStoreRecentGroupsStore], a row that fails to decode falls back per field rather
 * than discarding the whole blob: an unrecognised [ThemeMode] has no reason to take a perfectly
 * good [AppSettings.instanceBaseUrlOverride] with it.
 *
 * @param dataStore Usually `context.settingsDataStore`, taken as a parameter so this class
 *   touches nothing Android beyond the DataStore type.
 */
public class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    public constructor(context: Context) : this(context.settingsDataStore)

    override suspend fun load(): AppSettings {
        // See DataStoreRecentGroupsStore.load: the read can throw, not just the decode.
        val json = try {
            dataStore.data.first()[SETTINGS_KEY]
        } catch (_: IOException) {
            return AppSettings()
        } ?: return AppSettings()
        return try {
            StoredJson.decodeFromString(StoredSettings.serializer(), json).toCore()
        } catch (_: Exception) {
            // A blob this device itself wrote but can no longer parse. Falling back to the
            // defaults, follow the system, use the build's own instance, is the same choice
            // DataStoreRecentGroupsStore makes for the same reason: a settings row nobody can read
            // is not worth crashing launch over.
            AppSettings()
        }
    }

    /** Silently no-ops on an I/O failure: a theme that did not persist is worth less than the
     *  crash that reporting it from `viewModelScope` would otherwise cause. */
    override suspend fun save(settings: AppSettings) {
        val json = StoredJson.encodeToString(StoredSettings.serializer(), StoredSettings.from(settings))
        try {
            dataStore.edit { prefs -> prefs[SETTINGS_KEY] = json }
        } catch (_: IOException) {
            // Nothing to say and nowhere to say it; the value stays in AppSettingsHolder.
        }
    }
}

@Serializable
private data class StoredSettings(
    val themeMode: String = ThemeMode.FOLLOW_SYSTEM.name,
    val instanceBaseUrlOverride: String? = null,
) {
    fun toCore(): AppSettings = AppSettings(
        // A name this build has never heard of, written by a newer version, degrades to the
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
