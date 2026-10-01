package app.spliit.core

public interface SettingsStore {
    public suspend fun load(): AppSettings

    public suspend fun save(settings: AppSettings)
}
