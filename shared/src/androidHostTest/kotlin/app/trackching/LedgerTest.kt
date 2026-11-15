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
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        db.accountQueries.create("BPI", "BANK", AccountColors[2], 0, null, null)
        assertFails { db.accountQueries.create("bpi", "BANK", AccountColors[2], 0, null, null) }
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
        assertFails { db.eventQueries.insert("Oops", "2026-12-23", "2026-12-20", null, null, 0) }
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
            .replace("\"format\": 9", "\"format\": 1")
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

    // P3-4: repeating events

    private val weekly = EventRepeat(1, RepeatUnit.WEEK)

    @Test
    fun aRepeatingEventKeepsItsRepeat() {
        val id = ledger.addEvent("Groceries", d(11, 9), null, weekly)
        assertEquals(weekly, eventRowOf(db.eventQueries.byId(id).executeAsOne()).repeat)
    }

    @Test
    fun editingCanTurnTheRepeatOff() {
        val id = ledger.addEvent("Groceries", d(11, 9), null, weekly)
        ledger.updateEvent(id, "Groceries", d(11, 9), null, repeat = null)
        assertNull(eventRowOf(db.eventQueries.byId(id).executeAsOne()).repeat)
    }

    @Test
    fun undoingADeleteKeepsTheRepeat() {
        val id = ledger.addEvent("Groceries", d(11, 9), null, weekly)
        ledger.restoreEvent(ledger.deleteEvent(id)!!)
        assertEquals(weekly, eventRowOf(db.eventQueries.byId(id).executeAsOne()).repeat)
    }

    @Test
    fun theListShowsThisWeeksTotalOnly() {
        val zone = TimeZone.UTC
        val id = ledger.addEvent("Groceries", d(11, 2), null, weekly)
        fun spend(day: LocalDate, centavos: Long) {
            val txn = ledger.logExpense(centavos, category("Meals"), cash(), day.atStartOfDayIn(zone).toEpochMilliseconds())
            ledger.tagEvent(txn, id)
        }
        spend(d(11, 5), 100_000)  // last week
        spend(d(11, 10), 30_000)  // this week
        spend(d(11, 12), 20_000)  // this week

        val events = splitEvents(db.eventQueries.withTotals().executeAsList(), d(11, 13))
        val active = withPeriodTotals(
            events.getValue(EventStatus.Active), db.eventQueries.expensesByEvent().executeAsList(),
            today = d(11, 13), weekStart = DayOfWeek.MONDAY, zone = zone,
        )
        assertEquals(50_000, active.single().spent)
    }

    @Test
    fun repeatingEventsAreBackedUp() {
        val id = ledger.addEvent("Groceries", d(11, 9), null, weekly)
        val before = ledger.backupFile(d(11, 15))
        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(before)).let { (it as Opened.Ok).file })
        assertEquals(weekly, eventRowOf(db.eventQueries.byId(id).executeAsOne()).repeat)
    }

    // P3-19: occasional events

    private fun trips() = ledger.addEvent("Out-of-town trips", d(3, 10), d(3, 14), occasional = true)
    private fun runsOf(id: Long) = db.eventQueries.runsOf(id).executeAsList().map { it.toRun() }

    @Test
    fun anOccasionalEventStartsWithItsFirstRun() {
        val id = trips()
        assertEquals(listOf(d(3, 10) to d(3, 14)), runsOf(id).map { it.first to it.last })
    }

    @Test
    fun anOccasionalEventNeedsAnEndDate() {
        assertFailsWith<IllegalArgumentException> { ledger.addEvent("Trips", d(3, 10), null, occasional = true) }
    }

    @Test
    fun overlappingDatesAreRefused() {
        val id = trips()
        assertFailsWith<IllegalArgumentException> { ledger.addRun(id, d(3, 14), d(3, 16)) }
        assertEquals(1, runsOf(id).size)
    }

    @Test
    fun anotherEventMayUseTheSameDates() {
        trips()
        val other = ledger.addEvent("Work trips", d(1, 1), d(1, 2), occasional = true)
        ledger.addRun(other, d(3, 10), d(3, 14))
        assertEquals(2, runsOf(other).size)
    }

    @Test
    fun theEventsOwnDatesFollowItsRuns() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        val event = eventRowOf(db.eventQueries.byId(id).executeAsOne())
        assertEquals(d(3, 10), event.start)
        assertEquals(d(6, 5), event.end)
    }

    @Test
    fun newTransactionsAreTaggedOnlyDuringARun() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        assertEquals(id, ledger.activeEventOn(d(3, 12))?.id)
        assertNull(ledger.activeEventOn(d(4, 20)))   // between the two trips
        assertEquals(id, ledger.activeEventOn(d(6, 1))?.id)
    }

    @Test
    fun deletingAndUndoingKeepsTheRuns() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        ledger.restoreEvent(ledger.deleteEvent(id)!!)
        assertEquals(2, runsOf(id).size)
    }

    @Test
    fun runsAreBackedUp() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        val before = ledger.backupFile(d(11, 15))
        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(before)).let { (it as Opened.Ok).file })
        assertEquals(2, runsOf(id).size)
        assertTrue(eventRowOf(db.eventQueries.byId(id).executeAsOne()).occasional)
    }

    @Test
    fun theListShowsTheShownRunsTotalOnly() {
        val zone = TimeZone.UTC
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        fun spend(day: LocalDate, centavos: Long) {
            val txn = ledger.logExpense(centavos, category("Meals"), cash(), day.atStartOfDayIn(zone).toEpochMilliseconds())
            ledger.tagEvent(txn, id)
        }
        spend(d(3, 11), 100_000)  // March trip
        spend(d(6, 2), 40_000)    // June trip

        val today = d(6, 3)
        val runs = db.eventQueries.allRuns().executeAsList().map { it.toRun() }.groupBy { it.eventId }
        val active = withPeriodTotals(
            splitEvents(db.eventQueries.withTotals().executeAsList(), today, runs).getValue(EventStatus.Active),
            db.eventQueries.expensesByEvent().executeAsList(), today, DayOfWeek.MONDAY, zone,
        )
        assertEquals(40_000, active.single().spent)
    }

    // P3-20: changing and deleting runs

    private val utc = TimeZone.UTC
    private fun spendOn(id: Long, day: LocalDate): Long {
        val txn = ledger.logExpense(10_000, category("Meals"), cash(), day.atStartOfDayIn(utc).toEpochMilliseconds())
        ledger.tagEvent(txn, id)
        return txn
    }
    private fun eventOf(txn: Long) = db.txnQueries.byId(txn).executeAsOne().event_id

    @Test
    fun aRunsDatesCanChange() {
        val id = trips()
        val run = runsOf(id).single()
        ledger.changeRun(run.id, d(3, 9), d(3, 15), utc)
        assertEquals(d(3, 9) to d(3, 15), runsOf(id).single().let { it.first to it.last })
        assertEquals(d(3, 9), eventRowOf(db.eventQueries.byId(id).executeAsOne()).start)
    }

    @Test
    fun changedDatesMayNotOverlapAnotherRun() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        val march = runsOf(id).first()
        assertFailsWith<IllegalArgumentException> { ledger.changeRun(march.id, d(3, 10), d(6, 1), utc) }
        ledger.changeRun(march.id, d(3, 12), d(3, 14), utc) // overlapping its own old dates is fine
    }

    @Test
    fun shrinkingARunUntagsWhatFallsOutside() {
        val id = trips()
        val inside = spendOn(id, d(3, 11))
        val outside = spendOn(id, d(3, 14))
        val undo = ledger.changeRun(runsOf(id).single().id, d(3, 10), d(3, 12), utc)
        assertEquals(listOf(outside), undo.untagged)
        assertEquals(id, eventOf(inside))
        assertNull(eventOf(outside))
        ledger.undoRun(undo)
        assertEquals(id, eventOf(outside))
        assertEquals(d(3, 14), runsOf(id).single().last)
    }

    @Test
    fun deletingARunUntagsItsTransactionsAndUndoBringsThemBack() {
        val id = trips()
        ledger.addRun(id, d(6, 1), d(6, 5))
        val march = spendOn(id, d(3, 11))
        val june = spendOn(id, d(6, 2))
        val undo = ledger.deleteRun(runsOf(id).first().id, utc)
        assertNull(eventOf(march))
        assertEquals(id, eventOf(june))
        assertNotNull(db.txnQueries.byId(march).executeAsOneOrNull()) // still saved
        assertEquals(d(6, 1), eventRowOf(db.eventQueries.byId(id).executeAsOne()).start)

        ledger.undoRun(undo)
        assertEquals(2, runsOf(id).size)
        assertEquals(id, eventOf(march))
        assertEquals(d(3, 10), eventRowOf(db.eventQueries.byId(id).executeAsOne()).start)
    }

    @Test
    fun theOnlyRunCantBeDeleted() {
        val id = trips()
        assertFailsWith<IllegalArgumentException> { ledger.deleteRun(runsOf(id).single().id, utc) }
        assertEquals(1, runsOf(id).size)
    }

    // P3-6: the IOUs tab

    private fun rows(today: LocalDate) = db.iouQueries.iouList().executeAsList().map { it.toRow(today) }

    @Test
    fun theListWorksOutWhatsLeftWithInterest() {
        iou(IouKind.OWE, interest = Interest.Flat(5_000))
        assertEquals(55_000, rows(d(2, 1)).single().left)
    }

    @Test
    fun theListNamesThePerson() {
        iou(IouKind.OWED, "Maria")
        assertEquals("Maria", rows(d(2, 1)).single().person)
    }

    @Test
    fun anIouPastItsDueDateIsOverdue() {
        ledger.addIou(NewIou(IouKind.OWED, "Juan", 50_000, "", d(1, 15), dueOn = d(2, 15)), cash(), 0)
        assertFalse(rows(d(2, 15)).single().overdue)
        assertTrue(rows(d(2, 16)).single().overdue)
    }

    // P3-7: an IOU's payments

    /** A payment on an IOU I owe: the money leaves the account. Recording comes with P3-9, so the test does it by hand. */
    private fun pay(iouId: Long, centavos: Long, day: LocalDate, interest: Long = 0): Long {
        db.txnQueries.insert(IOU, -centavos, cash(), null, null, day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        val txn = db.txnQueries.lastId().executeAsOne()
        db.iouQueries.insertPayment(iouId, txn, centavos - interest, interest)
        return db.iouQueries.lastId().executeAsOne()
    }

    @Test
    fun anIouShowsItsPersonAndTerms() {
        val id = iou(IouKind.OWE, "Maria", interest = Interest.Flat(5_000))
        val detail = db.iouQueries.iouDetail(id).executeAsOne()
        assertEquals("Maria", detail.person)
        assertEquals(55_000, detail.leftAfter(emptyList()))
    }

    @Test
    fun paymentsAreNewestFirstWithTheirAccount() {
        val id = iou(IouKind.OWE)
        val first = pay(id, 10_000, d(2, 1))
        val second = pay(id, 5_000, d(3, 1), interest = 1_000)
        val rows = db.iouQueries.paymentRows(id).executeAsList()
        assertEquals(listOf(second, first), rows.map { it.id })
        assertEquals(5_000, rows.first().total)
        assertEquals(db.accountQueries.active().executeAsList().first().name, rows.first().account)
        assertEquals(35_000, db.iouQueries.iouDetail(id).executeAsOne().leftAfter(rows))
    }

    @Test
    fun deletingAPaymentPutsTheAmountBack() {
        val id = iou(IouKind.OWE)
        val before = ledger.balance(cash())
        val payment = pay(id, 10_000, d(2, 1))
        assertEquals(before - 10_000, ledger.balance(cash()))

        val deleted = assertNotNull(ledger.deletePayment(payment))
        assertEquals(before, ledger.balance(cash()))
        assertEquals(50_000, ledger.iouLeft(id))

        ledger.restorePayment(deleted)
        assertEquals(before - 10_000, ledger.balance(cash()))
        assertEquals(40_000, ledger.iouLeft(id))
    }

    // P3-8: add and edit an IOU

    private fun spendingIn(categoryId: Long) =
        db.txnQueries.backupAll().executeAsList().filter { it.kind == "EXPENSE" && it.category_id == categoryId }

    @Test
    fun aFriendPayingForLunchIsSpendingWithNoAccountChange() {
        val before = ledger.balance(cash())
        val meals = category("Meals")
        val id = ledger.addIou(NewIou(IouKind.OWE, "Juan", 25_000, "Lunch", d(1, 15), paidFor = meals), cash(), 0)
        assertEquals(before, ledger.balance(cash()))
        assertEquals(listOf(25_000L), spendingIn(meals).map { it.centavos })
        assertEquals(25_000, ledger.iouLeft(id))
    }

    @Test
    fun onlyAnIouIOweCanPayForSomething() {
        assertFailsWith<IllegalArgumentException> {
            ledger.addIou(NewIou(IouKind.OWED, "Juan", 25_000, "", d(1, 15), paidFor = category("Meals")), cash(), 0)
        }
    }

    @Test
    fun editingChangesTheIouAndItsMoney() {
        val before = ledger.balance(cash())
        val id = iou(IouKind.OWE) // ₱500 came in
        ledger.updateIou(id, NewIou(IouKind.OWED, "Maria", 30_000, "Rent", d(1, 20)), cash(), 0)
        assertEquals(before - 30_000, ledger.balance(cash())) // now ₱300 went out
        val edit = assertNotNull(ledger.iouToEdit(id))
        assertEquals("Maria", edit.person)
        assertEquals("OWED", edit.iou.kind)
        assertEquals("Rent", edit.iou.what_for)
    }

    @Test
    fun switchingPaidForOnAndOffAddsAndRemovesTheSpending() {
        val meals = category("Meals")
        val before = ledger.balance(cash())
        val id = iou(IouKind.OWE)
        ledger.updateIou(id, NewIou(IouKind.OWE, "Juan", 50_000, "", d(1, 15), paidFor = meals), cash(), 0)
        assertEquals(1, spendingIn(meals).size)
        assertEquals(before, ledger.balance(cash()))

        ledger.updateIou(id, NewIou(IouKind.OWE, "Juan", 50_000, "", d(1, 15)), cash(), 0)
        assertEquals(0, spendingIn(meals).size)
        assertNull(ledger.iouToEdit(id)?.spend)
        assertEquals(before + 50_000, ledger.balance(cash()))
    }

    @Test
    fun whatAnIouPaidForIsBackedUp() {
        val meals = category("Meals")
        val id = ledger.addIou(NewIou(IouKind.OWE, "Juan", 25_000, "", d(1, 15), paidFor = meals), cash(), 0)
        val file = ledger.backupFile(d(11, 15))
        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(file)).let { (it as Opened.Ok).file })
        assertEquals(meals, ledger.iouToEdit(id)?.spend?.category_id)
    }

    // P3-9: record a payment or hand money back

    @Test
    fun aPaymentLowersWhatsLeftAndTheAccount() {
        val id = iou(IouKind.OWE)
        val before = ledger.balance(cash())
        ledger.recordPayment(id, 20_000, 0, cash(), 1)
        assertEquals(30_000, ledger.iouLeft(id))
        assertEquals(before - 20_000, ledger.balance(cash()))
    }

    @Test
    fun aPaymentReceivedRaisesTheAccount() {
        val id = iou(IouKind.OWED)
        val before = ledger.balance(cash())
        ledger.recordPayment(id, 50_000, 0, cash(), 1)
        assertEquals(before + 50_000, ledger.balance(cash()))
        assertTrue(rows(d(2, 1)).single().settled) // settles at zero and moves under Settled
    }

    @Test
    fun interestIsSavedAsItsOwnPart() {
        val id = iou(IouKind.OWE, interest = Interest.Flat(5_000))
        assertEquals(5_000, ledger.paymentContext(id).interestDue)
        ledger.recordPayment(id, 20_000, 5_000, cash(), 1)
        val p = db.iouQueries.paymentsOf(id).executeAsList().single()
        assertEquals(15_000, p.principal_centavos)
        assertEquals(5_000, p.interest_centavos)
        assertEquals(0, ledger.paymentContext(id).interestDue)
    }

    @Test
    fun youCantPayMoreThanWhatsLeft() {
        val id = iou(IouKind.OWE)
        assertFailsWith<IllegalArgumentException> { ledger.recordPayment(id, 50_001, 0, cash(), 1) }
    }

    @Test
    fun paymentsNeverUseMoneyImHolding() {
        val owe = iou(IouKind.OWE, centavos = 10_000)
        iou(IouKind.HOLDING, "Maria", 1_000_000)
        // Spend until only ₱50 of the account is my own; the rest is Maria's.
        ledger.logExpense(ledger.balance(cash()) - 1_000_000 - 5_000, category("Meals"), cash(), 1)
        assertEquals(5_000, ledger.ownMoney().getValue(cash()))
        assertFailsWith<IllegalArgumentException> { ledger.recordPayment(owe, 10_000, 0, cash(), 1) }
        ledger.recordPayment(owe, 5_000, 0, cash(), 1)
        assertEquals(5_000, ledger.iouLeft(owe))
    }

    @Test
    fun theFlowSummaryShowsDebtPaymentsAndHandedBack() {
        val owe = iou(IouKind.OWE)
        val held = iou(IouKind.HOLDING, "Maria", 30_000)
        ledger.recordPayment(owe, 20_000, 0, cash(), 100)
        ledger.recordPayment(held, 30_000, 0, cash(), 100)
        val moves = iouMovesIn(db.iouQueries.paymentMoves().executeAsList(), 0L to 1_000L)
        assertEquals(IouMoves(debtPayments = 20_000, handedBack = 30_000), moves)
    }

    // P3-16: extra paid counts as interest

    private fun txnsIn(categoryId: Long) = db.txnQueries.backupAll().executeAsList().filter { it.category_id == categoryId }

    @Test
    fun extraReceivedIsInterestIncomeFromIous() {
        val id = iou(IouKind.OWED)
        val before = ledger.balance(cash())
        ledger.recordPayment(id, 50_000, 0, cash(), 1, extraAsInterest = 2_000)
        val fromIous = ledger.interestCategory("INCOME")
        assertEquals(listOf("INCOME" to 2_000L), txnsIn(fromIous).map { it.kind to it.centavos })
        assertEquals(before + 52_000, ledger.balance(cash()))
        assertEquals(0, ledger.iouLeft(id))
    }

    @Test
    fun extraPaidIsAFeesAndChargesInterestExpense() {
        val id = iou(IouKind.OWE)
        val before = ledger.balance(cash())
        ledger.recordPayment(id, 50_000, 0, cash(), 1, extraAsInterest = 1_000)
        val charge = ledger.interestCategory("EXPENSE")
        assertEquals(listOf("EXPENSE" to 1_000L), txnsIn(charge).map { it.kind to it.centavos })
        assertEquals(before - 51_000, ledger.balance(cash()))
    }

    @Test
    fun theInterestCategoriesAreMadeOnceAndReused() {
        assertEquals(ledger.interestCategory("EXPENSE"), ledger.interestCategory("EXPENSE"))
        // Income › Interest › From IOUs is a starter category, so it's the existing one.
        val sub = ledger.interestCategory("INCOME")
        assertEquals("From IOUs", db.categoryQueries.categoryById(sub).executeAsOne().name)
    }

    @Test
    fun holdingNeverCountsExtraAsInterest() {
        val id = iou(IouKind.HOLDING)
        assertFailsWith<IllegalArgumentException> { ledger.recordPayment(id, 50_000, 0, cash(), 1, extraAsInterest = 1_000) }
    }

    // P3-17: pay an IOU in installments

    private fun inParts(interest: Interest = Interest.None): Long {
        val dates = installmentDates(d(2, 15), 2, RepeatUnit.MONTH)
        val interestTotal = interestOf(IouTerms(50_000, interest, d(1, 15), dates.last()))
        val plan = planOf(dates, evenAmounts(50_000 + interestTotal, 2), interestTotal)
        return ledger.addIou(
            NewIou(IouKind.OWE, "Juan", 50_000, "Phone", d(1, 15), dueOn = dates.last(), interest = interest, installments = plan),
            cash(), 0,
        )
    }

    private fun parts(id: Long) = db.iouQueries.installmentsOf(id).executeAsList()

    @Test
    fun anIouInPartsSavesEachPayment() {
        val id = inParts()
        assertEquals(listOf("2026-02-15" to 25_000L, "2026-03-15" to 25_000L), parts(id).map { it.due_on to it.centavos })
        assertTrue(parts(id).all { it.state == "WAITING" })
    }

    @Test
    fun partsThatDontAddUpAreRefused() {
        val plan = planOf(listOf(d(2, 15), d(3, 15)), listOf(10_000, 10_000), 0)
        assertFailsWith<IllegalArgumentException> {
            ledger.addIou(NewIou(IouKind.OWE, "Juan", 50_000, "", d(1, 15), dueOn = d(3, 15), installments = plan), cash(), 0)
        }
    }

    @Test
    fun onlyDuePartsAreInTheCheckIn() {
        val id = inParts()
        assertEquals(emptyList(), ledger.dueInstallments(d(2, 14)))
        val due = ledger.dueInstallments(d(2, 15)).single()
        assertEquals(id, due.iou_id)
        assertEquals(2L, due.total_parts)
        assertEquals("Pay Juan", due.title())
    }

    @Test
    fun savingAPartPaysItWithItsShareOfInterest() {
        val id = inParts(interest = Interest.Flat(10_000))
        val due = ledger.dueInstallments(d(2, 15)).single()
        val before = ledger.balance(cash())
        assertNotNull(ledger.saveInstallment(due.id, due.centavos, 1))
        val paid = db.iouQueries.paymentsOf(id).executeAsList().single()
        assertEquals(30_000, paid.principal_centavos + paid.interest_centavos)
        assertEquals(5_000, paid.interest_centavos)
        assertEquals(before - 30_000, ledger.balance(cash()))
        assertEquals("SAVED", parts(id).first().state)
        assertNull(ledger.saveInstallment(due.id, due.centavos, 1)) // a double tap pays once
    }

    @Test
    fun anEditedPartNeverPaysMoreThanWhatsLeft() {
        val id = inParts()
        val due = ledger.dueInstallments(d(2, 15)).single()
        ledger.saveInstallment(due.id, 90_000, 1)
        assertEquals(0, ledger.iouLeft(id))
        assertEquals(emptyList(), ledger.dueInstallments(d(4, 1))) // paid off: the second part isn't asked
    }

    @Test
    fun skippingAPartPaysNothing() {
        val id = inParts()
        val due = ledger.dueInstallments(d(2, 15)).single()
        assertTrue(ledger.skipInstallment(due.id))
        assertFalse(ledger.skipInstallment(due.id))
        assertEquals(50_000, ledger.iouLeft(id))
        assertEquals("SKIPPED", parts(id).first().state)
    }

    @Test
    fun deletingASavedPartsPaymentPutsItBackAndUndoSavesItAgain() {
        val id = inParts()
        val due = ledger.dueInstallments(d(2, 15)).single()
        val paymentId = assertNotNull(ledger.saveInstallment(due.id, due.centavos, 1))
        val deleted = assertNotNull(ledger.deletePayment(paymentId))
        assertEquals("WAITING", parts(id).first().state)
        ledger.restorePayment(deleted)
        assertEquals("SAVED" to paymentId, parts(id).first().let { it.state to it.payment_id })
    }

    @Test
    fun thePlanChangesOnlyUntilAPartIsHandled() {
        val id = inParts()
        val edit = assertNotNull(ledger.iouToEdit(id))
        val three = planOf(installmentDates(d(2, 1), 3, RepeatUnit.MONTH), evenAmounts(50_000, 3), 0)
        val newIou = NewIou(IouKind.OWE, "Juan", 50_000, "Phone", d(1, 15), dueOn = d(4, 1), installments = three)
        ledger.updateIou(id, newIou, edit.money!!.account_id, 0)
        assertEquals(3, parts(id).size)

        ledger.skipInstallment(parts(id).first().id)
        ledger.updateIou(id, newIou.copy(installments = null), edit.money!!.account_id, 0)
        assertEquals(3, parts(id).size) // locked: the plan stays
    }

    @Test
    fun duePartsCountTowardTheCheckInBadge() = runBlocking {
        inParts()
        assertEquals(0, ledger.dueCount(d(2, 14)).first())
        assertEquals(1, ledger.dueCount(d(2, 15)).first())
        assertEquals(2, ledger.dueCount(d(3, 15)).first())
    }

    @Test
    fun backupKeepsInstallments() {
        val id = inParts()
        ledger.saveInstallment(ledger.dueInstallments(d(2, 15)).single().id, 25_000, 1)
        val file = ledger.backupFile(d(5, 4))
        assertEquals(2, file.installments.size)
        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(file)).let { (it as Opened.Ok).file })
        assertEquals(listOf("SAVED", "WAITING"), parts(id).map { it.state })
    }

    // P3-10: held money and net worth

    @Test
    fun heldMoneyIsCountedPerAccountUntilHandedBack() = runBlocking {
        val held = iou(IouKind.HOLDING, "Maria", 30_000)
        assertEquals(mapOf(cash() to 30_000L), ledger.held().first())
        ledger.recordPayment(held, 30_000, 0, cash(), 1)
        assertEquals(emptyMap(), ledger.held().first())
    }

    @Test
    fun netWorthCountsEveryOpenIou() = runBlocking {
        iou(IouKind.OWED, centavos = 20_000)
        iou(IouKind.OWE, centavos = 50_000)
        iou(IouKind.HOLDING, "Maria", 30_000)
        val worth = ledger.netWorth(d(2, 1)).first()
        assertEquals(Triple(20_000L, 50_000L, 30_000L), Triple(worth.owedToMe, worth.iOwe, worth.holding))
        assertEquals(worth.total + 20_000 - 50_000 - 30_000, worth.worth)
    }

    @Test
    fun anAccountHoldingMoneyCantBeArchived() {
        ledger.addAccount("BDO", AccountType.Bank, AccountColors.first(), 0)
        iou(IouKind.HOLDING, "Maria", 30_000)
        assertTrue(ledger.holdsMoney(cash()))
        val e = assertFailsWith<IllegalArgumentException> { ledger.archive(cash()) }
        assertEquals(HandBackFirst, e.message)
    }

    // P3-12: credit accounts

    private fun card(owed: Long = 0, dueDay: Int? = 20, minimum: Long? = 50_000) =
        ledger.addAccount("BPI Card", AccountType.Credit, AccountColors.first(), -owed, dueDay, minimum)

    private fun owed(id: Long) = db.accountQueries.creditAccounts().executeAsList().single { it.id == id }.owedNow

    @Test
    fun aPurchaseOnCreditIsSpendingAndAddsToWhatsOwed() {
        val bpi = card(owed = 10_000)
        ledger.logExpense(30_000, category("Meals"), bpi, 100)
        assertEquals(40_000, owed(bpi))
        assertEquals(30_000, ledger.totals(0, 1_000).spent) // spending on the day it's made
    }

    @Test
    fun payingTheBillIsATransferNotSpending() {
        val bpi = card(owed = 40_000)
        val before = ledger.balance(cash())
        ledger.logTransfer(25_000, cash(), bpi, 100)
        assertEquals(15_000, owed(bpi))
        assertEquals(before - 25_000, ledger.balance(cash()))
        assertEquals(0, ledger.totals(0, 1_000).spent)
    }

    @Test
    fun creditIsLeftOutOfTheTotalButTakenFromNetWorth() = runBlocking {
        val before = totalBalance(ledger.balances().first())
        card(owed = 70_000)
        assertEquals(before, totalBalance(ledger.balances().first()))
        val worth = ledger.netWorth(d(2, 1)).first()
        assertEquals(70_000, worth.onCredit)
        assertEquals(before - 70_000, worth.worth)
    }

    @Test
    fun aCardKeepsItsDueDayAndMinimum() {
        val bpi = card()
        val row = db.accountQueries.creditAccounts().executeAsList().single { it.id == bpi }
        assertEquals(20L to 50_000L, row.due_day to row.minimum_centavos)
    }

    @Test
    fun otherAccountsNeverKeepCreditTerms() {
        val bdo = ledger.addAccount("BDO", AccountType.Bank, AccountColors.first(), 0, 20, 50_000)
        val row = db.accountQueries.backupAll().executeAsList().single { it.id == bdo }
        assertNull(row.due_day)
        assertNull(row.minimum_centavos)
    }

    @Test
    fun backupKeepsCreditTerms() {
        val bpi = card()
        val file = ledger.backupFile(d(5, 4))
        ledger.eraseAll()
        ledger.restore(openBackup(encodeBackup(file)).let { (it as Opened.Ok).file })
        assertEquals(20L, db.accountQueries.creditAccounts().executeAsList().single { it.id == bpi }.due_day)
    }

    // P3-14: add a charge to a credit account

    @Test
    fun aChargeAddsToWhatsOwedAndToFeesAndCharges() {
        val bpi = card(owed = 10_000)
        val id = ledger.addCharge(bpi, ChargeKind.LateFee, 1_500, 100)
        assertEquals(11_500, owed(bpi))
        assertEquals(1_500, ledger.totals(0, 1_000).spent)
        val sub = db.categoryQueries.categoryById(categoryOf(id)!!).executeAsOne()
        assertEquals("Late fee", sub.name)
        assertEquals("Fees & Charges", db.categoryQueries.categoryById(sub.parent_id!!).executeAsOne().name)
    }

    @Test
    fun chargeInterestSharesTheIouInterestCategory() {
        val bpi = card()
        val id = ledger.addCharge(bpi, ChargeKind.Interest, 2_000, 100)
        assertEquals(ledger.interestCategory("EXPENSE"), categoryOf(id))
    }

    @Test
    fun chargesOnlyGoOnCredit() {
        assertFailsWith<IllegalArgumentException> { ledger.addCharge(cash(), ChargeKind.AnnualFee, 1_000, 1) }
    }
}
