package app.trackching

import app.trackching.db.DueInstallments
import app.trackching.db.Iou_installment
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** One part of an IOU paid in installments (P3-17): when, the whole amount, and its share of the interest. */
data class PlannedPayment(val dueOn: LocalDate, val centavos: Long, val interest: Long)

/** Installments go by the week or by the month. */
val InstallmentUnits = listOf(RepeatUnit.WEEK, RepeatUnit.MONTH)

/** [count] dates from [first], every week or month. Counted from [first], so Jan 31 → Feb 28 → Mar 31. */
fun installmentDates(first: LocalDate, count: Int, unit: RepeatUnit): List<LocalDate> =
    (0 until count).map { first.plus(it, unit.dateUnit) }

/** "Same each time": equal parts, and the last one takes the leftover centavos. */
fun evenAmounts(total: Long, count: Int): List<Long> {
    require(count >= 1) { "At least one payment" }
    val each = total / count
    return List(count) { if (it == count - 1) total - each * (count - 1) else each }
}

/**
 * Each payment carries the same share of the interest: interest × its part of the total.
 * Rounded down, and the last one takes what's left, so the shares always add up exactly.
 */
fun interestShares(amounts: List<Long>, totalInterest: Long): List<Long> {
    val total = amounts.sum()
    if (total <= 0 || totalInterest <= 0) return amounts.map { 0L }
    val shares = amounts.dropLast(1).map { it * totalInterest / total }
    return shares + (totalInterest - shares.sum())
}

fun planOf(dates: List<LocalDate>, amounts: List<Long>, totalInterest: Long): List<PlannedPayment> {
    require(dates.size == amounts.size) { "One amount per date" }
    return dates.zip(amounts).zip(interestShares(amounts, totalInterest)) { (date, amount), interest ->
        PlannedPayment(date, amount, interest)
    }
}

/** "3 of 6 payments left · next due Mar 10", or null without installments. */
fun installmentsLine(parts: List<Iou_installment>): String? {
    if (parts.isEmpty()) return null
    val waiting = parts.filter { it.state == "WAITING" }
    val left = "${waiting.size} of ${parts.size} payments left"
    val next = waiting.minByOrNull { it.n } ?: return left
    return "$left · next due ${dateLabel(LocalDate.parse(next.due_on))}"
}

/** The check-in's title for a due part: "Pay Juan" or "From Juan". */
fun DueInstallments.title(): String = if (kind == IouKind.OWED.name) "From $person" else "Pay $person"
