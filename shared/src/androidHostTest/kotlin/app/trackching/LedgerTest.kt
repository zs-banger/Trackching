package app.trackching

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trackching.db.TrackchingDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails
import java.util.Properties

class LedgerTest {

    private val db = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
    ).let { driver ->
        TrackchingDatabase.Schema.create(driver)
        TrackchingDatabase(driver)
    }.also { it.seedDefaults() }

    private val ledger = Ledger(db)

    private fun cash() = db.accountQueries.active().executeAsList().first().id
    private fun category(name: String) = ledger.quickCategories("EXPENSE", 50).first { it.name == name }.id
    private fun names(kind: String) = ledger.quickCategories(kind, 50).map { it.name }

    @Test
    fun seedingTwiceAddsNothing() {
        db.seedDefaults()
        assertEquals(1L, db.accountQueries.countAll().executeAsOne())
        assertEquals(20L, db.categoryQueries.countAll().executeAsOne())
    }

    @Test
    fun loggingLowersTheAccount() {
        ledger.logExpense(12_000, category("Meals"), cash(), 0)  // ₱120
        assertEquals(-12_000L, ledger.balance(cash()))
    }

    @Test
    fun undoRestoresTheBalance() {
        val id = ledger.logExpense(12_000, category("Meals"), cash(), 0)
        ledger.undo(id)
        assertEquals(0L, ledger.balance(cash()))
    }

    @Test
    fun zeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ledger.logExpense(0, category("Meals"), cash(), 0)
        }
    }

    @Test
    fun defaultOrderBeforeAnyUse() {
        assertEquals(listOf("Meals", "Coffee", "Snacks"), names("EXPENSE").take(3))
    }

    @Test
    fun mostUsedComesFirst() {
        ledger.logExpense(5_000, category("Coffee"), cash(), 0)
        ledger.logExpense(5_000, category("Coffee"), cash(), 0)
        ledger.logExpense(5_000, category("Meals"), cash(), 0)
        assertEquals(listOf("Coffee", "Meals", "Snacks"), names("EXPENSE").take(3))
    }

    @Test
    fun onlyPickableCategoriesShow() {
        assertFalse("Food & Beverages" in names("EXPENSE"))
        assertTrue("Salary" in names("INCOME"))
        assertFalse("Interest" in names("INCOME"))
    }

    @Test
    fun gridShowsAtMostEight() {
        assertTrue(ledger.quickCategories("INCOME").size <= 8)
    }

    @Test
    fun lastUsedAccountIsRemembered() {
        assertEquals(cash(), ledger.lastAccountUsed())
        db.accountQueries.insert("GCash", 0)
        val gcash = db.accountQueries.lastId().executeAsOne()
        ledger.logExpense(5_000, category("Meals"), gcash, 0)
        assertEquals(gcash, ledger.lastAccountUsed())
    }

    private fun newAccount(name: String, starting: Long): Long {
        db.accountQueries.insert(name, starting)
        return db.accountQueries.lastId().executeAsOne()
    }

    @Test
    fun transferMovesMoneyBetweenAccounts() {
        val bpi = newAccount("BPI", 500_000)    // ₱5,000
        val gcash = newAccount("GCash", 120_000) // ₱1,200
        ledger.logTransfer(100_000, bpi, gcash, 0)
        assertEquals(400_000L, ledger.balance(bpi))
        assertEquals(220_000L, ledger.balance(gcash))
    }

    @Test
    fun transferIsNotIncomeOrSpending() {
        val bpi = newAccount("BPI", 500_000)
        val gcash = newAccount("GCash", 0)
        ledger.logExpense(12_000, category("Meals"), bpi, 10)
        ledger.logTransfer(100_000, bpi, gcash, 20)
        val totals = ledger.totals(0, 100)
        assertEquals(0L, totals.income)
        assertEquals(12_000L, totals.spent)
    }

    @Test
    fun transferToSameAccountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ledger.logTransfer(5_000, cash(), cash(), 0)
        }
    }

    @Test
    fun incomeRaisesTheAccount() {
        val salary = ledger.quickCategories("INCOME", 50).first { it.name == "Salary" }.id
        ledger.logIncome(2_000_000, salary, cash(), 0)
        assertEquals(2_000_000L, ledger.balance(cash()))
    }

    @Test
    fun historyHandlesAThousandRows() {
        val meals = category("Meals")
        db.transaction {
            repeat(1_000) { i -> ledger.logExpense(100, meals, cash(), i.toLong()) }
        }
        val rows = db.txnQueries.history().executeAsList()
        assertEquals(1_000, rows.size)
        assertEquals(999L, rows.first().occurred_at) // newest first
    }

    @Test
    fun editingChangesTheBalance() {
        val id = ledger.logExpense(12_000, category("Meals"), cash(), 0)
        val row = ledger.get(id)!!
        ledger.update(row.copy(centavos = 20_000))
        assertEquals(-20_000L, ledger.balance(cash()))
    }

    @Test
    fun deleteThenRestoreIsExact() {
        val id = ledger.logExpense(12_000, category("Meals"), cash(), 1_234)
        val before = ledger.get(id)!!
        val deleted = ledger.delete(id)!!
        assertEquals(0L, ledger.balance(cash()))
        ledger.restore(deleted)
        assertEquals(before, ledger.get(id))          // same id, same everything
        assertEquals(-12_000L, ledger.balance(cash()))
    }

    @Test
    fun editingIntoABrokenTransferIsRejected() {
        val id = ledger.logExpense(12_000, category("Meals"), cash(), 0)
        val row = ledger.get(id)!!
        assertFailsWith<IllegalArgumentException> {
            ledger.update(row.copy(kind = "TRANSFER", category_id = null, to_account_id = cash()))
        }
    }

    @Test
    fun newAccountShowsItsStartingBalance() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 120_000)
        assertEquals(120_000L, ledger.balance(gcash))
        assertTrue(ledger.accounts().any { it.name == "GCash" })
    }

    @Test
    fun duplicateNameIsRejectedIgnoringCase() {
        ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        assertFailsWith<IllegalArgumentException> {
            ledger.addAccount("gcash", AccountType.EWallet, AccountColors[1], 0)
        }
    }

    @Test
    fun databaseAlsoBlocksDuplicates() {
        db.accountQueries.create("BPI", "BANK", AccountColors[2], 0)
        assertFails { db.accountQueries.create("bpi", "BANK", AccountColors[2], 0) }
    }

    @Test
    fun namesAreTrimmed() {
        val id = ledger.addAccount("  Maya  ", AccountType.EWallet, AccountColors[0], 0)
        assertEquals("Maya", ledger.accounts().first { it.id == id }.name)
    }

    @Test
    fun orderSurvivesAReload() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        val bpi = ledger.addAccount("BPI", AccountType.Bank, AccountColors[2], 0)
        ledger.reorder(listOf(bpi, cash(), gcash))
        val fresh = Ledger(db) // like restarting the app: a new Ledger, same database
        assertEquals(listOf("BPI", "Cash on hand", "GCash"), fresh.accounts().map { it.name })
    }

    @Test
    fun archivedAccountLeavesPickersButKeepsItsTransactions() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        ledger.logExpense(5_000, category("Meals"), gcash, 0)
        ledger.archive(gcash)
        assertTrue(ledger.accounts().none { it.id == gcash })
        assertEquals(1, db.txnQueries.history().executeAsList().size)
    }

    @Test
    fun archivedAccountIsNotTheDefault() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        ledger.logExpense(5_000, category("Meals"), gcash, 0)
        ledger.archive(gcash)
        assertEquals(cash(), ledger.lastAccountUsed())
    }

    @Test
    fun restoredAccountComesBack() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        ledger.archive(gcash)
        ledger.restoreAccount(gcash)
        assertTrue(ledger.accounts().any { it.id == gcash })
    }

    @Test
    fun lastAccountCannotBeArchived() {
        assertFailsWith<IllegalArgumentException> { ledger.archive(cash()) }
    }

    @Test
    fun newAccountGoesLast() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        ledger.reorder(listOf(gcash, cash()))
        ledger.addAccount("Maya", AccountType.EWallet, AccountColors[2], 0)
        assertEquals("Maya", ledger.accounts().last().name)
    }

    @Test
    fun archivedNamesStillCountAsTaken() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        ledger.archive(gcash)
        assertTrue("gcash" in ledger.accountNames())
        assertEquals(setOf("gcash"), ledger.archivedAccountNames())
    }

    @Test
    fun editingShowsAnArchivedAccount() {
        val gcash = ledger.addAccount("GCash", AccountType.EWallet, AccountColors[1], 0)
        val id = ledger.logExpense(5_000, category("Meals"), gcash, 0)
        ledger.archive(gcash)
        assertTrue(ledger.accountsFor(ledger.get(id)).any { it.id == gcash })
        assertTrue(ledger.accountsFor(null).none { it.id == gcash })
    }

    @Test
    fun transactionNeedsARealAccount() {
        assertFails { ledger.logExpense(5_000, category("Meals"), accountId = 999, occurredAt = 0) }
    }

    @Test
    fun adjustingSetsTheExactBalance() {
        val gcash = newAccount("GCash", 100_000)
        ledger.logExpense(12_000, category("Meals"), gcash, 0)   // app says ₱880
        ledger.adjust(gcash, 85_000, 0)                            // wallet says ₱850
        assertEquals(85_000L, ledger.balance(gcash))
        ledger.adjust(gcash, 90_000, 0)                            // found ₱50
        assertEquals(90_000L, ledger.balance(gcash))
    }

    @Test
    fun adjustmentIsNotIncomeOrSpending() {
        val gcash = newAccount("GCash", 100_000)
        ledger.adjust(gcash, 50_000, 0)
        ledger.adjust(gcash, 70_000, 0)
        val t = ledger.totals(0, 1)
        assertEquals(0L, t.income)
        assertEquals(0L, t.spent)
    }

    @Test
    fun matchingBalanceSavesNothing() {
        val gcash = newAccount("GCash", 100_000)
        assertEquals(null, ledger.adjust(gcash, 100_000, 0))
        assertEquals(0, db.txnQueries.history().executeAsList().size)
    }
}