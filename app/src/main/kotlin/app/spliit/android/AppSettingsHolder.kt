package app.spliit.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.spliit.android.feature.groups.DEFAULT_INSTANCE_BASE_URL
import app.spliit.core.ThemeMode

/**
 * The current settings as plain Compose state. A singleton because [MainActivity] needs the right
 * theme at its *first* composition, and a `LaunchedEffect` runs after that frame, which is the
 * snap from system theme to stored theme this avoids.
 *
 * It carries the instance override too: a screen that creates a group needs the instance *now*,
 * not the one captured when its ViewModel was built.
 */
public object AppSettingsHolder {
    public var themeMode: ThemeMode by mutableStateOf(ThemeMode.FOLLOW_SYSTEM)

    /** Null until Settings has changed it, see [AppSettings.instanceBaseUrlOverride][app.spliit.core.AppSettings]. */
    public var instanceBaseUrlOverride: String? by mutableStateOf(null)

    /** What every screen that creates or resolves a group should treat as "the default
     *  instance", the stored override once there is one, otherwise the build's own default. */
    public val defaultInstanceBaseUrl: String
        get() = instanceBaseUrlOverride ?: DEFAULT_INSTANCE_BASE_URL
}
