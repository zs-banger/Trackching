package app.trackching

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** What a repeat counts in. `one` and `many` are for sentences like "every 2 weeks". */
enum class RepeatUnit(val dateUnit: DateTimeUnit.DateBased, val one: String, val many: String) {
    DAY(DateTimeUnit.DAY, "day", "days"),
    WEEK(DateTimeUnit.WEEK, "week", "weeks"),
    MONTH(DateTimeUnit.MONTH, "month", "months"),
    YEAR(DateTimeUnit.YEAR, "year", "years"),
}

/** When a repeat stops. */
sealed interface RepeatEnd {
    data object Never : RepeatEnd
    data class After(val times: Int) : RepeatEnd
    data class Until(val date: LocalDate) : RepeatEnd   // this date counts
}

/**
 * "Every [every] [unit] from [start]", ending at [end].
 * Time number 0 is [start] itself. Every date is counted from [start], so month ends keep their day:
 * Jan 31 → Feb 28 → Mar 31.
 */
data class RepeatRule(
    val every: Int,
    val unit: RepeatUnit,
    val start: LocalDate,
    val end: RepeatEnd = RepeatEnd.Never,
) {
    init {
        require(every >= 1) { "Repeat every 1 or more" }
        when (end) {
            is RepeatEnd.After -> require(end.times >= 1) { "Repeat at least once" }
            is RepeatEnd.Until -> require(end.date >= start) { "The end date can't be before the start" }
            RepeatEnd.Never -> Unit
        }
    }

    /** The date of time number [n]. Never stepped from the previous date (see decision 2). */
    fun dateOf(n: Int): LocalDate = start.plus(n * every, unit.dateUnit)

    /** Whether time number [n] happens at all, or the repeat has ended before it. */
    fun hasTime(n: Int): Boolean = n >= 0 && when (end) {
        RepeatEnd.Never -> true
        is RepeatEnd.After -> n < end.times
        is RepeatEnd.Until -> dateOf(n) <= end.date
    }

    /** The next date after [done] times are handled, or null once the repeat is over. */
    fun next(done: Int): LocalDate? = if (hasTime(done)) dateOf(done) else null

    /** Every date up to and including [today] that isn't handled yet, oldest first. */
    fun due(done: Int, today: LocalDate): List<LocalDate> =
        generateSequence(done) { it + 1 }
            .takeWhile { hasTime(it) && dateOf(it) <= today }
            .map { dateOf(it) }
            .toList()
}
