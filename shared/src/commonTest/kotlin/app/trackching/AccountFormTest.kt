package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountFormTest {

    private fun filled() = AccountForm(takenNames = setOf("cash on hand")).apply {
        name = "GCash"
        type = AccountType.EWallet
        starting = "1200"
    }

    @Test
    fun completeFormCanSave() {
        val form = filled()
        assertTrue(form.canSave)
        assertEquals(120_000L, form.startingCentavos)
    }

    @Test
    fun colorHasADefault() {
        assertEquals(AccountColors.first(), AccountForm(emptySet()).color)
    }

    @Test
    fun nameTypeAndStartingAreRequired() {
        assertFalse(filled().apply { name = "  " }.canSave)
        assertFalse(filled().apply { type = null }.canSave)
        assertFalse(filled().apply { starting = "" }.canSave)
    }

    @Test
    fun zeroIsAValidStartingBalance() {
        assertTrue(filled().apply { starting = "0" }.canSave)
    }

    @Test
    fun duplicateNameIsCaughtIgnoringCase() {
        val form = filled().apply { name = "Cash On Hand" }
        assertNotNull(form.error)
        assertFalse(form.canSave)
    }

    @Test
    fun badNumberShowsAnError() {
        val form = filled().apply { starting = "12.345" }
        assertNotNull(form.error)
        assertFalse(form.canSave)
    }

    @Test
    fun noErrorWhenFine() {
        assertNull(filled().error)
    }
}