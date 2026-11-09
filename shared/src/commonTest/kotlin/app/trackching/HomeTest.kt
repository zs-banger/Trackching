package app.trackching

import app.trackching.db.Balances
import app.trackching.db.History
import app.trackching.db.MonthTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeTest {

    @Test
    fun totalAddsEveryAccountIncludingNegatives() {
        val accounts = listOf(
            Balances(1, "Cash", 0, 100_000),
            Balances(2, "GCash", 0, 50_000),
            Balances(3, "BPI", 0, -20_000),
        )
        assertEquals(130_000L, totalBalance(accounts))
    }

    private fun group(id: Long, name: String, total: Long) =
        CategoryGroup(MonthTotals(id, name, 0, "🏷️", null, total), emptyList(), total)

    @Test
    fun topCategoriesAreTheBiggestThree() {
        val groups = listOf(
            group(1, "Food", 50_000), group(2, "Transport", 10_000), group(3, "Bills", 80_000),
            group(4, "Health", 30_000), group(5, "Fun", 0),
        )
        assertEquals(listOf("Bills", "Food", "Health"), topCategories(groups).map { it.main.name })
    }

    @Test
    fun unusedCategoriesNeverShowAsTop() {
        assertTrue(topCategories(listOf(group(1, "Food", 0))).isEmpty())
    }

    private val hour = 60L * 60 * 1000
    private fun row(id: Long, at: Long) = History(id, "EXPENSE", 100, at, "Meals", "Cash", null)

    @Test
    fun todayOnlyKeepsToday() {
        val day2 = 24 * hour
        val rows = listOf(row(3, day2 + 5 * hour), row(2, day2 + hour), row(1, hour))
        val today = LocalDate(1970, 1, 2)
        assertEquals(listOf(3L, 2L), todayRows(rows, today, TimeZone.UTC).map { it.id })
    }

    @Test
    fun nothingTodayIsEmpty() {
        assertTrue(todayRows(listOf(row(1, hour)), LocalDate(1970, 1, 5), TimeZone.UTC).isEmpty())
    }
}