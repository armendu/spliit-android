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
 * The tokens DESIGN.md defines that `ColorScheme` has no slot for, such as "this number is a
 * debt". Resolved once per theme change rather than branched on [isSystemInDarkTheme] at each
 * use site, which is a branch somebody eventually forgets.
 *
 * @property moneyPositiveLarge,[moneyNegativeLarge] The bright ledger tier, >=24px and non-text
 *   marks only. Never body or label text.
 * @property moneyPositiveText,[moneyNegativeText] The darker tier, for a balance at body size.
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
     * A tint per category grouping. Resolved here rather than at the call site because the light
     * and dark values of one hue are deliberately different colours. Unknown groupings are
     * absent, and [app.spliit.android.ui.design.CategoryIcon] falls back to the row's colour.
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
 * The app's Material 3 theme, DESIGN.md's "Emerald Ledger".
 *
 * No dynamic colour, deliberately: `primary` is chosen for a measured 6.74:1 against white label
 * text, and the ledger axis is tuned to these exact hexes. A wallpaper seed promises neither.
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
