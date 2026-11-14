package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDate

/** How the Repeat row ends. Times counts this one (decision 2). */
enum class EndChoice { Forever, Times, Until }

/** The Repeat row on the Add sheet. `unit == null` means Never. */
class RepeatForm {
    var unit by mutableStateOf<RepeatUnit?>(null)
    var every by mutableStateOf(1)
        private set
    var end by mutableStateOf(EndChoice.Forever)
    var times by mutableStateOf(12)
        private set
    var until by mutableStateOf<LocalDate?>(null)

    val on: Boolean get() = unit != null

    fun stepEvery(by: Int) { every = (every + by).coerceIn(1, 99) }

    /** At least 2: "1 time" is just this transaction, which isn't a repeat. */
    fun stepTimes(by: Int) { times = (times + by).coerceIn(2, 99) }

    /** The rule starting on [start], or null if it's Never or the end date isn't after the start yet. */
    fun rule(start: LocalDate): RepeatRule? {
        val u = unit ?: return null
        val ends = when (end) {
            EndChoice.Forever -> RepeatEnd.Never
            EndChoice.Times -> RepeatEnd.After(times)
            EndChoice.Until -> until?.takeIf { it > start }?.let { RepeatEnd.Until(it) } ?: return null
        }
        return RepeatRule(every, u, start, ends)
    }
}

/**
 * "Next: Nov 30, then the last day of each month · 11 more".
 * [done] is how many times are handled already: 1 for a brand-new repeat (today's).
 */
fun repeatSentence(rule: RepeatRule, done: Int = 1): String {
    val next = rule.next(done) ?: return "No more after this one"
    val more = when (val e = rule.end) {
        RepeatEnd.Never -> ""
        is RepeatEnd.After -> " · ${e.times - done} more"
        is RepeatEnd.Until -> " · until ${monthDay(e.date)}"
    }
    return "Next: ${monthDay(next)}, then ${pattern(rule)}$more"
}

private fun pattern(rule: RepeatRule): String {
    val n = rule.every
    val start = rule.start
    return when (rule.unit) {
        RepeatUnit.DAY -> if (n == 1) "every day" else "every $n days"
        RepeatUnit.WEEK -> if (n == 1) "every ${weekday(start)}" else "every $n weeks on ${weekday(start)}"
        RepeatUnit.MONTH -> {
            val day = when {
                start.day == 31 -> "the last day"
                start.day >= 29 -> "the ${ordinal(start.day)} (or the last day)"
                else -> "the ${ordinal(start.day)}"
            }
            if (n == 1) "$day of each month" else "$day of every $n months"
        }
        RepeatUnit.YEAR -> if (n == 1) "every ${monthDay(start)}" else "every $n years on ${monthDay(start)}"
    }
}

/** Dec 31 */
fun monthDay(date: LocalDate): String =
    date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + date.day

/** Monday */
private fun weekday(date: LocalDate): String = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

/** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st, 22nd, 23rd … 31st */
fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
