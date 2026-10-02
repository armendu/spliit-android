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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

private val SETTINGS_KEY = stringPreferencesKey("settings")

public class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    public constructor(context: Context) : this(context.settingsDataStore)

    override suspend fun load(): AppSettings {
        val json = try {
            dataStore.data.first()[SETTINGS_KEY]
        } catch (_: IOException) {
            return AppSettings()
        } ?: return AppSettings()
        return try {
            StoredJson.decodeFromString(StoredSettings.serializer(), json).toCore()
        } catch (_: Exception) {
            AppSettings()
        }
    }

    override suspend fun save(settings: AppSettings) {
        val json = StoredJson.encodeToString(StoredSettings.serializer(), StoredSettings.from(settings))
        try {
            dataStore.edit { prefs -> prefs[SETTINGS_KEY] = json }
        } catch (_: IOException) {
        }
    }
}

@Serializable
private data class StoredSettings(
    val themeMode: String = ThemeMode.FOLLOW_SYSTEM.name,
    val instanceBaseUrlOverride: String? = null,
) {
    fun toCore(): AppSettings = AppSettings(
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
