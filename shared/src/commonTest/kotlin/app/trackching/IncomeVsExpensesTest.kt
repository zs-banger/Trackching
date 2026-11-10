package app.trackching

import app.trackching.db.History
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class IncomeVsExpensesTest {

    private val zone = TimeZone.of("Asia/Manila")
    private val today = LocalDate(2026, 11, 10)
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(zone).toEpochMilliseconds()
    private fun row(id: Long, kind: String, centavos: Long, at: Long) =
        History(id, kind, centavos, at, null, "Cash", null, 1, null)

    @Test
    fun monthlyIsTheLastSixMonths() {
        val b = bucketsFor(Grouping.Monthly, today, zone)
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), b.map { it.label })
        assertEquals("Nov 2026", b.last().title)
        assertEquals(listOf(false, false, false, false, false, true), b.map { it.running })
    }

    @Test
    fun monthlyCrossesNewYear() {
        val b = bucketsFor(Grouping.Monthly, LocalDate(2027, 2, 3), zone)
        assertEquals(listOf("Sep", "Oct", "Nov", "Dec", "Jan", "Feb"), b.map { it.label })
        assertEquals("Dec 2026", b[3].title)
    }

    @Test
    fun quartersOfThisYear() {
        val b = bucketsFor(Grouping.Quarterly, today, zone)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), b.map { it.label })
        assertEquals(listOf(false, false, false, true), b.map { it.running })
        assertEquals(at(2026, 10, 1, 0, 0), b[3].from)
        assertEquals(at(2027, 1, 1, 0, 0), b[3].until)
    }

    @Test
    fun yearsFromTheFirstRow() {
        val b = bucketsFor(Grouping.Yearly, today, zone, firstYear = 2024)
        assertEquals(listOf("2024", "2025", "2026"), b.map { it.label })
        assertEquals(listOf(false, false, true), b.map { it.running })
        assertEquals(listOf("2026"), bucketsFor(Grouping.Yearly, today, zone).map { it.label })
    }

    @Test
    fun firstYearIsTheOldestRow() {
        val rows = listOf(row(2, "EXPENSE", 1, at(2026, 3, 1)), row(1, "EXPENSE", 1, at(2025, 1, 1, 0, 30)))
        assertEquals(2025, firstYearOf(rows, zone))
        assertEquals(null, firstYearOf(emptyList(), zone))
    }

    @Test
    fun rowsLandInTheRightMonthAtTheEdges() {
        val rows = listOf(
            row(1, "INCOME", 100_00, at(2026, 10, 31, 23, 59)),   // October
            row(2, "EXPENSE", 40_00, at(2026, 11, 1, 0, 0)),      // November
            row(3, "TRANSFER", 999_00, at(2026, 11, 2)),          // never counts
            row(4, ADJUSTMENT, 500_00, at(2026, 11, 3)),          // never counts
        )
        val bars = upDowns(bucketsFor(Grouping.Monthly, today, zone), rows)
        assertEquals(UpDown("Oct", 100_00, 0), bars[4])
        assertEquals(UpDown("Nov", 0, 40_00, running = true), bars[5])
    }

    @Test
    fun quarterEdgesInManila() {
        val rows = listOf(
            row(1, "EXPENSE", 1, at(2026, 9, 30, 23, 59)),  // Q3
            row(2, "EXPENSE", 2, at(2026, 10, 1, 0, 0)),    // Q4
        )
        val bars = upDowns(bucketsFor(Grouping.Quarterly, today, zone), rows)
        assertEquals(listOf(0L, 0, 1, 2), bars.map { it.down })
    }

    @Test
    fun yearEdgesInManila() {
        val rows = listOf(
            row(1, "INCOME", 1, at(2025, 12, 31, 23, 59)),
            row(2, "INCOME", 2, at(2026, 1, 1, 0, 0)),
        )
        val bars = upDowns(bucketsFor(Grouping.Yearly, today, zone, firstYear = 2025), rows)
        assertEquals(listOf(1L, 2), bars.map { it.up })
    }
}
