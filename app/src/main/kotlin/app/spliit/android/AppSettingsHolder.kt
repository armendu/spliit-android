package app.spliit.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.spliit.android.feature.groups.DEFAULT_INSTANCE_BASE_URL
import app.spliit.core.ThemeMode

/**
 * The current settings, held as plain Compose state rather than collected from [SettingsStore]
 * through a `Flow`.
 *
 * **Why a singleton at all.** [MainActivity] must have the *right* [themeMode] at its very first
 * composition — CLAUDE.md/this part's own brief: "the theme must be read before the first
 * composition paints, or the app shows the system theme for a frame and then snaps." A
 * `LaunchedEffect` that loads the store and updates a local `mutableStateOf` runs *after* the
 * first composition, which is exactly the snap this exists to avoid. [MainActivity.onCreate]
 * instead loads [app.spliit.android.data.DataStoreSettingsStore] synchronously, before
 * `setContent`, and writes the result here — so the first frame Compose ever draws already reads
 * the stored choice, with nothing asynchronous between the two.
 *
 * **Why the same object also carries the instance override.** Settings screens (this one, the
 * group form, "add by link") all need "the instance a new group is created on, right now" — not
 * the value that was true when their ViewModel happened to be constructed. The groups-list screen
 * and its "add by link" sheet are scoped to one nav-graph entry that lives for the entire app
 * session, so a value captured once at construction would go stale the moment Settings changed it
 * without an app restart. Reading this property instead — a Compose-state field, or the
 * `AppSettingsHolder.defaultInstanceBaseUrl` getter for non-Compose call sites such as a
 * ViewModel's `submit()` — always answers with what Settings currently says.
 *
 * **Why not the interface `:core` defines instead of a bespoke Android singleton.** `SettingsStore`
 * has no notion of "the current value, updated live" — see its own doc — precisely so `:core` need
 * not depend on a Flow library it otherwise has no use for. This object is the `:app`-side answer
 * to the same question `RecentGroupsViewModel`'s local `snapshot` field answers for the groups
 * list: something has to hold "what's true right now" outside of storage, and it belongs beside
 * `Context`, not inside `:core`.
 */
public object AppSettingsHolder {
    public var themeMode: ThemeMode by mutableStateOf(ThemeMode.FOLLOW_SYSTEM)

    /** Null until Settings has changed it — see [AppSettings.instanceBaseUrlOverride][app.spliit.core.AppSettings]. */
    public var instanceBaseUrlOverride: String? by mutableStateOf(null)

    /** What every screen that creates or resolves a group should treat as "the default
     *  instance" — the stored override once there is one, otherwise the build's own default. */
    public val defaultInstanceBaseUrl: String
        get() = instanceBaseUrlOverride ?: DEFAULT_INSTANCE_BASE_URL
}
