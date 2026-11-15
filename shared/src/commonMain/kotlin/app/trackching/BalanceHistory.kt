package app.trackching

import app.trackching.db.History
import app.trackching.db.Movements
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One point on Balance over time: a month's short name, and the moment that month ends. */
data class MonthPoint(val label: String, val end: Long)

/**
 * The last `count` months, oldest first, ending with this month.
 * `end` is the first moment of the next month, so "the balance at `end`" counts everything in the month.
 */
fun lastMonths(today: LocalDate, zone: TimeZone, count: Int): List<MonthPoint> {
    val thisMonth = LocalDate(today.year, today.month, 1)
    return (count - 1 downTo 0).map { back ->
        val first = thisMonth.minus(back, DateTimeUnit.MONTH)
        val next = first.plus(1, DateTimeUnit.MONTH)
        MonthPoint(
            label = first.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            end = next.atStartOfDayIn(zone).toEpochMilliseconds(),
        )
    }
}

/** What one transaction did to one account's balance. The same rules as `balanceOf` in Account.sq. */
fun effectOn(accountId: Long, move: Movements): Long = when {
    move.kind == "INCOME" && move.account_id == accountId -> move.centavos
    move.kind == "EXPENSE" && move.account_id == accountId -> -move.centavos
    move.kind == "TRANSFER" && move.account_id == accountId -> -move.centavos
    move.kind == "TRANSFER" && move.to_account_id == accountId -> move.centavos
    (move.kind == ADJUSTMENT || move.kind == IOU) && move.account_id == accountId -> move.centavos
    else -> 0L
}

/**
 * An account's balance at the end of each month.
 * It works backwards from today's balance: take away everything that happened after that month.
 * That way the starting balance is never needed, and the last point always equals today's balance.
 */
fun balanceSeries(accountId: Long, balanceNow: Long, moves: List<Movements>, months: List<MonthPoint>): List<Long> =
    months.map { month ->
        balanceNow - moves.filter { it.occurred_at >= month.end }.sumOf { effectOn(accountId, it) }
    }

/** Adds several accounts' series together, month by month. */
fun totalSeries(series: List<List<Long>>, size: Int): List<Long> =
    (0 until size).map { i -> series.sumOf { it[i] } }

/** True if anything was logged before `moment`. Without that, a chart would be one flat point. */
fun hasHistoryBefore(moves: List<Movements>, moment: Long): Boolean =
    moves.any { it.occurred_at < moment }

/** The rows that touch one account: paid from it, received into it, or moved to it. */
fun historyFor(rows: List<History>, accountId: Long): List<History> =
    rows.filter { it.account_id == accountId || it.to_account_id == accountId }

/** Each balance's share of the total as whole %, adding up to 100. Accounts below zero count as 0. */
fun accountShares(balances: List<Long>): List<Int> =
    wholePercents(balances.map { maxOf(it, 0L) })
