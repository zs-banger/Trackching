package app.trackching

import app.trackching.db.History
import kotlin.math.roundToInt
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The periods Transactions can show. `running` = the period isn't over yet. */
enum class Period(val label: String, val running: Boolean) {
    Today("Today", true),
    ThisMonth("This month", true),
    LastMonth("Last month", false),
    ThisYear("This year", true),
    AllTime("All time", true),
}

/** From (included) and until (not included), in milliseconds. All time covers everything. */
fun periodRange(period: Period, today: LocalDate, zone: TimeZone): Pair<Long, Long> {
    fun start(d: LocalDate) = d.atStartOfDayIn(zone).toEpochMilliseconds()
    val month = LocalDate(today.year, today.month, 1)
    return when (period) {
        Period.Today -> start(today) to start(today.plus(1, DateTimeUnit.DAY))
        Period.ThisMonth -> start(month) to start(month.plus(1, DateTimeUnit.MONTH))
        Period.LastMonth -> start(month.minus(1, DateTimeUnit.MONTH)) to start(month)
        Period.ThisYear -> start(LocalDate(today.year, 1, 1)) to start(LocalDate(today.year + 1, 1, 1))
        Period.AllTime -> Long.MIN_VALUE to Long.MAX_VALUE
    }
}

/** The rows inside a range. */
fun inRange(rows: List<History>, range: Pair<Long, Long>): List<History> =
    rows.filter { it.occurred_at >= range.first && it.occurred_at < range.second }

/**
 * What the Flow summary card shows.
 * `count`, `income`, `expenses` and `net` cover income and expenses only.
 * `others` is how many other rows (transfers, adjustments) are listed in History.
 * `adjustments` is the adjustments added up, with their sign.
 */
data class Flow(
    val count: Int,
    val income: Long,
    val expenses: Long,
    val others: Int,
    val adjustments: Long,
) {
    val net: Long get() = income - expenses
}

fun flowOf(rows: List<History>): Flow {
    val income = rows.filter { it.kind == "INCOME" }
    val expenses = rows.filter { it.kind == "EXPENSE" }
    return Flow(
        count = income.size + expenses.size,
        income = income.sumOf { it.centavos },
        expenses = expenses.sumOf { it.centavos },
        others = rows.size - income.size - expenses.size,
        adjustments = rows.filter { it.kind == ADJUSTMENT }.sumOf { it.centavos },
    )
}

/** Net ÷ Income as a whole %, rounded. Null when there's no income, shown as "—". Can be below zero. */
fun savingsRate(flow: Flow): Int? =
    if (flow.income <= 0) null else (flow.net * 100.0 / flow.income).roundToInt()

/** The tabs above History. Adjustments only show under All. */
enum class TypeTab(val label: String) { All("All"), Expenses("Expenses"), Income("Income"), Transfers("Transfers") }

fun rowsFor(tab: TypeTab, rows: List<History>): List<History> = when (tab) {
    TypeTab.All -> rows
    TypeTab.Expenses -> rows.filter { it.kind == "EXPENSE" }
    TypeTab.Income -> rows.filter { it.kind == "INCOME" }
    TypeTab.Transfers -> rows.filter { it.kind == "TRANSFER" }
}
