package app.spliit.core

/**
 * Everything the Settings screen persists, in one row.
 *
 * One value, not a list, unlike [RecentGroupsSnapshot] there is exactly one of these per device,
 * so none of that type's merge, tombstone or ordering machinery applies; [SettingsStore] simply
 * replaces the whole thing on every [SettingsStore.save].
 *
 * @property themeMode See [ThemeMode]. Defaults to [ThemeMode.FOLLOW_SYSTEM] so a device that has
 *   never written a settings row behaves exactly as the app already does.
 * @property instanceBaseUrlOverride Where a new group is created, and what a link naming no server
 *  , a bare group ID, is resolved against, when it has been changed from the build's own
 *   default. Null means "use the build default," not "no server": `:core` does not know what that
 *   default *is* (`DEFAULT_INSTANCE_BASE_URL` reads a `BuildConfig` field, which is an `:app`
 *   concept, see `InstanceAddress.kt`), so the override is nullable rather than defaulted to a
 *   URL this module would have to invent. A group already on the list is unaffected either way:
 *   see [RecentGroup.instanceBaseUrl], which every row carries for itself.
 */
public data class AppSettings(
    public val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    public val instanceBaseUrlOverride: String? = null,
)
