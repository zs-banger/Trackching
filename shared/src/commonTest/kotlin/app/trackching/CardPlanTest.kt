package app.trackching

import app.trackching.db.AllCardPayments
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CardPlanTest {
    @Test
    fun interestPerPaymentIsPaymentLessItsShareOfThePrice() {
        // ₱6,000 earphones, 6 × ₱1,064: each payment is ₱1,000 of price and ₱64 of interest.
        assertEquals(List(6) { 6_400L }, cardInterestShares(List(6) { 106_400L }, 600_000))
    }

    @Test
    fun theLastPaymentTakesWhatsLeft() {
        val shares = cardInterestShares(List(3) { 33_400L }, 100_000)
        assertEquals(listOf(67L, 67L, 66L), shares)
        assertEquals(200, shares.sum())
    }

    @Test
    fun paymentsThatDifferGetInterestBySize() {
        val shares = cardInterestShares(listOf(200_000, 250_000, 200_000), 600_000)
        assertEquals(listOf(15_385L, 19_231L, 15_384L), shares)
        assertEquals(50_000, shares.sum())
    }

    @Test
    fun noInterestWhenThePaymentsAreThePrice() = assertEquals(listOf(0L, 0L), cardInterestShares(listOf(500, 500), 1_000))

    @Test
    fun thePreviewShowsTheTrueCost() {
        assertEquals("6 × ₱ 1,064.00 = ₱ 6,384.00 · ₱ 384.00 interest", cardPlanPreview(List(6) { 106_400L }, 600_000))
        assertEquals("2 payments = ₱ 6,500.00 · ₱ 500.00 interest", cardPlanPreview(listOf(300_000, 350_000), 600_000))
    }

    private fun form() = CardPlanForm().apply {
        on = true
        count = 6
        each = "1064"
        firstDue = LocalDate(2026, 12, 15)
    }

    @Test
    fun aPlanHasMonthlyDatesAndInterestParts() {
        val plan = assertNotNull(form().plan(600_000, "Gadgets"))
        assertEquals("Gadgets", plan.name) // no name typed: the category's
        assertEquals(LocalDate(2027, 5, 15), plan.payments.last().dueOn)
        assertEquals(38_400, plan.payments.sumOf { it.interest })
    }

    @Test
    fun paymentsBelowThePriceAreRefused() {
        val f = form().apply { each = "900" }
        assertEquals("The payments add up to less than the price", f.error(600_000))
        assertNull(f.plan(600_000, "Gadgets"))
    }

    @Test
    fun theyDifferNeedsEveryPayment() {
        val f = form().apply { count = 3; sameEach = false; fitAmounts(); name = " Earphones " }
        f.amounts[0] = "2000"; f.amounts[1] = "2500"
        assertNull(f.plan(600_000, "Gadgets"))
        f.amounts[2] = "2000"
        val plan = assertNotNull(f.plan(600_000, "Gadgets"))
        assertEquals("Earphones", plan.name)
        assertEquals(listOf(200_000L, 250_000L, 200_000L), plan.payments.map { it.centavos })
    }

    private fun row(id: Long, n: Long, paid: Long?) =
        AllCardPayments(id, 1, n, "2026-12-${(n + 9)}", 106_400, 6_400, null, paid, "Earphones", 5)

    @Test
    fun theCreditRowLineCountsWhatsLeft() {
        val line = cardPlanLines(listOf(row(1, 1, 99), row(2, 2, null), row(3, 3, null))).single()
        assertEquals("Earphones · 2 of 3 payments left", line.title)
        assertEquals(LocalDate(2026, 12, 11), line.nextDue)
        assertEquals(19_200, line.interest)
    }

    @Test
    fun aPaidOffPlanHasNoLine() = assertEquals(emptyList(), cardPlanLines(listOf(row(1, 1, 99))))
}
