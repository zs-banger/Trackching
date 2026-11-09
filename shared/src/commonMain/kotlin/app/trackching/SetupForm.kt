package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The 3 setup steps: 1 your first account, 2 starter categories, 3 done. */
class SetupForm {
    var step by mutableStateOf(1)
        private set

    /** Step 1 uses the New account rules. It starts as a cash wallet; you type what's in it. */
    val account = AccountForm(takenNames = emptySet()).apply {
        name = "Cash on hand"
        type = AccountType.Cash
    }

    /** Step 2: everything ticked to start with. */
    var picked by mutableStateOf(StarterCategories.toSet())
        private set

    fun toggle(category: StarterCategory) {
        picked = if (category in picked) picked - category else picked + category
    }

    /** The ticked ones, in the order they're listed. */
    val pickedCategories: List<StarterCategory>
        get() = StarterCategories.filter { it in picked }

    val categoryError: String?
        get() = if (picked.any { it.kind == "EXPENSE" } && picked.any { it.kind == "INCOME" }) null
        else "Keep at least one expense and one income category"

    val canContinue: Boolean
        get() = when (step) {
            1 -> account.canSave
            2 -> categoryError == null
            else -> true
        }

    fun next() {
        if (canContinue && step < 3) step++
    }

    fun back() {
        if (step > 1) step--
    }
}
