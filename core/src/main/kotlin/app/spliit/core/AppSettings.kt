package app.spliit.core

public data class AppSettings(
    public val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    public val instanceBaseUrlOverride: String? = null,
)
