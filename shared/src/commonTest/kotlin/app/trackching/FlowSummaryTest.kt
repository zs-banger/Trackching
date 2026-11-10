package app.trackching

import app.trackching.db.History
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowSummaryTest {

    private val zone = TimeZone.of("Asia/Manila")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(zone).toEpochMilliseconds()
    private fun row(id: Long, kind: String, centavos: Long, at: Long = at(2026, 11, 5)) =
        History(id, kind, centavos, at, null, "BPI", null, 1, null)

    // The issue's example: ₱5,000 income, a ₱1,000 transfer, a ₱200 adjustment, plus ₱1,500 spent.
    private val rows = listOf(
        row(1, "INCOME", 5_000_00),
        row(2, "TRANSFER", 1_000_00),
        row(3, ADJUSTMENT, 200_00),
        row(4, "EXPENSE", 1_500_00),
    )

    @Test
    fun transfersAndAdjustmentsDontChangeTheFlow() {
        val flow = flowOf(rows)
        assertEquals(5_000_00L, flow.income)
        assertEquals(1_500_00L, flow.expenses)
        assertEquals(3_500_00L, flow.net)
        assertEquals(2, flow.count)
        assertEquals(2, flow.others)        // still listed in History
        assertEquals(200_00L, flow.adjustments)
    }

    @Test
    fun negativeAdjustmentsKeepTheirSign() {
        assertEquals(-50_00L, flowOf(listOf(row(1, ADJUSTMENT, -50_00))).adjustments)
    }

    @Test
    fun savingsRateIsNetOverIncome() {
        assertEquals(70, savingsRate(flowOf(rows)))                 // 3,500 ÷ 5,000
        assertEquals(-50, savingsRate(flowOf(listOf(row(1, "INCOME", 100_00), row(2, "EXPENSE", 150_00)))))
    }

    @Test
    fun noIncomeMeansNoSavingsRate() {
        assertNull(savingsRate(flowOf(listOf(row(1, "EXPENSE", 100_00)))))
        assertNull(savingsRate(flowOf(emptyList())))
    }

    @Test
    fun monthEdgesInManila() {
        val range = periodRange(Period.ThisMonth, LocalDate(2026, 11, 10), zone)
        val edge = listOf(
            row(1, "EXPENSE", 1, at(2026, 10, 31, 23, 59)), // October
            row(2, "EXPENSE", 2, at(2026, 11, 1, 0, 0)),    // first moment of November
            row(3, "EXPENSE", 3, at(2026, 11, 30, 23, 59)), // November
            row(4, "EXPENSE", 4, at(2026, 12, 1, 0, 0)),    // December
        )
        assertEquals(listOf(2L, 3L), inRange(edge, range).map { it.id })
    }

    @Test
    fun lastMonthAcrossNewYear() {
        val range = periodRange(Period.LastMonth, LocalDate(2027, 1, 15), zone)
        assertEquals(at(2026, 12, 1, 0, 0), range.first)
        assertEquals(at(2027, 1, 1, 0, 0), range.second)
    }

    @Test
    fun yearEdgesInManila() {
        val range = periodRange(Period.ThisYear, LocalDate(2026, 6, 1), zone)
        val edge = listOf(
            row(1, "INCOME", 1, at(2025, 12, 31, 23, 59)),
            row(2, "INCOME", 2, at(2026, 1, 1, 0, 0)),
            row(3, "INCOME", 3, at(2026, 12, 31, 23, 59)),
            row(4, "INCOME", 4, at(2027, 1, 1, 0, 0)),
        )
        assertEquals(listOf(2L, 3L), inRange(edge, range).map { it.id })
    }

    @Test
    fun todayAndAllTime() {
        val today = periodRange(Period.Today, LocalDate(2026, 11, 5), zone)
        assertEquals(4, inRange(rows, today).size)
        assertEquals(4, inRange(rows, periodRange(Period.AllTime, LocalDate(2026, 11, 5), zone)).size)
    }

    @Test
    fun typeTabsPickTheirKind() {
        assertEquals(listOf(1L, 2L, 3L, 4L), rowsFor(TypeTab.All, rows).map { it.id })
        assertEquals(listOf(4L), rowsFor(TypeTab.Expenses, rows).map { it.id })
        assertEquals(listOf(1L), rowsFor(TypeTab.Income, rows).map { it.id })
        assertEquals(listOf(2L), rowsFor(TypeTab.Transfers, rows).map { it.id })
    }
}
