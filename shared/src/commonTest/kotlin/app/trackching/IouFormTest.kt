package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IouFormTest {
    private val today = LocalDate(2026, 3, 10)
    private fun filled(kind: IouKind = IouKind.OWE) = IouForm(today, accountId = 1).apply {
        this.kind = kind
        person = " Juan "
        amount = "500"
    }

    @Test
    fun aPersonAndAnAmountAreEnough() {
        val iou = assertNotNull(filled().toNewIou())
        assertEquals("Juan", iou.person)
        assertEquals(50_000, iou.centavos)
        assertEquals(Interest.None, iou.interest)
        assertNull(iou.paidFor)
    }

    @Test
    fun aBadAmountSaysSo() {
        val form = filled().apply { amount = "5.555" }
        assertEquals("The amount must be a number, like 1500.00", form.error)
        assertFalse(form.canSave)
    }

    @Test
    fun percentAMonthIsBasisPoints() {
        val form = filled().apply {
            interestMode = InterestMode.Monthly
            interestText = "2.5"
            dueOn = LocalDate(2026, 6, 10)
        }
        assertEquals(Interest.Monthly(250), form.toNewIou()?.interest)
    }

    @Test
    fun interestByTheMonthNeedsADueDate() {
        val form = filled().apply { interestMode = InterestMode.Monthly; interestText = "5" }
        assertEquals("Interest by the month needs a due date", form.error)
    }

    @Test
    fun holdingIgnoresInterestAndDueDate() {
        val form = filled(IouKind.HOLDING).apply {
            interestMode = InterestMode.Flat
            interestText = "100"
            dueOn = LocalDate(2026, 4, 1)
        }
        val iou = assertNotNull(form.toNewIou())
        assertEquals(Interest.None, iou.interest)
        assertNull(iou.dueOn)
    }

    @Test
    fun paidForSomethingNeedsACategoryAndOnlyWhenIOwe() {
        val form = filled().apply { paidSomething = true }
        assertFalse(form.canSave)
        form.categoryId = 7
        assertEquals(7L, form.toNewIou()?.paidFor)

        form.kind = IouKind.OWED // lending can't "pay for something" of mine
        assertNull(form.toNewIou()?.paidFor)
    }

    @Test
    fun theDueDateCantBeBeforeTheStart() {
        val form = filled().apply { dueOn = LocalDate(2026, 3, 1) }
        assertTrue(form.error!!.startsWith("The due date"))
    }

    // P3-17: installments

    private fun inParts(count: String = "3") = filled().apply {
        inParts = true
        partsCount = count
        firstDue = LocalDate(2026, 4, 10)
        fitAmounts()
    }

    @Test
    fun sameEachTimeSplitsTheAmountAndTheLastDateIsDue() {
        val iou = assertNotNull(inParts().toNewIou())
        val plan = assertNotNull(iou.installments)
        assertEquals(listOf(16_666L, 16_666L, 16_668L), plan.map { it.centavos })
        assertEquals(LocalDate(2026, 6, 10), iou.dueOn)
    }

    @Test
    fun interestByTheMonthRunsToTheLastPayment() {
        val form = inParts("2").apply {
            interestMode = InterestMode.Monthly
            interestText = "10"
        }
        val plan = assertNotNull(form.plan)
        // Mar 10 to May 10 is 2 months at 10%: ₱100 interest on ₱500, ₱50 in each payment.
        assertEquals(listOf(5_000L, 5_000L), plan.map { it.interest })
        assertEquals(60_000, plan.sumOf { it.centavos })
    }

    @Test
    fun typedAmountsMustAddUp() {
        val form = inParts("2").apply {
            sameEach = false
            fitAmounts()
            partAmounts[0] = "200"
            partAmounts[1] = "200"
        }
        assertEquals("The payments add up to ₱400.00, not ₱500.00", form.error)
        assertFalse(form.canSave)
        form.partAmounts[1] = "300"
        assertEquals(listOf(20_000L, 30_000L), assertNotNull(form.toNewIou()?.installments).map { it.centavos })
    }

    @Test
    fun oneOrTooManyPaymentsSaySo() {
        assertEquals("Installments need 2 to 60 payments", inParts("1").error)
        assertEquals("Installments need 2 to 60 payments", inParts("61").error)
    }

    @Test
    fun theFirstPaymentCantBeBeforeTheStart() {
        val form = inParts().apply { firstDue = LocalDate(2026, 3, 1) }
        assertEquals("The first payment can't be before the date it started", form.error)
    }

    @Test
    fun holdingIsNeverInParts() {
        val iou = assertNotNull(inParts().apply { kind = IouKind.HOLDING }.toNewIou())
        assertNull(iou.installments)
        assertNull(iou.dueOn)
    }

    @Test
    fun countChangesKeepOneBoxPerPayment() {
        val form = inParts("4").apply { sameEach = false }
        assertEquals(4, form.partAmounts.size)
        form.partsCount = "2"
        form.fitAmounts()
        assertEquals(2, form.partAmounts.size)
    }
}
