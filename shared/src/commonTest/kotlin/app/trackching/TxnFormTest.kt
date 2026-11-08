package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}