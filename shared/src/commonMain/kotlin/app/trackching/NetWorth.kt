package app.trackching

import app.trackching.db.HeldByAccount

/** Money I'm still holding for others, per account (P3-10). Only accounts that hold some. */
fun heldPerAccount(rows: List<HeldByAccount>): Map<Long, Long> =
    rows.groupBy { it.account_id }
        .mapValues { (_, ious) -> ious.sumOf { (it.centavos - it.paid).coerceAtLeast(0) } }
        .filterValues { it > 0 }

/** Archiving an account that still holds someone's money. */
const val HandBackFirst = "Hand back the money you are holding in this account first."

/**
 * "₱ 5,000.00 of this is held for others · Yours ₱ 5,000.00", or null when nothing is held.
 * [show] masks each amount while HIDE ₱ is on.
 */
fun heldLine(total: Long, held: Long, show: (String) -> String = { it }): String? {
    if (held <= 0) return null
    val yours = total - held
    val yoursText = formatAmount(kotlin.math.abs(yours), if (yours < 0) AmountKind.Expense else AmountKind.Transfer)
    return "${show(formatAmount(held, AmountKind.Transfer))} of this is held for others · Yours ${show(yoursText)}"
}

/** The striped part of an account's bar: its held money as a share of the balance, 0 to 1. */
fun heldShare(balance: Long, held: Long): Float =
    if (balance <= 0 || held <= 0) 0f else (held.toFloat() / balance).coerceIn(0f, 1f)

/**
 * Net worth (P3-10): the total balance, plus what's owed to me, minus what I owe,
 * what I'm holding for others, and what's owed on credit. Each is its own line on the card.
 */
data class NetWorth(
    val total: Long,
    val owedToMe: Long,
    val iOwe: Long,
    val holding: Long,
    val onCredit: Long = 0, // P3-12 fills this in
) {
    val worth: Long get() = total + owedToMe - iOwe - holding - onCredit
}

/** Adds up what's left on each open IOU, by which way it goes. */
fun netWorthOf(total: Long, ious: List<IouRow>, onCredit: Long = 0): NetWorth {
    fun left(kind: IouKind) = ious.filter { it.kind == kind && !it.settled }.sumOf { it.left }
    return NetWorth(total, left(IouKind.OWED), left(IouKind.OWE), left(IouKind.HOLDING), onCredit)
}
