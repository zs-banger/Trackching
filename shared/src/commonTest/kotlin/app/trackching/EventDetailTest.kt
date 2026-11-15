package app.trackching

import app.trackching.db.Event
import app.trackching.db.History
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventDetailTest {

    private val zone = TimeZone.of("Asia/Manila")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(zone).toEpochMilliseconds()
    private val trip = EventRow(1, "Baguio", LocalDate(2026, 12, 20), LocalDate(2026, 12, 23), 0)

    @Test
    fun readsTheDatesBack() {
        assertEquals(trip.copy(spent = 0), eventRowOf(Event(1, "Baguio", "2026-12-20", "2026-12-23", null, null)))
        assertEquals(null, eventRowOf(Event(2, "Fund", "2026-11-01", null, null, null)).end)
    }

    @Test
    fun bothEndDaysCount() {
        val b = eventBucket(trip, LocalDate(2026, 11, 10), zone)
        assertEquals(at(2026, 12, 20, 0, 0), b.from)
        assertEquals(at(2026, 12, 24, 0, 0), b.until) // all of Dec 23 is inside
        assertTrue(at(2026, 12, 23, 23, 59) < b.until)
    }

    @Test
    fun runningUntilTheLastDayIsOver() {
        assertTrue(eventBucket(trip, LocalDate(2026, 12, 23), zone).running)
        assertFalse(eventBucket(trip, LocalDate(2026, 12, 24), zone).running)
    }

    @Test
    fun ongoingEventsHaveNoEnd() {
        val fund = EventRow(2, "Laptop fund", LocalDate(2026, 11, 1), null, 0)
        val b = eventBucket(fund, LocalDate(2027, 5, 1), zone)
        assertEquals(Long.MAX_VALUE, b.until)
        assertTrue(b.running)
    }

    @Test
    fun onlyTaggedRowsCount() {
        val rows = (1L..4L).map { History(it, "EXPENSE", 100, 0, null, "Cash", null, 1, null) }
        assertEquals(listOf(2L, 4L), rowsOfEvent(rows, setOf(2L, 4L)).map { it.id })
    }

    @Test
    fun editStartsFromTheSavedEvent() {
        val form = EventForm(LocalDate(2026, 11, 10))
        form.loadFrom(trip)
        assertEquals("Baguio", form.name)
        assertEquals(LocalDate(2026, 12, 20), form.start)
        assertEquals(LocalDate(2026, 12, 23), form.end)
        assertTrue(form.canSave)
    }
}
