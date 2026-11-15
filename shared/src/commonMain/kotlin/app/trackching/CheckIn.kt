package app.trackching

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import app.trackching.db.DueInstallments

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

/**
 * The check-in's IOU installments (P3-17): each due part, oldest first, read once when the sheet opened.
 * Same rules as repeats: Save, Skip or Edit, and an IOU's later part waits for its earlier one.
 */
class InstallmentCheckIn(val rows: List<DueInstallments>) {
    private val states = mutableStateListOf<RowState>().apply { repeat(rows.size) { add(RowState.Waiting) } }
    private val amounts = mutableStateMapOf<Int, Long>()

    fun stateOf(i: Int): RowState = states[i]

    /** What to pay for row [i]: what you typed in Edit, or the part's own amount. */
    fun amountOf(i: Int): Long = amounts[i] ?: rows[i].centavos

    /** Edit: changes this payment only. The rest of the plan stays. */
    fun setAmount(i: Int, centavos: Long) {
        require(centavos > 0) { "An amount must be more than ₱0.00" }
        amounts[i] = centavos
    }

    fun mark(i: Int, state: RowState) {
        states[i] = state
    }

    /** The same IOU's earlier part that's still waiting, if any. */
    fun blockedBy(i: Int): DueInstallments? =
        (0 until i).firstOrNull { rows[it].iou_id == rows[i].iou_id && states[it] == RowState.Waiting }
            ?.let { rows[it] }

    fun canAct(i: Int): Boolean = states[i] == RowState.Waiting && blockedBy(i) == null

    val waiting: Int get() = states.count { it == RowState.Waiting }
}
