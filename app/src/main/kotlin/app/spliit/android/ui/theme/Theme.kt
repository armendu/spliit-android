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
    val categoryGlyphs: Map<String, Color>,
)

private val LocalSpliitColors = staticCompositionLocalOf<SpliitExtendedColors> {
    error("No SpliitExtendedColors provided, wrap the content in SpliitTheme { }.")
}

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

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryDark,
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

object SpliitTheme {
    val colors: SpliitExtendedColors
        @Composable get() = LocalSpliitColors.current
}

// No dynamic colour: the palette is tuned for contrast and a wallpaper seed isn't.
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
