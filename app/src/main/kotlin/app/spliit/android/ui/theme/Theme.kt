package app.spliit.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The tokens DESIGN.md defines that `ColorScheme` has no slot for, see Color.kt. `ColorScheme`
 * has no meaning for "this number is a debt," so these live beside it instead of inside it, and
 * are resolved once per theme change rather than having every use site branch on
 * [isSystemInDarkTheme] for itself, a branch at the use site is a branch somebody will
 * eventually forget (DESIGN.md §3).
 *
 * @property moneyPositiveLarge,[moneyNegativeLarge] The bright ledger tier, DESIGN.md §1:
 *   "large text and non-text only" (>=24px, dots, bars, icons). Never body or label text; see
 *   [moneyPositiveText]/[moneyNegativeText] for why.
 * @property moneyPositiveText,[moneyNegativeText] The darker ledger tier, for a balance drawn at
 *   body or label size, the bright tier measures under the 4.5:1 body threshold (DESIGN.md §1),
 *   so a row- or caption-sized amount in it is a contrast bug, not a style choice.
 */
@Immutable
data class SpliitExtendedColors(
    val moneyPositiveLarge: Color,
    val moneyPositiveText: Color,
    val moneyPositiveBg: Color,
    val moneyNegativeLarge: Color,
    val moneyNegativeText: Color,
    val moneyNegativeBg: Color,
    val brandAccentSoft: Color,
    val monogramPalette: List<Color>,
    /**
     * A tint per category grouping, DESIGN.md §5. Resolved here, with everything else, rather
     * than branched on [isSystemInDarkTheme] at the call site: the light and dark values of one
     * hue are deliberately different colours (the bolt's amber/yellow split), so a use site that
     * picked between them itself would be a second place for that reasoning to go missing.
     * Unknown groupings are absent, and [app.spliit.android.ui.design.CategoryIcon] falls back to
     * the row's own colour for them.
     */
    val categoryGlyphs: Map<String, Color>,
)

private val LocalSpliitColors = staticCompositionLocalOf<SpliitExtendedColors> {
    // A screen reading this outside SpliitTheme is a bug to surface immediately, not a shape to
    // guess a fallback for.
    error("No SpliitExtendedColors provided, wrap the content in SpliitTheme { }.")
}

// Every M3 slot DESIGN.md §1's table actually names is set explicitly; the handful it says
// nothing about (tertiary, inverse*, scrim, …) are left at lightColorScheme()'s own baseline
// rather than guessed at.
private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryLight,
    secondary = SecondaryLight,
    onSecondary = OnPrimaryLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    outline = BorderSubtleLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorLight,
    onError = OnPrimaryLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

// Dark surfaces are M3's own darkColorScheme() defaults, seeded only by `primary`, DESIGN.md §1
// supplies a light palette and says dark is *derived*, not a second hand-picked table. See
// Color.kt's own note on PrimaryDark for the one pair that is chosen rather than defaulted.
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryDark,
    // Pinned rather than left to the baseline, because a measured pair that a BOM bump could
    // move is not a measured pair, see Color.kt for the numbers.
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

private val LightExtendedColors = SpliitExtendedColors(
    moneyPositiveLarge = BalancePositiveLight,
    moneyPositiveText = BalancePositiveTextLight,
    moneyPositiveBg = BalancePositiveBgLight,
    moneyNegativeLarge = BalanceNegativeLight,
    moneyNegativeText = BalanceNegativeTextLight,
    moneyNegativeBg = BalanceNegativeBgLight,
    brandAccentSoft = BrandAccentSoftLight,
    monogramPalette = MonogramColors,
    categoryGlyphs = CategoryGlyphColorsLight,
)

private val DarkExtendedColors = SpliitExtendedColors(
    // A single tone each in dark mode, DESIGN.md §1 lightens the axis to clear AA on a dark
    // surface rather than asking for a second large/text split, which the light palette needs
    // only because white forces it (see Color.kt).
    moneyPositiveLarge = BalancePositiveDark,
    moneyPositiveText = BalancePositiveDark,
    moneyPositiveBg = BalancePositiveDark.copy(alpha = 0.16f),
    moneyNegativeLarge = BalanceNegativeDark,
    moneyNegativeText = BalanceNegativeDark,
    moneyNegativeBg = BalanceNegativeDark.copy(alpha = 0.16f),
    brandAccentSoft = PrimaryDark.copy(alpha = 0.16f),
    monogramPalette = MonogramColors,
    categoryGlyphs = CategoryGlyphColorsDark,
)

/**
 * Mirrors [MaterialTheme]'s own accessor shape, so a call site reads `SpliitTheme.colors.…`
 * beside `MaterialTheme.colorScheme.…` instead of importing a bare top-level property.
 */
object SpliitTheme {
    val colors: SpliitExtendedColors
        @Composable get() = LocalSpliitColors.current
}

/**
 * The app's Material 3 theme, in light and dark, DESIGN.md's "Emerald Ledger".
 *
 * Deliberately offers no dynamic-colour (Android 12+ wallpaper-derived) option. Two independent
 * reasons, both from DESIGN.md §1: `primary` is chosen for a specific measured contrast against
 * white label text (6.74:1, distinct from the logo's own green precisely so that holds), which a
 * wallpaper seed cannot promise; and the ledger axis's AA tuning is pinned to these exact hexes,
 * not to whatever primary a photo produces. Static schemes only.
 */
@Composable
fun SpliitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalSpliitColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SpliitTypography,
            content = content,
        )
    }
}
