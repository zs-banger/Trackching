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
import app.trackching.db.ActiveOn
import app.trackching.db.Movements
import app.trackching.db.Repeat_rule
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.trackching.db.Event
import app.trackching.db.EventSpending
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone

/** A deleted event and the transactions it had, so Undo can put both back. */
data class DeletedEvent(val event: Event, val txnIds: List<Long>)

/** One date a repeat is waiting on. [n] is which time it is (0 = the start date). */
data class DueRepeat(val n: Int, val date: LocalDate, val rule: Repeat_rule)

/** The saved row as a [RepeatRule]. */
fun Repeat_rule.toRule() = RepeatRule(
    every = every.toInt(),
    unit = RepeatUnit.valueOf(unit),
    start = LocalDate.parse(start_date),
    end = when {
        times != null -> RepeatEnd.After(times.toInt())
        until_date != null -> RepeatEnd.Until(LocalDate.parse(until_date))
        else -> RepeatEnd.Never
    },
)

class Ledger(private val db: TrackchingDatabase) {

    /** Saves an expense and returns its id, so it can be undone. Tags it to an event if given. */
    fun logExpense(centavos: Long, categoryId: Long, accountId: Long, occurredAt: Long, eventId: Long? = null): Long =
        insert("EXPENSE", centavos, accountId, null, categoryId, occurredAt, eventId)

