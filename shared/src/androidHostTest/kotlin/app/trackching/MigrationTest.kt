package app.trackching

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trackching.db.TrackchingDatabase
import kotlinx.datetime.LocalDate
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationTest {

    /** The committed snapshot of schema version 1. Tests run from the `shared/` folder. */
    private val version1 = File("src/commonMain/sqldelight/databases/1.db")

    private val drivers = mutableListOf<SqlDriver>()

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }

    /** A throwaway copy of 1.db, so a test can never change the committed file. */
    private fun openCopyOfVersion1(): SqlDriver {
        val copy = File.createTempFile("trackching-v1-", ".db").apply { deleteOnExit() }
        version1.copyTo(copy, overwrite = true)
        return JdbcSqliteDriver("jdbc:sqlite:${copy.path}", Properties().apply { put("foreign_keys", "true") })
            .also { drivers += it }
    }

    /**
     * One account and one ₱120 expense, written in plain SQL for version 1's tables.
     * Plain SQL on purpose: the app's generated queries always match the *newest* tables,
     * and this has to keep working against version 1 forever.
     */
    private fun addOneExpense(driver: SqlDriver) {
        driver.execute(null, "INSERT INTO account(name) VALUES ('Cash')", 0)
        driver.execute(null, "INSERT INTO txn(kind, centavos, account_id, occurred_at) VALUES ('EXPENSE', 12000, 1, 0)", 0)
    }

    @Test
    fun version1GetsAnEmptyRepeatTable() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        val db = TrackchingDatabase(driver)
        assertEquals(emptyList(), db.repeatRuleQueries.allRules().executeAsList())
        Ledger(db).addRepeat(RepeatRule(1, RepeatUnit.MONTH, LocalDate(2026, 1, 31)), "EXPENSE", 50_000, 1, null, null)
        assertEquals(1, db.repeatRuleQueries.allRules().executeAsList().size)
    }

    @Test
    fun upgradingFromVersion1KeepsMyTransactions() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        // Exactly what Android does when an older app's database meets this version.
        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        // The app's own queries, on the newest tables, still see the transaction.
        val db = TrackchingDatabase(driver)
        assertEquals(1L, db.txnQueries.countAll().executeAsOne())
        assertEquals(12_000L, db.txnQueries.byId(1).executeAsOne().centavos)
    }

    @Test
    fun oldTransactionsAreNotFromARepeat() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        assertNull(TrackchingDatabase(driver).txnQueries.byId(1).executeAsOne().repeat_id)
    }

    @Test
    fun rebuildingTxnKeepsEveryColumn() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        val txn = TrackchingDatabase(driver).txnQueries.byId(1).executeAsOne()
        assertEquals("EXPENSE", txn.kind)
        assertEquals(1L, txn.account_id)
    }

    @Test
    fun afterUpgradingAnIouCanBeSaved() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        val ledger = Ledger(TrackchingDatabase(driver))
        val id = ledger.addIou(NewIou(IouKind.OWE, "Juan", 50_000, "", LocalDate(2026, 1, 15)), 1, 0)
        assertEquals(50_000, ledger.iouLeft(id))
        assertEquals(50_000 - 12_000, ledger.balance(1))
    }

    @Test
    fun oldEventsDontRepeat() {
        val driver = openCopyOfVersion1()
        driver.execute(null, "INSERT INTO event(name, start_date) VALUES ('Baguio', '2026-12-20')", 0)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        assertNull(eventRowOf(TrackchingDatabase(driver).eventQueries.byId(1).executeAsOne()).repeat)
    }

    @Test
    fun oldEventsAreNotOccasional() {
        val driver = openCopyOfVersion1()
        driver.execute(null, "INSERT INTO event(name, start_date) VALUES ('Baguio', '2026-12-20')", 0)

        TrackchingDatabase.Schema.migrate(driver, 1, TrackchingDatabase.Schema.version)

        val db = TrackchingDatabase(driver)
        assertEquals(false, eventRowOf(db.eventQueries.byId(1).executeAsOne()).occasional)
        assertEquals(emptyList(), db.eventQueries.runsOf(1).executeAsList())
    }
}
