package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IouTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private fun terms(centavos: Long, interest: Interest = Interest.None, due: LocalDate? = null) =
        IouTerms(centavos, interest, d(1, 15), due)

    @Test
    fun leftIsTheAmountMinusPayments() =
        assertEquals(70_000, leftOf(terms(100_000), listOf(Paid(30_000, 0))))

    @Test
    fun settlesAtZero() {
        val iou = terms(100_000)
        assertFalse(isSettled(iou, listOf(Paid(99_999, 0))))
        assertTrue(isSettled(iou, listOf(Paid(60_000, 0), Paid(40_000, 0))))
    }

    @Test
    fun flatInterestIsAddedOnce() {
        val iou = terms(100_000, Interest.Flat(10_000))
        assertEquals(110_000, leftOf(iou, emptyList()))
        assertTrue(isSettled(iou, listOf(Paid(100_000, 10_000))))
    }

    @Test
    fun monthlyInterestCountsEachFullMonthToTheDueDate() {
        // ₱10,000 at 5% a month, Jan 15 to Apr 15: 3 months, ₱1,500.
        val iou = terms(1_000_000, Interest.Monthly(500), due = d(4, 15))
        assertEquals(150_000, interestOf(iou))
        assertEquals(1_150_000, leftOf(iou, emptyList()))
    }

    @Test
    fun aMonthNotFinishedDoesNotCount() =
        // Jan 15 to Apr 14 is 2 full months.
        assertEquals(100_000, interestOf(terms(1_000_000, Interest.Monthly(500), due = d(4, 14))))

    @Test
    fun aShortLoanStillPaysOneMonth() =
        assertEquals(50_000, interestOf(terms(1_000_000, Interest.Monthly(500), due = d(1, 30))))

    @Test
    fun monthlyInterestRoundsToTheNearestCentavo() =
        // ₱333.33 at 3.33%: 11.0999… → ₱11.10
        assertEquals(1_110, interestOf(terms(33_333, Interest.Monthly(333), due = d(2, 15))))

    @Test
    fun monthlyInterestNeedsADueDate() {
        assertFailsWith<IllegalArgumentException> { interestOf(terms(100_000, Interest.Monthly(500))) }
    }

    @Test
    fun interestSurvivesTheDatabase() {
        listOf(Interest.None, Interest.Flat(2_500), Interest.Monthly(500)).forEach {
            assertEquals(it, interestFrom(it.kindName(), it.value()))
        }
    }
}
