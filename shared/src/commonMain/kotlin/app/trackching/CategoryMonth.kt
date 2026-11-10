package app.trackching

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The first day of the month `back` months before `today`'s month. 0 is this month. */
fun monthBack(today: LocalDate, back: Int): LocalDate =
    LocalDate(today.year, today.month, 1).minus(back, DateTimeUnit.MONTH)

/** "NOV 2026" for the month label. */
fun monthTag(month: LocalDate): String = "${month.month.name.take(3)} ${month.year}"

/** One donut slice per main category, using the main's total (its subs included) and color. */
fun categoryShares(groups: List<CategoryGroup>): List<Share> =
    groups.map { Share(it.main.name, it.total, it.main.color) }

/** Each main category's whole % of the month, in the same order as `groups`. Adds up to 100. */
fun mainPercents(groups: List<CategoryGroup>): List<Int> =
    wholePercents(groups.map { maxOf(it.total, 0L) })
