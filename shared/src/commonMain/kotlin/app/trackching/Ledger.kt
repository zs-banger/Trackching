package app.trackching

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.trackching.db.Active
import app.trackching.db.Balances
import app.trackching.db.MostUsed
import app.trackching.db.Totals
import app.trackching.db.TrackchingDatabase
import app.trackching.db.Iou
import app.trackching.db.Event_run
import app.trackching.db.EventSpendingBetween
import app.trackching.db.ExpensesByEvent
import app.trackching.db.Iou_payment
import app.trackching.db.CreditAccounts
import app.trackching.db.Credit_plan
import app.trackching.db.Credit_installment
import app.trackching.db.DueCardPayments
import app.trackching.db.Iou_installment
import app.trackching.db.DueInstallments
import app.trackching.db.IouDetail
import app.trackching.db.PaymentRows
import app.trackching.db.PaymentMoves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.atStartOfDayIn

/** A deleted event and the transactions it had, so Undo can put both back. */
/** What Undo needs after a run's dates are changed or deleted (P3-20): the run as it was, and what got untagged. */
data class RunUndo(val run: Event_run, val untagged: List<Long>, val deleted: Boolean)

/** What Undo needs after a payment is deleted (P3-7): the payment and the money that moved. */
/** An IOU as the Edit IOU sheet loads it: the row, the person's name, its money and its spending (P3-8). */
data class IouToEdit(
    val iou: Iou,
    val person: String,
    val money: Txn?,
    val spend: Txn?,
    val installments: List<Iou_installment> = emptyList(),
)

/** What Pay bill saved (P3-13), so Undo can take it back. */
data class BillPaid(val payments: List<Long>, val txns: List<Txn>)

data class DeletedPayment(val payment: Iou_payment, val txn: Txn, val installmentId: Long? = null)

data class DeletedEvent(val event: Event, val txnIds: List<Long>, val runs: List<Event_run> = emptyList())

/** One date a repeat is waiting on. [n] is which time it is (0 = the start date). */
data class DueRepeat(val n: Int, val date: LocalDate, val rule: Repeat_rule)

/** How a repeat reads in the check-in: "Meals" over "GCash", or "Transfer" over "GCash → BDO". */
data class RepeatLabel(val title: String, val subtitle: String)

