package app.trackching

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class PesoFontTest {
    @Test
    fun onlyThePesoSignGetsThePesoFont() {
        val s = withPesoFont("− ₱ 1,232.00", FontFamily.Serif)
        assertEquals("− ₱ 1,232.00", s.text) // the words don't change
        assertEquals(1, s.spanStyles.size)
        val span = s.spanStyles.single()
        assertEquals(2, span.start) // "−", " ", then "₱"
        assertEquals(3, span.end)
        assertEquals(FontFamily.Serif, span.item.fontFamily)
    }

    @Test
    fun hiddenAmountKeepsItsPesoSign() {
        val s = withPesoFont(MaskedAmount, FontFamily.Serif)
        assertEquals(0, s.spanStyles.single().start)
    }

    @Test
    fun textWithoutPesoHasNoSpans() {
        assertEquals(0, withPesoFont("12%", FontFamily.Serif).spanStyles.size)
    }
}
