package app.spliit.android

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import app.spliit.android.data.DataStoreSettingsStore
import app.spliit.android.ui.SpliitNavHost
import app.spliit.android.ui.theme.SpliitTheme
import app.spliit.core.ThemeMode
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!settingsLoaded) {
            val settings = runBlocking { DataStoreSettingsStore(this@MainActivity).load() }
            AppSettingsHolder.themeMode = settings.themeMode
            AppSettingsHolder.instanceBaseUrlOverride = settings.instanceBaseUrlOverride
            settingsLoaded = true
        }

        enableEdgeToEdge()
        setContent {
            // Read outside the `when`, or FOLLOW_SYSTEM stops tracking the system theme.
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (AppSettingsHolder.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> systemDarkTheme
            }

            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            SpliitTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SpliitNavHost()
                }
            }
        }
    }

    private companion object {
        var settingsLoaded: Boolean = false
    }
}
