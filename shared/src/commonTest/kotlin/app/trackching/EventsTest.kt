package app.trackching

import app.trackching.db.WithTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class EventsTest {
    private val today = LocalDate(2026, 11, 9)
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)

    @Test fun beforeStartIsUpcoming() = assertEquals(EventStatus.Upcoming, statusOf(d(12, 20), d(12, 23), today))
    @Test fun afterEndIsPast() = assertEquals(EventStatus.Past, statusOf(d(10, 1), d(10, 3), today))
    @Test fun inBetweenIsActive() = assertEquals(EventStatus.Active, statusOf(d(11, 1), d(11, 30), today))
    @Test fun startDayIsActive() = assertEquals(EventStatus.Active, statusOf(today, d(11, 12), today))
    @Test fun endDayIsStillActive() = assertEquals(EventStatus.Active, statusOf(d(11, 1), today, today))
    @Test fun noEndIsOngoing() = assertEquals(EventStatus.Active, statusOf(d(1, 1), null, today))
    @Test fun noEndButNotStartedIsUpcoming() = assertEquals(EventStatus.Upcoming, statusOf(d(12, 1), null, today))

    @Test
    fun sectionsAreSortedTheUsefulWay() {
        val rows = listOf(
            WithTotals(1, "Christmas", "2026-12-24", "2026-12-25", 0),
            WithTotals(2, "Baguio", "2026-12-20", "2026-12-23", 0),
            WithTotals(3, "Halloween", "2026-10-31", "2026-10-31", 0),
            WithTotals(4, "Boracay", "2026-08-01", "2026-08-05", 0),
            WithTotals(5, "Laptop fund", "2026-11-01", null, 15_000),
        )
        val split = splitEvents(rows, today)
        assertEquals(listOf("Laptop fund"), split.getValue(EventStatus.Active).map { it.name })
        assertEquals(listOf("Baguio", "Christmas"), split.getValue(EventStatus.Upcoming).map { it.name })
        assertEquals(listOf("Halloween", "Boracay"), split.getValue(EventStatus.Past).map { it.name })
    }

    @Test
    fun rangeLabels() {
        assertEquals("Since Nov 1, 2026", rangeLabel(d(11, 1), null))
        assertEquals("Dec 25, 2026", rangeLabel(d(12, 25), d(12, 25)))
        assertEquals("Dec 20, 2026 – Dec 23, 2026", rangeLabel(d(12, 20), d(12, 23)))
    }

    @Test
    fun lateEveningStaysOnItsDay() {
        // 11:30 PM on Dec 23 in Manila (UTC+8) is still Dec 23 there, even though it's Dec 23 15:30 in UTC.
        val millis = LocalDateTime(2026, 12, 23, 23, 30).toInstant(TimeZone.of("Asia/Manila")).toEpochMilliseconds()
        assertEquals(LocalDate(2026, 12, 23), dayOf(millis, TimeZone.of("Asia/Manila")))
    }
}
