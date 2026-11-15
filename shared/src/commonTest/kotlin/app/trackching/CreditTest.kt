package app.trackching

import app.trackching.db.Balances
import app.trackching.db.CreditAccounts
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreditTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)

    @Test
    fun theDueDateIsThisMonthsUntilItPasses() {
        assertEquals(d(11, 20), nextDueDate(20, d(11, 15)))
        assertEquals(d(11, 15), nextDueDate(15, d(11, 15)))
        assertEquals(d(12, 10), nextDueDate(10, d(11, 15)))
    }

    @Test
    fun aDueDayPastTheMonthsEndIsItsLastDay() {
        assertEquals(d(2, 28), nextDueDate(31, d(2, 3)))
        assertEquals(d(4, 30), nextDueDate(31, d(4, 1)))
        assertEquals(LocalDate(2027, 1, 31), nextDueDate(31, LocalDate(2027, 1, 1)))
    }

    @Test
    fun afterTheLastDayOfTheYearItsJanuary() = assertEquals(LocalDate(2027, 1, 5), nextDueDate(5, LocalDate(2026, 12, 31)))

    @Test
    fun theDueLineSaysWhenInDays() {
        assertEquals("Due Nov 20 · in 5 days", creditDueLine(20, d(11, 15)))
        assertEquals("Due Nov 15 · today", creditDueLine(15, d(11, 15)))
        assertEquals("Due Nov 16 · tomorrow", creditDueLine(16, d(11, 15)))
        assertEquals("No due day set", creditDueLine(null, d(11, 15)))
    }

    private fun card(owed: Long) = CreditAccounts(1, "BPI Card", 0, null, null, owed)

    @Test
    fun overpayingDoesntMakeOwedNegative() {
        assertEquals(0, card(-5_000).owedNow)
        assertEquals(15_000, totalOwed(listOf(card(10_000), card(5_000), card(-2_000))))
    }

    @Test
    fun theTotalBalanceLeavesCreditOut() {
        val accounts = listOf(Balances(1, "Cash", 0, 100_000, "CASH"), Balances(2, "BPI Card", 0, -30_000, "CREDIT"))
        assertEquals(100_000, totalBalance(accounts))
        assertTrue(accounts[1].isCredit)
        assertFalse(accounts[0].isCredit)
    }

    private fun creditForm() = AccountForm(takenNames = emptySet()).apply {
        name = "SPayLater"
        type = AccountType.Credit
    }

    @Test
    fun alreadyOwedIsSavedBelowZeroAndIsOptional() {
        assertEquals(0, creditForm().startingCentavos)
        assertEquals(-150_000, creditForm().apply { starting = "1500" }.startingCentavos)
        assertTrue(creditForm().canSave)
    }

    @Test
    fun dueDayAndMinimumAreOptionalButChecked() {
        val form = creditForm().apply { dueDay = "15"; minimum = "500" }
        assertEquals(15, form.dueDayNumber)
        assertEquals(50_000, form.minimumCentavos)
        assertEquals("The due day is a day of the month, 1 to 31", creditForm().apply { dueDay = "32" }.error)
        assertEquals("The minimum due must be a number, like 500.00", creditForm().apply { minimum = "abc" }.error)
    }

    @Test
    fun otherTypesNeverKeepCreditTerms() {
        val form = creditForm().apply { dueDay = "15"; type = AccountType.Bank; starting = "100" }
        assertNull(form.dueDayNumber)
        assertEquals(10_000, form.startingCentavos)
    }

    @Test
    fun theChargeNoteNamesTheCard() = assertEquals(
        "Saved as an expense under Fees & Charges, and added to what you owe on BPI Card.",
        chargeNote("BPI Card"),
    )

    @Test
    fun payBillRefusesMoreThanTheChargesOrYourOwnMoney() {
        assertEquals("That is more than these charges", billError(10_001, 10_000, "GCash", 50_000))
        assertEquals("GCash only has ₱ 50.00 of your own money", billError(6_000, 10_000, "GCash", 5_000))
        assertNull(billError(5_000, 10_000, "GCash", 5_000))
    }

    @Test
    fun theToastSaysWhichPaymentsWerePaid() {
        assertEquals("Payments 4–5 of Earphones paid", planPaidMessage("Earphones", 4, 5))
        assertEquals("Payment 4 of Earphones paid", planPaidMessage("Earphones", 4, 4))
    }

    @Test
    fun aLoanSummaryAndItsDefaults() {
        val form = BorrowForm().apply { amount = "5000"; into = 1; plan.count = 1; plan.each = "5250"; plan.firstDue = LocalDate(2026, 3, 1) }
        val loan = form.loan()!!
        assertEquals("Cash loan", loan.name)
        assertEquals(1, loan.payments.size)
        assertEquals(25_000, loan.payments.single().interest)
        assertEquals("1 × ₱ 5,250.00 = ₱ 5,250.00 · ₱ 250.00 interest", cardPlanPreview(listOf(525_000), 500_000))
        assertEquals(listOf(1, 3, 6, 12), LoanCounts)
    }

    @Test
    fun aLoanNeedsWhereTheMoneyWent() {
        val form = BorrowForm().apply { amount = "5000"; plan.each = "2000"; plan.firstDue = LocalDate(2026, 3, 1) }
        assertNull(form.loan())
        assertEquals("The amount must be a number, like 5000.00", BorrowForm().apply { amount = "abc" }.error())
    }
}
