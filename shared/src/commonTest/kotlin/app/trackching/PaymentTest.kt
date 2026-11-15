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
    fun youCantHandBackMoreThanWhatsLeft() {
        val f = form(IouKind.HOLDING, left = 50_000, interestDue = 0).apply { amount = "501" }
        assertEquals("That's more than what's left (₱ 500.00)", f.error)
        assertEquals(0, f.extra) // never offered on Holding
    }

    // P3-16: extra paid counts as interest

    @Test
    fun payingMoreAsksWhetherItsInterest() {
        val f = form(IouKind.OWED, left = 50_000, interestDue = 0).apply { amount = "520" }
        assertEquals(2_000, f.extra)
        assertEquals("₱ 20.00 more than they owe. Count it as interest?", f.extraQuestion)
        assertFalse(f.canSave) // not answered yet
        f.countExtra = true
        assertTrue(f.canSave)
        assertEquals(50_000, f.onIou) // the IOU only gets what was left
    }

    @Test
    fun noCapsThePaymentAtWhatsLeft() {
        val f = form(IouKind.OWED, left = 50_000, interestDue = 0).apply { amount = "520" }
        f.capAtLeft()
        assertEquals(50_000, f.centavos)
        assertEquals(0, f.extra)
        assertTrue(f.canSave)
    }

    @Test
    fun whenIOweItSaysYouOwe() {
        val f = form(left = 50_000, interestDue = 0).apply { amount = "510" }
        assertEquals("₱ 10.00 more than you owe. Count it as interest?", f.extraQuestion)
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
