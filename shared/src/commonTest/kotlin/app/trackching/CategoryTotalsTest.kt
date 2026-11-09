package app.trackching

import app.trackching.db.MonthTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

class CategoryTotalsTest {

    private fun row(id: Long, name: String, parent: Long?, total: Long) =
        MonthTotals(id, name, 0xFF000000, "🏷️", parent, total)

    private val rows = listOf(
        row(1, "Food", null, 0),
        row(2, "Meals", 1, 12_000),
        row(3, "Coffee", 1, 4_500),
        row(4, "Transport", null, 0),
        row(5, "Jeep", 4, 0),
        row(6, "Health", null, 2_000), // a main with no subs, logged on directly
    )

    @Test
    fun subsGoUnderTheirMain() {
        val groups = groupCategories(rows)
        assertEquals(listOf("Food", "Transport", "Health"), groups.map { it.main.name })
        assertEquals(listOf("Meals", "Coffee"), groups[0].subs.map { it.name })
    }

    @Test
    fun mainTotalIncludesItsSubs() {
        assertEquals(16_500L, groupCategories(rows)[0].total)
    }

    @Test
    fun unusedCategoriesStayAtZero() {
        val transport = groupCategories(rows)[1]
        assertEquals(0L, transport.total)
        assertEquals(1, transport.subs.size)
    }

    @Test
    fun mainWithoutSubsCountsItself() {
        assertEquals(2_000L, groupCategories(rows)[2].total)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun decemberRollsIntoNextYear() {
        val (from, until) = monthRange(LocalDate(2026, 12, 15), TimeZone.UTC)
        assertEquals(LocalDate(2026, 12, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), from)
        assertEquals(LocalDate(2027, 1, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), until)
    }
}