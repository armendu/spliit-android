package app.spliit.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.spliit.android.feature.groups.DEFAULT_INSTANCE_BASE_URL
import app.spliit.core.ThemeMode

public object AppSettingsHolder {
    public var themeMode: ThemeMode by mutableStateOf(ThemeMode.FOLLOW_SYSTEM)

    public var instanceBaseUrlOverride: String? by mutableStateOf(null)

    public val defaultInstanceBaseUrl: String
        get() = instanceBaseUrlOverride ?: DEFAULT_INSTANCE_BASE_URL
}
