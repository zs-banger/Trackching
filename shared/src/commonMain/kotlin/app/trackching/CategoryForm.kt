package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Category colors. The first one is the default. */
val CategoryColors: List<Long> = listOf(
    0xFFE07A00, 0xFF1663FF, 0xFF7B5CD6, 0xFF1B8A3A, 0xFFC0569A, 0xFF1D9BB3, 0xFF5C616C,
)

/** Category icons. The first one is the default. */
val CategoryIcons: List<String> = listOf(
    "🏷️", "🍔", "☕", "🛒", "🚌", "🚕", "💡", "📱", "🏠", "🎓", "💊", "🎮", "👕", "🎁", "💼", "💰",
)

/**
 * The New category sheet's state and rules.
 * `takenNames(kind, parentId)` returns the names already used under that parent, in lowercase.
 */
class CategoryForm(private val takenNames: (kind: String, parentId: Long?) -> Set<String>) {
    var name by mutableStateOf("")
    var kind by mutableStateOf("EXPENSE")
        private set
    var parentId by mutableStateOf<Long?>(null)
    var color by mutableStateOf(CategoryColors.first())
    var icon by mutableStateOf(CategoryIcons.first())

    /** Switching Expense/Income clears "Under", because the old parent belongs to the other kind. */
    fun pickKind(newKind: String) {
        if (newKind == kind) return
        kind = newKind
        parentId = null
    }

    val error: String?
        get() {
            val clean = name.trim()
            if (clean.isEmpty() || clean.lowercase() !in takenNames(kind, parentId)) return null
            return if (parentId == null) "You already have a main category called $clean"
            else "This main category already has $clean"
        }

    val canSave: Boolean get() = name.isNotBlank() && error == null
}
