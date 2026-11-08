package app.trackching

import app.trackching.db.History
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** One day in the list: its date, its net, and its transactions (newest first). */
data class Day(val date: LocalDate, val net: Long, val items: List<History>)

fun kindOf(kind: String): AmountKind = when (kind) {
    "INCOME" -> AmountKind.Income
    "TRANSFER" -> AmountKind.Transfer
    else -> AmountKind.Expense
}

/** Rows must already be newest first (the history query does that). */
@OptIn(ExperimentalTime::class)
fun groupByDay(rows: List<History>, zone: TimeZone): List<Day> =
    rows.groupBy { Instant.fromEpochMilliseconds(it.occurred_at).toLocalDateTime(zone).date }
        .map { (date, items) ->
            val net = items.sumOf { row ->
                when (kindOf(row.kind)) {
                    AmountKind.Income -> row.centavos
                    AmountKind.Expense -> -row.centavos
                    AmountKind.Transfer -> 0L
                }
            }
            Day(date, net, items)
        }

/** "Today", "Yesterday", or "Mon, Nov 3". */
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
    else -> {
        val weekday = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        "$weekday, $month ${date.day}"
    }
}