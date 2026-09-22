package app.spliit.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DESIGN.md §1's palette, "Emerald Ledger". Light values are the brief's own, measured for WCAG
 * AA by the brief itself (see its own prose for the exact ratios); dark values are *derived*
 * where the brief says to derive them, not invented, see the per-token notes below.
 */

// ---- core ------------------------------------------------------------------------------------

// The brand green (#059669, the logo) and the UI primary are deliberately different colours.
// White label text sits on primary everywhere, every button, the FAB, and white-on-#059669
// measures 3.77:1, below AA's 4.5:1 body threshold. #006948 measures 6.74:1. The logo keeps its
// own green; the interface uses the darker one. Do not "correct" one to the other.
internal val PrimaryLight = Color(0xFF006948)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFF00855D)
internal val SecondaryLight = Color(0xFF565E74)

internal val SurfaceLight = Color(0xFFFBF8FF) // surface / background, the canvas
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF) // cards and modules
internal val SurfaceContainerLowLight = Color(0xFFF4F2FD) // nested blocks inside a card
internal val SurfaceContainerLight = Color(0xFFF4F4F5) // chips, inert tiles
internal val SurfaceContainerHighLight = Color(0xFFE8E7F1) // pressed and selected chrome

internal val OnSurfaceLight = Color(0xFF1A1B22)
internal val OnSurfaceVariantLight = Color(0xFF3D4A42)
internal val OutlineVariantLight = Color(0xFFBCCAC0) // hairline dividers
internal val BorderSubtleLight = Color(0xFFE4E4E7) // card outlines, M3's `outline` slot
internal val ErrorLight = Color(0xFFBA1A1A)

// The tonal error pair, for a destructive action that is offered rather than announced.
// Measured: #93000A on #FFDAD6 is 7.24:1. The container on the light canvas is 1.23:1, a tint
// rather than a boundary, which is why the button keeps a text label and a divider above it.
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF93000A)

// Dark mirrors the relationship, not the hexes: #4A1113 is 1.22:1 on the dark canvas, matching
// the light pair's 1.23:1. M3's baseline #93000A was rejected at 1.99:1, a solid red bar; its
// label measured fine, so this is about weight rather than contrast.
internal val ErrorContainerDark = Color(0xFF4A1113)
internal val OnErrorContainerDark = Color(0xFFFFB4AB)

// Dark: the brief supplies a light palette only, so surfaces are M3's dark defaults seeded by
// `primary`, which is overridden to a lighter tone with a dark `onPrimary`. These replace
// #00855D on #003D29, which measured 2.66:1 under a comment claiming ">8:1"; now 10.01:1.
internal val PrimaryDark = Color(0xFF68DBA9) // the brief's `inverse-primary`
internal val OnPrimaryDark = Color(0xFF002114) // the brief's `on-primary-fixed`
internal val PrimaryContainerDark = Color(0xFF00855D)

// The tile an empty state's icon sits in. A curated tint rather than "primary at N% opacity"
// composited over an arbitrary background, which is a measurement nobody made.
internal val BrandAccentSoftLight = Color(0xFFE6F5EF)

// ---- the ledger axis ---------------------------------------------------------------------

// Two tokens per side: the bright pair measures 3.30:1 against white, short of the 4.5:1 body
// threshold, so the darker pair (5.02:1 / 4.70:1) carries an amount at row or label size.
// Reusing the bright pair for body text is the mistake this pairing prevents.
internal val BalancePositiveLight = Color(0xFF16A34A) // large text (>=24px) and non-text only
internal val BalancePositiveTextLight = Color(0xFF15803D) // body and label sizes
internal val BalancePositiveBgLight = Color(0xFFDCFCE7)
internal val BalanceNegativeLight = Color(0xFFE11D48)
internal val BalanceNegativeTextLight = Color(0xFFBE123C)
internal val BalanceNegativeBgLight = Color(0xFFFFE4E6)

// Dark: lightened to clear AA on a dark surface, per DESIGN.md §1's own instruction, a single
// tone each, since a dark surface (unlike white) does not force the same large/body split; both
// measure comfortably past 4.5:1 against M3's baseline dark surface (~#141218).
internal val BalancePositiveDark = Color(0xFF4ADE80)
internal val BalanceNegativeDark = Color(0xFFFDA4AF)

/**
 * Eight colours for participant monograms, keyed by a stable hash of the participant id. Chosen
 * for >=4.5:1 against the white initials. A monogram sits on its own solid circle, so it needs
 * no light/dark tuning.
 */
internal val MonogramColors = listOf(
    Color(0xFF06805F), // emerald
    Color(0xFF0E7490), // cyan
    Color(0xFF4F46E5), // indigo
    Color(0xFFDB2777), // pink
    Color(0xFFC2410C), // orange
    Color(0xFFB45309), // amber
    Color(0xFF6B7A1F), // olive
    Color(0xFF7C3AED), // violet
)

// ---- category glyphs ---------------------------------------------------------------------

/**
 * One hue per category grouping, measured against the tile the glyph sits on.
 *
 * Light and dark values of a hue differ, and the bolt is why: #FBBF24 is 1.52:1 on the light tile
 * and invisible, even #D97706 is 2.90:1, under the 3:1 a non-text graphic needs. **Do not match
 * them up.** Keyed by grouping, so a category invented later falls back consistently.
 */
internal val CategoryGlyphColorsLight = mapOf(
    "Utilities" to Color(0xFFB45309), // deep amber, not yellow, see above
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
