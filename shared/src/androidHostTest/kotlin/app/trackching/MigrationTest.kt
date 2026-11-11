package app.trackching

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trackching.db.TrackchingDatabase
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

    /** Every (centavos, note) pair in txn, read with plain SQL. */
    private fun amountsAndNotes(driver: SqlDriver): List<Pair<Long, String?>> =
        driver.executeQuery(null, "SELECT centavos, note FROM txn", { cursor ->
            val rows = mutableListOf<Pair<Long, String?>>()
            while (cursor.next().value) rows += cursor.getLong(0)!! to cursor.getString(1)
            QueryResult.Value(rows)
        }, 0).value

    @Test
    fun practiceMigrationAddsAColumnAndKeepsTheTransaction() {
        val driver = openCopyOfVersion1()
        addOneExpense(driver)

        // What a real 1.sqm would hold. Adding a column never touches the rows already there.
        driver.execute(null, "ALTER TABLE txn ADD COLUMN note TEXT", 0)

        val rows = amountsAndNotes(driver)
        assertEquals(1, rows.size)
        assertEquals(12_000L, rows.single().first)
        assertNull(rows.single().second) // old rows get NULL in the new column
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
}
