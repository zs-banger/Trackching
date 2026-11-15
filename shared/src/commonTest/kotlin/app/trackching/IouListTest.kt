package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IouListTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private val today = d(3, 10)

    private fun row(
        id: Long, kind: IouKind = IouKind.OWE, personId: Long = 1, person: String = "Juan",
        left: Long = 10_000, due: LocalDate? = null,
    ) = IouRow(id, kind, personId, person, "", left, due, isOverdue(due, left, today))

    @Test
    fun overdueStartsTheDayAfterTheDueDate() {
        assertFalse(isOverdue(d(3, 10), 10_000, today))
        assertTrue(isOverdue(d(3, 9), 10_000, today))
    }

    @Test
    fun aPaidOffIouIsNeverOverdue() = assertFalse(isOverdue(d(1, 1), 0, today))

    @Test
    fun noDueDateIsNeverOverdue() = assertFalse(isOverdue(null, 10_000, today))

    @Test
    fun aTabAddsUpOnlyItsOwnKind() {
        val rows = listOf(row(1, left = 10_000), row(2, IouKind.OWED, left = 99_000), row(3, left = 5_000, personId = 2, person = "Maria"))
        assertEquals(15_000, tabOf(rows, IouKind.OWE).total)
    }

    @Test
    fun onePersonIsOneLineWithTheirTotal() {
        val tab = tabOf(listOf(row(1, left = 10_000), row(2, left = 2_500)), IouKind.OWE)
        assertEquals(listOf(PersonLine(1, "Juan", 12_500, overdue = false, count = 2)), tab.people)
    }

    @Test
    fun overduePeopleComeFirstThenTheBiggest() {
        val rows = listOf(
            row(1, personId = 1, person = "Big", left = 90_000),
            row(2, personId = 2, person = "Late", left = 1_000, due = d(3, 1)),
            row(3, personId = 3, person = "Small", left = 2_000),
        )
        assertEquals(listOf("Late", "Big", "Small"), tabOf(rows, IouKind.OWE).people.map { it.person })
    }

    @Test
    fun settledIousLeaveThePeopleListAndTheTotal() {
        val tab = tabOf(listOf(row(1, left = 0), row(2, left = 3_000)), IouKind.OWE)
        assertEquals(3_000, tab.total)
        assertEquals(listOf(1L), tab.settled.map { it.id })
        assertEquals(1, tab.people.single().count)
    }

    @Test
    fun dueSoonIsSoonestFirstWithoutHoldingOrSettled() {
        val rows = listOf(
            row(1, due = d(5, 1)),
            row(2, IouKind.OWED, due = d(4, 1)),
            row(3, due = d(3, 1), left = 0),   // settled
            row(4, due = null),                // no due date
            row(5, IouKind.HOLDING),
        )
        assertEquals(listOf(2L, 1L), dueSoon(rows).map { it.id })
    }
}
