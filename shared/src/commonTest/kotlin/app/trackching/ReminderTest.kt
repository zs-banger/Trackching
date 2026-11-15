package app.trackching

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime

class ReminderTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)

    @Test
    fun theDailyCheckCoversTheDaysSinceTheLastOneUpToAWeek() {
        assertEquals(listOf(d(3, 10)), reminderDays(null, d(3, 10)))
        assertEquals(listOf(d(3, 8), d(3, 9), d(3, 10)), reminderDays(d(3, 7), d(3, 10)))
        assertEquals(7, reminderDays(d(1, 1), d(3, 10)).size)
        assertEquals(emptyList(), reminderDays(d(3, 10), d(3, 10))) // already ran today
    }

    @Test
    fun aReminderTheMorningItsDueAndWhenItTurnsOverdue() {
        val iou = DueThing("iou-1", d(3, 10), "IOU due today", "You owe Maria ₱ 500.00", ReminderTarget.Ious, "IOU overdue")
        assertEquals("IOU due today", remindersFor(listOf(iou), listOf(d(3, 10))).single().title)
        assertEquals("IOU overdue", remindersFor(listOf(iou), listOf(d(3, 11))).single().title)
        assertEquals(emptyList(), remindersFor(listOf(iou), listOf(d(3, 12))))
        val card = iou.copy(overdueTitle = null)
        assertEquals(emptyList(), remindersFor(listOf(card), listOf(d(3, 11))))
    }

    @Test
    fun theTextSaysWhoOwesWhom() {
        assertEquals("You owe Maria ₱ 500.00 · Lunch", iouReminderText(IouKind.OWE, "Maria", 50_000, "Lunch"))
        assertEquals("Maria owes you ₱ 500.00", iouReminderText(IouKind.OWED, "Maria", 50_000, ""))
        assertEquals("Hand back ₱ 500.00 to Maria", iouReminderText(IouKind.HOLDING, "Maria", 50_000, " "))
    }

    @Test
    fun repeatsComeAsOneReminder() {
        assertNull(repeatReminder(emptyList(), d(3, 10)))
        assertEquals("1 repeat waits for your OK", repeatReminder(listOf("Rent"), d(3, 10))!!.title)
        val many = repeatReminder(listOf("Rent", "Netflix", "Rent", "Load", "Gym"), d(3, 10))!!
        assertEquals("5 repeats wait for your OK", many.title)
        assertEquals("Rent, Netflix, Load and more", many.text)
        assertEquals(ReminderTarget.CheckIn, many.target)
    }

    @Test
    fun theBackupNudgeComesWhenTheBackupIsAMonthOld() {
        assertNull(backupReminder(d(3, 1), null, d(3, 30)))
        assertEquals("Your last backup was 30 days ago. Save a fresh copy in Settings.", backupReminder(d(3, 1), null, d(3, 31))!!.text)
        assertNull(backupReminder(d(1, 1), d(3, 20), d(3, 31))) // nudged 11 days ago
        assertEquals(ReminderTarget.Settings, backupReminder(null, null, d(3, 31))!!.target)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun theFirstCheckLandsAtEightInTheMorning() {
        val zone = TimeZone.of("Asia/Manila")
        assertEquals(60, minutesUntilMorning(LocalDateTime(2026, 3, 10, 7, 0).toInstant(zone), zone))
        assertEquals(23 * 60, minutesUntilMorning(LocalDateTime(2026, 3, 10, 9, 0).toInstant(zone), zone))
    }
}
