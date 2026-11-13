package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)
    private fun monthly(start: LocalDate, end: RepeatEnd = RepeatEnd.Never) = RepeatRule(1, RepeatUnit.MONTH, start, end)

    @Test
    fun monthlyKeepsItsDayAndClampsToMonthEnd() {
        val rule = monthly(d(2026, 1, 31))
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30)),
            (0..3).map { rule.dateOf(it) },
        )
    }

    @Test
    fun monthlyLandsOnFeb29InALeapYear() =
        assertEquals(d(2028, 2, 29), monthly(d(2028, 1, 31)).dateOf(1))

    @Test
    fun yearlyFromFeb29() {
        val rule = RepeatRule(1, RepeatUnit.YEAR, d(2024, 2, 29))
        assertEquals(
            listOf(d(2025, 2, 28), d(2026, 2, 28), d(2027, 2, 28), d(2028, 2, 29)),
            (1..4).map { rule.dateOf(it) },
        )
    }

    @Test
    fun everyTwoWeeks() {
        val rule = RepeatRule(2, RepeatUnit.WEEK, d(2026, 11, 2))
        assertEquals(listOf(d(2026, 11, 16), d(2026, 11, 30)), (1..2).map { rule.dateOf(it) })
    }

    @Test
    fun everyTenDaysCrossesTheYear() =
        assertEquals(d(2027, 1, 5), RepeatRule(10, RepeatUnit.DAY, d(2026, 12, 26)).dateOf(1))

    @Test
    fun dueListsEveryPassedDateOldestFirst() =
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31)),
            monthly(d(2026, 1, 31)).due(done = 0, today = d(2026, 4, 15)),
        )

    @Test
    fun dueIncludesToday() =
        assertEquals(listOf(d(2026, 11, 13)), monthly(d(2026, 11, 13)).due(0, d(2026, 11, 13)))

    @Test
    fun nothingIsDueBeforeTheStart() =
        assertEquals(emptyList(), monthly(d(2026, 12, 1)).due(0, d(2026, 11, 13)))

    @Test
    fun savedOrSkippedDatesAreNeverListedAgain() {
        val rule = monthly(d(2026, 1, 31))
        val due = rule.due(done = 2, today = d(2026, 4, 30))   // Jan 31 and Feb 28 are done
        assertEquals(listOf(d(2026, 3, 31), d(2026, 4, 30)), due)
    }

    @Test
    fun nTimesStopsAfterN() {
        val rule = monthly(d(2026, 1, 31), RepeatEnd.After(3))
        assertEquals(3, rule.due(0, d(2030, 1, 1)).size)
        assertNull(rule.next(3))
    }

    @Test
    fun untilADateIncludesThatDate() {
        val start = d(2026, 1, 31)
        assertEquals(3, monthly(start, RepeatEnd.Until(d(2026, 3, 31))).due(0, d(2030, 1, 1)).size)
        assertEquals(2, monthly(start, RepeatEnd.Until(d(2026, 3, 30))).due(0, d(2030, 1, 1)).size)
    }

    @Test
    fun nextIsTheFirstDateNotDone() =
        assertEquals(d(2026, 2, 28), monthly(d(2026, 1, 31)).next(1))

    @Test
    fun fourteenMonthsBehind() {
        // Monthly from Sep 30, 2025, and the app wasn't opened until Nov 13, 2026.
        val due = monthly(d(2025, 9, 30)).due(0, d(2026, 11, 13))
        assertEquals(14, due.size)
        assertEquals(d(2025, 9, 30), due.first())
        assertEquals(d(2026, 10, 30), due.last())
        assertTrue(d(2026, 2, 28) in due)
    }

    @Test
    fun badRulesCantBeMade() {
        assertFailsWith<IllegalArgumentException> { RepeatRule(0, RepeatUnit.MONTH, d(2026, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { monthly(d(2026, 1, 1), RepeatEnd.After(0)) }
        assertFailsWith<IllegalArgumentException> { monthly(d(2026, 2, 1), RepeatEnd.Until(d(2026, 1, 1))) }
    }
}
