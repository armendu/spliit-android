package app.spliit.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DESIGN.md §1's palette — "Emerald Ledger". Light values are the brief's own, measured for WCAG
 * AA by the brief itself (see its own prose for the exact ratios); dark values are *derived*
 * where the brief says to derive them, not invented — see the per-token notes below.
 */

// ---- core ------------------------------------------------------------------------------------

// The brand green (#059669, the logo) and the UI primary are deliberately different colours.
// White label text sits on primary everywhere — every button, the FAB — and white-on-#059669
// measures 3.77:1, below AA's 4.5:1 body threshold. #006948 measures 6.74:1. The logo keeps its
// own green; the interface uses the darker one. Do not "correct" one to the other.
internal val PrimaryLight = Color(0xFF006948)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFF00855D)
internal val SecondaryLight = Color(0xFF565E74)

internal val SurfaceLight = Color(0xFFFBF8FF) // surface / background — the canvas
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF) // cards and modules
internal val SurfaceContainerLowLight = Color(0xFFF4F2FD) // nested blocks inside a card
internal val SurfaceContainerLight = Color(0xFFF4F4F5) // chips, inert tiles
internal val SurfaceContainerHighLight = Color(0xFFE8E7F1) // pressed and selected chrome

internal val OnSurfaceLight = Color(0xFF1A1B22)
internal val OnSurfaceVariantLight = Color(0xFF3D4A42)
internal val OutlineVariantLight = Color(0xFFBCCAC0) // hairline dividers
internal val BorderSubtleLight = Color(0xFFE4E4E7) // card outlines — M3's `outline` slot
internal val ErrorLight = Color(0xFFBA1A1A)

// The tonal error pair, for a destructive action that is offered rather than announced — the
// delete button on the expense form. DESIGN.md §1 names `error-container` #FFDAD6 with
// `on-error-container` #93000A; a filled `error` behind white shouts at somebody who has merely
// scrolled to the bottom of a form.
//
// Measured, not asserted: #93000A on #FFDAD6 is **7.24:1**, well past the 4.5:1 a button label
// needs. The container against the light canvas #FBF8FF is 1.23:1 — that is a tint, not a
// boundary, which is why the button keeps a visible text label and a divider above it rather
// than relying on its own edge to be found.
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF93000A)

// Dark mirrors the *relationship*, not the hexes — DESIGN.md §1's instruction for dark is
// "derived", and what carries over is a faint tint of the canvas toward the error hue, carrying
// a label in the tone that theme uses to mean error.
//
// Measured against the light pair it mirrors: #FFDAD6 on the light canvas #FBF8FF is **1.23:1**,
// and #4A1113 on M3's dark canvas (~#141218) is **1.22:1** — the same strength of tint, read the
// other way up. The label, #FFB4AB (M3's own dark `error` tone), measures **8.96:1** on it.
//
// M3's baseline dark pair — container #93000A, label #FFDAD6 — was tried first and rejected on
// looking at it: #93000A is **1.99:1** against the canvas, nearly twice the tint the light side
// carries, and on screen it is a solid red bar, which is exactly the shout this button is not
// supposed to be. Its label measured 7.24:1, so the rejection is about weight, not contrast.
internal val ErrorContainerDark = Color(0xFF4A1113)
internal val OnErrorContainerDark = Color(0xFFFFB4AB)

// Dark: the brief supplies a light palette only, so surfaces here are M3's own dark-scheme
// defaults seeded by `primary` (see Theme.kt's darkColorScheme call) rather than a hand-picked
// hex per slot — hand-tuning eleven more surface tones with no brief to check them against would
// be inventing a palette DESIGN.md never asked for. Only `primary` itself is overridden: M3's
// usual light-on-dark pattern for a saturated primary is a *lighter* tone of the same hue, paired
// with a dark `onPrimary` rather than white.
//
// These are the brief's own tokens, not invented ones: `inverse-primary` in Material 3 *means*
// primary as it appears in the opposite theme, so the light palette already names what dark's
// primary should be, and `on-primary-fixed` names the label that belongs on it.
//
// They replace a pair that failed twice over, measured rather than argued: `#00855D` on
// `#003D29` is **2.66:1**, nowhere near the 4.5:1 a button label needs — and the comment that
// used to sit here claimed ">8:1", which was simply wrong. The same `#00855D` as accent *text*
// on the dark canvas measured 3.68:1, so every green label failed too, not just the ones inside
// a filled button. The replacements are 10.01:1 for the label on primary and 10.03:1 for primary
// on the canvas.
internal val PrimaryDark = Color(0xFF68DBA9) // the brief's `inverse-primary`
internal val OnPrimaryDark = Color(0xFF002114) // the brief's `on-primary-fixed`
internal val PrimaryContainerDark = Color(0xFF00855D)

// The tile an empty state's icon sits in — not one of DESIGN.md's named tokens (its table stops
// at `primary-container`), but the same soft-tint role the icon tile has always played. A curated
// tint rather than a computed one, the way the accent's own soft tile always has been: measured,
// not "primary at N% opacity" composited over an arbitrary background.
internal val BrandAccentSoftLight = Color(0xFFE6F5EF)

// ---- the ledger axis ---------------------------------------------------------------------

// Two tokens per side, not one — DESIGN.md §1 is explicit about why. The bright pair reads fine
// at large sizes and on non-text marks (bars, dots, icons) but measures only 3.30:1 / 3.30:1
// against white, short of the 4.5:1 body-text threshold; the darker pair (5.02:1 / 4.70:1) is what
// carries an amount at row or label size. Reusing the bright pair for body text is the mistake
// this pairing exists to prevent.
internal val BalancePositiveLight = Color(0xFF16A34A) // large text (>=24px) and non-text only
internal val BalancePositiveTextLight = Color(0xFF15803D) // body and label sizes
internal val BalancePositiveBgLight = Color(0xFFDCFCE7)
internal val BalanceNegativeLight = Color(0xFFE11D48)
internal val BalanceNegativeTextLight = Color(0xFFBE123C)
internal val BalanceNegativeBgLight = Color(0xFFFFE4E6)

// Dark: lightened to clear AA on a dark surface, per DESIGN.md §1's own instruction — a single
// tone each, since a dark surface (unlike white) does not force the same large/body split; both
// measure comfortably past 4.5:1 against M3's baseline dark surface (~#141218).
internal val BalancePositiveDark = Color(0xFF4ADE80)
internal val BalanceNegativeDark = Color(0xFFFDA4AF)

/**
 * Eight colours for participant monograms — DESIGN.md's closing note: "Participants are
 * identified by monograms on the eight-colour palette, keyed by a stable hash of the participant
 * id." Unchanged from the values [app.spliit.android.ui.design.MonogramPalette] already hashes
 * into: chosen for >=4.5:1 against the white initials drawn on top, and a monogram sits on its
 * own solid circle rather than on a themed surface, so it does not need separate light/dark
 * tuning the way the ledger axis does.
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
