package app.trackching

import kotlinx.datetime.LocalDate
import kotlinx.datetime.monthsUntil

/** The transaction kind for money that moves because of an IOU. It's never income or spending. */
const val IOU = "IOU"

/**
 * Which way the money went when the IOU started, as a sign on the account.
 * I borrowed (OWE): money came in. I lent (OWED): money went out. I'm holding someone's money: it came in.
 */
enum class IouKind(val sign: Long) { OWE(1), OWED(-1), HOLDING(1) }

/** Interest on an IOU. Holding money never has any. */
sealed interface Interest {
    data object None : Interest
    data class Flat(val centavos: Long) : Interest
    /** 500 basis points = 5.00% a month. Whole numbers, so no rounding surprises from Double. */
    data class Monthly(val basisPoints: Long) : Interest
}

/** How the database stores it: interest_kind and interest_value. */
fun Interest.kindName(): String = when (this) {
    Interest.None -> "NONE"
    is Interest.Flat -> "FLAT"
    is Interest.Monthly -> "MONTHLY"
}

fun Interest.value(): Long = when (this) {
    Interest.None -> 0
    is Interest.Flat -> centavos
    is Interest.Monthly -> basisPoints
}

fun interestFrom(kind: String, value: Long): Interest = when (kind) {
    "FLAT" -> Interest.Flat(value)
    "MONTHLY" -> Interest.Monthly(value)
    else -> Interest.None
}

/** What was agreed: the amount, the interest, and the dates the interest depends on. */
data class IouTerms(val centavos: Long, val interest: Interest, val openedOn: LocalDate, val dueOn: LocalDate?)

/** One payment, already split. */
data class Paid(val principal: Long, val interest: Long)

/**
 * All the interest the IOU will ever have.
 * Monthly: the rate × the amount × each full month from the start to the due date, at least one.
 * Rounded to the nearest centavo, half up.
 */
fun interestOf(terms: IouTerms): Long = when (val i = terms.interest) {
    Interest.None -> 0
    is Interest.Flat -> i.centavos
    is Interest.Monthly -> {
        val due = requireNotNull(terms.dueOn) { "Interest by the month needs a due date" }
        val months = terms.openedOn.monthsUntil(due).coerceAtLeast(1)
        (terms.centavos * i.basisPoints * months + 5_000) / 10_000
    }
}

/** The amount plus interest, minus everything paid so far. */
fun leftOf(terms: IouTerms, payments: List<Paid>): Long =
    terms.centavos + interestOf(terms) - payments.sumOf { it.principal + it.interest }

fun isSettled(terms: IouTerms, payments: List<Paid>): Boolean = leftOf(terms, payments) <= 0

/** Everything the New IOU sheet (P3-8) will collect. */
data class NewIou(
    val kind: IouKind,
    val person: String,
    val centavos: Long,
    val whatFor: String,
    val openedOn: LocalDate,
    val dueOn: LocalDate? = null,
    val interest: Interest = Interest.None,
    val note: String = "",
    /** "It paid for something" (P3-8): the expense category, instead of money moving. Only for OWE. */
    val paidFor: Long? = null,
    /** Paid back in parts (P3-17). They add up to the amount plus interest; the last one is the due date. */
    val installments: List<PlannedPayment>? = null,
)
