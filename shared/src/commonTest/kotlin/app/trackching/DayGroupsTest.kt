package app.trackching

import app.trackching.db.History
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DayGroupsTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun row(id: Long, kind: String, centavos: Long, at: Long) =
        History(id, kind, centavos, at, "Meals", "Cash on hand", null)

    @Test
    fun groupsNewestDayFirst() {
        val rows = listOf(
            row(3, "EXPENSE", 100, 2 * day + hour),
            row(2, "EXPENSE", 100, day + hour),
            row(1, "EXPENSE", 100, hour),
        )
        val days = groupByDay(rows, TimeZone.UTC)
        assertEquals(3, days.size)
        assertEquals(LocalDate(1970, 1, 3), days.first().date)
    }

    @Test
    fun sameDayStaysTogether() {
        val rows = listOf(row(2, "EXPENSE", 100, 5 * hour), row(1, "EXPENSE", 100, hour))
        assertEquals(1, groupByDay(rows, TimeZone.UTC).size)
    }

    @Test
    fun netIsIncomeMinusSpendingWithoutTransfers() {
        val rows = listOf(
            row(3, "TRANSFER", 50_000, 3 * hour),
            row(2, "INCOME", 100_000, 2 * hour),
            row(1, "EXPENSE", 12_000, hour),
        )
        assertEquals(88_000L, groupByDay(rows, TimeZone.UTC).single().net)
    }

    @Test
    fun dayLabels() {
        val today = LocalDate(2026, 11, 8)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(LocalDate(2026, 11, 7), today))
        assertEquals("Tue, Nov 3", dayLabel(LocalDate(2026, 11, 3), today))
    }

    @Test
    fun adjustmentsAreLeftOutOfTheNet() {
        val rows = listOf(
            row(2, "ADJUSTMENT", -5_000, 2 * hour),
            row(1, "EXPENSE", 12_000, hour),
        )
        assertEquals(-12_000L, groupByDay(rows, TimeZone.UTC).single().net)
    }
}
