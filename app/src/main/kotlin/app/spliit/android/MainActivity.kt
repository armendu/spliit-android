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

/**
 * The single activity. Navigation Compose owns everything above it.
 *
 * **Edge to edge, deliberately**: the platform theme paints the status bar a flat grey belonging
 * to no palette in DESIGN.md, and `targetSdk 37` enforces edge-to-edge anyway. Everything below
 * reads window insets accordingly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Synchronously, before setContent, once per process: the first composition already
        // reads the stored choice, so there is no frame showing the system theme first. A config
        // change recreates the Activity but never clears AppSettingsHolder, so this does not
        // re-block on rotation.
        if (!settingsLoaded) {
            // `this@MainActivity`, not `this`, inside runBlocking's lambda, `this` is the
            // CoroutineScope it hands the block, and DataStoreSettingsStore wants a Context.
            val settings = runBlocking { DataStoreSettingsStore(this@MainActivity).load() }
            AppSettingsHolder.themeMode = settings.themeMode
            AppSettingsHolder.instanceBaseUrlOverride = settings.instanceBaseUrlOverride
            settingsLoaded = true
        }

        // Before the first frame, so the launch animation never shows the grey bar. The call
        // inside the composition below then keeps the bars in step with the theme.
        enableEdgeToEdge()
        setContent {
            // isSystemInDarkTheme() is called unconditionally, never behind the `when`: a read
            // living inside a branch would silently stop observing, and FOLLOW_SYSTEM has to
            // keep tracking the system.
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (AppSettingsHolder.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> systemDarkTheme
            }

            // Re-applied on every theme flip, not read once: status-bar icons are the window's
            // business, and a light scheme with light icons draws an invisible clock.
            // `detectDarkMode` gets the same boolean SpliitTheme does, so they cannot disagree.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    // Transparent on both sides of the light/dark pair: the app's own surface is
                    // what should show through, not a scrim mixed over it.
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                // `auto` guarantees contrast by painting a scrim, measured at #0C0B0E against a
                // #141218 surface, so the bar still did not match the app. Nothing scrolls under
                // the bar here, so the scrim has nothing to rescue and only puts the band back.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            SpliitTheme(darkTheme = darkTheme) {
                // `MaterialTheme.colorScheme.background`, not a hardcoded colour, DESIGN.md §1:
                // the day this app has a real background token of its own, ColorScheme still
                // owns it. This is also what now draws behind the status bar.
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
        /** Guards the blocking read above so a rotation or other Activity recreation doesn't
         *  repeat it, AppSettingsHolder already holds the answer once one process has loaded it. */
        var settingsLoaded: Boolean = false
    }
}
