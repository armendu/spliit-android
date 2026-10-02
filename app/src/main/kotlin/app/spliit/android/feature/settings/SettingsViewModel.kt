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

data class SettingsUiState(
    val themeMode: ThemeMode,
    val instanceAddressText: String,
    val hasAttemptedSaveInstance: Boolean = false,
    val instanceIsValid: Boolean = true,
    val isUsingBuildDefault: Boolean,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val buildDefaultInstanceBaseUrl: String = DEFAULT_INSTANCE_BASE_URL,
    initial: AppSettings = AppSettings(
        themeMode = AppSettingsHolder.themeMode,
        instanceBaseUrlOverride = AppSettingsHolder.instanceBaseUrlOverride,
    ),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    private val applySettings: (AppSettings) -> Unit = {
        AppSettingsHolder.themeMode = it.themeMode
        AppSettingsHolder.instanceBaseUrlOverride = it.instanceBaseUrlOverride
    },
) : ViewModel() {
    private var current: AppSettings = initial

    private val _state = MutableStateFlow(stateFor(initial))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { updateThemeMode(mode) }
    }

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
