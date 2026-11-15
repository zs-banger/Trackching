package app.trackching

import app.trackching.db.Balances
import app.trackching.db.History
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Every active account added up, except credit: what a card holds is owed, not mine (P3-12). */
fun totalBalance(accounts: List<Balances>): Long = accounts.filterNot { it.isCredit }.sumOf { it.balance }

/** The `n` main categories with the most spending this month. Unused ones are left out. */
fun topCategories(groups: List<CategoryGroup>, n: Int = 3): List<CategoryGroup> =
    groups.filter { it.total > 0 }.sortedByDescending { it.total }.take(n)

/** Today's transactions, newest first. Empty if nothing was logged today. */
fun todayRows(rows: List<History>, today: LocalDate, zone: TimeZone): List<History> =
    groupByDay(rows, zone).firstOrNull { it.date == today }?.items.orEmpty()
