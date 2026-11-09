package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryFormTest {

    // Pretend database: "Meals" exists under main category 1, and "Food" is a main.
    private val taken = { _: String, parentId: Long? ->
        when (parentId) {
            null -> setOf("food")
            1L -> setOf("meals")
            else -> emptySet()
        }
    }

    @Test
    fun needsAName() {
        val form = CategoryForm(taken)
        assertFalse(form.canSave)
        form.name = "   "
        assertFalse(form.canSave)
        form.name = "Health"
        assertTrue(form.canSave)
    }

    @Test
    fun colorAndIconHaveDefaults() {
        val form = CategoryForm(taken)
        assertEquals(CategoryColors.first(), form.color)
        assertEquals(CategoryIcons.first(), form.icon)
    }

    @Test
    fun sameNameUnderSameParentIsBlocked() {
        val form = CategoryForm(taken).apply { name = "MEALS"; parentId = 1 }
        assertNotNull(form.error)
        assertFalse(form.canSave)
    }

    @Test
    fun sameNameUnderAnotherParentIsFine() {
        val form = CategoryForm(taken).apply { name = "Meals"; parentId = 2 }
        assertNull(form.error)
        assertTrue(form.canSave)
    }

    @Test
    fun switchingKindClearsTheParent() {
        val form = CategoryForm(taken).apply { parentId = 1 }
        form.pickKind("INCOME")
        assertNull(form.parentId)
    }
}
