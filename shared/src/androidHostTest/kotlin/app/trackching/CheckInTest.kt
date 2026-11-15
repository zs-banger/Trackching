package app.trackching

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trackching.db.TrackchingDatabase
import kotlinx.datetime.LocalDate
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckInTest {
    private val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        .let { TrackchingDatabase.Schema.create(it); TrackchingDatabase(it) }
        .also { it.seedDefaults() }
    private val ledger = Ledger(db)
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private fun cash() = db.accountQueries.active().executeAsList().first().id
    private fun meals() = ledger.quickCategories("EXPENSE", 50).first { it.name == "Meals" }.id

    /** Rent from Jan 31 and Gym from Feb 15, monthly. Due through Mar 31: Jan 31, Feb 15, Feb 28, Mar 15, Mar 31. */
    private fun checkIn(): CheckIn {
        ledger.addRepeat(RepeatRule(1, RepeatUnit.MONTH, d(1, 31)), "EXPENSE", 1_500_000, cash(), null, meals())
        ledger.addRepeat(RepeatRule(1, RepeatUnit.MONTH, d(2, 15)), "EXPENSE", 200_000, cash(), null, meals())
        return CheckIn(ledger.dueRepeats(d(3, 31)))
    }

    @Test
    fun theOldestRowOfEachRepeatCanBeHandled() {
        val c = checkIn()
        assertEquals(listOf(true, true, false, false, false), c.rows.indices.map { c.canAct(it) })
    }

    @Test
    fun aLaterRowSaysWhichDateIsFirst() =
        assertEquals(d(1, 31), checkIn().blockedBy(2)!!.date)   // rent's Feb 28 waits for rent's Jan 31

    @Test
    fun handlingARowFreesTheNextOneOfThatRepeat() {
        val c = checkIn()
        c.mark(0, RowState.Skipped)
        assertTrue(c.canAct(2))      // rent's Feb 28
        assertFalse(c.canAct(4))     // rent's Mar 31 still waits for Feb 28
        assertFalse(c.canAct(0))     // a handled row has no buttons
    }

    @Test
    fun editChangesOnlyThatRow() {
        val c = checkIn()
        c.setAmount(0, 1_600_000)
        assertEquals(1_600_000L, c.amountOf(0))
        assertEquals(1_500_000L, c.amountOf(2))
    }

    @Test
    fun waitingCountsWhatsLeft() {
        val c = checkIn()
        c.mark(0, RowState.Saved)
        c.mark(1, RowState.Skipped)
        assertEquals(3, c.waiting)
    }
}
