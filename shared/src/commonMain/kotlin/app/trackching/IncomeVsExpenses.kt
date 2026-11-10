package app.trackching

import app.trackching.db.History
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** How Income vs Expenses groups its bars. */
enum class Grouping(val label: String, val unit: String) {
    Monthly("Monthly", "month"),
    Quarterly("Quarterly", "quarter"),
    Yearly("Yearly", "year"),
}

/**
 * One bar: its short label under the bar ("Nov", "Q4", "2026"), a longer title ("Nov 2026", "Q4 2026"),
 * from (included) until (not included) in milliseconds, and whether it's still running.
 */
data class Bucket(val label: String, val title: String, val from: Long, val until: Long, val running: Boolean)

/**
 * The bars for a grouping, oldest first.
 * Monthly: the last 6 months. Quarterly: Q1 to Q4 of this year. Yearly: every year from `firstYear` to this one.
 */
fun bucketsFor(grouping: Grouping, today: LocalDate, zone: TimeZone, firstYear: Int = today.year): List<Bucket> {
    fun start(d: LocalDate) = d.atStartOfDayIn(zone).toEpochMilliseconds()
    return when (grouping) {
        Grouping.Monthly -> {
            val thisMonth = LocalDate(today.year, today.month, 1)
            (5 downTo 0).map { back ->
                val first = thisMonth.minus(back, DateTimeUnit.MONTH)
                val name = first.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                Bucket(name, "$name ${first.year}", start(first), start(first.plus(1, DateTimeUnit.MONTH)), back == 0)
            }
        }
        Grouping.Quarterly -> {
            val current = (today.month.ordinal / 3) + 1
            (1..4).map { q ->
                val first = LocalDate(today.year, (q - 1) * 3 + 1, 1)
                Bucket("Q$q", "Q$q ${today.year}", start(first), start(first.plus(3, DateTimeUnit.MONTH)), q == current)
            }
        }
        Grouping.Yearly -> (minOf(firstYear, today.year)..today.year).map { y ->
            Bucket("$y", "$y", start(LocalDate(y, 1, 1)), start(LocalDate(y + 1, 1, 1)), y == today.year)
        }
    }
}

/** The year of the oldest row, or null when there are no rows. Rows are newest first. */
@OptIn(ExperimentalTime::class)
fun firstYearOf(rows: List<History>, zone: TimeZone): Int? =
    rows.minOfOrNull { it.occurred_at }?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).year }

/** The rows inside one bar. */
fun rowsIn(bucket: Bucket, rows: List<History>): List<History> = inRange(rows, bucket.from to bucket.until)

/** One up-and-down bar per bucket: income up, spending down. Transfers and adjustments never count. */
fun upDowns(buckets: List<Bucket>, rows: List<History>): List<UpDown> =
    buckets.map { b ->
        val flow = flowOf(rowsIn(b, rows))
        UpDown(b.label, flow.income, flow.expenses, b.running)
    }
