package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.trackching.db.Txn
import kotlin.test.assertNull

class TxnFormTest {

    private fun TxnForm.type(vararg keys: Key) = keys.forEach { onKey(it) }
    private fun d(text: String) = Key.Digits(text)

    @Test
    fun saveIsOffAtFirst() {
        assertFalse(TxnForm(accountId = 1).canSave)
    }

    @Test
    fun amountAloneIsNotEnough() {
        val form = TxnForm(accountId = 1)
        form.type(d("1"), d("2"), d("0"))
        assertEquals(12_000L, form.centavos)
        assertFalse(form.canSave)
    }

    @Test
    fun categoryAloneIsNotEnough() {
        val form = TxnForm(accountId = 1)
        form.categoryId = 5
        assertFalse(form.canSave)
    }

    @Test
    fun amountAndCategoryTurnSaveOn() {
        val form = TxnForm(accountId = 1)
        form.type(d("1"), d("2"), d("0"))
        form.categoryId = 5
        assertTrue(form.canSave)
    }

    @Test
    fun noAccountMeansNoSave() {
        val form = TxnForm(accountId = null)
        form.type(d("5"))
        form.categoryId = 5
        assertFalse(form.canSave)
    }

    @Test
    fun transferNeedsTwoDifferentAccounts() {
        val form = TxnForm(accountId = 1)
        form.selectKind(AmountKind.Transfer)
        form.type(d("5"))
        assertFalse(form.canSave)
        form.toAccountId = 1
        assertFalse(form.canSave)
        form.toAccountId = 2
        assertTrue(form.canSave)
    }

    @Test
    fun transferDoesNotNeedACategory() {
        val form = TxnForm(accountId = 1)
        form.selectKind(AmountKind.Transfer)
        form.type(d("5"))
        form.toAccountId = 2
        assertEquals(null, form.categoryId)
        assertTrue(form.canSave)
    }

    @Test
    fun switchingTabsDropsTheCategory() {
        val form = TxnForm(accountId = 1)
        form.categoryId = 5
        form.selectKind(AmountKind.Income)
        assertEquals(null, form.categoryId)
    }
    private val lunch = Txn(7, "EXPENSE", 12_050, 1, null, 3, 1_000, null)

    @Test
    fun typedOfMatchesTheKeypad() {
        assertEquals("120.50", typedOf(12_050))
        assertEquals("120", typedOf(12_000))
        assertEquals("0.05", typedOf(5))
    }

    @Test
    fun editFormIsFilledIn() {
        val form = TxnForm.from(lunch)
        assertEquals(12_050L, form.centavos)
        assertEquals(3L, form.categoryId)
        assertEquals(7L, form.editingId)
        assertTrue(form.canSave)
    }

    @Test
    fun editKeepsTheOriginalTime() {
        val form = TxnForm.from(lunch)
        assertEquals(1_000L, form.edited(now = 999_999)!!.occurred_at)
    }

    @Test
    fun editIntoTransferDropsTheCategory() {
        val form = TxnForm.from(lunch)
        form.selectKind(AmountKind.Transfer)
        form.toAccountId = 2
        val row = form.edited(now = 0)!!
        assertEquals("TRANSFER", row.kind)
        assertEquals(null, row.category_id)
    }

    @Test
    fun newFormIsNotAnEdit() {
        assertEquals(null, TxnForm(accountId = 1).edited(now = 0))
    }

    @Test
    fun pickingToAsFromClearsTo() {
        val form = TxnForm(accountId = 1L)
        form.selectKind(AmountKind.Transfer)
        form.toAccountId = 2L
        form.pickFrom(2L)
        assertNull(form.toAccountId)
        assertFalse(form.canSave)
    }
}