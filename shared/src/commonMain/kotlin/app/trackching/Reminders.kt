package app.trackching

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The three switches under Settings › Reminders (P3-11). Saved as settings, off until you turn one on. */
enum class ReminderSwitch(val key: String, val title: String, val detail: String) {
    Ious("reminders_ious", "IOU reminders", "The morning a payment is due, and when it turns overdue"),
    Repeats("reminders_repeats", "Repeat reminders", "When a repeat comes due and waits for your OK"),
    Backup("reminders_backup", "Monthly backup reminder", "A nudge when your last backup is a month old"),
}

/** Where tapping a notification opens the app. */
enum class ReminderTarget { Ious, CheckIn, Settings }

/** One notification. [key] stays the same for the same thing, so it's posted once. */
data class Reminder(val key: String, val title: String, val text: String, val target: ReminderTarget)

/** Something with a due date: an IOU, an IOU part, a card payment or a card's due day. */
data class DueThing(
    val key: String,
    val due: LocalDate,
    val dueTitle: String,
    val text: String,
    val target: ReminderTarget,
    /** null: no reminder when it turns overdue. */
    val overdueTitle: String? = null,
)

/** How many days back a missed daily check still reminds about. */
const val ReminderCatchUpDays = 7

/**
 * The days the daily check covers: the days since the last check, up to a week, ending [today].
 * Empty if it already ran today, so nothing is posted twice.
 */
fun reminderDays(lastChecked: LocalDate?, today: LocalDate): List<LocalDate> {
    val earliest = today.minus(ReminderCatchUpDays - 1, DateTimeUnit.DAY)
    val from = if (lastChecked == null) today else maxOf(lastChecked.plus(1, DateTimeUnit.DAY), earliest)
    return generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= today }.toList()
}

/** A reminder for each thing due on one of [days], and for each that turned overdue on one of them. */
fun remindersFor(things: List<DueThing>, days: List<LocalDate>): List<Reminder> = things.mapNotNull { t ->
    val overdueOn = t.due.plus(1, DateTimeUnit.DAY)
    when {
        t.overdueTitle != null && overdueOn in days -> Reminder("${t.key}-overdue", t.overdueTitle, t.text, t.target)
        t.due in days -> Reminder("${t.key}-due", t.dueTitle, t.text, t.target)
        else -> null
    }
}

/** "You owe Maria ₱ 500.00 · Lunch", "Maria owes you …", "Hand back ₱ 500.00 to Maria". */
fun iouReminderText(kind: IouKind, person: String, left: Long, whatFor: String, show: (String) -> String = { it }): String {
    val amount = show(formatAmount(left, AmountKind.Transfer))
    val head = when (kind) {
        IouKind.OWE -> "You owe $person $amount"
        IouKind.OWED -> "$person owes you $amount"
        IouKind.HOLDING -> "Hand back $amount to $person"
    }
    return if (whatFor.isBlank()) head else "$head · ${whatFor.trim()}"
}

/** "1 repeat waits for your OK", "3 repeats wait for your OK", naming the first few. */
fun repeatReminder(titles: List<String>, today: LocalDate): Reminder? {
    if (titles.isEmpty()) return null
    val head = if (titles.size == 1) "1 repeat waits for your OK" else "${titles.size} repeats wait for your OK"
    val names = titles.distinct()
    val text = names.take(3).joinToString(", ") + if (names.size > 3) " and more" else ""
    return Reminder("repeats-$today", head, text, ReminderTarget.CheckIn)
}

/**
 * The monthly backup nudge: when the last backup is 30 days old (or there's none),
 * and no nudge came in the last 30 days.
 */
fun backupReminder(lastBackup: LocalDate?, lastNudge: LocalDate?, today: LocalDate): Reminder? {
    val age = lastBackup?.daysUntil(today)
    if (age != null && age < 30) return null
    if (lastNudge != null && lastNudge.daysUntil(today) < 30) return null
    val text = if (age == null) "You haven't backed up yet. Save a copy of your data in Settings."
    else "Your last backup was $age days ago. Save a fresh copy in Settings."
    return Reminder("backup-$today", "Time for a backup", text, ReminderTarget.Settings)
}
