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
 * **Edge to edge, deliberately.** The platform theme this window starts from paints the status
 * bar `?android:colorPrimaryDark` — a flat grey that belongs to no palette in DESIGN.md and sat
 * over the top of every screen. `targetSdk 37` means the platform enforces edge-to-edge anyway,
 * so the app draws its own surface behind both system bars rather than discovering that later.
 * Everything below here reads window insets: the app bars consume the status-bar inset, the
 * scrolling lists add the navigation-bar inset to their content padding, and the sheets pad
 * themselves against it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Loaded synchronously, before setContent, and only once per process — a config change
        // recreates this Activity (and would otherwise re-block on every rotation) but never
        // clears AppSettingsHolder, which lives as long as the process does. The point of doing
        // this here rather than in a LaunchedEffect is the "before": Compose's very first
        // composition below already reads the stored choice, so there is no frame where the app
        // shows the system theme and then snaps to it — see AppSettingsHolder's own doc. The
        // blocking read itself costs a few bytes off a Preferences file that is either absent
        // (fresh install) or already warm in the page cache; not a multi-frame stall.
        if (!settingsLoaded) {
            // `this@MainActivity`, not `this` — inside runBlocking's lambda, `this` is the
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
            // AppSettingsHolder.themeMode is Compose state: this recomposes both the instant
            // MainActivity reads a fresher value (a settings change applies without navigating
            // back here) and, for FOLLOW_SYSTEM, whenever the system theme itself changes —
            // isSystemInDarkTheme() is always called, never behind the `when`, precisely so a
            // user on FOLLOW_SYSTEM still tracks the system living inside a conditional branch
            // would silently stop observing.
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (AppSettingsHolder.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> systemDarkTheme
            }

            // Re-applied whenever the theme flips rather than read once at startup: the *icons*
            // in the status bar are the window's business, not Compose's, and a light scheme
            // with light icons draws an invisible clock. `detectDarkMode` is handed the same
            // boolean SpliitTheme is given, so the two cannot disagree — including when the
            // resolved theme is a user's explicit choice rather than the system's, e.g. Light
            // while the system itself is Dark.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    // Transparent on both sides of the light/dark pair: the app's own surface is
                    // what should show through, not a scrim mixed over it.
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                // `auto` asks the platform to guarantee contrast behind the navigation bar,
                // which it does by painting a translucent scrim over whatever is there —
                // measured at #0C0B0E against a #141218 surface, so the bar still did not match
                // the app. Nothing scrolls under the bar here (both lists add its inset to their
                // content padding on top of the FAB clearance), so there is nothing for that
                // scrim to rescue and it only puts a darker band back where the grey one was.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            SpliitTheme(darkTheme = darkTheme) {
                // `MaterialTheme.colorScheme.background`, not a hardcoded colour — DESIGN.md §1:
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
         *  repeat it — AppSettingsHolder already holds the answer once one process has loaded it. */
        var settingsLoaded: Boolean = false
    }
}
