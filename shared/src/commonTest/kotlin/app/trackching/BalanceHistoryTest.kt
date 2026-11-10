package app.trackching

import app.trackching.db.History
import app.trackching.db.Movements
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BalanceHistoryTest {

    private val zone = TimeZone.of("Asia/Manila")
    private fun at(date: String) = LocalDate.parse(date).atStartOfDayIn(zone).toEpochMilliseconds() + 3_600_000 // 1 am

    // Cash (1) started at ₱1,000 and GCash (2) at ₱0. Made-up rows, oldest first.
    private val moves = listOf(
        Movements("EXPENSE", 200_00, 1, null, at("2026-10-05")),
        Movements("TRANSFER", 300_00, 1, 2, at("2026-11-03")),
        Movements(ADJUSTMENT, 50_00, 1, null, at("2026-11-04")),
        Movements("INCOME", 1_000_00, 2, null, at("2026-11-06")),
    )
    private val months = lastMonths(LocalDate.parse("2026-11-10"), zone, 3)

    @Test
    fun lastMonthsEndsWithThisMonth() {
        assertEquals(listOf("Sep", "Oct", "Nov"), months.map { it.label })
        assertEquals(LocalDate.parse("2026-12-01").atStartOfDayIn(zone).toEpochMilliseconds(), months.last().end)
    }

    @Test
    fun lastMonthsCrossesNewYear() {
        val labels = lastMonths(LocalDate.parse("2027-01-15"), zone, 3).map { it.label }
        assertEquals(listOf("Nov", "Dec", "Jan"), labels)
    }

    @Test
    fun cashGoesBackToItsStartingBalance() {
        // Today: 1,000 − 200 − 300 + 50 = ₱550
        assertEquals(listOf(1_000_00L, 800_00, 550_00), balanceSeries(1, 550_00, moves, months))
    }

    @Test
    fun transfersCountOnBothSides() {
        // GCash today: 300 in from Cash + 1,000 income = ₱1,300
        assertEquals(listOf(0L, 0, 1_300_00), balanceSeries(2, 1_300_00, moves, months))
    }

    @Test
    fun theLastPointIsAlwaysTodaysBalance() {
        assertEquals(550_00L, balanceSeries(1, 550_00, moves, months).last())
    }

    @Test
    fun totalAddsAccountsMonthByMonth() {
        val series = listOf(listOf(1_000_00L, 800_00, 550_00), listOf(0L, 0, 1_300_00))
        assertEquals(listOf(1_000_00L, 800_00, 1_850_00), totalSeries(series, 3))
    }

    @Test
    fun aRowLateAtNightLandsInTheRightMonth() {
        // 11:30 pm on Oct 31 in Manila is still October, even though it's Oct 31 15:30 in UTC.
        val late = LocalDate.parse("2026-10-31").atStartOfDayIn(zone).toEpochMilliseconds() + (23 * 60 + 30) * 60_000L
        val oneRow = listOf(Movements("EXPENSE", 100_00, 1, null, late))
        assertEquals(listOf(1_000_00L, 900_00, 900_00), balanceSeries(1, 900_00, oneRow, months)) // counted in Oct, not Nov
    }

    @Test
    fun historyBeforeThisMonth() {
        val novFirst = LocalDate.parse("2026-11-01").atStartOfDayIn(zone).toEpochMilliseconds()
        assertTrue(hasHistoryBefore(moves, novFirst))
        assertFalse(hasHistoryBefore(moves.drop(1), novFirst))
    }

    @Test
    fun historyForOneAccountIncludesTransfersIn() {
        val rows = listOf(
            History(1, "EXPENSE", 100, 0, "Meals", "Cash", null, 1, null),
            History(2, "TRANSFER", 300, 0, null, "Cash", "GCash", 1, 2),
            History(3, "INCOME", 900, 0, "Salary", "BPI", null, 3, null),
        )
        assertEquals(listOf(2L), historyFor(rows, 2).map { it.id })
        assertEquals(listOf(1L, 2L), historyFor(rows, 1).map { it.id })
    }

    @Test
    fun sharesIgnoreAccountsBelowZero() {
        assertEquals(listOf(75, 25, 0), accountShares(listOf(300_00, 100_00, -50_00)))
    }
}
