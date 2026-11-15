package app.trackching

import app.trackching.db.Balances
import app.trackching.db.CreditAccounts
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** Credit cards and lenders like SPayLater (P3-12). What they hold is what I owe, never money I have. */
val Balances.isCredit: Boolean get() = type == AccountType.Credit.dbName

/** What's owed, never below zero: paying more than the bill doesn't make it money I have. */
val CreditAccounts.owedNow: Long get() = owed.coerceAtLeast(0)

fun totalOwed(rows: List<CreditAccounts>): Long = rows.sumOf { it.owedNow }

/**
 * The next due date for a due day: this month's if it hasn't passed, otherwise next month's.
 * A due day past the month's end (31 in February) is the month's last day.
 */
fun nextDueDate(dueDay: Int, today: LocalDate): LocalDate {
    fun inMonth(firstOfMonth: LocalDate): LocalDate {
        val last = firstOfMonth.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).day
        return LocalDate(firstOfMonth.year, firstOfMonth.month, minOf(dueDay, last))
    }
    val first = LocalDate(today.year, today.month, 1)
    val thisMonth = inMonth(first)
    return if (thisMonth >= today) thisMonth else inMonth(first.plus(1, DateTimeUnit.MONTH))
}

/** "Due Nov 20 · in 5 days", "Due Nov 15 · today", or "No due day set". */
fun creditDueLine(dueDay: Long?, today: LocalDate): String {
    if (dueDay == null) return "No due day set"
    val due = nextDueDate(dueDay.toInt(), today)
    val days = today.daysUntil(due)
    val whenText = when (days) {
        0 -> "today"
        1 -> "tomorrow"
        else -> "in $days days"
    }
    return "Due ${monthDay(due)} · $whenText"
}

/** What a statement charge is (P3-14). Each is saved under Fees & Charges in its own subcategory. */
enum class ChargeKind(val label: String) { Interest("Interest"), LateFee("Late fee"), AnnualFee("Annual fee") }

/** The Add charge sheet's note. */
fun chargeNote(card: String): String = "Saved as an expense under Fees & Charges, and added to what you owe on $card."

/** "GCash only has ₱ 500.00 of your own money" (P3-13). */
fun ownMoneyMessage(account: String, own: Long): String =
    "$account only has ${formatAmount(own.coerceAtLeast(0), AmountKind.Transfer)} of your own money"

/** Pay bill's one message, or null when it can be paid. */
fun billError(centavos: Long, charges: Long, account: String?, own: Long?): String? = when {
    centavos > charges -> "That is more than these charges"
    account != null && own != null && centavos > own -> ownMoneyMessage(account, own)
    else -> null
}

/** The toast after paying a plan: "Payments 4–5 of Earphones paid", or "Payment 4 of Earphones paid". */
fun planPaidMessage(name: String, first: Long, last: Long): String =
    if (first == last) "Payment $first of $name paid" else "Payments $first–$last of $name paid"
