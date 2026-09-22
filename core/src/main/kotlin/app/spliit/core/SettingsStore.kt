package app.spliit.core

/**
 * Where [AppSettings] lives. Same split as [RecentGroupsStore], for the same reason: `:core` owns
 * the shape and decides nothing about storage; `:app` supplies a real DataStore behind it, and
 * must stay **thin**, serialise, write, read back, and nothing else. See [RecentGroupsStore]'s
 * own doc for why that boundary matters.
 *
 * Suspending functions rather than a `Flow`, again for the same reason as [RecentGroupsStore]:
 * `:core` pulls in no dependency beyond the Kotlin stdlib, and a single settings row has no
 * "watch for changes made elsewhere" use case of its own to justify one. `:app` already applies a
 * change the moment it is made (see `AppSettingsHolder`), so nothing in this app actually needs to
 * *observe* this store, only load it once and write to it.
 */
public interface SettingsStore {
    /** The settings as currently stored, or [AppSettings()][AppSettings] for a device that has
     *  never written any, which is also a fresh install's correct behaviour. */
    public suspend fun load(): AppSettings

    /** Replaces the stored settings with [settings], in full. */
    public suspend fun save(settings: AppSettings)
}
