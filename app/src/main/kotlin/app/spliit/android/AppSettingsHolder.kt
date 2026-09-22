package app.spliit.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.spliit.android.feature.groups.DEFAULT_INSTANCE_BASE_URL
import app.spliit.core.ThemeMode

/**
 * The current settings, held as plain Compose state rather than collected from [SettingsStore].
 *
 * A singleton because [MainActivity] needs the right [themeMode] at its *first* composition. A
 * `LaunchedEffect` runs after that first frame, which is the snap from system theme to stored
 * theme this exists to avoid. `onCreate` loads the store synchronously before `setContent` and
 * writes the result here.
 *
 * It carries the instance override too, because every screen that creates a group needs "the
 * instance right now", not the value that was true when its ViewModel was constructed. The
 * groups-list entry lives for the whole session, so a captured value goes stale the moment
 * Settings changes it.
 *
 * `:core`'s [SettingsStore] has no notion of a live current value, deliberately, so that it need
 * not depend on a Flow library. Holding that is this object's job, and it belongs beside
 * `Context`.
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
