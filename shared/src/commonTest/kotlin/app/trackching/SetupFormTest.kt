package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupFormTest {
    @Test
    fun startsWithACashAccountAndEverythingTicked() {
        val form = SetupForm()
        assertEquals(1, form.step)
        assertEquals("Cash on hand", form.account.name)
        assertEquals(StarterCategories, form.pickedCategories)
    }

    @Test
    fun stepOneNeedsAStartingBalance() {
        val form = SetupForm()
        form.next()
        assertEquals(1, form.step) // blocked: no starting balance typed yet
        form.account.starting = "500"
        form.next()
        assertEquals(2, form.step)
    }

    @Test
    fun untickingRemovesACategoryAndTickingBringsItBack() {
        val form = SetupForm()
        val salary = StarterCategories.first { it.name == "Salary" }
        form.toggle(salary)
        assertFalse(salary in form.pickedCategories)
        form.toggle(salary)
        assertTrue(salary in form.pickedCategories)
    }

    @Test
    fun needsOneExpenseAndOneIncome() {
        val form = SetupForm()
        StarterCategories.filter { it.kind == "INCOME" }.forEach { form.toggle(it) } // untick all income
        assertNotNull(form.categoryError)
        form.toggle(StarterCategories.first { it.name == "Other" })
        assertNull(form.categoryError)
    }

    @Test
    fun backNeverGoesBelowStepOne() {
        val form = SetupForm()
        form.back()
        assertEquals(1, form.step)
    }
}