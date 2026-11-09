package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventFormTest {
    private val nov9 = LocalDate(2026, 11, 9)

    @Test
    fun needsAName() {
        val form = EventForm(nov9)
        assertFalse(form.canSave)
        form.name = "  "
        assertFalse(form.canSave)
        form.name = "Baguio trip"
        assertTrue(form.canSave)
    }

    @Test
    fun startsTodayWithNoEnd() {
        val form = EventForm(nov9)
        assertEquals(nov9, form.start)
        assertNull(form.end)
    }

    @Test
    fun endBeforeStartIsBlocked() {
        val form = EventForm(nov9).apply { name = "Trip"; end = LocalDate(2026, 11, 8) }
        assertNotNull(form.error)
        assertFalse(form.canSave)
    }

    @Test
    fun sameDayIsFine() {
        val form = EventForm(nov9).apply { name = "Party"; end = nov9 }
        assertNull(form.error)
        assertTrue(form.canSave)
    }

    @Test
    fun labelReadsLikeADate() {
        assertEquals("Dec 20, 2026", dateLabel(LocalDate(2026, 12, 20)))
    }
}
