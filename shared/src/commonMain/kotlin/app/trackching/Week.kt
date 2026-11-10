package app.trackching

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The first day of the week that holds [today], when weeks start on [startsOn]. */
fun startOfWeek(today: LocalDate, startsOn: DayOfWeek): LocalDate {
    val back = (today.dayOfWeek.ordinal - startsOn.ordinal + 7) % 7
    return today.minus(back, DateTimeUnit.DAY)
}

/** The two theme choices plus "follow the phone". */
enum class ThemeChoice(val label: String) { System("System"), Light("Light"), Dark("Dark") }
