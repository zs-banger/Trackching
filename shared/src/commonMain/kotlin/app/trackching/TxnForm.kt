package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trackching.db.Txn

private const val OneDayMillis = 24L * 60 * 60 * 1000

/** Which date a transaction gets when saved. `Original` only exists when editing. */
enum class DateChoice { Original, Today, Yesterday }

/** The state of the transaction sheet, for a new transaction or an edit. */
class TxnForm(accountId: Long?, val editingId: Long? = null) {
    var kind by mutableStateOf(AmountKind.Expense)
        private set
    var typed by mutableStateOf("")
        private set
    var categoryId by mutableStateOf<Long?>(null)
    var accountId by mutableStateOf(accountId)
    var toAccountId by mutableStateOf<Long?>(null)
    var date by mutableStateOf(DateChoice.Today)
    var originalAt: Long? = null
        private set

    /** The event the transaction was tagged to, kept as-is when editing. */
    var eventId: Long? = null

    /** True after tapping "No event": save without the suggested tag. */
    var tagOff by mutableStateOf(false)

    /** The event to save with: the suggestion, unless you turned it off. */
    fun eventFor(suggested: Long?): Long? = if (tagOff) null else suggested

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

    /** Picking the To account as From clears To, so Save and the hint stay honest. */
    fun pickFrom(id: Long) {
        accountId = id
        if (toAccountId == id) toAccountId = null
    }

    /** The moment to save: the original time, now, or this time yesterday. */
    fun occurredAt(now: Long): Long = when (date) {
        DateChoice.Original -> originalAt ?: now
        DateChoice.Today -> now
        DateChoice.Yesterday -> now - OneDayMillis
    }

    /** The updated row when editing, or null if this is a new transaction or something's missing. */
    fun edited(now: Long): Txn? {
        val id = editingId ?: return null
        val account = accountId ?: return null
        if (!canSave) return null
        val transfer = kind == AmountKind.Transfer
        return Txn(
            id = id,
            kind = kind.name.uppercase(),
            centavos = centavos,
            account_id = account,
            to_account_id = if (transfer) toAccountId else null,
            category_id = if (transfer) null else categoryId,
            occurred_at = occurredAt(now),
            event_id = eventId,
        )
    }

    companion object {
        /** A form filled in from an existing transaction. */
        fun from(txn: Txn): TxnForm = TxnForm(txn.account_id, editingId = txn.id).apply {
            kind = kindOf(txn.kind)
            typed = typedOf(txn.centavos)
            categoryId = txn.category_id
            toAccountId = txn.to_account_id
            originalAt = txn.occurred_at
            date = DateChoice.Original
            eventId = txn.event_id
        }
    }
}

/** 12050 -> "120.50", 12000 -> "120": what you'd have typed on the keypad. */
fun typedOf(centavos: Long): String {
    val pesos = centavos / 100
    val cents = centavos % 100
    return if (cents == 0L) "$pesos" else "$pesos.${cents.toString().padStart(2, '0')}"
}
