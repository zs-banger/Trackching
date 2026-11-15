package app.trackching

import app.trackching.db.ExpensesByEvent
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** How often a repeating event starts a new period: every [every] days, weeks or months. */
data class EventRepeat(val every: Int, val unit: RepeatUnit) {
    init {
        require(every >= 1) { "Repeat every 1 or more" }
        require(unit != RepeatUnit.YEAR) { "Events repeat by the day, week or month" }
    }
}

/** What the database stores: both columns set, or both NULL. */
fun eventRepeatOf(every: Long?, unit: String?): EventRepeat? =
    if (every == null || unit == null) null else EventRepeat(every.toInt(), RepeatUnit.valueOf(unit))

/** One period, first and last day included. */
data class EventPeriod(val first: LocalDate, val last: LocalDate)

/**
 * The periods of one repeating event. Weekly ones line up with Settings › Week starts on,
 * so a weekly event started on a Wednesday still counts Monday to Sunday (or Sunday to Saturday).
 */
class EventPeriods(start: LocalDate, val repeat: EventRepeat, weekStart: DayOfWeek) {
    private val rule = RepeatRule(
        repeat.every,
        repeat.unit,
        if (repeat.unit == RepeatUnit.WEEK) startOfWeek(start, weekStart) else start,
    )

    /** Period number [n], counted from 0. Every date comes from the start, so month ends keep their day. */
    fun period(n: Int): EventPeriod = EventPeriod(rule.dateOf(n), rule.dateOf(n + 1).minus(1, DateTimeUnit.DAY))

    /** The number of the period [day] falls in, or null before the first one. */
    fun indexOn(day: LocalDate): Int? {
        if (day < rule.dateOf(0)) return null
        var n = 0
        while (rule.dateOf(n + 1) <= day) n++
        return n
    }
}

/** The period to show first: today's, or the last one if the event has ended. Null if it hasn't started. */
fun currentIndex(periods: EventPeriods, end: LocalDate?, today: LocalDate): Int? =
    periods.indexOn(if (end != null && end < today) end else today)

/** "Nov 3–9", "Oct 27 – Nov 2", "Nov 3" (one day), or "Dec 29, 2026 – Jan 4, 2027" across years. */
fun periodLabel(p: EventPeriod): String {
    fun month(d: LocalDate) = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return when {
        p.first == p.last -> "${month(p.first)} ${p.first.day}"
        p.first.year != p.last.year -> "${dateLabel(p.first)} – ${dateLabel(p.last)}"
        p.first.month == p.last.month -> "${month(p.first)} ${p.first.day}–${p.last.day}"
        else -> "${month(p.first)} ${p.first.day} – ${month(p.last)} ${p.last.day}"
    }
}

/** "Every week", "Every 2 months". */
fun repeatLabel(r: EventRepeat): String = if (r.every == 1) "Every ${r.unit.one}" else "Every ${r.every} ${r.unit.many}"

/** A period as a range of moments, for Transactions and the Flow summary. */
fun periodBucket(p: EventPeriod, today: LocalDate, zone: TimeZone): Bucket = Bucket(
    label = monthDay(p.first), // short, for the chart: "Nov 3"
    title = periodLabel(p),
    from = p.first.atStartOfDayIn(zone).toEpochMilliseconds(),
    until = p.last.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
    running = today in p.first..p.last,
)

/** The last [count] periods up to and including [index], oldest first, for the "Past N" chart. */
fun pastPeriods(periods: EventPeriods, index: Int, count: Int = 6): List<Pair<Int, EventPeriod>> =
    (maxOf(0, index - count + 1)..index).map { it to periods.period(it) }

/**
 * The events list, with each repeating event's spending for its current period only.
 * One-off events keep their all-time total.
 */
fun withPeriodTotals(
    events: List<EventRow>,
    expenses: List<ExpensesByEvent>,
    today: LocalDate,
    weekStart: DayOfWeek,
    zone: TimeZone,
): List<EventRow> = events.map { event ->
    val repeat = event.repeat ?: return@map event
    val periods = EventPeriods(event.start, repeat, weekStart)
    val index = currentIndex(periods, event.end, today) ?: return@map event.copy(spent = 0)
    val bucket = periodBucket(periods.period(index), today, zone)
    event.copy(
        spent = expenses.filter { it.event_id == event.id && it.occurred_at >= bucket.from && it.occurred_at < bucket.until }
            .sumOf { it.centavos },
    )
}
