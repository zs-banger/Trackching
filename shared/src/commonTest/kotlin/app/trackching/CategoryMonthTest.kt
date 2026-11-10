package app.trackching

import app.trackching.db.MonthTotals
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryMonthTest {

    private val today = LocalDate(2026, 11, 10)
    private fun cat(id: Long, name: String, total: Long, parent: Long? = null) =
        MonthTotals(id, name, 0xFF1663FF, "•", parent, total)

    @Test
    fun monthBackStartsOnTheFirst() {
        assertEquals(LocalDate(2026, 11, 1), monthBack(today, 0))
        assertEquals(LocalDate(2026, 10, 1), monthBack(today, 1))
    }

    @Test
    fun monthBackCrossesNewYear() {
        assertEquals(LocalDate(2025, 12, 1), monthBack(LocalDate(2026, 1, 31), 1))
        assertEquals(LocalDate(2025, 1, 1), monthBack(LocalDate(2026, 1, 31), 12))
    }

    @Test
    fun monthTagIsShortAndUppercase() {
        assertEquals("NOV 2026", monthTag(LocalDate(2026, 11, 1)))
        assertEquals("JAN 2027", monthTag(LocalDate(2027, 1, 1)))
    }

    @Test
    fun sharesUseTheMainTotalWithSubs() {
        val groups = groupCategories(listOf(
            cat(1, "Food", 100_00),
            cat(2, "Groceries", 300_00, parent = 1),
            cat(3, "Bills", 600_00),
        ))
        assertEquals(listOf(400_00L, 600_00L), categoryShares(groups).map { it.centavos })
        assertEquals(listOf(40, 60), mainPercents(groups))
    }

    @Test
    fun percentsOfAnEmptyMonthAreZero() {
        val groups = groupCategories(listOf(cat(1, "Food", 0), cat(2, "Bills", 0)))
        assertEquals(listOf(0, 0), mainPercents(groups))
    }
}
