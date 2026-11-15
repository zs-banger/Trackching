package app.trackching

import androidx.compose.runtime.Composable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Reminders are Android only (P3-11). Settings hides the section where this is false, like the iPhone web build. */
expect val remindersSupported: Boolean

/** The phone's side of reminders: the permission, and the once-a-day check. */
interface ReminderControl {
    /** Asks for notification permission if it's needed. [onResult] gets true when reminders can show. */
    fun ask(onResult: (Boolean) -> Unit)

    /** Starts the daily check, or stops it when no switch is on. */
    fun schedule(on: Boolean)
}

@Composable
expect fun rememberReminderControl(): ReminderControl

/** The hour of the morning the daily check runs. */
const val ReminderHour = 8

/** Minutes from [now] to the next [hour] o'clock, so the first check lands in the morning. */
@OptIn(ExperimentalTime::class)
fun minutesUntilMorning(now: Instant, zone: TimeZone, hour: Int = ReminderHour): Long {
    val today = now.toLocalDateTime(zone).date
    var next = LocalDateTime(today, LocalTime(hour, 0)).toInstant(zone)
    if (next <= now) next = LocalDateTime(today.plus(1, DateTimeUnit.DAY), LocalTime(hour, 0)).toInstant(zone)
    return (next - now).inWholeMinutes
}
