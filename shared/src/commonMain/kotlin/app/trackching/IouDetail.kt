package app.trackching

import app.trackching.db.IouDetail
import app.trackching.db.PaymentRows
import kotlinx.datetime.LocalDate

/** The IOU's terms, for the interest and "what's left" math in Iou.kt. */
fun IouDetail.terms() = IouTerms(
    centavos, interestFrom(interest_kind, interest_value), LocalDate.parse(opened_on), due_on?.let(LocalDate::parse),
)

/** What's left after these payments. */
fun IouDetail.leftAfter(payments: List<PaymentRows>): Long =
    leftOf(terms(), payments.map { Paid(it.principal_centavos, it.interest_centavos) })

/** A payment's whole amount: principal plus interest. */
val PaymentRows.total: Long get() = principal_centavos + interest_centavos

/** "No interest", "₱ 500.00 flat" or "5.00% a month". */
fun interestLabel(interest: Interest): String = when (interest) {
    Interest.None -> "No interest"
    is Interest.Flat -> "₱ ${formatCentavos(interest.centavos)} flat"
    is Interest.Monthly -> "${interest.basisPoints / 100}.${(interest.basisPoints % 100).toString().padStart(2, '0')}% a month"
}

/** The IOU screen's main button: which way the money goes back. */
fun IouKind.actionLabel(): String = when (this) {
    IouKind.OWE -> "Record payment"
    IouKind.OWED -> "Payment received"
    IouKind.HOLDING -> "Hand back"
}

/** "I owe", "Owes me" or "Holding for", above the person's name. */
fun IouKind.withPerson(): String = when (this) {
    IouKind.OWE -> "I owe"
    IouKind.OWED -> "Owes me"
    IouKind.HOLDING -> "Holding for"
}
