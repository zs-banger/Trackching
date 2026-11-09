package app.trackching

import app.trackching.db.MonthTotals
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** One main category, its subs, and the month's total for all of them together. */
data class CategoryGroup(
    val main: MonthTotals,      // main.total = only what was logged on the main itself
    val subs: List<MonthTotals>,
    val total: Long,            // main + all subs
)

/** Nests subcategories under their main. Rows must already be in display order. */
fun groupCategories(rows: List<MonthTotals>): List<CategoryGroup> {
    val subsByParent = rows.filter { it.parent_id != null }.groupBy { it.parent_id }
    return rows.filter { it.parent_id == null }.map { main ->
        val subs = subsByParent[main.id].orEmpty()
        CategoryGroup(main, subs, main.total + subs.sumOf { it.total })
    }
}

/** From the 1st of `today`'s month (included) to the 1st of next month (not included), in milliseconds. */
@OptIn(ExperimentalTime::class)
fun monthRange(today: LocalDate, zone: TimeZone): Pair<Long, Long> {
    val first = LocalDate(today.year, today.month, 1)
    val next = first.plus(1, DateTimeUnit.MONTH)
    return first.atStartOfDayIn(zone).toEpochMilliseconds() to next.atStartOfDayIn(zone).toEpochMilliseconds()
}