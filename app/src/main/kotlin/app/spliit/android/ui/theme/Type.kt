package app.spliit.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.spliit.android.R

/**
 * Inter, bundled as four static weights rather than the single variable `.ttf` upstream ships -
 * variable-font axis rendering (`FontVariation`) needs API 31, and this app's `minSdk` is 26.
 * Static per-weight files render identically on every supported OS version, at the cost of one
 * file per weight instead of one file total. Only the weights DESIGN.md's type table actually
 * uses are bundled: 400, 500, 600, 700, there is no 300 or 800 role to ask a fifth file for.
 *
 * Not a downloadable font (Google Play Services' Fonts provider): DESIGN.md is explicit that a
 * ledger which reflows when a font arrives late is worse than one that never had it, and a
 * downloaded font is exactly a font that can arrive late, or not at all, on a device without
 * Play Services. Licensed under the SIL Open Font License 1.1, see
 * `app/src/main/assets/licenses/inter_OFL.txt`, bundled unmodified alongside it.
 */
internal val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * DESIGN.md §2's type table, mapped onto M3's named styles rather than reached for by a table of
 * our own, every screen that writes `MaterialTheme.typography.bodyLarge` gets the brief's
 * `body-lg` for free, with no second vocabulary to keep in step with the first.
 *
 * Sizes and line heights are `sp`; tracking is `.em`, spelled the same way DESIGN.md spells it
 * (`-0.02em`, not a hand-converted `sp` value) so the two stay trivially comparable. A handful of
 * M3 slots, `displayMedium`, `titleLarge`, `titleSmall`, `bodySmall`, `headlineSmall`, have no
 * row in DESIGN.md's table at all; each is interpolated from its neighbours rather than left at
 * M3's default Roboto metrics, which would silently reintroduce the one font this screen was
 * asked not to draw in. `headlineSmall` is `Money`'s own `LEAD` size (a suggested payment), see
 * Money.kt, which the brief's table doesn't name directly either.
 */
internal val SpliitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em,
    ),
    // display-amount, a hero balance. Money.HERO borrows this size; see Money.kt.
    displaySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.015).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.0075).em,
    ),
    titleLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em,
    ),
    // title-md, row titles, row amounts.
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.005).em,
    ),
    titleSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.0025).em,
    ),
    // body-lg, reading text.
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    // body-md, descriptions, split detail.
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    // label-lg, buttons.
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.em,
    ),
    // label-md, metadata.
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em,
    ),
    // label-sm, captions, bucket headers.
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.03.em,
    ),
)
