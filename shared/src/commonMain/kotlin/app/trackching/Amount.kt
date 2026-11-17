package app.trackching

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Shows an amount as "− ₱ 1,232.00" on one line, in Barlow Condensed.
 * If it doesn't fit, the text shrinks (down to 12 sp) instead of being cut off.
 */
@Composable
fun Amount(
    centavos: Long,
    kind: AmountKind,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 32.sp,
    alwaysShow: Boolean = false,
) {
    val fonts = TrackchingTheme.fonts
    val text = formatAmount(centavos, kind)
    BasicText(
        text = withPesoFont(if (alwaysShow) text else shown(text), fonts.peso),
        modifier = modifier,
        style = TextStyle(
            fontFamily = fonts.condensed,
            fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = maxFontSize),
    )
}

/** Draws every ₱ in [peso], because Barlow has no ₱ sign. Pure, so it's easy to test. */
fun withPesoFont(text: String, peso: FontFamily): AnnotatedString = buildAnnotatedString {
    text.forEach { ch ->
        if (ch == '₱') withStyle(SpanStyle(fontFamily = peso)) { append(ch) } else append(ch)
    }
}
