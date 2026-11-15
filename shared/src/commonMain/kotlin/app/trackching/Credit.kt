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
