package app.trackching

import app.trackching.db.Event
import app.trackching.db.History
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** An event row from the database, with its dates read back from 'YYYY-MM-DD'. */
fun eventRowOf(event: Event): EventRow =
    EventRow(
        event.id, event.name, LocalDate.parse(event.start_date), event.end_date?.let(LocalDate::parse), spent = 0,
        repeat = eventRepeatOf(event.repeat_every, event.repeat_unit),
        occasional = event.occasional == 1L,
    )

/**
 * The event's dates as a range for Transactions: from the start of its first day
 * until the start of the day after its last day. No end date = until now and beyond.
 * It's running until its last day is over, so an ongoing event is always running.
 */
fun eventBucket(event: EventRow, today: LocalDate, zone: TimeZone): Bucket {
    val from = event.start.atStartOfDayIn(zone).toEpochMilliseconds()
    val until = event.end?.plus(1, DateTimeUnit.DAY)?.atStartOfDayIn(zone)?.toEpochMilliseconds() ?: Long.MAX_VALUE
    val running = event.end == null || today <= event.end
    return Bucket(event.name, event.name, from, until, running)
}

/** Only the rows tagged to the event, using the ids the database gave back. */
fun rowsOfEvent(rows: List<History>, ids: Set<Long>): List<History> =
    rows.filter { it.id in ids }

/** Fills the edit sheet from a saved event. */
fun EventForm.loadFrom(event: EventRow) {
    name = event.name
    start = event.start
    end = event.end
    repeatUnit = event.repeat?.unit
    repeatEvery = event.repeat?.every ?: 1
    occasional = event.occasional
}
