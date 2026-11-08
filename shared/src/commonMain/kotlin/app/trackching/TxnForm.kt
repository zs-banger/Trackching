package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The state of the New transaction sheet. */
class TxnForm(accountId: Long?) {
    var kind by mutableStateOf(AmountKind.Expense)
        private set
    var typed by mutableStateOf("")
        private set
    var categoryId by mutableStateOf<Long?>(null)
    var accountId by mutableStateOf(accountId)
    var toAccountId by mutableStateOf<Long?>(null)
    var yesterday by mutableStateOf(false)

    val centavos: Long get() = parseCentavos(typed) ?: 0L

    /** Transfers need two different accounts; the others need a category. */
    val canSave: Boolean
        get() = centavos > 0 && accountId != null && when (kind) {
            AmountKind.Transfer -> toAccountId != null && toAccountId != accountId
            else -> categoryId != null
        }

    fun onKey(key: Key) {
        typed = press(typed, key)
    }

    /** Switching tabs keeps the amount but drops a category from the other list. */
    fun selectKind(newKind: AmountKind) {
        if (newKind != kind) categoryId = null
        kind = newKind
    }
}