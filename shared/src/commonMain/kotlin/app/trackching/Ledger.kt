package app.trackching

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.trackching.db.Active
import app.trackching.db.Balances
import app.trackching.db.MostUsed
import app.trackching.db.Totals
import app.trackching.db.TrackchingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import app.trackching.db.History
import app.trackching.db.Txn
import app.trackching.db.ManageList
import app.trackching.db.Mains
import app.trackching.db.MonthTotals
import app.trackching.db.Leaves
import kotlinx.datetime.LocalDate
import app.trackching.db.WithTotals

class Ledger(private val db: TrackchingDatabase) {

    /** Saves an expense and returns its id, so it can be undone. */
    fun logExpense(centavos: Long, categoryId: Long, accountId: Long, occurredAt: Long): Long =
        insert("EXPENSE", centavos, accountId, null, categoryId, occurredAt)

    /** Saves income into an account. */
    fun logIncome(centavos: Long, categoryId: Long, accountId: Long, occurredAt: Long): Long =
        insert("INCOME", centavos, accountId, null, categoryId, occurredAt)

    /** Moves money between two of your own accounts. No category. */
    fun logTransfer(centavos: Long, fromId: Long, toId: Long, occurredAt: Long): Long {
        require(fromId != toId) { "From and To must be different accounts" }
        return insert("TRANSFER", centavos, fromId, toId, null, occurredAt)
    }

    private fun insert(
        kind: String,
        centavos: Long,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        occurredAt: Long,
    ): Long {
        require(centavos > 0) { "An amount must be more than ₱0.00" }
        return db.transactionWithResult {
            db.txnQueries.insert(kind, centavos, accountId, toAccountId, categoryId, occurredAt)
            db.txnQueries.lastId().executeAsOne()
        }
    }

    /** Removes a transaction that was just saved ("Saved · Undo"). */
    fun undo(txnId: Long) {
        db.txnQueries.delete(txnId)
    }

    /** The category grid: most used first. */
    fun quickCategories(kind: String, limit: Long = 8): List<MostUsed> =
        db.categoryQueries.mostUsed(kind, limit).executeAsList()

    /** "Paid from" default: the last account used, or the first account if nothing is logged yet. */
    fun lastAccountUsed(): Long? =
        db.txnQueries.lastAccountUsed().executeAsOneOrNull()
            ?: db.accountQueries.active().executeAsList().firstOrNull()?.id

    fun balance(accountId: Long): Long = db.accountQueries.balanceOf(accountId).executeAsOne()

    /**
     * Makes the account show `realCentavos` by saving the difference as an Adjustment.
     * Returns the new id, or null if the balance was already right.
     */
    fun adjust(accountId: Long, realCentavos: Long, occurredAt: Long): Long? =
        db.transactionWithResult {
            val diff = realCentavos - balance(accountId)
            if (diff == 0L) return@transactionWithResult null
            db.txnQueries.insert("ADJUSTMENT", diff, accountId, null, null, occurredAt)
            db.txnQueries.lastId().executeAsOne()
        }

    /** Income and spending from `from` (included) to `until` (not included). */
    fun totals(from: Long, until: Long): Totals = db.txnQueries.totals(from, until).executeAsOne()

    /** Accounts you can pay from, in order. */
    fun accounts(): List<Active> = db.accountQueries.active().executeAsList()

    /** Active accounts, plus any archived one this transaction already uses (so editing still shows it). */
    fun accountsFor(txn: Txn?): List<Active> {
        val active = accounts()
        if (txn == null) return active
        val missing = listOfNotNull(txn.account_id, txn.to_account_id)
            .filter { id -> active.none { it.id == id } }
        return active + missing.map { id -> Active(id, db.accountQueries.nameOf(id).executeAsOne()) }
    }

    /** Every account's balance, re-sent automatically whenever a transaction changes. */
    fun balances(): Flow<List<Balances>> =
        db.accountQueries.balances().asFlow().mapToList(Dispatchers.Default)

    /** Every transaction, newest first. Re-sent whenever anything changes. */
    fun history(): Flow<List<History>> =
        db.txnQueries.history().asFlow().mapToList(Dispatchers.Default)

    /** One transaction, for the edit sheet. */
    fun get(id: Long): Txn? = db.txnQueries.byId(id).executeAsOneOrNull()

    /** Saves changes to an existing transaction. Same rules as logging a new one. */
    fun update(txn: Txn) {
        require(txn.centavos > 0) { "An amount must be more than ₱0.00" }
        if (txn.kind == "TRANSFER") {
            require(txn.to_account_id != null && txn.to_account_id != txn.account_id) {
                "From and To must be different accounts"
            }
        } else {
            require(txn.category_id != null) { "Income and expenses need a category" }
        }
        db.txnQueries.update(
            txn.kind, txn.centavos, txn.account_id, txn.to_account_id,
            txn.category_id, txn.occurred_at, txn.id,
        )
    }

    /** Deletes a transaction and hands back the whole row, so Undo can restore it exactly. */
    fun delete(id: Long): Txn? = db.transactionWithResult {
        val row = db.txnQueries.byId(id).executeAsOneOrNull()
        if (row != null) db.txnQueries.delete(id)
        row
    }

    /** Puts a deleted transaction back, with its original id. */
    fun restore(txn: Txn) {
        db.txnQueries.restore(txn)
    }

