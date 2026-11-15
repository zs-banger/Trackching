package app.trackching

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trackching.db.TrackchingDatabase
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails
import java.util.Properties
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    private fun main(name: String, kind: String = "EXPENSE") = ledger.mainCategories(kind).first { it.name == name }.id

    private fun categoryOf(txnId: Long) = db.txnQueries.byId(txnId).executeAsOne().category_id
    private fun names(kind: String) = ledger.quickCategories(kind, 50).map { it.name }

    /** A brand-new database, like a fresh install: no accounts, no categories. */
    private fun emptyDb() = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
    ).let { driver ->
        TrackchingDatabase.Schema.create(driver)
        TrackchingDatabase(driver)
    }

    @Test
    fun aFreshInstallNeedsSetup() = assertFalse(Ledger(emptyDb()).setupDone())

    @Test
    fun anAppWithAccountsCountsAsSetUp() = assertTrue(ledger.setupDone()) // like a phone from before P1-19

    @Test
    fun finishingSetupSavesOnlyWhatYouTicked() {
        val fresh = emptyDb()
        val picked = StarterCategories.filter { it.name == "Food & Beverages" || it.name == "Salary" }
        Ledger(fresh).finishSetup("Wallet", AccountType.Cash, AccountColors.first(), 50_000, picked)

        val wallet = fresh.accountQueries.active().executeAsList().single()
        assertEquals("Wallet", wallet.name)
        assertEquals(50_000L, Ledger(fresh).balance(wallet.id))
        assertEquals(6L, fresh.categoryQueries.countAll().executeAsOne()) // Food + its 4 subs, and Salary
        assertTrue(Ledger(fresh).setupDone()) // a new Ledger, same database: like reopening the app
    }

    @Test
    fun setupNeedsAnExpenseAndAnIncomeCategory() {
        val fresh = emptyDb()
        val onlyFood = StarterCategories.filter { it.name == "Food & Beverages" }
        assertFailsWith<IllegalArgumentException> {
            Ledger(fresh).finishSetup("Wallet", AccountType.Cash, AccountColors.first(), 0, onlyFood)
        }
        assertEquals(0L, fresh.accountQueries.countAll().executeAsOne()) // nothing half-saved
    }

    @Test
    fun setupRunsOnlyOnce() {
        val fresh = Ledger(emptyDb())
        fresh.finishSetup("Wallet", AccountType.Cash, AccountColors.first(), 0, StarterCategories)
        assertFailsWith<IllegalArgumentException> {
            fresh.finishSetup("Wallet 2", AccountType.Cash, AccountColors.first(), 0, StarterCategories)
        }
    }

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

    @Test
    fun newSubcategoryCanBeLoggedAgainst() {
        val health = ledger.addCategory("Health", "EXPENSE", null, CategoryColors[0], "💊")
        ledger.addCategory("Medicine", "EXPENSE", health, CategoryColors[0], "💊")
        val pickable = names("EXPENSE")
        assertTrue("Medicine" in pickable)
        assertFalse("Health" in pickable) // it has a subcategory now, so you log against that
    }

    @Test
    fun sameNameUnderSameParentIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ledger.addCategory("meals", "EXPENSE", main("Food & Beverages"), CategoryColors[0], "🍔")
        }
    }

    @Test
    fun sameNameUnderAnotherParentIsAllowed() {
        ledger.addCategory("Meals", "EXPENSE", main("Transportation"), CategoryColors[0], "🍔")
    }

    @Test
    fun subcategoryOfASubcategoryIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ledger.addCategory("Spicy", "EXPENSE", category("Meals"), CategoryColors[0], "🍔")
        }
    }

    @Test
    fun subcategoryMustMatchItsMainsKind() {
        assertFailsWith<IllegalArgumentException> {
            ledger.addCategory("Bonus", "EXPENSE", main("Salary", "INCOME"), CategoryColors[0], "💰")
        }
    }

    @Test
    fun databaseAlsoBlocksDuplicateMains() {
        // Skips Ledger on purpose: proves the unique index works on its own, even with NULL parents.
        assertFails { db.categoryQueries.create("TRANSPORTATION", "EXPENSE", null, CategoryColors[0], "🚌") }
    }

    @Test
    fun monthTotalsKeepUnusedCategoriesAtZero() {
        ledger.logExpense(12_000, category("Meals"), cash(), occurredAt = 1_000)
        ledger.logExpense(9_999, category("Meals"), cash(), occurredAt = 5_000) // outside the range
        val rows = db.categoryQueries.monthTotals(0, 2_000, "EXPENSE").executeAsList()
        assertEquals(12_000L, rows.first { it.name == "Meals" }.total)
        assertEquals(0L, rows.first { it.name == "Coffee" }.total)
        assertEquals(db.categoryQueries.mains("EXPENSE").executeAsList().size + 7, rows.size)
    }

    @Test
    fun unusedCategoryDeletesStraightAway() {
        ledger.deleteCategory(category("Snacks"), moveTo = null)
        assertFalse("Snacks" in names("EXPENSE"))
    }

    @Test
    fun usedCategoryNeedsSomewhereToGo() {
        ledger.logExpense(5_000, category("Meals"), cash(), 0)
        assertFailsWith<IllegalArgumentException> { ledger.deleteCategory(category("Meals"), moveTo = null) }
    }

    @Test
    fun mergeMovesEveryTransaction() {
        val meals = category("Meals")
        val coffee = category("Coffee")
        val a = ledger.logExpense(5_000, meals, cash(), 0)
        val b = ledger.logExpense(7_000, meals, cash(), 0)
        ledger.deleteCategory(meals, moveTo = coffee)
        assertEquals(coffee, categoryOf(a))
        assertEquals(coffee, categoryOf(b))
        assertEquals(-12_000L, ledger.balance(cash())) // money doesn't change, only the label
    }

    @Test
    fun deletingAMainTakesItsSubsAndTheirTransactions() {
        val txn = ledger.logExpense(5_000, category("Meals"), cash(), 0)
        val jeep = category("Jeep & Bus")
        ledger.deleteCategory(main("Food & Beverages"), moveTo = jeep)
        assertEquals(jeep, categoryOf(txn))
        assertTrue(names("EXPENSE").none { it in listOf("Meals", "Coffee", "Snacks", "Groceries") })
    }

    @Test
    fun cannotMoveIntoItsOwnSubcategory() {
        ledger.logExpense(5_000, category("Meals"), cash(), 0)
        assertFailsWith<IllegalArgumentException> {
            ledger.deleteCategory(main("Food & Beverages"), moveTo = category("Meals"))
        }
    }

    @Test
    fun cannotMoveIntoTheOtherKind() {
        ledger.logExpense(5_000, category("Meals"), cash(), 0)
        assertFailsWith<IllegalArgumentException> {
            ledger.deleteCategory(category("Meals"), moveTo = main("Salary", "INCOME"))
        }
    }

    @Test
    fun moveTargetsLeaveOutItselfAndItsSubs() {
        val targets = ledger.moveTargets(main("Food & Beverages")).map { it.name }
        assertTrue("Jeep & Bus" in targets)
        assertTrue(targets.none { it in listOf("Food & Beverages", "Meals", "Coffee") })
        assertTrue("Salary" !in targets)
    }

    @Test
    fun databaseRefusesToOrphanTransactions() {
        // Skips Ledger on purpose: the foreign key alone blocks it.
        ledger.logExpense(5_000, category("Meals"), cash(), 0)
        assertFails { db.categoryQueries.deleteOne(category("Meals")) }
    }

    @Test
    fun eventIsSavedWithItsDates() {
        val id = ledger.addEvent("  Baguio trip ", LocalDate(2026, 12, 20), LocalDate(2026, 12, 23))
        val e = db.eventQueries.byId(id).executeAsOne()
        assertEquals("Baguio trip", e.name)
        assertEquals("2026-12-20", e.start_date)
        assertEquals("2026-12-23", e.end_date)
    }

    @Test
    fun eventWithoutAnEndIsFine() {
        val id = ledger.addEvent("Laptop fund", LocalDate(2026, 11, 1), null)
        assertEquals(null, db.eventQueries.byId(id).executeAsOne().end_date)
    }

    @Test
    fun eventEndingBeforeItStartsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ledger.addEvent("Oops", LocalDate(2026, 12, 23), LocalDate(2026, 12, 20))
        }
    }

    @Test
    fun databaseAlsoRejectsEndBeforeStart() {
        // Skips Ledger on purpose: the table's CHECK alone blocks it.
        assertFails { db.eventQueries.insert("Oops", "2026-12-23", "2026-12-20") }
    }

    @Test
    fun textDatesCompareAcrossMonthsAndYears() {
        // Zero-padding is what makes this work: "2026-12-31" < "2027-01-01" as text.
        // No assert needed: the test passes if the table's CHECK accepts it, and fails if it throws.
        ledger.addEvent("New Year", LocalDate(2026, 12, 31), LocalDate(2027, 1, 1))
    }

    @Test
    fun eventTotalIsItsTaggedSpending() {
        val trip = ledger.addEvent("Baguio", LocalDate(2026, 12, 20), LocalDate(2026, 12, 23))
        val a = ledger.logExpense(50_000, category("Meals"), cash(), 0)
        val b = ledger.logExpense(20_000, category("Coffee"), cash(), 0)
        ledger.logExpense(9_900, category("Meals"), cash(), 0) // not tagged
        ledger.tagEvent(a, trip)
        ledger.tagEvent(b, trip)
        val row = db.eventQueries.withTotals().executeAsList().single()
        assertEquals(70_000L, row.spent)
    }

    @Test
    fun eventWithNothingTaggedShowsZero() {
        ledger.addEvent("Christmas", LocalDate(2026, 12, 24), LocalDate(2026, 12, 25))
        assertEquals(0L, db.eventQueries.withTotals().executeAsList().single().spent)
    }

    @Test
    fun taggedIncomeDoesNotChangeTheTotal() {
        val trip = ledger.addEvent("Baguio", LocalDate(2026, 12, 20), null)
        val pay = ledger.logIncome(10_000, ledger.quickCategories("INCOME", 50).first().id, cash(), 0)
        ledger.tagEvent(pay, trip)
        assertEquals(0L, db.eventQueries.withTotals().executeAsList().single().spent)
    }

    @Test
    fun editingKeepsTheTag() {
        val trip = ledger.addEvent("Baguio", LocalDate(2026, 12, 20), null)
        val id = ledger.logExpense(50_000, category("Meals"), cash(), 0)
        ledger.tagEvent(id, trip)
        val form = TxnForm.from(ledger.get(id)!!)
        ledger.update(form.edited(now = 0)!!)
        assertEquals(trip, ledger.get(id)!!.event_id)
    }

    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)

    @Test
    fun activeEventCoversItsWholeRange() {
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        assertEquals(null, ledger.activeEventOn(d(12, 19)))
        assertEquals(trip, ledger.activeEventOn(d(12, 20))?.id)  // start day
        assertEquals(trip, ledger.activeEventOn(d(12, 23))?.id)  // end day
        assertEquals(null, ledger.activeEventOn(d(12, 24)))
    }

    @Test
    fun ongoingEventStaysActive() {
        val fund = ledger.addEvent("Laptop fund", d(11, 1), null)
        assertEquals(fund, ledger.activeEventOn(d(12, 31))?.id)
    }

    @Test
    fun newestOverlappingEventWins() {
        ledger.addEvent("Laptop fund", d(11, 1), null)
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        assertEquals(trip, ledger.activeEventOn(d(12, 21))?.id)
    }

    @Test
    fun loggingWithAnEventTagsIt() {
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        val id = ledger.logExpense(50_000, category("Meals"), cash(), 0, eventId = trip)
        assertEquals(trip, ledger.get(id)!!.event_id)
        assertEquals(50_000L, db.eventQueries.withTotals().executeAsList().single().spent)
    }

    @Test
    fun loggingWithoutAnEventLeavesItUntagged() {
        ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        val id = ledger.logExpense(50_000, category("Meals"), cash(), 0)
        assertEquals(null, ledger.get(id)!!.event_id)
    }

    @Test
    fun amountsAreShownUntilYouHideThem() {
        assertFalse(ledger.amountsHidden())
        ledger.setAmountsHidden(true)
        assertTrue(Ledger(db).amountsHidden()) // a new Ledger, same database: like restarting the app
        ledger.setAmountsHidden(false)
        assertFalse(ledger.amountsHidden())
    }

    @Test
    fun eventSpendingCountsSubsUnderTheirMain() {
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        ledger.logExpense(300_00, category("Meals"), cash(), 0, eventId = trip)
        ledger.logExpense(100_00, category("Jeep & Bus"), cash(), 0, eventId = trip)
        ledger.logExpense(50_00, category("Coffee"), cash(), 0) // not tagged: doesn't count
        val spending = db.txnQueries.eventSpending(trip).executeAsList()
        assertEquals(listOf("Food & Beverages" to 300_00L, "Transportation" to 100_00L), spending.map { it.name to it.total })
    }

    @Test
    fun editingAnEventChangesNameAndDates() {
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        ledger.updateEvent(trip, "  Baguio trip ", d(12, 21), null)
        val e = db.eventQueries.byId(trip).executeAsOne()
        assertEquals("Baguio trip", e.name)
        assertEquals("2026-12-21", e.start_date)
        assertEquals(null, e.end_date)
    }

    @Test
    fun deletingAnEventKeepsItsTransactionsAndUndoRetagsThem() {
        val trip = ledger.addEvent("Baguio", d(12, 20), d(12, 23))
        val id = ledger.logExpense(500_00, category("Meals"), cash(), 0, eventId = trip)
        val deleted = ledger.deleteEvent(trip)!!
        assertEquals(null, db.eventQueries.byId(trip).executeAsOneOrNull())
        assertEquals(null, ledger.get(id)!!.event_id) // still there, just untagged
        ledger.restoreEvent(deleted)
        assertEquals("Baguio", db.eventQueries.byId(trip).executeAsOne().name)
        assertEquals(trip, ledger.get(id)!!.event_id)
    }

    @Test
    fun eraseAllEmptiesEveryTableAndUndoesSetup() {
        ledger.setAmountsHidden(true)
        ledger.setTheme(ThemeChoice.Dark)
        ledger.setWeekStart(DayOfWeek.SUNDAY)
        val trip = ledger.addEvent("Trip", d(5, 1), null)
        ledger.logExpense(12_000, category("Meals"), cash(), 0, eventId = trip)

        ledger.eraseAll()

        assertEquals(0L, db.txnQueries.countAll().executeAsOne())
        assertEquals(0L, db.eventQueries.countAll().executeAsOne())
        assertEquals(0L, db.categoryQueries.countAll().executeAsOne())
        assertEquals(0L, db.accountQueries.countAll().executeAsOne())
        assertEquals(0L, db.settingQueries.countAll().executeAsOne())
        assertFalse(ledger.setupDone())
        assertEquals(ThemeChoice.System, ledger.theme())
        assertEquals(DayOfWeek.MONDAY, ledger.weekStart())
    }

    @Test
    fun backupEraseRestoreGivesBackTheSameData() {
        val trip = ledger.addEvent("Trip", d(5, 1), d(5, 3))
        ledger.logExpense(12_345, category("Meals"), cash(), 1_000, eventId = trip)
        ledger.logIncome(500_000, ledger.mainCategories("INCOME").first().id, cash(), 2_000)
        ledger.setTheme(ThemeChoice.Dark)
        val before = ledger.backupFile(d(5, 4))

        val text = encodeBackup(before)
        ledger.eraseAll()
        val opened = openBackup(text)
        ledger.restore((opened as Opened.Ok).file)

        assertEquals(before.copy(settings = before.settings + ("setup_done" to "true")), ledger.backupFile(d(5, 4)))
        assertTrue(ledger.setupDone())
        assertEquals(ThemeChoice.Dark, ledger.theme())
    }

    @Test
    fun aRestoreThatFailsLeavesTheOldDataAlone() {
        ledger.logExpense(12_000, category("Meals"), cash(), 0)
        val before = ledger.backupFile(d(5, 4))
        val broken = before.copy(txns = before.txns + before.txns.first().copy(id = 999, accountId = 12345))

        assertFails { ledger.restore(broken) }

        assertEquals(before, ledger.backupFile(d(5, 4)))
    }

    @Test
    fun csvHasOneRowPerTransactionWithPlainAmounts() {
        ledger.logExpense(123_200, category("Meals"), cash(), 0)
        val lines = ledger.csv(TimeZone.UTC).trim().lines()
        assertEquals("date,type,amount,account,to account,category,subcategory,event,person", lines[0])
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("1970-01-01,Expense,1232.00,"))
    }

    @Test
    fun restoringOnAFreshPhoneFinishesSetup() {
        ledger.logExpense(12_000, category("Meals"), cash(), 0)
        val backup = ledger.backupFile(d(5, 4))
        ledger.eraseAll()
        assertFalse(ledger.setupDone())

        ledger.restore(backup)

        assertTrue(ledger.setupDone())
        assertEquals(1L, db.txnQueries.countAll().executeAsOne())
    }

    @Test
    fun restoreMarksSetupDoneEvenWithoutTheSetting() {
        val backup = ledger.backupFile(d(5, 4))
        val bare = backup.copy(accounts = emptyList(), txns = emptyList(), settings = emptyMap())
        ledger.eraseAll()

        ledger.restore(bare)

        assertTrue(ledger.setupDone())
    }

    private fun rent() = ledger.addRepeat(
        RepeatRule(1, RepeatUnit.MONTH, d(1, 31)), "EXPENSE", 1_500_000, cash(), null, category("Meals"),
    )

    @Test
    fun dueRepeatsAreListedOldestFirstAcrossRules() {
        rent()
        ledger.addRepeat(RepeatRule(1, RepeatUnit.MONTH, d(2, 15)), "EXPENSE", 50_000, cash(), null, category("Meals"))
        assertEquals(
            listOf(d(1, 31), d(2, 15), d(2, 28), d(3, 15), d(3, 31)),
            ledger.dueRepeats(d(3, 31)).map { it.date },
        )
    }

    @Test
    fun savingADueDateLogsItAndMovesOn() {
        rent()
        val first = ledger.dueRepeats(d(3, 31)).first()

        val id = ledger.saveDue(first, 5_000)

        assertEquals(1_500_000L, db.txnQueries.byId(id!!).executeAsOne().centavos)
        assertEquals(listOf(d(2, 28), d(3, 31)), ledger.dueRepeats(d(3, 31)).map { it.date })
    }

    @Test
    fun skippingMovesOnWithoutLogging() {
        rent()
        assertTrue(ledger.skipDue(ledger.dueRepeats(d(3, 31)).first()))
        assertEquals(0L, db.txnQueries.countAll().executeAsOne())
        assertEquals(d(2, 28), ledger.dueRepeats(d(3, 31)).first().date)
    }

    @Test
    fun aDoubleTapLogsOnlyOnce() {
        rent()
        val first = ledger.dueRepeats(d(3, 31)).first()
        assertNotNull(ledger.saveDue(first, 5_000))
        assertNull(ledger.saveDue(first, 5_000))
        assertFalse(ledger.skipDue(first))
        assertEquals(1L, db.txnQueries.countAll().executeAsOne())
        assertEquals(2, ledger.dueRepeats(d(3, 31)).size)   // Feb 28 and Mar 31, nothing skipped
    }

    @Test
    fun backupKeepsRepeatsAndEraseClearsThem() {
        rent()
        ledger.skipDue(ledger.dueRepeats(d(3, 31)).first())
        val before = ledger.backupFile(d(5, 4))
        assertEquals(1, before.repeats.size)

        ledger.eraseAll()
        assertEquals(emptyList(), ledger.dueRepeats(d(3, 31)))

        ledger.restore(openBackup(encodeBackup(before)).let { (it as Opened.Ok).file })
        assertEquals(before.repeats, ledger.backupFile(d(5, 4)).repeats)   // done = 1 came back too
    }

    @Test
    fun aFormat1BackupStillRestores() {
        val old = ledger.backupFile(d(5, 4))
        val text = encodeBackup(old)
            .replace("\"format\": 4", "\"format\": 1")
            .substringBefore(",\n    \"repeats\"") + "\n}"
        ledger.eraseAll()
        val opened = openBackup(text)
        assertTrue(opened is Opened.Ok, "a backup from before P3-1 must still open")
        ledger.restore(opened.file)
        assertEquals(emptyList(), ledger.backupFile(d(5, 4)).repeats)
    }

    private fun monthlyRent(): Long = ledger.logRepeating(
        "EXPENSE", 1_500_000, cash(), null, category("Meals"), 1_000, null,
        RepeatRule(1, RepeatUnit.MONTH, d(1, 31)),
    )

    @Test
    fun logRepeatingSavesTodayAndTheRule() {
        val id = monthlyRent()
        val txn = db.txnQueries.byId(id).executeAsOne()
        assertNotNull(txn.repeat_id)
        // Jan 31 is this transaction, so only the next ones are due.
        assertEquals(listOf(d(2, 28), d(3, 31)), ledger.dueRepeats(d(3, 31)).map { it.date })
    }

    @Test
    fun checkInSavesAreLinkedToTheirRule() {
        val first = monthlyRent()
        val ruleId = db.txnQueries.byId(first).executeAsOne().repeat_id
        val saved = ledger.saveDue(ledger.dueRepeats(d(3, 31)).first(), 2_000)!!
        assertEquals(ruleId, db.txnQueries.byId(saved).executeAsOne().repeat_id)
    }

    @Test
    fun undoRemovesABrandNewRepeat() {
        ledger.undo(monthlyRent())
        assertEquals(0L, db.txnQueries.countAll().executeAsOne())
        assertEquals(emptyList(), ledger.dueRepeats(d(3, 31)))
    }

    @Test
    fun undoKeepsARepeatThatHasOtherTransactions() {
        val ruleId = db.txnQueries.byId(monthlyRent()).executeAsOne().repeat_id!!
        val saved = ledger.saveDue(ledger.dueRepeats(d(3, 31)).first(), 2_000)!!
        ledger.undo(saved)
        assertNotNull(ledger.repeatRule(ruleId))  // Jan 31's transaction still points at it
    }

    @Test
    fun onlyThisOneLeavesTheRuleAlone() {
        val txn = db.txnQueries.byId(monthlyRent()).executeAsOne()
        ledger.update(txn.copy(centavos = 1_600_000))
        assertEquals(1_500_000L, ledger.dueRepeats(d(3, 31)).first().rule.centavos)
    }

    @Test
    fun thisAndTheNextOnesChangesTheRule() {
        val txn = db.txnQueries.byId(monthlyRent()).executeAsOne()
        ledger.updateThisAndNext(txn.copy(centavos = 1_600_000))
        assertEquals(1_600_000L, db.txnQueries.byId(txn.id).executeAsOne().centavos)
        assertEquals(1_600_000L, ledger.dueRepeats(d(3, 31)).first().rule.centavos)
    }

    @Test
    fun stoppingKeepsWhatsSavedAndEndsTheRest() {
        val txn = db.txnQueries.byId(monthlyRent()).executeAsOne()
        ledger.stopRepeat(txn.repeat_id!!)
        assertEquals(emptyList(), ledger.dueRepeats(d(12, 31)))
        assertEquals(1L, db.txnQueries.countAll().executeAsOne())
    }

    @Test
    fun backupKeepsTheLinkToTheRepeat() {
        val id = monthlyRent()
        val before = ledger.backupFile(d(5, 4))
        ledger.eraseAll()
        ledger.restore((openBackup(encodeBackup(before)) as Opened.Ok).file)
        assertNotNull(db.txnQueries.byId(id).executeAsOne().repeat_id)
        assertEquals(before.repeats, ledger.backupFile(d(5, 4)).repeats)
    }

    @Test
    fun anEditedAmountIsSavedForThisTimeOnly() {
        rent()
        val first = ledger.dueRepeats(d(3, 31)).first()
        val id = ledger.saveDue(first, 5_000, centavos = 1_600_000)!!
        assertEquals(1_600_000L, db.txnQueries.byId(id).executeAsOne().centavos)
        assertEquals(1_500_000L, ledger.dueRepeats(d(3, 31)).first().rule.centavos)
    }

    @Test
    fun countDueAddsUpEveryRepeat() {
        rent()
        ledger.addRepeat(RepeatRule(1, RepeatUnit.MONTH, d(2, 15)), "EXPENSE", 50_000, cash(), null, category("Meals"))
        assertEquals(5, countDue(db.repeatRuleQueries.allRules().executeAsList(), d(3, 31)))
    }

    @Test
    fun comingUpIsTheNextSevenDaysOnly() {
        rent()   // Jan 31, Feb 28, Mar 31, Apr 30 …
        val upcoming = ledger.upcomingRepeats(d(3, 25))
        assertEquals(listOf(d(3, 31)), upcoming.map { it.date })          // not Jan 31 or Feb 28 (due), not Apr 30 (too far)
        assertEquals(emptyList(), ledger.upcomingRepeats(d(4, 1)).map { it.date })
    }

    @Test
    fun labelsUseNames() {
        val id = rent()
        val cashName = db.accountQueries.active().executeAsList().first().name
        assertEquals(RepeatLabel("Meals", cashName), ledger.repeatLabels()[id])
    }

    // P3-5: IOUs

    private fun iou(kind: IouKind, person: String = "Juan", centavos: Long = 50_000, interest: Interest = Interest.None) =
        ledger.addIou(NewIou(kind, person, centavos, "Lunch money", d(1, 15), interest = interest), cash(), 0)

    @Test
    fun borrowingRaisesTheAccountButIsNotIncome() {
        val before = ledger.balance(cash())
        iou(IouKind.OWE)
        assertEquals(before + 50_000, ledger.balance(cash()))
        assertEquals(0, ledger.totals(0, Long.MAX_VALUE).income)
    }

    @Test
    fun lendingLowersTheAccountButIsNotSpending() {
        val before = ledger.balance(cash())
        iou(IouKind.OWED)
        assertEquals(before - 50_000, ledger.balance(cash()))
        assertEquals(0, ledger.totals(0, Long.MAX_VALUE).spent)
    }

    @Test
    fun holdingMoneyIsNotIncome() {
        val before = ledger.balance(cash())
        iou(IouKind.HOLDING)
        assertEquals(before + 50_000, ledger.balance(cash()))
        assertEquals(0, ledger.totals(0, Long.MAX_VALUE).income)
    }

    @Test
    fun holdingMoneyNeverHasInterest() {
        val before = db.txnQueries.countAll().executeAsOne()
        assertFailsWith<IllegalArgumentException> { iou(IouKind.HOLDING, interest = Interest.Flat(1_000)) }
        assertEquals(before, db.txnQueries.countAll().executeAsOne()) // nothing half-saved
    }

    @Test
    fun namesAreRememberedOnceAndSuggested() {
        iou(IouKind.OWE, "Juan")
        iou(IouKind.OWED, "  juan ")
        iou(IouKind.OWED, "Maria")
        assertEquals(listOf("Juan"), ledger.personNames("ju"))
        assertEquals(listOf("Maria", "Juan"), ledger.personNames("")) // most recent first
        assertEquals(2, db.iouQueries.backupPeople().executeAsList().size)
    }

    @Test
    fun aNewIouHasEverythingLeft() {
        val id = iou(IouKind.OWE, interest = Interest.Flat(5_000))
        assertEquals(55_000, ledger.iouLeft(id))
    }

    @Test
    fun iouMoneyStaysOutOfTheListUntilP3_8() {
        iou(IouKind.OWE)
        assertEquals(0, db.txnQueries.history().executeAsList().size)
    }

    @Test
    fun iousAreBackedUpAndRestored() {
        val id = iou(IouKind.OWE, interest = Interest.Flat(5_000))
        val balance = ledger.balance(cash())
        val before = ledger.backupFile(d(5, 4))

        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(before)).let { (it as Opened.Ok).file })

        assertEquals(before.ious, ledger.backupFile(d(5, 4)).ious)
        assertEquals(55_000, ledger.iouLeft(id))
        assertEquals(balance, ledger.balance(cash()))
    }

    @Test
    fun csvNamesThePerson() {
        iou(IouKind.OWE)
        val line = ledger.csv(TimeZone.UTC).trim().lines()[1]
        assertTrue(line.startsWith("1970-01-01,IOU,500.00,"))
        assertTrue(line.endsWith(",Juan"))
    }
}
