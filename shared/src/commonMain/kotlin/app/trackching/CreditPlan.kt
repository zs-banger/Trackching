package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trackching.db.AllCardPayments
import kotlinx.datetime.LocalDate

/** How many payments a card plan can have (P3-15). */
val CardPlanCounts = listOf(3, 6, 12)

/**
 * The interest in each payment: payment − price × payment / total.
 * Rounded down per payment, and the last one takes what's left, so they add up to total − price exactly.
 */
fun cardInterestShares(payments: List<Long>, price: Long): List<Long> {
    val total = payments.sum()
    if (total <= price || payments.isEmpty()) return payments.map { 0L }
    val shares = payments.dropLast(1).map { it - price * it / total }
    return shares + ((total - price) - shares.sum())
}

/** A plan to save: its name and each payment, with the interest part. */
data class NewCardPlan(val name: String, val payments: List<PlannedPayment>)

/** "6 × ₱ 1,064.00 = ₱ 6,384.00 · ₱ 384.00 interest", or "3 payments = …" when they differ. */
fun cardPlanPreview(payments: List<Long>, price: Long, show: (String) -> String = { it }): String {
    val total = payments.sum()
    val each = payments.distinct().singleOrNull()
    val head = if (each != null) "${payments.size} × ${show(formatAmount(each, AmountKind.Transfer))}" else "${payments.size} payments"
    val interest = (total - price).coerceAtLeast(0)
    return "$head = ${show(formatAmount(total, AmountKind.Transfer))} · ${show(formatAmount(interest, AmountKind.Transfer))} interest"
}

const val CardPlanNote =
    "The full price counts as spending today. Each payment waits for your OK when it's due, and only its interest part counts as spending that month."

/** The Installments part of the Add sheet, on a credit account (P3-15). */
class CardPlanForm {
    var on by mutableStateOf(false)
    var name by mutableStateOf("")
    var count by mutableStateOf(3)
    var sameEach by mutableStateOf(true)
    var each by mutableStateOf("")
    val amounts = mutableStateListOf<String>()
    var firstDue by mutableStateOf<LocalDate?>(null)

    /** One box per payment for "They differ". */
    fun fitAmounts() {
        while (amounts.size < count) amounts.add("")
        while (amounts.size > count) amounts.removeAt(amounts.size - 1)
    }

    /** Every payment's amount, or null while one is missing. */
    val payments: List<Long>?
        get() = if (sameEach) {
            parseCentavos(each)?.takeIf { it > 0 }?.let { e -> List(count) { e } }
        } else {
            if (amounts.size != count) null
            else amounts.map { parseCentavos(it)?.takeIf { c -> c > 0 } ?: return null }
        }

    /** One message for what you typed. [price] is the cost you typed on the keypad. */
    fun error(price: Long): String? {
        val p = payments
        return when {
            sameEach && each.isNotBlank() && parseCentavos(each).let { it == null || it <= 0 } ->
                "Each payment must be a number, like 1064.00"
            !sameEach && amounts.any { it.isNotBlank() && parseCentavos(it).let { c -> c == null || c <= 0 } } ->
                "Each payment must be a number, like 1064.00"
            p != null && price > 0 && p.sum() < price -> "The payments add up to less than the price"
            else -> null
        }
    }

    /** What the Ledger saves, or null while something's missing. [fallbackName] is the category's name. */
    fun plan(price: Long, fallbackName: String): NewCardPlan? {
        val p = payments ?: return null
        val first = firstDue ?: return null
        if (price <= 0 || error(price) != null) return null
        val dates = installmentDates(first, count, RepeatUnit.MONTH)
        val shares = cardInterestShares(p, price)
        return NewCardPlan(
            name.trim().ifEmpty { fallbackName },
            dates.indices.map { PlannedPayment(dates[it], p[it], shares[it]) },
        )
    }
}

/** A plan on the credit row: "Earphones · 3 of 6 payments left", "Next ₱ X on Mar 10 · ₱ Y interest in total". */
data class CardPlanLine(
    val planId: Long,
    val accountId: Long,
    val name: String,
    val left: Int,
    val total: Int,
    val next: Long?,
    val nextDue: LocalDate?,
    val interest: Long,
) {
    val title: String get() = "$name · $left of $total payments left"
}

/** The plans with payments still waiting, per credit account. */
fun cardPlanLines(rows: List<AllCardPayments>): List<CardPlanLine> =
    rows.groupBy { it.plan_id }.map { (planId, parts) ->
        val waiting = parts.filter { it.paid_txn_id == null }.sortedBy { it.n }
        CardPlanLine(
            planId, parts.first().account_id, parts.first().name, waiting.size, parts.size,
            waiting.firstOrNull()?.centavos, waiting.firstOrNull()?.due_on?.let(LocalDate::parse),
            parts.sumOf { it.interest_centavos },
        )
    }.filter { it.left > 0 }
