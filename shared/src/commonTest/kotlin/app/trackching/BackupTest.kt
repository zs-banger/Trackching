package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackupTest {
    private val today = LocalDate(2026, 11, 10)

    @Test fun fileName() = assertEquals("trackching-backup-2026-11-10.json", backupFileName(today))
    @Test fun never() = assertEquals(LastBackup("Never. Back up once you've added a few things", false), lastBackupOf(null, today))
    @Test fun today() = assertEquals(LastBackup("2026-11-10 · today", false), lastBackupOf(today, today))
    @Test fun yesterday() = assertEquals("2026-11-09 · yesterday", lastBackupOf(LocalDate(2026, 11, 9), today).text)
    @Test fun day29IsFine() = assertFalse(lastBackupOf(LocalDate(2026, 10, 12), today).overdue)
    @Test fun day30IsOverdue() = assertEquals(
        LastBackup("2026-10-11 · 30 days ago. Time for a new one", true),
        lastBackupOf(LocalDate(2026, 10, 11), today),
    )

    @Test fun amounts() {
        assertEquals("1232.00", plainAmount(123_200))
        assertEquals("0.05", plainAmount(5))
        assertEquals("-12.50", plainAmount(-1_250))
    }

    @Test fun csvQuotes() = assertEquals("a,\"b,c\",\"say \"\"hi\"\"\",", csvLine(listOf("a", "b,c", "say \"hi\"", null)))

    @Test fun refusesDamaged() = assertEquals(Opened.Refused(DamagedFile), openBackup("{ not json"))
    @Test fun refusesOtherJson() = assertEquals(Opened.Refused(NotABackup), openBackup("""{"name":"shopping list"}"""))
    @Test fun refusesNewer() = assertEquals(Opened.Refused(NewerBackup), openBackup("""{"app":"trackching","format":99}"""))
    @Test fun refusesMissingParts() = assertEquals(Opened.Refused(DamagedFile), openBackup("""{"app":"trackching","format":1}"""))

    @Test fun roundTripsText() {
        val file = BackupFile(
            createdOn = "2026-11-10",
            accounts = listOf(AccountBackup(1, "Cash", "CASH", 0xFF9B7BFF, 10_000, 0, 0)),
            categories = emptyList(), events = emptyList(), txns = emptyList(),
            settings = mapOf("theme" to "Dark"),
        )
        assertEquals(Opened.Ok(file), openBackup(encodeBackup(file)))
    }
}
