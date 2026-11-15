package app.trackching

import app.trackching.db.Event_run
import kotlinx.datetime.LocalDate

/** One run of an occasional event: one trip, first and last day included. */
data class EventRun(val id: Long, val eventId: Long, val first: LocalDate, val last: LocalDate)

fun Event_run.toRun() = EventRun(id, event_id, LocalDate.parse(start_date), LocalDate.parse(end_date))

/** The run these dates would overlap, or null if they fit. Sharing even one day counts as overlapping. */
fun overlapping(runs: List<EventRun>, first: LocalDate, last: LocalDate, except: Long? = null): EventRun? =
    runs.firstOrNull { it.id != except && first <= it.last && last >= it.first }

/** Why these dates can't be saved, or null if they can. Used by the sheet and the Ledger. */
fun runError(runs: List<EventRun>, first: LocalDate, last: LocalDate, except: Long? = null): String? {
    if (last < first) return "The end date can't be before the start date"
    val clash = overlapping(runs, first, last, except) ?: return null
    return "These dates overlap ${periodLabel(EventPeriod(clash.first, clash.last))}"
}

/** The run the event screen shows first: the active one, else the last finished one, else the first upcoming one. */
fun runToShow(runs: List<EventRun>, today: LocalDate): EventRun? =
    runs.firstOrNull { today in it.first..it.last }
        ?: runs.filter { it.last < today }.maxByOrNull { it.last }
        ?: runs.filter { it.first > today }.minByOrNull { it.first }

/**
 * An occasional event as the Events list sees it: its dates become the run it's showing,
 * so it lands on Active, Upcoming or Past by that run, and its label and total are that run's.
 */
fun EventRow.showingRun(runs: List<EventRun>, today: LocalDate): EventRow {
    if (!occasional) return this
    val run = runToShow(runs, today) ?: return this
    return copy(start = run.first, end = run.last)
}
