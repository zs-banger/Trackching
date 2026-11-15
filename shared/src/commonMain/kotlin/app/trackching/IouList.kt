package app.trackching

import app.trackching.db.IouList
import kotlinx.datetime.LocalDate

/** One IOU, ready for a list: what's left and whether it's overdue are already worked out. */
data class IouRow(
    val id: Long,
    val kind: IouKind,
    val personId: Long,
    val person: String,
    val whatFor: String,
    val left: Long,
    val due: LocalDate?,
    val overdue: Boolean,
) {
    val settled: Boolean get() = left <= 0
}

/** Past the due date and not paid off. The due date itself isn't overdue yet. */
fun isOverdue(due: LocalDate?, left: Long, today: LocalDate): Boolean = due != null && left > 0 && today > due

fun IouList.toRow(today: LocalDate): IouRow {
    val due = due_on?.let(LocalDate::parse)
    val terms = IouTerms(centavos, interestFrom(interest_kind, interest_value), LocalDate.parse(opened_on), due)
    val left = leftOf(terms, listOf(Paid(paid, 0))) // only the total paid matters here
    return IouRow(id, IouKind.valueOf(kind), person_id, person, what_for, left, due, isOverdue(due, left, today))
}

/** One person on a tab: everything they still owe (or I owe them), added up. */
data class PersonLine(val personId: Long, val person: String, val left: Long, val overdue: Boolean, val count: Int)

/** What one tab (I owe, Owed to me, Holding) shows. */
data class IouTab(val total: Long, val people: List<PersonLine>, val settled: List<IouRow>)

/**
 * People with something left, overdue first, then the biggest amount first.
 * Settled IOUs go in their own list, newest first, for the collapsed "Settled" row.
 */
fun tabOf(rows: List<IouRow>, kind: IouKind): IouTab {
    val mine = rows.filter { it.kind == kind }
    val open = mine.filterNot { it.settled }
    val people = open.groupBy { it.personId }.map { (personId, ious) ->
        PersonLine(personId, ious.first().person, ious.sumOf { it.left }, ious.any { it.overdue }, ious.size)
    }.sortedWith(compareByDescending<PersonLine> { it.overdue }.thenByDescending { it.left })
    return IouTab(open.sumOf { it.left }, people, mine.filter { it.settled }.sortedByDescending { it.id })
}

/** Everything with a due date and something left, soonest first. Holding never has a due date. */
fun dueSoon(rows: List<IouRow>): List<IouRow> =
    rows.filter { it.kind != IouKind.HOLDING && it.due != null && !it.settled }.sortedBy { it.due }

/** The tab names the screen and the issue use. */
fun IouKind.tabLabel(): String = when (this) {
    IouKind.OWE -> "I owe"
    IouKind.OWED -> "Owed to me"
    IouKind.HOLDING -> "Holding"
}