    /** Creates an account and returns its id. Names are unique, ignoring case. */
    fun addAccount(name: String, type: AccountType, color: Long, startingCentavos: Long): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An account needs a name" }
        require(db.accountQueries.nameTaken(clean).executeAsOne() == 0L) { "An account called $clean already exists" }
        return db.transactionWithResult {
            db.accountQueries.create(clean, type.dbName, color, startingCentavos)
            db.accountQueries.lastId().executeAsOne()
        }
    }

    /** Every account name in lowercase, archived ones too (the database blocks those as well). */
    fun accountNames(): Set<String> =
        db.accountQueries.allNames().executeAsList().map { it.name.lowercase() }.toSet()

    /** Just the archived names, so the form can suggest Restore instead. */
    fun archivedAccountNames(): Set<String> =
        db.accountQueries.allNames().executeAsList()
            .filter { it.archived != 0L }
            .map { it.name.lowercase() }
            .toSet()

    /** All accounts for the Manage screen, archived ones last. */
    fun manageList(): Flow<List<ManageList>> =
        db.accountQueries.manageList().asFlow().mapToList(Dispatchers.Default)

    /** Saves a new order. `ids` is every active account, top to bottom. */
    fun reorder(ids: List<Long>) {
        db.transaction {
            ids.forEachIndexed { index, id -> db.accountQueries.setOrder(index.toLong(), id) }
        }
    }

    /** Hides an account from Home and pickers. Its transactions stay. You must keep at least one. */
    fun archive(accountId: Long) {
        require(db.accountQueries.countActive().executeAsOne() > 1) { "Keep at least one account" }
        db.accountQueries.setArchived(1, accountId)
    }

    fun restoreAccount(accountId: Long) {
        db.accountQueries.setArchived(0, accountId)
    }

    /** Main categories of one kind, for the "Under" picker. */
    fun mainCategories(kind: String): List<Mains> = db.categoryQueries.mains(kind).executeAsList()

    /** Names already used under one parent (null = main categories), in lowercase. */
    fun categoryNames(kind: String, parentId: Long?): Set<String> =
        db.categoryQueries.namesUnder(kind, parentId).executeAsList().map { it.lowercase() }.toSet()

    /** Adds a main category (parentId = null) or a subcategory. Returns its id. */
    fun addCategory(name: String, kind: String, parentId: Long?, color: Long, icon: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A category needs a name" }
        return db.transactionWithResult {
            if (parentId != null) {
                val parent = db.categoryQueries.parentInfo(parentId).executeAsOne()
                require(parent.parent_id == null) { "A subcategory can only go under a main category" }
                require(parent.kind == kind) { "A subcategory must be the same kind as its main category" }
            }
            require(db.categoryQueries.nameTaken(kind, parentId, clean).executeAsOne() == 0L) {
                "$clean already exists there"
            }
            db.categoryQueries.create(clean, kind, parentId, color, icon)
            db.categoryQueries.lastId().executeAsOne()
        }
    }

    /** Every category of `kind` with its own total in [from, until). Re-sent when anything changes. */
    fun monthTotals(kind: String, from: Long, until: Long): Flow<List<MonthTotals>> =
        db.categoryQueries.monthTotals(from, until, kind).asFlow().mapToList(Dispatchers.Default)

    /** The category plus its subcategories: everything a delete removes. */
    private fun withSubs(categoryId: Long): List<Long> =
        listOf(categoryId) + db.categoryQueries.subIds(categoryId).executeAsList()

    /** How many transactions would need to move if this category (and its subs) were deleted. */
    fun categoryUsage(categoryId: Long): Long =
        db.txnQueries.usageOf(withSubs(categoryId)).executeAsOne()

    /** Where this category's transactions may go: same kind, loggable, not itself or its subs. */
    fun moveTargets(categoryId: Long): List<Leaves> {
        val gone = withSubs(categoryId)
        val kind = db.categoryQueries.categoryById(categoryId).executeAsOne().kind
        return db.categoryQueries.leaves(kind).executeAsList().filter { it.id !in gone }
    }

    /**
     * Deletes a category (and its subcategories). If any transactions use them,
     * they all move to `moveTo` first. That's also how "merge" works.
     */
    fun deleteCategory(categoryId: Long, moveTo: Long?) {
        db.transaction {
            val gone = withSubs(categoryId)
            val kind = db.categoryQueries.categoryById(categoryId).executeAsOne().kind
            require(db.categoryQueries.countOthersOfKind(kind, gone).executeAsOne() > 0) {
                "Keep at least one category"
            }
            if (db.txnQueries.usageOf(gone).executeAsOne() > 0) {
                require(moveTo != null) { "Choose where its transactions go first" }
                require(db.categoryQueries.leaves(kind).executeAsList().any { it.id == moveTo && it.id !in gone }) {
                    "Transactions can only move to another category of the same kind"
                }
                db.txnQueries.moveCategory(moveTo, gone)
            }
            db.categoryQueries.deleteSubs(categoryId)
            db.categoryQueries.deleteOne(categoryId)
        }
    }

    /** Saves an event. No end date means it's ongoing. Returns its id. */
    fun addEvent(name: String, start: LocalDate, end: LocalDate?): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An event needs a name" }
        require(end == null || end >= start) { "The end date can't be before the start date" }
        return db.transactionWithResult {
            db.eventQueries.insert(clean, start.toString(), end?.toString())
            db.eventQueries.lastId().executeAsOne()
        }
    }

    /** Every event with its spending. Re-sent when an event or a transaction changes. */
    fun eventsWithTotals(): Flow<List<WithTotals>> =
        db.eventQueries.withTotals().asFlow().mapToList(Dispatchers.Default)

    /** Tags a transaction to an event, or clears the tag with null. (The screen uses this in P1-16.) */
    fun tagEvent(txnId: Long, eventId: Long?) {
        db.txnQueries.setEvent(eventId, txnId)
    }
}