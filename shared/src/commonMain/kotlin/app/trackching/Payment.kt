package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trackching.db.PaymentMoves
import kotlinx.datetime.LocalDate

/** What the payment sheet needs to know about an IOU (P3-9). */
data class PaymentContext(val kind: IouKind, val person: String, val left: Long, val interestDue: Long)

/** Interest still to pay: all of it, minus the interest parts of payments so far. Never below zero. */
fun interestDue(terms: IouTerms, payments: List<Paid>): Long =
    (interestOf(terms) - payments.sumOf { it.interest }).coerceAtLeast(0)

/** Without installments, interest is paid first: a payment covers what interest is due, then the principal. */
fun splitOf(amount: Long, interestDue: Long): Paid {
    val interest = minOf(amount, interestDue)
    return Paid(principal = amount - interest, interest = interest)
}

/**
 * The payment sheet's state and rules. [own] is each account's own money (its balance minus
 * what I'm holding in it for others), only for accounts that hold someone's money.
 */
class PaymentForm(val context: PaymentContext, today: LocalDate, accountId: Long?, private val own: Map<Long, Long>) {
    var amount by mutableStateOf("")
    /** Blank = the automatic split (interest first). Typed = the user's own interest part. */
    var interestText by mutableStateOf("")
    var accountId by mutableStateOf(accountId)
    var date by mutableStateOf(today)

    val centavos: Long? get() = parseCentavos(amount)?.takeIf { it > 0 }

    /** The principal / interest split, or null while the amount or the interest isn't a number. */
    val split: Paid?
        get() {
            val total = centavos ?: return null
            if (interestText.isBlank()) return splitOf(total, context.interestDue)
            val interest = parseCentavos(interestText) ?: return null
            return Paid(principal = total - interest, interest = interest)
        }

    val error: String?
        get() {
            val total = centavos
            val paid = split
            val ownHere = accountId?.let { own[it] }
            return when {
                amount.isNotBlank() && total == null -> "The amount must be a number, like 1500.00"
                total != null && total > context.left -> "That's more than what's left (₱ ${formatCentavos(context.left)})"
                interestText.isNotBlank() && paid == null -> "The interest must be a number, like 50"
                paid != null && paid.principal < 0 -> "The interest can't be more than the payment"
                paid != null && paid.interest > context.interestDue ->
                    "Only ₱ ${formatCentavos(context.interestDue)} of interest is due"
                // Payments come from my own money, never money I'm holding for someone.
                context.kind == IouKind.OWE && total != null && ownHere != null && total > ownHere ->
                    "Only ₱ ${formatCentavos(ownHere.coerceAtLeast(0))} there is your own money. The rest is money you're holding."
                else -> null
            }
        }

    val canSave: Boolean get() = error == null && split != null && accountId != null
}

/** The account label on the sheet: where the money comes from or goes. */
fun IouKind.paymentAccountLabel(): String = when (this) {
    IouKind.OWE -> "Paid from"
    IouKind.OWED -> "Received into"
    IouKind.HOLDING -> "Leaves from"
}

/** Debt payments (I paid back what I owe) and Handed back (money I was holding) in a range. */
data class IouMoves(val debtPayments: Long, val handedBack: Long)

fun iouMovesIn(moves: List<PaymentMoves>, range: Pair<Long, Long>, account: Long? = null): IouMoves {
    val inside = moves.filter { it.occurred_at >= range.first && it.occurred_at < range.second && (account == null || it.account_id == account) }
    return IouMoves(
        debtPayments = inside.filter { it.kind == IouKind.OWE.name }.sumOf { it.centavos },
        handedBack = inside.filter { it.kind == IouKind.HOLDING.name }.sumOf { it.centavos },
    )
}
