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
}
