package app.trackching

import app.trackching.db.WithTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class EventStatus { Active, Upcoming, Past }

/** Where an event sits today. No end date = ongoing. The start and end days both count. */
fun statusOf(start: LocalDate, end: LocalDate?, today: LocalDate): EventStatus = when {
    today < start -> EventStatus.Upcoming
    end != null && today > end -> EventStatus.Past
    else -> EventStatus.Active
}

/** One event, ready for the screen. */
data class EventRow(
    val id: Long,
    val name: String,
    val start: LocalDate,
    val end: LocalDate?,
    val spent: Long,
)

/**
 * Splits events into Active, Upcoming and Past.
 * Active: newest start first. Upcoming: soonest first. Past: most recently ended first.
 */
fun splitEvents(rows: List<WithTotals>, today: LocalDate): Map<EventStatus, List<EventRow>> {
    val events = rows.map {
        EventRow(it.id, it.name, LocalDate.parse(it.start_date), it.end_date?.let(LocalDate::parse), it.spent)
    }
    val byStatus = events.groupBy { statusOf(it.start, it.end, today) }
    return mapOf(
        EventStatus.Active to byStatus[EventStatus.Active].orEmpty().sortedByDescending { it.start },
        EventStatus.Upcoming to byStatus[EventStatus.Upcoming].orEmpty().sortedBy { it.start },
        EventStatus.Past to byStatus[EventStatus.Past].orEmpty().sortedByDescending { it.end },
    )
}

/** "Dec 20, 2026 – Dec 23, 2026", "Since Nov 1, 2026" or "Dec 25, 2026" (one day). */
fun rangeLabel(start: LocalDate, end: LocalDate?): String = when (end) {
    null -> "Since ${dateLabel(start)}"
    start -> dateLabel(start)
    else -> "${dateLabel(start)} – ${dateLabel(end)}"
}

/** The calendar day a saved moment falls on, in the given time zone. */
@OptIn(ExperimentalTime::class)
fun dayOf(millis: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date