    /** Saves income into an account. Tags it to an event if given. */
    fun logIncome(centavos: Long, categoryId: Long, accountId: Long, occurredAt: Long, eventId: Long? = null): Long =
        insert("INCOME", centavos, accountId, null, categoryId, occurredAt, eventId)

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
        eventId: Long? = null
    ): Long {
        require(centavos > 0) { "An amount must be more than ₱0.00" }
        return db.transactionWithResult {
            db.txnQueries.insert(kind, centavos, accountId, toAccountId, categoryId, occurredAt)
            val id = db.txnQueries.lastId().executeAsOne()
            if (eventId != null) db.txnQueries.setEvent(eventId, id)
            id
        }
    }

    /** Removes a transaction just saved. If it was the only one from its repeat, the repeat goes too. */
    fun undo(txnId: Long) = db.transaction {
        val repeatId = db.txnQueries.byId(txnId).executeAsOneOrNull()?.repeat_id
        db.txnQueries.delete(txnId)
        if (repeatId != null && db.txnQueries.countForRepeat(repeatId).executeAsOne() == 0L) {
            db.repeatRuleQueries.delete(repeatId)
        }
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
            db.txnQueries.insert(ADJUSTMENT, diff, accountId, null, null, occurredAt)
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

    fun movements(): Flow<List<Movements>> =
        db.txnQueries.movements().asFlow().mapToList(Dispatchers.Default)

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

    /** The event a transaction on `day` should be tagged to, or null. */
    fun activeEventOn(day: LocalDate): ActiveOn? =
        db.eventQueries.activeOn(day.toString()).executeAsOneOrNull()

    /** One event, re-sent when it changes. Null once it's deleted. */
    fun event(id: Long): Flow<Event?> =
        db.eventQueries.byId(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    /** The ids of the transactions tagged to an event. */
    fun eventTxnIds(id: Long): Flow<List<Long>> =
        db.txnQueries.idsForEvent(id).asFlow().mapToList(Dispatchers.Default)

    /** An event's spending per main category, largest first. */
    fun eventSpending(id: Long): Flow<List<EventSpending>> =
        db.txnQueries.eventSpending(id).asFlow().mapToList(Dispatchers.Default)

    /** Renames an event or changes its dates. Same rules as adding one. */
    fun updateEvent(id: Long, name: String, start: LocalDate, end: LocalDate?) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An event needs a name" }
        require(end == null || end >= start) { "The end date can't be before the start date" }
        db.eventQueries.update(clean, start.toString(), end?.toString(), id)
    }

    /** Deletes an event. Its transactions stay but are untagged. Returns what Undo needs. */
    fun deleteEvent(id: Long): DeletedEvent? = db.transactionWithResult {
        val event = db.eventQueries.byId(id).executeAsOneOrNull() ?: return@transactionWithResult null
        val ids = db.txnQueries.idsForEvent(id).executeAsList()
        db.txnQueries.untagEvent(id)
        db.eventQueries.delete(id)
        DeletedEvent(event, ids)
    }

    /** Undo for deleteEvent: the event comes back with its old id, and its transactions are tagged again. */
    fun restoreEvent(deleted: DeletedEvent) = db.transaction {
        val e = deleted.event
        db.eventQueries.restore(e.id, e.name, e.start_date, e.end_date)
        deleted.txnIds.forEach { db.txnQueries.setEvent(e.id, it) }
    }

    /** Saves a repeating transaction's rule. Today's transaction is logged separately (P3-2). Returns its id. */
    fun addRepeat(
        rule: RepeatRule,
        kind: String,
        centavos: Long,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        done: Long = 0,
    ): Long {
        require(centavos > 0) { "An amount must be more than ₱0.00" }
        val end = rule.end
        return db.transactionWithResult {
            db.repeatRuleQueries.insert(
                rule.every.toLong(), rule.unit.name, rule.start.toString(),
                (end as? RepeatEnd.After)?.times?.toLong(),
                (end as? RepeatEnd.Until)?.date?.toString(),
                done,
                kind, centavos, accountId, toAccountId, categoryId,
            )
            db.repeatRuleQueries.lastId().executeAsOne()
        }
    }

    /** Every date waiting for an OK, across all repeats, oldest first. */
    fun dueRepeats(today: LocalDate): List<DueRepeat> =
        db.repeatRuleQueries.allRules().executeAsList()
            .flatMap { row ->
                val done = row.done.toInt()
                row.toRule().due(done, today).mapIndexed { i, date -> DueRepeat(done + i, date, row) }
            }
            .sortedBy { it.date }

    /**
     * Logs one due date as a transaction at [occurredAt] and moves its rule on.
     * Returns the new transaction's id, or null if that date was already handled (a double tap).
     */
    fun saveDue(due: DueRepeat, occurredAt: Long): Long? = db.transactionWithResult {
        val r = due.rule
        val moved = db.repeatRuleQueries.moveOn(id = r.id, done = due.n.toLong()).value == 1L
        if (moved) insert(r.kind, r.centavos, r.account_id, r.to_account_id, r.category_id, occurredAt)
            .also { db.txnQueries.setRepeat(r.id, it) }
        else null
    }

    /** Skips one due date: nothing is logged, and it's never listed again. False if it was already handled. */
    fun skipDue(due: DueRepeat): Boolean =
        db.repeatRuleQueries.moveOn(id = due.rule.id, done = due.n.toLong()).value == 1L

    /** Saves today's transaction and a rule for the next ones, together. Returns the transaction's id. */
    fun logRepeating(
        kind: String,
        centavos: Long,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        occurredAt: Long,
        eventId: Long?,
        rule: RepeatRule,
    ): Long = db.transactionWithResult {
        // This transaction is time number 0, so the rule starts at done = 1 (decision 1).
        val ruleId = addRepeat(rule, kind, centavos, accountId, toAccountId, categoryId, done = 1)
        val id = insert(kind, centavos, accountId, toAccountId, categoryId, occurredAt, eventId)
        db.txnQueries.setRepeat(ruleId, id)
        id
    }

    fun repeatRule(id: Long): Repeat_rule? = db.repeatRuleQueries.byId(id).executeAsOneOrNull()

    /** "This and the next ones": saves the edit and makes the next check-ins log the same. */
    fun updateThisAndNext(txn: Txn) = db.transaction {
        val ruleId = requireNotNull(txn.repeat_id) { "This transaction doesn't repeat" }
        update(txn)
        db.repeatRuleQueries.updateTemplate(
            txn.kind, txn.centavos, txn.account_id, txn.to_account_id, txn.category_id, ruleId,
        )
    }

    /** Stop repeating: nothing more is due. Transactions already saved stay. */
    fun stopRepeat(ruleId: Long) {
        db.repeatRuleQueries.stop(ruleId)
    }

    /** Whether amounts are hidden (the HIDE ₱ button). Off until you turn it on. */
    fun amountsHidden(): Boolean =
        db.settingQueries.get("amounts_hidden").executeAsOneOrNull() == "true"

    fun setAmountsHidden(hidden: Boolean) {
        db.settingQueries.put("amounts_hidden", hidden.toString())
    }

    fun theme(): ThemeChoice =
        ThemeChoice.entries.find { it.name == db.settingQueries.get("theme").executeAsOneOrNull() }
            ?: ThemeChoice.System

    fun setTheme(choice: ThemeChoice) {
        db.settingQueries.put("theme", choice.name)
    }

    fun weekStart(): DayOfWeek =
        if (db.settingQueries.get("week_start").executeAsOneOrNull() == "SUNDAY") DayOfWeek.SUNDAY
        else DayOfWeek.MONDAY

    fun setWeekStart(day: DayOfWeek) {
        db.settingQueries.put("week_start", day.name)
    }

    fun accountCount(): Long = db.accountQueries.countAll().executeAsOne()
    fun categoryCount(): Long = db.categoryQueries.countAll().executeAsOne()

    /** Erase all data: every transaction, event, category, account and setting. Setup runs again. */
    fun eraseAll() {
        db.transaction {
            db.txnQueries.eraseAll()
            db.repeatRuleQueries.eraseAll()
            db.eventQueries.eraseAll()
            db.categoryQueries.eraseAll()
            db.accountQueries.eraseAll()
            db.settingQueries.eraseAll()
        }
    }

    /** Everything in the database, ready to save. */
    fun backupFile(today: LocalDate): BackupFile = BackupFile(
        createdOn = today.toString(),
        accounts = db.accountQueries.backupAll().executeAsList().map {
            AccountBackup(it.id, it.name, it.type, it.color, it.starting_centavos, it.archived, it.sort_order)
        },
        categories = db.categoryQueries.backupAll().executeAsList().map {
            CategoryBackup(it.id, it.name, it.kind, it.parent_id, it.color, it.sort_order, it.icon)
        },
        events = db.eventQueries.backupAll().executeAsList().map {
            EventBackup(it.id, it.name, it.start_date, it.end_date)
        },
        txns = db.txnQueries.backupAll().executeAsList().map {
            TxnBackup(it.id, it.kind, it.centavos, it.account_id, it.to_account_id, it.category_id, it.occurred_at, it.event_id, it.repeat_id)
        },
        settings = db.settingQueries.backupAll().executeAsList().associate { it.key to it.value_ },
        repeats = db.repeatRuleQueries.allRules().executeAsList().map {
            RepeatBackup(
                it.id, it.every, it.unit, it.start_date, it.times, it.until_date, it.done,
                it.kind, it.centavos, it.account_id, it.to_account_id, it.category_id,
            )
        },
    )

    /** Replaces everything with the backup. All or nothing: if any row fails, the old data stays. */
    fun restore(file: BackupFile) {
        db.transaction {
            eraseAll()
            file.accounts.forEach {
                db.accountQueries.backupInsert(it.id, it.name, it.type, it.color, it.startingCentavos, it.archived, it.sortOrder)
            }
            file.categories.forEach {
                db.categoryQueries.backupInsert(it.id, it.name, it.kind, it.parentId, it.color, it.sortOrder, it.icon)
            }
            file.events.forEach { db.eventQueries.restore(it.id, it.name, it.startDate, it.endDate) }
            file.repeats.forEach {
                db.repeatRuleQueries.backupInsert(
                    it.id, it.every, it.unit, it.startDate, it.times, it.untilDate, it.done,
                    it.kind, it.centavos, it.accountId, it.toAccountId, it.categoryId,
                )
            }
            file.txns.forEach {
                db.txnQueries.backupInsert(it.id, it.kind, it.centavos, it.accountId, it.toAccountId, it.categoryId, it.occurredAt, it.eventId, it.repeatId)
            }
            file.settings.forEach { (key, value) -> db.settingQueries.put(key, value) }

            db.settingQueries.put("setup_done", "true") // a restored phone never shows setup again
        }
    }

    fun lastBackup(): LocalDate? =
        db.settingQueries.get("last_backup").executeAsOneOrNull()?.let { LocalDate.parse(it) }

    fun markBackedUp(today: LocalDate) {
        db.settingQueries.put("last_backup", today.toString())
    }

    /** Every transaction as a spreadsheet, oldest first. */
    fun csv(zone: TimeZone): String {
        val header = csvLine(listOf("date", "type", "amount", "account", "to account", "category", "subcategory", "event"))
        val rows = db.txnQueries.csvRows().executeAsList().map {
            csvLine(listOf(
                dayOf(it.occurred_at, zone).toString(),
                it.kind.lowercase().replaceFirstChar { c -> c.uppercase() },
                plainAmount(it.centavos),
                it.account,
                it.to_account,
                it.category,
                it.subcategory,
                it.event,
            ))
        }
        return (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n")
    }

    /** True once setup is finished. An app that already has accounts counts as set up. */
    fun setupDone(): Boolean =
        db.settingQueries.get("setup_done").executeAsOneOrNull() == "true" ||
                db.accountQueries.countAll().executeAsOne() > 0

    /** Setup's last step: the first account, the ticked categories and the "done" flag, all or nothing. */
    fun finishSetup(
        account: String,
        type: AccountType,
        color: Long,
        startingCentavos: Long,
        categories: List<StarterCategory>,
    ) {
        require(!setupDone()) { "Setup is already done" }
        require(categories.any { it.kind == "EXPENSE" } && categories.any { it.kind == "INCOME" }) {
            "Keep at least one expense and one income category"
        }
        db.transaction {
            addAccount(account, type, color, startingCentavos)
            db.seedCategories(categories)
            db.settingQueries.put("setup_done", "true")
        }
    }
}
