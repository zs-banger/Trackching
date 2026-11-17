package app.trackching

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import trackching.shared.generated.resources.Res
import trackching.shared.generated.resources.barlow_bold
import trackching.shared.generated.resources.barlow_condensed_bold
import trackching.shared.generated.resources.barlow_condensed_extrabold
import trackching.shared.generated.resources.barlow_condensed_semibold
import trackching.shared.generated.resources.barlow_regular
import trackching.shared.generated.resources.barlow_semibold
import trackching.shared.generated.resources.noto_sans_regular

/** Trackching's fonts, from prototype v59. All of them ship inside the app. */
@Immutable
data class TrackchingFonts(
    val body: FontFamily,      // Barlow: body text, hints, lines under rows
    val condensed: FontFamily, // Barlow Condensed: titles, tags, tab labels, buttons, amounts
    val peso: FontFamily,      // Noto Sans: only for ₱, which Barlow doesn't have
)

/** The phone's default font until TrackchingTheme provides ours (for example, in a bare preview). */
val LocalTrackchingFonts = staticCompositionLocalOf {
    TrackchingFonts(FontFamily.Default, FontFamily.Default, FontFamily.Default)
}

/** Loads the font files. Only TrackchingTheme calls this; screens use TrackchingTheme.fonts. */
@Composable
fun trackchingFonts(): TrackchingFonts = TrackchingFonts(
    body = FontFamily(
        Font(Res.font.barlow_regular, FontWeight.Normal),
        Font(Res.font.barlow_semibold, FontWeight.SemiBold),
        Font(Res.font.barlow_bold, FontWeight.Bold),
    ),
    condensed = FontFamily(
        Font(Res.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(Res.font.barlow_condensed_bold, FontWeight.Bold),
        Font(Res.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
    ),
    peso = FontFamily(Font(Res.font.noto_sans_regular)),
)

/**
 * Material's text styles with our fonts, so Material's own parts match:
 * buttons (labelLarge), the bottom bar (labelMedium), text fields and plain Text (bodyLarge).
 */
fun trackchingTypography(fonts: TrackchingFonts): Typography {
    val m = Typography() // Material's sizes and weights; we only swap the font
    return Typography(
        displayLarge = m.displayLarge.copy(fontFamily = fonts.condensed),
        displayMedium = m.displayMedium.copy(fontFamily = fonts.condensed),
        displaySmall = m.displaySmall.copy(fontFamily = fonts.condensed),
        headlineLarge = m.headlineLarge.copy(fontFamily = fonts.condensed),
        headlineMedium = m.headlineMedium.copy(fontFamily = fonts.condensed),
        headlineSmall = m.headlineSmall.copy(fontFamily = fonts.condensed),
        titleLarge = m.titleLarge.copy(fontFamily = fonts.condensed),
        titleMedium = m.titleMedium.copy(fontFamily = fonts.condensed),
        titleSmall = m.titleSmall.copy(fontFamily = fonts.condensed),
        bodyLarge = m.bodyLarge.copy(fontFamily = fonts.body),
        bodyMedium = m.bodyMedium.copy(fontFamily = fonts.body),
        bodySmall = m.bodySmall.copy(fontFamily = fonts.body),
        labelLarge = m.labelLarge.copy(fontFamily = fonts.condensed),
        labelMedium = m.labelMedium.copy(fontFamily = fonts.condensed),
        labelSmall = m.labelSmall.copy(fontFamily = fonts.condensed),
    )
}
