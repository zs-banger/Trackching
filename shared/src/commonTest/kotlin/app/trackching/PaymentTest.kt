package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentTest {
    private val today = LocalDate(2026, 3, 10)
    private fun form(kind: IouKind = IouKind.OWE, left: Long = 55_000, interestDue: Long = 5_000, own: Map<Long, Long> = emptyMap()) =
        PaymentForm(PaymentContext(kind, "Juan", left, interestDue), today, accountId = 1, own = own)

    @Test
    fun interestIsPaidFirst() {
        assertEquals(Paid(principal = 15_000, interest = 5_000), splitOf(20_000, 5_000))
        assertEquals(Paid(principal = 0, interest = 3_000), splitOf(3_000, 5_000))
    }

    @Test
    fun interestStillDueIsWhatsNotPaidYet() {
        val terms = IouTerms(50_000, Interest.Flat(5_000), today, null)
        assertEquals(2_000, interestDue(terms, listOf(Paid(10_000, 3_000))))
    }

    @Test
    fun theSplitCanBeChanged() {
        val f = form().apply { amount = "200"; interestText = "20" }
        assertEquals(Paid(principal = 18_000, interest = 2_000), f.split)
        assertTrue(f.canSave)
    }

    @Test
    fun youCantPayMoreThanWhatsLeft() {
        val f = form().apply { amount = "551" }
        assertEquals("That's more than what's left (₱ 550.00)", f.error)
    }

    @Test
    fun theInterestPartCantBeMoreThanWhatsDue() {
        val f = form().apply { amount = "200"; interestText = "60" }
        assertEquals("Only ₱ 50.00 of interest is due", f.error)
    }

    @Test
    fun payingWhatIOweNeverUsesMoneyImHolding() {
        val f = form(own = mapOf(1L to 10_000)).apply { amount = "200" }
        assertTrue(f.error!!.startsWith("Only ₱ 100.00 there is your own money"))
        assertFalse(f.canSave)
    }

    @Test
    fun handingBackMayUseTheHeldMoney() {
        val f = form(IouKind.HOLDING, left = 50_000, interestDue = 0, own = mapOf(1L to 0)).apply { amount = "500" }
        assertNull(f.error)
    }

    @Test
    fun theAccountLabelFollowsTheKind() {
        assertEquals("Paid from", IouKind.OWE.paymentAccountLabel())
        assertEquals("Received into", IouKind.OWED.paymentAccountLabel())
        assertEquals("Leaves from", IouKind.HOLDING.paymentAccountLabel())
    }
}
