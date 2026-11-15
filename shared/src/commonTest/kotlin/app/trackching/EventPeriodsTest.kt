package app.trackching

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EventPeriodsTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private val weekly = EventRepeat(1, RepeatUnit.WEEK)

    @Test
    fun weeklyLinesUpWithMonday() {
        // Started on Wednesday Nov 11; weeks start on Monday, so the first period is Nov 9–15.
        val periods = EventPeriods(d(11, 11), weekly, DayOfWeek.MONDAY)
        assertEquals(EventPeriod(d(11, 9), d(11, 15)), periods.period(0))
        assertEquals(EventPeriod(d(11, 16), d(11, 22)), periods.period(1))
    }

    @Test
    fun weeklyLinesUpWithSundayToo() {
        val periods = EventPeriods(d(11, 11), weekly, DayOfWeek.SUNDAY)
        assertEquals(EventPeriod(d(11, 8), d(11, 14)), periods.period(0))
    }

    @Test
    fun everyTwoWeeks() {
        val periods = EventPeriods(d(11, 9), EventRepeat(2, RepeatUnit.WEEK), DayOfWeek.MONDAY)
        assertEquals(EventPeriod(d(11, 23), d(12, 6)), periods.period(1))
    }

    @Test
    fun monthlyKeepsItsDayAndClamps() {
        val periods = EventPeriods(d(1, 31), EventRepeat(1, RepeatUnit.MONTH), DayOfWeek.MONDAY)
        assertEquals(EventPeriod(d(1, 31), d(2, 27)), periods.period(0))
        assertEquals(EventPeriod(d(2, 28), d(3, 30)), periods.period(1))
        assertEquals(EventPeriod(d(3, 31), d(4, 29)), periods.period(2))
    }

    @Test
    fun findsThePeriodOfADay() {
        val periods = EventPeriods(d(11, 9), weekly, DayOfWeek.MONDAY)
        assertEquals(0, periods.indexOn(d(11, 15)))
        assertEquals(1, periods.indexOn(d(11, 16)))
        assertNull(periods.indexOn(d(11, 8)))
    }

    @Test
    fun anEndedEventShowsItsLastPeriod() {
        val periods = EventPeriods(d(11, 9), weekly, DayOfWeek.MONDAY)
        assertEquals(1, currentIndex(periods, end = d(11, 20), today = d(12, 25)))
        assertEquals(2, currentIndex(periods, end = null, today = d(11, 25)))
    }

    @Test
    fun pastNEndsAtThePickedPeriod() {
        val periods = EventPeriods(d(1, 5), weekly, DayOfWeek.MONDAY)
        assertEquals(listOf(4, 5, 6, 7, 8, 9), pastPeriods(periods, 9).map { it.first })
        assertEquals(listOf(0, 1), pastPeriods(periods, 1).map { it.first })
    }

    @Test
    fun labels() {
        assertEquals("Nov 9–15", periodLabel(EventPeriod(d(11, 9), d(11, 15))))
        assertEquals("Oct 26 – Nov 1", periodLabel(EventPeriod(d(10, 26), d(11, 1))))
        assertEquals("Nov 9", periodLabel(EventPeriod(d(11, 9), d(11, 9))))
        assertEquals("Dec 28, 2026 – Jan 3, 2027", periodLabel(EventPeriod(d(12, 28), LocalDate(2027, 1, 3))))
        assertEquals("Every week", repeatLabel(weekly))
        assertEquals("Every 2 months", repeatLabel(EventRepeat(2, RepeatUnit.MONTH)))
    }

    @Test
    fun eventsDontRepeatByTheYear() {
        assertFailsWith<IllegalArgumentException> { EventRepeat(1, RepeatUnit.YEAR) }
    }

    @Test
    fun theDatabaseNeedsBothColumns() {
        assertNull(eventRepeatOf(1, null))
        assertEquals(weekly, eventRepeatOf(1, "WEEK"))
    }
}
