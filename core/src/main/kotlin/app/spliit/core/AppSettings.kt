package app.spliit.core

/**
 * Everything the Settings screen persists. One per device, replaced whole on every save.
 *
 * @property instanceBaseUrlOverride Null means "use the build default", not "no server": `:core`
 *   cannot name that default, it is a `BuildConfig` field. Groups already on the list carry their
 *   own [RecentGroup.instanceBaseUrl] and are unaffected.
 */
public data class AppSettings(
    public val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    public val instanceBaseUrlOverride: String? = null,
)
