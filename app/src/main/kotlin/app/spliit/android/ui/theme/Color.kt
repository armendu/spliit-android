package app.spliit.android.ui.theme

import androidx.compose.ui.graphics.Color

// Not the logo green #059669: white text on that fails AA. Keep them different.
internal val PrimaryLight = Color(0xFF006948)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFF00855D)
internal val SecondaryLight = Color(0xFF565E74)

internal val SurfaceLight = Color(0xFFFBF8FF)
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val SurfaceContainerLowLight = Color(0xFFF4F2FD)
internal val SurfaceContainerLight = Color(0xFFF4F4F5)
internal val SurfaceContainerHighLight = Color(0xFFE8E7F1)

internal val OnSurfaceLight = Color(0xFF1A1B22)
internal val OnSurfaceVariantLight = Color(0xFF3D4A42)
internal val OutlineVariantLight = Color(0xFFBCCAC0)
internal val BorderSubtleLight = Color(0xFFE4E4E7)
internal val ErrorLight = Color(0xFFBA1A1A)

internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF93000A)

internal val ErrorContainerDark = Color(0xFF4A1113)
internal val OnErrorContainerDark = Color(0xFFFFB4AB)

internal val PrimaryDark = Color(0xFF68DBA9)
internal val OnPrimaryDark = Color(0xFF002114)
internal val PrimaryContainerDark = Color(0xFF00855D)

internal val BrandAccentSoftLight = Color(0xFFE6F5EF)

internal val BalancePositiveLight = Color(0xFF16A34A)
internal val BalancePositiveTextLight = Color(0xFF15803D)
internal val BalancePositiveBgLight = Color(0xFFDCFCE7)
internal val BalanceNegativeLight = Color(0xFFE11D48)
internal val BalanceNegativeTextLight = Color(0xFFBE123C)
internal val BalanceNegativeBgLight = Color(0xFFFFE4E6)

internal val BalancePositiveDark = Color(0xFF4ADE80)
internal val BalanceNegativeDark = Color(0xFFFDA4AF)

internal val MonogramColors = listOf(
    Color(0xFF06805F),
    Color(0xFF0E7490),
    Color(0xFF4F46E5),
    Color(0xFFDB2777),
    Color(0xFFC2410C),
    Color(0xFFB45309),
    Color(0xFF6B7A1F),
    Color(0xFF7C3AED),
)

// Light and dark hues differ on purpose, for contrast on each tile. Don't match them up.
internal val CategoryGlyphColorsLight = mapOf(
    "Utilities" to Color(0xFFB45309),
    "Uncategorized" to Color(0xFF15803D),
    "Food and Drink" to Color(0xFFC2410C),
    "Transportation" to Color(0xFF1D4ED8),
    "Entertainment" to Color(0xFF7E22CE),
    "Home" to Color(0xFF0F766E),
    "Life" to Color(0xFFBE123C),
)

internal val CategoryGlyphColorsDark = mapOf(
    "Utilities" to Color(0xFFFBBF24),
    "Uncategorized" to Color(0xFF4ADE80),
    "Food and Drink" to Color(0xFFFB923C),
    "Transportation" to Color(0xFF7DB3FF),
    "Entertainment" to Color(0xFFC4A0F5),
    "Home" to Color(0xFF5EEAD4),
    "Life" to Color(0xFFFDA4AF),
)
