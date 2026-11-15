package app.trackching

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

enum class RowState { Waiting, Saved, Skipped }

/** One open check-in sheet. [rows] is the due list, oldest first, read once when the sheet opened. */
class CheckIn(val rows: List<DueRepeat>) {
    private val states = mutableStateListOf<RowState>().apply { repeat(rows.size) { add(RowState.Waiting) } }
    private val amounts = mutableStateMapOf<Int, Long>()

    fun stateOf(i: Int): RowState = states[i]

    /** The amount to save for row [i]: what you typed in Edit, or the repeat's own. */
    fun amountOf(i: Int): Long = amounts[i] ?: rows[i].rule.centavos

    /** Edit: changes this time only (decision 4). */
    fun setAmount(i: Int, centavos: Long) {
        require(centavos > 0) { "An amount must be more than ₱0.00" }
        amounts[i] = centavos
    }

    fun mark(i: Int, state: RowState) {
        states[i] = state
    }

    /** The earlier row of the same repeat that's still waiting, if any. Row [i] has to wait for it. */
    fun blockedBy(i: Int): DueRepeat? =
        (0 until i).firstOrNull { rows[it].rule.id == rows[i].rule.id && states[it] == RowState.Waiting }
            ?.let { rows[it] }

    /** Save, Skip and Edit show only when this is true. */
    fun canAct(i: Int): Boolean = states[i] == RowState.Waiting && blockedBy(i) == null

    val waiting: Int get() = states.count { it == RowState.Waiting }
}