/** How many dates are waiting across [rows]. */
fun countDue(rows: List<Repeat_rule>, today: LocalDate): Int =
    rows.sumOf { it.toRule().due(it.done.toInt(), today).size }

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

    /**
     * Removes a transaction just saved. If it was the only one from its repeat, the repeat goes too.
     * A purchase in installments (P3-15) or a loan (P3-18) takes its plan with it (audit #3).
     */
    fun undo(txnId: Long) = db.transaction {
        val repeatId = db.txnQueries.byId(txnId).executeAsOneOrNull()?.repeat_id
        db.creditQueries.planOfTxn(txnId).executeAsOneOrNull()?.let { plan ->
            require(db.creditQueries.countPaidOfPlan(plan.id).executeAsOne() == 0L) { "A payment of this plan is already paid" }
            db.creditQueries.deletePaymentsOfPlan(plan.id)
            db.creditQueries.deletePlan(plan.id)
        }
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

    /** Credit accounts with what's owed, due day and minimum (P3-12). */
    fun creditAccounts(): Flow<List<CreditAccounts>> =
        db.accountQueries.creditAccounts().asFlow().mapToList(Dispatchers.Default)

    fun movements(): Flow<List<Movements>> =
        db.txnQueries.movements().asFlow().mapToList(Dispatchers.Default)

    /** Every transaction, newest first. Re-sent whenever anything changes. */
    fun history(): Flow<List<History>> =
        db.txnQueries.history().asFlow().mapToList(Dispatchers.Default)

    /** One transaction, for the edit sheet. */
    fun get(id: Long): Txn? = db.txnQueries.byId(id).executeAsOneOrNull()

    /**
     * Why a transaction can't be edited or deleted on its own, or null if it can (audit #2, #4).
     * IOU spending changes with its IOU, and a card plan's transactions with the plan.
     */
    fun linkedTo(txnId: Long): String? {
        db.iouQueries.iouOfSpend(txnId).executeAsOneOrNull()?.let { return linkedToIou(it) }
        db.creditQueries.planOfTxn(txnId).executeAsOneOrNull()?.let { return linkedToPlan(it.name, it.account) }
        db.creditQueries.paymentOfTxn(txnId).executeAsList().firstOrNull()?.let { return linkedToPlan(it.name, it.account) }
        return null
    }

    /** Saves changes to an existing transaction. Same rules as logging a new one. */
    fun update(txn: Txn) {
        linkedTo(txn.id)?.let { throw IllegalArgumentException(it) }
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
        linkedTo(id)?.let { throw IllegalArgumentException(it) }
        val row = db.txnQueries.byId(id).executeAsOneOrNull()
        if (row != null) db.txnQueries.delete(id)
        row
    }

    /** Puts a deleted transaction back, with its original id. */
    fun restore(txn: Txn) {
        db.txnQueries.restore(txn)
    }

    /** Creates an account and returns its id. Names are unique, ignoring case. */
    /** [dueDay] and [minimumCentavos] are for credit only (P3-12); other types never keep them. */
    fun addAccount(
        name: String,
        type: AccountType,
        color: Long,
        startingCentavos: Long,
        dueDay: Int? = null,
        minimumCentavos: Long? = null,
    ): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An account needs a name" }
        require(db.accountQueries.nameTaken(clean).executeAsOne() == 0L) { "An account called $clean already exists" }
        return db.transactionWithResult {
            val credit = type == AccountType.Credit
            require(dueDay == null || dueDay in 1..31) { "The due day is a day of the month, 1 to 31" }
            require(minimumCentavos == null || minimumCentavos >= 0) { "The minimum due can't be below zero" }
            db.accountQueries.create(
                clean, type.dbName, color, startingCentavos,
                if (credit) dueDay?.toLong() else null,
                if (credit) minimumCentavos else null,
            )
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

    /** Whether an account still holds someone's money, so it can't be archived (P3-10). */
    fun holdsMoney(accountId: Long): Boolean =
        accountId in heldPerAccount(db.iouQueries.heldByAccount().executeAsList())

    /**
     * Why an account can't be archived yet, or null if it can: it holds someone's money (P3-10),
     * or it's a card with something still owed (audit #7), which would hide the debt.
     */
    fun archiveRefusal(accountId: Long): String? {
        if (holdsMoney(accountId)) return HandBackFirst
        val card = db.accountQueries.creditAccounts().executeAsList().firstOrNull { it.id == accountId }
        if (card != null && card.owedNow > 0) return payOffFirst(card.name, card.owedNow)
        return null
    }

    /** Hides an account from Home and pickers. Its transactions stay. You must keep at least one. */
    fun archive(accountId: Long) {
        require(db.accountQueries.countActive().executeAsOne() > 1) { "Keep at least one account" }
        archiveRefusal(accountId)?.let { throw IllegalArgumentException(it) }
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

    /** How many repeats use this category (and its subs). They move with the transactions (audit #1). */
    fun categoryRepeats(categoryId: Long): Long =
        db.repeatRuleQueries.usageOfCategories(withSubs(categoryId)).executeAsOne()

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
            val used = db.txnQueries.usageOf(gone).executeAsOne() + db.repeatRuleQueries.usageOfCategories(gone).executeAsOne()
            if (used > 0) {
                require(moveTo != null) { "Choose where its transactions go first" }
                require(db.categoryQueries.leaves(kind).executeAsList().any { it.id == moveTo && it.id !in gone }) {
                    "Transactions can only move to another category of the same kind"
                }
                db.txnQueries.moveCategory(moveTo, gone)
                db.repeatRuleQueries.moveCategory(moveTo, gone) // a repeat still pointing here would block the delete
            }
            db.categoryQueries.deleteSubs(categoryId)
            db.categoryQueries.deleteOne(categoryId)
        }
    }

    /**
     * Saves an event. No end date means it's ongoing. [repeat]: a new period every N days, weeks or months.
     * [occasional]: start and end become its first run (P3-19); it needs an end date and never repeats. Returns its id.
     */
    fun addEvent(
        name: String,
        start: LocalDate,
        end: LocalDate?,
        repeat: EventRepeat? = null,
        occasional: Boolean = false,
    ): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An event needs a name" }
        require(end == null || end >= start) { "The end date can't be before the start date" }
        require(!occasional || (end != null && repeat == null)) { "An occasional event needs an end date and doesn't repeat" }
        return db.transactionWithResult {
            db.eventQueries.insert(
                clean, start.toString(), end?.toString(), repeat?.every?.toLong(), repeat?.unit?.name, if (occasional) 1L else 0L,
            )
            val id = db.eventQueries.lastId().executeAsOne()
            if (occasional) db.eventQueries.insertRun(id, start.toString(), requireNotNull(end).toString())
            id
        }
    }

    /** An occasional event's runs, oldest first. Re-sent when one changes. */
    fun runs(eventId: Long): Flow<List<EventRun>> =
        db.eventQueries.runsOf(eventId).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toRun() } }

    /** Every run of every event, by event, for the Events list. */
    fun allRuns(): Flow<Map<Long, List<EventRun>>> =
        db.eventQueries.allRuns().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toRun() }.groupBy { it.eventId } }

    /** "+ Set new dates": adds a run. Fails if the dates overlap another run of the same event. Returns its id. */
    fun addRun(eventId: Long, first: LocalDate, last: LocalDate): Long = db.transactionWithResult {
        val runs = db.eventQueries.runsOf(eventId).executeAsList().map { it.toRun() }
        runError(runs, first, last)?.let { throw IllegalArgumentException(it) }
        db.eventQueries.insertRun(eventId, first.toString(), last.toString())
        val id = db.eventQueries.lastId().executeAsOne()
        db.eventQueries.fitToRuns(eventId)
        id
    }

    /** The event's transactions dated inside a run: first day's midnight up to the midnight after the last day. */
    private fun idsInRun(eventId: Long, first: String, last: String, zone: TimeZone): List<Long> {
        val from = LocalDate.parse(first).atStartOfDayIn(zone).toEpochMilliseconds()
        val until = LocalDate.parse(last).plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        return db.txnQueries.idsForEventBetween(eventId, from, until).executeAsList()
    }

    /**
     * Changes a run's dates (P3-20). Same overlap rule as adding one, but it can't clash with itself.
     * Transactions that fall outside the new dates are untagged: they'd belong to no run. Returns what Undo needs.
     */
    fun changeRun(runId: Long, first: LocalDate, last: LocalDate, zone: TimeZone): RunUndo = db.transactionWithResult {
        val old = db.eventQueries.runById(runId).executeAsOne()
        val runs = db.eventQueries.runsOf(old.event_id).executeAsList().map { it.toRun() }
        runError(runs, first, last, except = runId)?.let { throw IllegalArgumentException(it) }
        val before = idsInRun(old.event_id, old.start_date, old.end_date, zone)
        db.eventQueries.updateRun(first.toString(), last.toString(), runId)
        val after = idsInRun(old.event_id, first.toString(), last.toString(), zone).toSet()
        val untagged = before.filterNot { it in after }
        untagged.forEach { db.txnQueries.setEvent(null, it) }
        db.eventQueries.fitToRuns(old.event_id)
        RunUndo(old, untagged, deleted = false)
    }

    /** Deletes a run (P3-20). Its transactions stay but are untagged. The event's only run can't go. */
    fun deleteRun(runId: Long, zone: TimeZone): RunUndo = db.transactionWithResult {
        val old = db.eventQueries.runById(runId).executeAsOne()
        require(db.eventQueries.runsOf(old.event_id).executeAsList().size > 1) {
            "This is the event's only time. Delete the event instead"
        }
        val untagged = idsInRun(old.event_id, old.start_date, old.end_date, zone)
        untagged.forEach { db.txnQueries.setEvent(null, it) }
        db.eventQueries.deleteRun(runId)
        db.eventQueries.fitToRuns(old.event_id)
        RunUndo(old, untagged, deleted = true)
    }

    /** Undo for changeRun and deleteRun: the old dates come back and the transactions are tagged again. */
    fun undoRun(undo: RunUndo) = db.transaction {
        val r = undo.run
        if (undo.deleted) db.eventQueries.restoreRun(r.id, r.event_id, r.start_date, r.end_date)
        else db.eventQueries.updateRun(r.start_date, r.end_date, r.id)
        undo.untagged.forEach { db.txnQueries.setEvent(r.event_id, it) }
        db.eventQueries.fitToRuns(r.event_id)
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

    /** One period's spending per main category, for a repeating event. */
    fun eventSpendingBetween(id: Long, from: Long, until: Long): Flow<List<EventSpendingBetween>> =
        db.txnQueries.eventSpendingBetween(id, from, until).asFlow().mapToList(Dispatchers.Default)

    /** Every expense tagged to an event, for the period totals on the Events list. */
    fun eventExpenses(): Flow<List<ExpensesByEvent>> =
        db.eventQueries.expensesByEvent().asFlow().mapToList(Dispatchers.Default)

    /** Renames an event or changes its dates. Same rules as adding one. */
    fun updateEvent(id: Long, name: String, start: LocalDate, end: LocalDate?, repeat: EventRepeat? = null) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An event needs a name" }
        require(end == null || end >= start) { "The end date can't be before the start date" }
        db.eventQueries.update(clean, start.toString(), end?.toString(), repeat?.every?.toLong(), repeat?.unit?.name, id)
    }

    /** Deletes an event. Its transactions stay but are untagged. Returns what Undo needs. */
    fun deleteEvent(id: Long): DeletedEvent? = db.transactionWithResult {
        val event = db.eventQueries.byId(id).executeAsOneOrNull() ?: return@transactionWithResult null
        val ids = db.txnQueries.idsForEvent(id).executeAsList()
        val runs = db.eventQueries.runsOf(id).executeAsList()
        db.txnQueries.untagEvent(id)
        db.eventQueries.deleteRunsOf(id) // first: a run points at its event
        db.eventQueries.delete(id)
        DeletedEvent(event, ids, runs)
    }

    /** Undo for deleteEvent: the event comes back with its old id, and its transactions are tagged again. */
    fun restoreEvent(deleted: DeletedEvent) = db.transaction {
        val e = deleted.event
        db.eventQueries.restore(e.id, e.name, e.start_date, e.end_date, e.repeat_every, e.repeat_unit, e.occasional)
        deleted.runs.forEach { db.eventQueries.restoreRun(it.id, it.event_id, it.start_date, it.end_date) }
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
    fun saveDue(due: DueRepeat, occurredAt: Long, centavos: Long = due.rule.centavos): Long? = db.transactionWithResult {
        val r = due.rule
        val moved = db.repeatRuleQueries.moveOn(id = r.id, done = due.n.toLong()).value == 1L
        if (moved) insert(r.kind, centavos, r.account_id, r.to_account_id, r.category_id, occurredAt)
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

    /** The number waiting for an OK. Re-sent whenever a repeat changes, so the badge keeps up by itself. */
    fun dueCount(today: LocalDate): Flow<Int> =
        db.repeatRuleQueries.allRules().asFlow().mapToList(Dispatchers.Default).map { countDue(it, today) }
            .combine(db.iouQueries.allWaitingInstallments().asFlow().mapToList(Dispatchers.Default)) { repeats, parts ->
                repeats to parts
            }
            .combine(ious(today)) { (repeats, parts), rows ->
                // P3-17: IOU parts wait in the check-in too, unless the IOU is paid off.
                val open = rows.filterNot { it.settled }.map { it.id }.toSet()
                repeats + parts.count { it.iou_id in open && LocalDate.parse(it.due_on) <= today }
            }
            .combine(db.creditQueries.allCardPayments().asFlow().mapToList(Dispatchers.Default)) { count, card ->
                // P3-15: and card payments, until they're paid.
                count + card.count { it.paid_txn_id == null && LocalDate.parse(it.due_on) <= today }
            }

    /** Dates after today and up to 7 days from now: "Coming up this week". */
    fun upcomingRepeats(today: LocalDate): List<DueRepeat> {
        val weekOut = today.plus(7, DateTimeUnit.DAY)
        return db.repeatRuleQueries.allRules().executeAsList()
            .flatMap { row ->
                val rule = row.toRule()
                generateSequence(row.done.toInt()) { it + 1 }
                    .takeWhile { rule.hasTime(it) && rule.dateOf(it) <= weekOut }
                    .filter { rule.dateOf(it) > today }
                    .map { DueRepeat(it, rule.dateOf(it), row) }
                    .toList()
            }
            .sortedBy { it.date }
    }

    fun repeatLabels(): Map<Long, RepeatLabel> =
        db.repeatRuleQueries.labels().executeAsList().associate {
            it.id to RepeatLabel(
                title = it.category ?: "Transfer",
                subtitle = if (it.to_account != null) "${it.account} → ${it.to_account}" else it.account,
            )
        }

    /**
     * Saves an IOU and moves the money in or out of [accountId] as one 'IOU' transaction,
     * so the balance changes but it's never income or spending. Returns the IOU's id.
     * With [NewIou.paidFor] it's also spending in that category, and the account ends up unchanged (P3-8).
     */
    fun addIou(iou: NewIou, accountId: Long, occurredAt: Long): Long = db.transactionWithResult {
        val personId = checkIou(iou)
        val txnId = insertIouMoney(iou, accountId, occurredAt)
        val spendId = iou.paidFor?.let { insertSpend(iou.centavos, accountId, it, occurredAt) }
        db.iouQueries.insert(
            iou.kind.name, personId, iou.centavos, iou.whatFor.trim(), iou.openedOn.toString(), iou.dueOn?.toString(),
            iou.interest.kindName(), iou.interest.value(), iou.note.trim(), txnId, spendId,
        )
        val id = db.iouQueries.lastId().executeAsOne()
        insertInstallments(id, iou.installments)
        id
    }

    private fun insertInstallments(iouId: Long, parts: List<PlannedPayment>?) {
        parts?.forEachIndexed { i, p ->
            db.iouQueries.insertInstallment(iouId, i + 1L, p.dueOn.toString(), p.centavos, p.interest)
        }
    }

    /** The rules for adding or editing an IOU. Saves the person's name and returns their id. */
    private fun checkIou(iou: NewIou): Long {
        val name = iou.person.trim()
        require(name.isNotEmpty()) { "An IOU needs a person" }
        require(iou.centavos > 0) { "An IOU needs an amount" }
        require(iou.kind != IouKind.HOLDING || iou.interest == Interest.None) { "Money you're holding has no interest" }
        require(iou.paidFor == null || iou.kind == IouKind.OWE) { "Only an IOU you owe can pay for something" }
        val interest = interestOf(IouTerms(iou.centavos, iou.interest, iou.openedOn, iou.dueOn)) // fails early on a bad interest
        iou.installments?.let { parts ->
            require(iou.kind != IouKind.HOLDING) { "Money you're holding is handed back, not paid in parts" }
            require(parts.size >= 2) { "Installments need at least 2 payments" }
            require(parts.sumOf { it.centavos } == iou.centavos + interest) { "The payments must add up to the amount plus interest" }
            require(parts.all { it.dueOn >= iou.openedOn }) { "A payment can't be due before the IOU started" }
            require(iou.dueOn == parts.maxOf { it.dueOn }) { "The due date is the last payment's" }
        }
        db.iouQueries.addPerson(name)
        return db.iouQueries.personId(name).executeAsOne()
    }

    /** The money that moved when the IOU started, signed for the account (see IouKind). */
    private fun insertIouMoney(iou: NewIou, accountId: Long, occurredAt: Long): Long {
        db.txnQueries.insert(IOU, iou.kind.sign * iou.centavos, accountId, null, null, occurredAt)
        return db.txnQueries.lastId().executeAsOne()
    }

    /**
     * "It paid for something": the spending, in the same account as the IOU's money.
     * The IOU's +amount and this -amount cancel out, so the account doesn't change.
     */
    private fun insertSpend(centavos: Long, accountId: Long, categoryId: Long, occurredAt: Long): Long {
        db.txnQueries.insert("EXPENSE", centavos, accountId, null, categoryId, occurredAt)
        return db.txnQueries.lastId().executeAsOne()
    }

    /** Everything the Edit IOU sheet needs, or null if it's gone (P3-8). */
    fun iouToEdit(id: Long): IouToEdit? {
        val iou = db.iouQueries.byId(id).executeAsOneOrNull() ?: return null
        val person = db.iouQueries.iouDetail(id).executeAsOne().person
        val money = iou.txn_id?.let { db.txnQueries.byId(it).executeAsOneOrNull() }
        val spend = iou.spend_txn_id?.let { db.txnQueries.byId(it).executeAsOneOrNull() }
        return IouToEdit(iou, person, money, spend, db.iouQueries.installmentsOf(id).executeAsList())
    }

    /**
     * Edit IOU (P3-8): same rules as adding one. Its money and its spending change with it.
     * Turning "It paid for something" off deletes the spending; turning it on adds it.
     */
    fun updateIou(id: Long, iou: NewIou, accountId: Long, occurredAt: Long) = db.transaction {
        val old = db.iouQueries.byId(id).executeAsOne()
        val personId = checkIou(iou)
        // Audit #5: once something's paid, the payments' money only fits the same kind and at least that amount.
        val paid = paidOn(id)
        if (paid > 0) {
            require(iou.kind.name == old.kind) { KindLocked }
            val total = iou.centavos + interestOf(IouTerms(iou.centavos, iou.interest, iou.openedOn, iou.dueOn))
            require(total >= paid) { alreadyPaidMessage(paid) }
        }
        val txnId = old.txn_id?.also {
            db.txnQueries.update(IOU, iou.kind.sign * iou.centavos, accountId, null, null, occurredAt, it)
        } ?: insertIouMoney(iou, accountId, occurredAt)
        val category = iou.paidFor
        val oldSpend = old.spend_txn_id
        val spendId = when {
            category == null -> null
            oldSpend != null -> oldSpend.also {
                db.txnQueries.update("EXPENSE", iou.centavos, accountId, null, category, occurredAt, it)
            }
            else -> insertSpend(iou.centavos, accountId, category, occurredAt)
        }
        db.iouQueries.update(
            iou.kind.name, personId, iou.centavos, iou.whatFor.trim(), iou.openedOn.toString(), iou.dueOn?.toString(),
            iou.interest.kindName(), iou.interest.value(), iou.note.trim(), txnId, spendId, id,
        )
        // After the IOU stops pointing at it.
        if (category == null && oldSpend != null) db.txnQueries.delete(oldSpend)
        // P3-17: the plan can change only while no part is saved or skipped.
        if (db.iouQueries.countHandled(id).executeAsOne() == 0L) {
            db.iouQueries.deleteInstallmentsOf(id)
            insertInstallments(id, iou.installments)
        }
    }

    /** What's been paid on an IOU so far, interest included (audit #5). */
    fun paidOn(iouId: Long): Long = db.iouQueries.paidOn(iouId).executeAsOne()

    /** Remembered names that start with what's typed, for the suggestions under the field. */
    fun personNames(typed: String): List<String> = db.iouQueries.namesLike(typed.trim()).executeAsList()

    /** What's left on an IOU, interest included. */
    fun iouLeft(id: Long): Long {
        val iou = db.iouQueries.byId(id).executeAsOne()
        val terms = IouTerms(
            iou.centavos, interestFrom(iou.interest_kind, iou.interest_value),
            LocalDate.parse(iou.opened_on), iou.due_on?.let { LocalDate.parse(it) },
        )
        val paid = db.iouQueries.paymentsOf(id).executeAsList().map { Paid(it.principal_centavos, it.interest_centavos) }
        return leftOf(terms, paid)
    }

    /** Whether amounts are hidden (the HIDE ₱ button). Off until you turn it on. */
    /** Every IOU, worked out for [today]. Re-sent whenever an IOU or a payment changes. */
    fun ious(today: LocalDate): Flow<List<IouRow>> =
        db.iouQueries.iouList().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toRow(today) } }

    /** How many IOUs are overdue, for the badge on the IOUs tab. */
    fun overdueCount(today: LocalDate): Flow<Int> = ious(today).map { rows -> rows.count { it.overdue } }

    /** One IOU with its person, or null once it's gone. Re-sent when it changes (P3-7). */
    fun iou(id: Long): Flow<IouDetail?> = db.iouQueries.iouDetail(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    /** An IOU's payments, newest first, with their account. Re-sent when one changes. */
    fun payments(iouId: Long): Flow<List<PaymentRows>> =
        db.iouQueries.paymentRows(iouId).asFlow().mapToList(Dispatchers.Default)

    /**
     * Deletes a payment and the money it moved, so the account and "what's left" go back to before it.
     * Returns what Undo needs.
     */
    fun deletePayment(paymentId: Long): DeletedPayment? = db.transactionWithResult {
        val payment = db.iouQueries.paymentById(paymentId).executeAsOneOrNull() ?: return@transactionWithResult null
        val txn = db.txnQueries.byId(payment.txn_id).executeAsOne()
        // P3-17: a part the check-in saved as this payment is waiting again.
        val installment = db.iouQueries.installmentOfPayment(paymentId).executeAsOneOrNull()
        db.iouQueries.unmarkByPayment(paymentId)
        db.iouQueries.deletePayment(paymentId) // first: the payment points at its transaction
        db.txnQueries.delete(txn.id)
        DeletedPayment(payment, txn, installment)
    }

    /** Undo for deletePayment: both come back with their old ids. */
    fun restorePayment(deleted: DeletedPayment) = db.transaction {
        db.txnQueries.restore(deleted.txn)
        val p = deleted.payment
        db.iouQueries.restorePayment(p.id, p.iou_id, p.txn_id, p.principal_centavos, p.interest_centavos)
        deleted.installmentId?.let { db.iouQueries.setInstallmentPayment(p.id, it) }
    }

    /** An IOU's parts, in order. Re-sent when one is saved or skipped (P3-17). */
    fun installments(iouId: Long): Flow<List<Iou_installment>> =
        db.iouQueries.installmentsOf(iouId).asFlow().mapToList(Dispatchers.Default)

    /** Parts due by [today] and not handled, oldest first, for the check-in. Paid-off IOUs are left out. */
    fun dueInstallments(today: LocalDate): List<DueInstallments> =
        db.iouQueries.dueInstallments(today.toString()).executeAsList().filter { iouLeft(it.iou_id) > 0 }

    /** Where a part's money moves: the account the IOU's money used, or the last one used. */
    fun installmentAccount(iouId: Long): Long? {
        val active = accounts().map { it.id }.toSet()
        return db.iouQueries.byId(iouId).executeAsOneOrNull()?.txn_id
            ?.let { db.txnQueries.byId(it).executeAsOneOrNull()?.account_id }
            ?.takeIf { it in active }
            ?: lastAccountUsed()
    }

    /**
     * Check-in Save for a due part (P3-17): records it as a payment, with its share of interest.
     * [centavos] is the part's amount, or what Edit changed it to; never more than what's left.
     * Returns the payment's id, or null if the part was already handled (a double tap).
     */
    fun saveInstallment(installmentId: Long, centavos: Long, occurredAt: Long): Long? = db.transactionWithResult {
        val part = db.iouQueries.installmentById(installmentId).executeAsOne()
        if (part.state != "WAITING") return@transactionWithResult null
        val context = paymentContext(part.iou_id)
        val amount = minOf(centavos, context.left)
        val interest = minOf(part.interest_centavos, amount, context.interestDue)
        val account = requireNotNull(installmentAccount(part.iou_id)) { "Add an account first" }
        val paymentId = recordPayment(part.iou_id, amount, interest, account, occurredAt)
        db.iouQueries.markInstallment(state = "SAVED", paymentId = paymentId, id = installmentId)
        paymentId
    }

    /** Check-in Skip: nothing is paid, and the part isn't listed again. False if it was already handled. */
    fun skipInstallment(installmentId: Long): Boolean = db.transactionWithResult {
        val waiting = db.iouQueries.installmentById(installmentId).executeAsOneOrNull()?.state == "WAITING"
        if (waiting) db.iouQueries.markInstallment(state = "SKIPPED", paymentId = null, id = installmentId)
        waiting
    }

    /** What the payment sheet needs: the kind, the person, what's left and the interest still due (P3-9). */
    fun paymentContext(iouId: Long): PaymentContext {
        val detail = db.iouQueries.iouDetail(iouId).executeAsOne()
        val paid = db.iouQueries.paymentsOf(iouId).executeAsList().map { Paid(it.principal_centavos, it.interest_centavos) }
        val terms = detail.terms()
        return PaymentContext(IouKind.valueOf(detail.kind), detail.person, leftOf(terms, paid), interestDue(terms, paid))
    }

    /** Each account's own money: its balance minus what I'm still holding in it for others. Only accounts that hold some. */
    fun ownMoney(): Map<Long, Long> =
        heldPerAccount(db.iouQueries.heldByAccount().executeAsList()).mapValues { (account, holding) -> balance(account) - holding }

    /** What I'm holding for others in each account, re-sent when an IOU or payment changes (P3-10). */
    fun held(): Flow<Map<Long, Long>> =
        db.iouQueries.heldByAccount().asFlow().mapToList(Dispatchers.Default).map(::heldPerAccount)

    /** The net worth card: balances and open IOUs together. */
    fun netWorth(today: LocalDate): Flow<NetWorth> =
        balances().combine(ious(today)) { balances, rows -> totalBalance(balances) to rows }
            .combine(creditAccounts()) { (total, rows), credit -> netWorthOf(total, rows, totalOwed(credit)) }

    /**
     * Records a payment (P3-9): Record payment, Payment received or Hand back.
     * The money moves in [accountId] as an 'IOU' transaction, the other way from when the IOU started.
     * Returns the payment's id.
     */
    fun recordPayment(
        iouId: Long,
        centavos: Long,
        interest: Long,
        accountId: Long,
        occurredAt: Long,
        extraAsInterest: Long = 0,
    ): Long =
        db.transactionWithResult {
            val context = paymentContext(iouId)
            require(centavos > 0) { "A payment needs an amount" }
            require(centavos <= context.left) { "That's more than what's left" }
            require(interest in 0..minOf(centavos, context.interestDue)) { "That interest part doesn't fit" }
            if (context.kind == IouKind.OWE) {
                ownMoney()[accountId]?.let { own -> require(centavos + extraAsInterest <= own) { "That would use money you're holding" } }
            }
            db.txnQueries.insert(IOU, -context.kind.sign * centavos, accountId, null, null, occurredAt)
            val txnId = db.txnQueries.lastId().executeAsOne()
            db.iouQueries.insertPayment(iouId, txnId, centavos - interest, interest)
            val paymentId = db.iouQueries.lastId().executeAsOne()
            // P3-16: paid more than what was left. The extra is interest: income when they paid me, a charge when I paid.
            if (extraAsInterest > 0) {
                require(context.kind != IouKind.HOLDING) { "Money you're holding has no interest" }
                if (context.kind == IouKind.OWED) {
                    insert("INCOME", extraAsInterest, accountId, null, interestCategory("INCOME"), occurredAt, null)
                } else {
                    insert("EXPENSE", extraAsInterest, accountId, null, interestCategory("EXPENSE"), occurredAt, null)
                }
            }
            paymentId
        }

    /**
     * Where extra paid on an IOU goes (P3-16): Income › Interest › From IOUs, or Fees & Charges › Interest.
     * Made if it's not there (setup lets you untick it). Returns the subcategory's id.
     */
    fun interestCategory(kind: String): Long =
        if (kind == "INCOME") subcategory("INCOME", "Interest", "From IOUs", 0xFF6B93D6)
        else chargeCategory(ChargeKind.Interest)

    /** Whether an account is a card or lender (P3-12). */
    fun isCredit(accountId: Long): Boolean = db.accountQueries.typeOf(accountId).executeAsOneOrNull() == AccountType.Credit.dbName

    /** Ids of the active credit accounts, so the Add sheet knows when to offer Installments. */
    fun creditIds(): Set<Long> = db.accountQueries.creditAccounts().executeAsList().map { it.id }.toSet()

    /**
     * P3-15: buy in installments on a credit account. The full price is spending today (it adds to what's owed),
     * and each payment waits in the check-in. Returns the purchase's transaction id.
     */
    fun buyInInstallments(
        price: Long,
        categoryId: Long,
        accountId: Long,
        occurredAt: Long,
        eventId: Long?,
        plan: NewCardPlan,
    ): Long = db.transactionWithResult {
        require(isCredit(accountId)) { "Installments are for credit accounts" }
        require(plan.payments.isNotEmpty()) { "A plan needs payments" }
        require(plan.payments.sumOf { it.centavos } >= price) { "The payments add up to less than the price" }
        require(plan.payments.sumOf { it.interest } == plan.payments.sumOf { it.centavos } - price) { "The interest parts don't add up" }
        val txnId = insert("EXPENSE", price, accountId, null, categoryId, occurredAt, eventId)
        db.creditQueries.insertPlan(accountId, plan.name.trim().ifEmpty { "Installments" }, price, txnId)
        val planId = db.creditQueries.lastId().executeAsOne()
        plan.payments.forEachIndexed { i, p ->
            db.creditQueries.insertInstallment(planId, i + 1L, p.dueOn.toString(), p.centavos, p.interest)
        }
        txnId
    }

    /**
     * Borrow (P3-18): a cash loan from a credit account, like GLoan or a cash advance.
     * The money is a transfer from the card into [intoAccountId], so it isn't income, and what's owed goes up.
     * The plan works like a purchase's (P3-15): each payment's interest part is spending when it's paid.
     * Returns the transfer's id.
     */
    fun borrow(cardId: Long, borrowed: Long, intoAccountId: Long, plan: NewCardPlan, occurredAt: Long): Long = db.transactionWithResult {
        require(isCredit(cardId)) { "Borrow from a credit account" }
        require(!isCredit(intoAccountId)) { "The money goes into cash, bank or e-wallet" }
        require(plan.payments.isNotEmpty()) { "A plan needs payments" }
        require(plan.payments.sumOf { it.centavos } >= borrowed) { LoanShort }
        require(plan.payments.sumOf { it.interest } == plan.payments.sumOf { it.centavos } - borrowed) { "The interest parts don't add up" }
        val txnId = insert("TRANSFER", borrowed, cardId, intoAccountId, null, occurredAt, null)
        db.creditQueries.insertPlan(cardId, plan.name.trim().ifEmpty { "Cash loan" }, borrowed, txnId)
        val planId = db.creditQueries.lastId().executeAsOne()
        plan.payments.forEachIndexed { i, p ->
            db.creditQueries.insertInstallment(planId, i + 1L, p.dueOn.toString(), p.centavos, p.interest)
        }
        txnId
    }

    /** Every card plan with payments still waiting, re-sent when one is paid. */
    fun cardPlanLines(): Flow<List<CardPlanLine>> =
        db.creditQueries.allCardPayments().asFlow().mapToList(Dispatchers.Default).map(::cardPlanLines)

    /** Card payments due by [today] and not paid, oldest first, for the check-in. */
    fun dueCardPayments(today: LocalDate): List<DueCardPayments> =
        db.creditQueries.dueCardPayments(today.toString()).executeAsList()

    /** Where a card payment's money comes from: the last account used, if it isn't credit, else the first that isn't. */
    fun cardPayFrom(): Long? {
        val last = lastAccountUsed()
        if (last != null && !isCredit(last)) return last
        return accounts().map { it.id }.firstOrNull { !isCredit(it) }
    }

    /**
     * Check-in Save for a due card payment (P3-15): its interest part is spending under Fees & Charges › Interest,
     * charged to the card, and the whole payment is a transfer from [fromAccountId] to the card.
     * So what's owed drops by the principal part. False if it was already paid (a double tap).
     */
    fun payCardPayment(paymentId: Long, fromAccountId: Long, occurredAt: Long): Boolean = db.transactionWithResult {
        val part = db.creditQueries.cardPaymentById(paymentId).executeAsOne()
        if (part.paid_txn_id != null) return@transactionWithResult false
        require(!isCredit(fromAccountId)) { "Pay from cash, bank or e-wallet" }
        checkOwnMoney(fromAccountId, part.centavos) // audit #6: same rule as Pay bill
        payOne(paymentId, fromAccountId, occurredAt) != null
    }

    /** Pays one plan payment. Returns the transactions it made, or null if it was already paid. */
    private fun payOne(paymentId: Long, fromAccountId: Long, occurredAt: Long): List<Long>? {
        val part = db.creditQueries.cardPaymentById(paymentId).executeAsOne()
        if (part.paid_txn_id != null) return null
        require(!isCredit(fromAccountId)) { "Pay from cash, bank or e-wallet" }
        val accountId = db.creditQueries.planAccount(part.plan_id).executeAsOne()
        val interestTxn = if (part.interest_centavos > 0) {
            insert("EXPENSE", part.interest_centavos, accountId, null, chargeCategory(ChargeKind.Interest), occurredAt, null)
        } else null
        val paid = insert("TRANSFER", part.centavos, fromAccountId, accountId, null, occurredAt, null)
        db.creditQueries.markPaid(interestTxnId = interestTxn, paidTxnId = paid, id = paymentId)
        return listOfNotNull(interestTxn, paid)
    }

    /** P3-13: a plan's payments still waiting, in order, for "Which payments?". */
    fun waitingPayments(planId: Long): List<Credit_installment> = db.creditQueries.waitingOfPlan(planId).executeAsList()

    /** What's owed on a card beyond its plans' waiting price parts: purchases and fees with no installments. */
    fun otherCharges(cardId: Long): Long {
        val owed = db.accountQueries.creditAccounts().executeAsList().firstOrNull { it.id == cardId }?.owedNow ?: 0
        return (owed - db.creditQueries.waitingPrincipal(cardId).executeAsOne()).coerceAtLeast(0)
    }

    /** An account's own money: its balance, minus anything held for others (P3-10). */
    fun ownMoneyOf(accountId: Long): Long = ownMoney()[accountId] ?: balance(accountId)

    /**
     * Pay bill for a plan (P3-13): its next [count] waiting payments, from [fromAccountId], today.
     * Each one is saved like the check-in's Save, so they leave the check-in.
     */
    fun payPlan(planId: Long, count: Int, fromAccountId: Long, occurredAt: Long): BillPaid = db.transactionWithResult {
        val parts = waitingPayments(planId).take(count)
        require(count >= 1 && parts.size == count) { "That is more than these charges" }
        checkOwnMoney(fromAccountId, parts.sumOf { it.centavos })
        val txns = parts.flatMap { payOne(it.id, fromAccountId, occurredAt).orEmpty() }
        BillPaid(parts.map { it.id }, txns.map { db.txnQueries.byId(it).executeAsOne() })
    }

    /** Pay bill for "Other charges": a transfer to the card, never more than those charges. */
    fun payOtherCharges(cardId: Long, centavos: Long, fromAccountId: Long, occurredAt: Long): BillPaid = db.transactionWithResult {
        require(!isCredit(fromAccountId)) { "Pay from cash, bank or e-wallet" }
        require(centavos <= otherCharges(cardId)) { "That is more than these charges" }
        checkOwnMoney(fromAccountId, centavos)
        val id = insert("TRANSFER", centavos, fromAccountId, cardId, null, occurredAt, null)
        BillPaid(emptyList(), listOf(db.txnQueries.byId(id).executeAsOne()))
    }

    private fun checkOwnMoney(accountId: Long, centavos: Long) {
        val own = ownMoneyOf(accountId)
        val name = db.accountQueries.nameOf(accountId).executeAsOne()
        require(centavos <= own) { ownMoneyMessage(name, own) }
    }

    /** Undo of Pay bill: the payments wait again and the transactions go. */
    fun undoBill(paid: BillPaid) = db.transaction {
        paid.payments.forEach { db.creditQueries.unmarkPaid(it) }
        paid.txns.forEach { db.txnQueries.delete(it.id) }
    }

    /** Fees & Charges › Interest, Late fee or Annual fee (P3-14). Made if it's not there. */
    fun chargeCategory(charge: ChargeKind): Long = subcategory("EXPENSE", "Fees & Charges", charge.label, 0xFF8A6D5C)

    private fun subcategory(kind: String, mainName: String, subName: String, color: Long): Long {
        val main = db.categoryQueries.findCategory(kind, null, mainName).executeAsOneOrNull()
            ?: addCategory(mainName, kind, null, color, CategoryIcons.first())
        return db.categoryQueries.findCategory(kind, main, subName).executeAsOneOrNull()
            ?: addCategory(subName, kind, main, color, CategoryIcons.first())
    }

    /**
     * Add charge (P3-14): interest or a fee from the statement. It's spending under Fees & Charges,
     * in the credit account, so it adds to what's owed. Returns the transaction's id.
     */
    fun addCharge(accountId: Long, charge: ChargeKind, centavos: Long, occurredAt: Long): Long = db.transactionWithResult {
        require(db.accountQueries.typeOf(accountId).executeAsOneOrNull() == AccountType.Credit.dbName) { "Charges go on a credit account" }
        insert("EXPENSE", centavos, accountId, null, chargeCategory(charge), occurredAt, null)
    }

    /** Every payment's money, for the Flow summary. Re-sent when a payment changes. */
    fun paymentMoves(): Flow<List<PaymentMoves>> = db.iouQueries.paymentMoves().asFlow().mapToList(Dispatchers.Default)

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
            db.iouQueries.eraseAll() // first: an IOU points at its transaction
            db.creditQueries.eraseAll() // and a card plan at its account
            db.txnQueries.eraseAll()
            db.repeatRuleQueries.eraseAll()
            db.eventQueries.eraseRuns()
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
            AccountBackup(it.id, it.name, it.type, it.color, it.starting_centavos, it.archived, it.sort_order, it.due_day, it.minimum_centavos)
        },
        categories = db.categoryQueries.backupAll().executeAsList().map {
            CategoryBackup(it.id, it.name, it.kind, it.parent_id, it.color, it.sort_order, it.icon)
        },
        events = db.eventQueries.backupAll().executeAsList().map {
            EventBackup(it.id, it.name, it.start_date, it.end_date, it.repeat_every, it.repeat_unit, it.occasional)
        },
        eventRuns = db.eventQueries.allRuns().executeAsList().map { RunBackup(it.id, it.event_id, it.start_date, it.end_date) },
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
        people = db.iouQueries.backupPeople().executeAsList().map { PersonBackup(it.id, it.name) },
        ious = db.iouQueries.backupIous().executeAsList().map {
            IouBackup(
                it.id, it.kind, it.person_id, it.centavos, it.what_for, it.opened_on, it.due_on,
                it.interest_kind, it.interest_value, it.note, it.txn_id, it.spend_txn_id,
            )
        },
        iouPayments = db.iouQueries.backupPayments().executeAsList().map {
            IouPaymentBackup(it.id, it.iou_id, it.txn_id, it.principal_centavos, it.interest_centavos)
        },
        installments = db.iouQueries.backupInstallments().executeAsList().map {
            InstallmentBackup(it.id, it.iou_id, it.n, it.due_on, it.centavos, it.interest_centavos, it.state, it.payment_id)
        },
        cardPlans = db.creditQueries.backupPlans().executeAsList().map {
            CardPlanBackup(it.id, it.account_id, it.name, it.principal_centavos, it.txn_id)
        },
        cardPayments = db.creditQueries.backupInstallments().executeAsList().map {
            CardPaymentBackup(it.id, it.plan_id, it.n, it.due_on, it.centavos, it.interest_centavos, it.interest_txn_id, it.paid_txn_id)
        },
    )

    /** Replaces everything with the backup. All or nothing: if any row fails, the old data stays. */
    fun restore(file: BackupFile) {
        db.transaction {
            eraseAll()
            file.accounts.forEach {
                db.accountQueries.backupInsert(
                    it.id, it.name, it.type, it.color, it.startingCentavos, it.archived, it.sortOrder, it.dueDay, it.minimumCentavos,
                )
            }
            file.categories.forEach {
                db.categoryQueries.backupInsert(it.id, it.name, it.kind, it.parentId, it.color, it.sortOrder, it.icon)
            }
            file.events.forEach {
                db.eventQueries.restore(it.id, it.name, it.startDate, it.endDate, it.repeatEvery, it.repeatUnit, it.occasional)
            }
            file.eventRuns.forEach { db.eventQueries.restoreRun(it.id, it.eventId, it.startDate, it.endDate) }
            file.repeats.forEach {
                db.repeatRuleQueries.backupInsert(
                    it.id, it.every, it.unit, it.startDate, it.times, it.untilDate, it.done,
                    it.kind, it.centavos, it.accountId, it.toAccountId, it.categoryId,
                )
            }
            file.txns.forEach {
                db.txnQueries.backupInsert(it.id, it.kind, it.centavos, it.accountId, it.toAccountId, it.categoryId, it.occurredAt, it.eventId, it.repeatId)
            }
            // After txns: an IOU and its payments point at their transactions.
            file.people.forEach { db.iouQueries.backupInsertPerson(it.id, it.name) }
            file.ious.forEach {
                db.iouQueries.backupInsertIou(Iou(
                    it.id, it.kind, it.personId, it.centavos, it.whatFor, it.openedOn, it.dueOn,
                    it.interestKind, it.interestValue, it.note, it.txnId, it.spendTxnId,
                ))
            }
            file.iouPayments.forEach {
                db.iouQueries.backupInsertPayment(Iou_payment(it.id, it.iouId, it.txnId, it.principalCentavos, it.interestCentavos))
            }
            file.installments.forEach {
                db.iouQueries.backupInsertInstallment(
                    Iou_installment(it.id, it.iouId, it.n, it.dueOn, it.centavos, it.interestCentavos, it.state, it.paymentId),
                )
            }
            file.cardPlans.forEach {
                db.creditQueries.backupInsertPlan(Credit_plan(it.id, it.accountId, it.name, it.principalCentavos, it.txnId))
            }
            file.cardPayments.forEach {
                db.creditQueries.backupInsertInstallment(
                    Credit_installment(it.id, it.planId, it.n, it.dueOn, it.centavos, it.interestCentavos, it.interestTxnId, it.paidTxnId),
                )
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

    /** A Reminders switch (P3-11). Off until you turn it on. */
    fun reminderOn(switch: ReminderSwitch): Boolean = db.settingQueries.get(switch.key).executeAsOneOrNull() == "true"

    fun setReminder(switch: ReminderSwitch, on: Boolean) {
        db.settingQueries.put(switch.key, on.toString())
    }

    fun anyReminderOn(): Boolean = ReminderSwitch.entries.any { reminderOn(it) }

    /**
     * The daily check (P3-11): what to remind about since the last check, for the switches that are on.
     * It notes today as checked, so a second run today returns nothing.
     * Amounts are masked when HIDE ₱ is on, since notifications show on the lock screen.
     */
    fun reminders(today: LocalDate): List<Reminder> = db.transactionWithResult {
        val checked = db.settingQueries.get("reminders_checked").executeAsOneOrNull()?.let(LocalDate::parse)
        val days = reminderDays(checked, today)
        db.settingQueries.put("reminders_checked", today.toString())
        if (days.isEmpty()) return@transactionWithResult emptyList<Reminder>()
        val hidden = amountsHidden()
        val show: (String) -> String = { masked(it, hidden) }
        fun money(c: Long) = show(formatAmount(c, AmountKind.Transfer))
        val out = mutableListOf<Reminder>()
        if (reminderOn(ReminderSwitch.Ious)) {
            val things = mutableListOf<DueThing>()
            db.iouQueries.iouList().executeAsList().map { it.toRow(today) }.forEach { row ->
                val due = row.due ?: return@forEach
                if (row.settled) return@forEach
                things += DueThing(
                    "iou-${row.id}-$due", due, "IOU due today",
                    iouReminderText(row.kind, row.person, row.left, row.whatFor, show), ReminderTarget.Ious, "IOU overdue",
                )
            }
            dueInstallments(today).forEach { p ->
                things += DueThing(
                    "part-${p.id}", LocalDate.parse(p.due_on), "IOU payment due today",
                    "${p.person} · payment ${p.n} of ${p.total_parts} · ${money(p.centavos)}", ReminderTarget.CheckIn, "IOU payment overdue",
                )
            }
            dueCardPayments(today).forEach { p ->
                things += DueThing(
                    "card-payment-${p.id}", LocalDate.parse(p.due_on), "Card payment due today",
                    "${p.account} · ${p.name} · payment ${p.n} of ${p.total_parts} · ${money(p.centavos)}", ReminderTarget.CheckIn,
                )
            }
            db.accountQueries.creditAccounts().executeAsList().forEach { card ->
                val dueDay = card.due_day?.toInt() ?: return@forEach
                if (card.owedNow <= 0) return@forEach
                days.filter { nextDueDate(dueDay, it) == it }.forEach { d ->
                    val minimum = card.minimum_centavos?.let { " · minimum ${money(it)}" }.orEmpty()
                    things += DueThing("card-${card.id}-$d", d, "Credit bill due today", "${card.name} · ${money(card.owedNow)} owed$minimum", ReminderTarget.Ious)
                }
            }
            out += remindersFor(things, days)
        }
        if (reminderOn(ReminderSwitch.Repeats)) {
            val labels = repeatLabels()
            val titles = dueRepeats(today).filter { it.date in days }.map { labels[it.rule.id]?.title ?: "Repeat" }
            repeatReminder(titles, today)?.let { out += it }
        }
        if (reminderOn(ReminderSwitch.Backup)) {
            val nudged = db.settingQueries.get("backup_nudged").executeAsOneOrNull()?.let(LocalDate::parse)
            backupReminder(lastBackup(), nudged, today)?.let {
                out += it
                db.settingQueries.put("backup_nudged", today.toString())
            }
        }
        out
    }

    /** Every transaction as a spreadsheet, oldest first. */
    fun csv(zone: TimeZone): String {
        val header = csvLine(listOf("date", "type", "amount", "account", "to account", "category", "subcategory", "event", "person"))
        val rows = db.txnQueries.csvRows().executeAsList().map {
            csvLine(listOf(
                dayOf(it.occurred_at, zone).toString(),
                if (it.kind == IOU) IOU else it.kind.lowercase().replaceFirstChar { c -> c.uppercase() },
                plainAmount(it.centavos),
                it.account,
                it.to_account,
                it.category,
                it.subcategory,
                it.event,
                it.person,
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
