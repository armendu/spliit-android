package app.spliit.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spliit.android.AppSettingsHolder
import app.spliit.android.BuildConfig
import app.spliit.android.feature.groups.DEFAULT_INSTANCE_BASE_URL
import app.spliit.android.feature.groups.InstanceAddress
import app.spliit.core.AppSettings
import app.spliit.core.SettingsStore
import app.spliit.core.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Settings screen draws for the "default instance" section. The theme section has no
 * field of its own to spare, [themeMode] is read directly, and choosing an option applies
 * immediately with nothing left to confirm.
 */
data class SettingsUiState(
    val themeMode: ThemeMode,
    /** What the server-address field shows, exactly as typed, kept separate from what is stored
     *  until [SettingsViewModel.saveInstanceAddress] commits it, the same draft/valid split
     *  GroupFormScreen's own server field keeps. */
    val instanceAddressText: String,
    val hasAttemptedSaveInstance: Boolean = false,
    val instanceIsValid: Boolean = true,
    /** Whether the *stored* override is unset, independent of whether the typed text happens to
     *  match the build default mid-edit. Drives whether "Reset to default" has anything to do. */
    val isUsingBuildDefault: Boolean,
)

/**
 * Settings: the theme, the default instance, and the static rows.
 *
 * Seeds from [AppSettingsHolder] rather than loading the store again. `MainActivity` has always
 * loaded it by the time this screen is reachable, and a second asynchronous load would add a
 * frame where the radio selection is wrong before snapping to the true one.
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val buildDefaultInstanceBaseUrl: String = DEFAULT_INSTANCE_BASE_URL,
    initial: AppSettings = AppSettings(
        themeMode = AppSettingsHolder.themeMode,
        instanceBaseUrlOverride = AppSettingsHolder.instanceBaseUrlOverride,
    ),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    /**
     * Applies a change app-wide immediately, rather than once it round-trips through the store:
     * without it, switching the theme would take effect on the *next* launch. Injected so a test
     * can assert it happened without touching a Compose-runtime singleton.
     */
    private val applySettings: (AppSettings) -> Unit = {
        AppSettingsHolder.themeMode = it.themeMode
        AppSettingsHolder.instanceBaseUrlOverride = it.instanceBaseUrlOverride
    },
) : ViewModel() {

    /** The last settings known to be persisted, the base every edit reads and writes back, so
     *  setting the theme does not clobber an instance override saved a moment earlier. */
    private var current: AppSettings = initial

    private val _state = MutableStateFlow(stateFor(initial))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { updateThemeMode(mode) }
    }

    /** The actual work, as a plain suspend function, called through [setThemeMode] in
     *  production (on [viewModelScope], `Main.immediate`, so [applySettings] still runs
     *  synchronously within the click that triggered it), and called directly in tests, the same
     *  split [app.spliit.android.feature.groups.GroupsListViewModel.refresh] documents. */
    internal suspend fun updateThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        persist(current.copy(themeMode = mode))
    }

    fun setInstanceAddressText(text: String) {
        _state.update { it.copy(instanceAddressText = text) }
    }

    fun saveInstanceAddress() {
        viewModelScope.launch { commitInstanceAddress() }
    }

    /**
     * Validates and commits the typed address, through the same [InstanceAddress.normalize] the
     * group form uses. Refused rather than silently kept: a save that quietly does nothing is
     * indistinguishable from one that worked.
     */
    internal suspend fun commitInstanceAddress() {
        val normalized = InstanceAddress.normalize(_state.value.instanceAddressText)
        _state.update { it.copy(hasAttemptedSaveInstance = true, instanceIsValid = normalized != null) }
        if (normalized == null) return
        persist(current.copy(instanceBaseUrlOverride = normalized))
        _state.update {
            it.copy(
                instanceAddressText = normalized,
                isUsingBuildDefault = false,
                hasAttemptedSaveInstance = false,
                instanceIsValid = true,
            )
        }
    }

    fun resetInstanceAddress() {
        viewModelScope.launch { commitInstanceReset() }
    }

    /**
     * The way back to the build default. Clears the stored override rather than merely typing the
     * default text into the field, so this is a real action rather than a save waiting to happen.
     * Touches nothing about a group already on the list, every recent-group row stores its own
     * instance and never reads this one, so changing or resetting the default cannot strand it.
     */
    internal suspend fun commitInstanceReset() {
        persist(current.copy(instanceBaseUrlOverride = null))
        _state.update {
            it.copy(
                instanceAddressText = buildDefaultInstanceBaseUrl,
                isUsingBuildDefault = true,
                hasAttemptedSaveInstance = false,
                instanceIsValid = true,
            )
        }
    }

    private fun stateFor(settings: AppSettings): SettingsUiState = SettingsUiState(
        themeMode = settings.themeMode,
        instanceAddressText = settings.instanceBaseUrlOverride ?: buildDefaultInstanceBaseUrl,
        isUsingBuildDefault = settings.instanceBaseUrlOverride == null,
    )

    private suspend fun persist(settings: AppSettings) {
        current = settings
        applySettings(settings)
        settingsStore.save(settings)
    }
}
