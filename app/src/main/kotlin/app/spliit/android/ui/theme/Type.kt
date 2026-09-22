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
 * Inter, bundled as four static weights rather than one variable `.ttf`: `FontVariation` needs
 * API 31 and `minSdk` is 26. Only the weights the type table uses, 400/500/600/700.
 *
 * Not a downloadable font: one that arrives late reflows the ledger, and on a device without
 * Play Services it never arrives. SIL Open Font License 1.1, see `assets/licenses/inter_OFL.txt`.
 */
internal val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * DESIGN.md §2's type table on M3's named styles, so there is no second vocabulary to keep in
 * step. Tracking is spelled in `.em` the way the brief spells it, so the two stay comparable.
 *
 * The M3 slots the table has no row for are interpolated from their neighbours rather than left
 * at M3's Roboto defaults, which would reintroduce the one font this app does not draw in.
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
