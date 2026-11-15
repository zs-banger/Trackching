package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventRunsTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private fun run(id: Long, first: LocalDate, last: LocalDate) = EventRun(id, 1, first, last)

    private val march = run(1, d(3, 10), d(3, 14))
    private val june = run(2, d(6, 1), d(6, 5))
    private val runs = listOf(march, june)

    @Test
    fun datesInTheGapFit() = assertNull(overlapping(runs, d(4, 1), d(4, 3)))

    @Test
    fun sharingOneDayOverlaps() = assertEquals(march, overlapping(runs, d(3, 14), d(3, 16)))

    @Test
    fun coveringAWholeRunOverlaps() = assertEquals(june, overlapping(runs, d(5, 30), d(6, 10)))

    @Test
    fun aRunNeverOverlapsItself() = assertNull(overlapping(runs, d(3, 10), d(3, 15), except = 1))

    @Test
    fun theErrorSaysWhichDates() {
        assertEquals("These dates overlap Mar 10–14", runError(runs, d(3, 12), d(3, 20)))
        assertEquals("The end date can't be before the start date", runError(runs, d(4, 5), d(4, 1)))
        assertNull(runError(runs, d(4, 1), d(4, 5)))
    }

    @Test
    fun showsTheActiveRunFirst() = assertEquals(june, runToShow(runs, d(6, 3)))

    @Test
    fun elseTheLastFinishedOne() = assertEquals(march, runToShow(runs, d(5, 1)))

    @Test
    fun elseTheFirstUpcomingOne() = assertEquals(march, runToShow(runs, d(1, 1)))

    @Test
    fun noRunsShowNothing() = assertNull(runToShow(emptyList(), d(1, 1)))

    @Test
    fun theListUsesTheRunsDates() {
        val trips = EventRow(1, "Out-of-town trips", d(3, 10), d(6, 5), 0, occasional = true)
        val shown = trips.showingRun(runs, d(5, 1))
        assertEquals(d(3, 10), shown.start)
        assertEquals(d(3, 14), shown.end)
        assertEquals(EventStatus.Past, statusOf(shown.start, shown.end, d(5, 1)))
    }
}
