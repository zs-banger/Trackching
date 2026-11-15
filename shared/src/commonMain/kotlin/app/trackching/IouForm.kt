package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import app.trackching.db.Iou_installment
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDate

/** Interest as the New IOU sheet offers it. */
enum class InterestMode(val label: String) { None("No interest"), Flat("Flat amount"), Monthly("% a month") }

/** The New IOU / Edit IOU sheet's state and rules (P3-8). No Compose screens here, so it's quick to test. */
class IouForm(today: LocalDate, accountId: Long?) {
    var kind by mutableStateOf(IouKind.OWE)
    var person by mutableStateOf("")
    var amount by mutableStateOf("")
    var whatFor by mutableStateOf("")
    var openedOn by mutableStateOf(today)
    var dueOn by mutableStateOf<LocalDate?>(null)
    var note by mutableStateOf("")
    var accountId by mutableStateOf(accountId)

    /** "It paid for something": spending in a category instead of money moving. Only for I owe. */
    var paidSomething by mutableStateOf(false)
    var categoryId by mutableStateOf<Long?>(null)

    var interestMode by mutableStateOf(InterestMode.None)
    var interestText by mutableStateOf("")

    /** Installments (P3-17): paid back in parts. Not for money I'm holding. */
    var inParts by mutableStateOf(false)
    var partsCount by mutableStateOf("")
    var partsUnit by mutableStateOf(RepeatUnit.MONTH)
    var firstDue by mutableStateOf<LocalDate?>(null)
    var sameEach by mutableStateOf(true)
    /** "They differ": one typed amount per payment. */
    val partAmounts = mutableStateListOf<String>()

    /** Edit IOU after a part was saved or skipped: the plan stays as it is. */
    var planLocked by mutableStateOf(false)
    var lockedLine by mutableStateOf<String?>(null)

    val centavos: Long? get() = parseCentavos(amount)?.takeIf { it > 0 }

    val usesParts: Boolean get() = inParts && kind != IouKind.HOLDING && !planLocked

    /** 2 to 60 payments. */
    val count: Int? get() = partsCount.trim().toIntOrNull()?.takeIf { it in 2..MaxParts }

    val partDates: List<LocalDate>?
        get() {
            val n = count ?: return null
            val first = firstDue ?: return null
            return installmentDates(first, n, partsUnit)
        }

    /** The due date the IOU saves: the last payment's when it's in parts. */
    val effectiveDue: LocalDate?
        get() = when {
            kind == IouKind.HOLDING -> null
            usesParts -> partDates?.last()
            else -> dueOn
        }

    /** Amount plus interest: what the payments must add up to. */
    val partsTotal: Long?
        get() {
            val amt = centavos ?: return null
            val i = interest ?: return null
            if (i is Interest.Monthly && effectiveDue == null) return null
            return amt + interestOf(IouTerms(amt, i, openedOn, effectiveDue))
        }

    /** Keeps one box per payment for "They differ". */
    fun fitAmounts() {
        val n = count ?: return
        while (partAmounts.size < n) partAmounts.add("")
        while (partAmounts.size > n) partAmounts.removeAt(partAmounts.size - 1)
    }

    private val typedAmounts: List<Long>?
        get() {
            val n = count ?: return null
            if (partAmounts.size != n) return null
            return partAmounts.map { parseCentavos(it)?.takeIf { c -> c > 0 } ?: return null }
        }

    /** Each payment's amount and share of interest, or null while something's missing. */
    val plan: List<PlannedPayment>?
        get() {
            val dates = partDates ?: return null
            val total = partsTotal ?: return null
            val amounts = if (sameEach) evenAmounts(total, dates.size) else typedAmounts ?: return null
            if (amounts.sum() != total || amounts.any { it <= 0 }) return null
            return planOf(dates, amounts, interestOf(IouTerms(centavos ?: return null, interest ?: return null, openedOn, dates.last())))
        }

    /** Paying for something only makes sense when I owe: someone paid for me. */
    val paysForSomething: Boolean get() = kind == IouKind.OWE && paidSomething

    /** Holding money never has interest, so the toggle is hidden and ignored. */
    val interest: Interest?
        get() = when {
            kind == IouKind.HOLDING || interestMode == InterestMode.None -> Interest.None
            interestMode == InterestMode.Flat -> parseCentavos(interestText)?.let { Interest.Flat(it) }
            // "5" -> 500 basis points, "2.5" -> 250: the same two decimals as pesos and centavos.
            else -> parseCentavos(interestText)?.let { Interest.Monthly(it) }
        }

    /** One message at a time, shown above Save. Only for things the user typed; blanks just keep Save grey. */
    val error: String?
        get() {
            val due = dueOn
            return when {
                amount.isNotBlank() && centavos == null -> "The amount must be a number, like 1500.00"
                interestText.isNotBlank() && interest == null -> "The interest must be a number, like 5 or 2.5"
                usesParts && partsCount.isNotBlank() && count == null -> "Installments need 2 to $MaxParts payments"
                usesParts && firstDue.let { it != null && it < openedOn } -> "The first payment can't be before the date it started"
                usesParts && !sameEach && typedAmounts != null && partsTotal != null && typedAmounts?.sum() != partsTotal ->
                    "The payments add up to ₱${formatCentavos(typedAmounts?.sum() ?: 0)}, not ₱${formatCentavos(partsTotal ?: 0)}"
                usesParts && !sameEach && partAmounts.any { it.isNotBlank() && (parseCentavos(it) ?: 0) <= 0 } ->
                    "Each payment must be a number, like 500.00"
                !usesParts && due != null && due < openedOn -> "The due date can't be before the date it started"
                kind != IouKind.HOLDING && interestMode == InterestMode.Monthly && !usesParts && due == null ->
                    "Interest by the month needs a due date"
                else -> null
            }
        }

    val canSave: Boolean
        get() = error == null && person.isNotBlank() && centavos != null && interest != null &&
            accountId != null && (!paysForSomething || categoryId != null) &&
            (kind == IouKind.HOLDING || interestMode == InterestMode.None || interestText.isNotBlank()) &&
            (!usesParts || plan != null)

    /** What the Ledger saves, or null while something's missing. */
    fun toNewIou(): NewIou? {
        if (!canSave) return null
        return NewIou(
            kind, person.trim(), centavos ?: return null, whatFor.trim(), openedOn,
            dueOn = effectiveDue,
            interest = interest ?: return null,
            note = note.trim(),
            paidFor = if (paysForSomething) categoryId else null,
            installments = if (usesParts) plan else null,
        )
    }

    /** Fills the installment fields from a saved plan. Locked once any part was saved or skipped. */
    private fun loadPlan(parts: List<Iou_installment>) {
        inParts = true
        partsCount = parts.size.toString()
        val dates = parts.map { LocalDate.parse(it.due_on) }
        firstDue = dates.first()
        partsUnit = InstallmentUnits.firstOrNull { installmentDates(dates.first(), dates.size, it) == dates } ?: RepeatUnit.MONTH
        val amounts = parts.map { it.centavos }
        sameEach = evenAmounts(amounts.sum(), amounts.size) == amounts
        partAmounts.clear()
        if (!sameEach) partAmounts.addAll(amounts.map(::typedOf))
        planLocked = parts.any { it.state != "WAITING" }
        if (planLocked) lockedLine = installmentsLine(parts)
    }

    companion object {
        const val MaxParts = 60

        /** Edit IOU: the same sheet with everything filled in. */
        fun from(edit: IouToEdit, today: LocalDate): IouForm = IouForm(today, edit.money?.account_id).apply {
            val iou = edit.iou
            kind = IouKind.valueOf(iou.kind)
            person = edit.person
            amount = typedOf(iou.centavos)
            whatFor = iou.what_for
            openedOn = LocalDate.parse(iou.opened_on)
            dueOn = iou.due_on?.let(LocalDate::parse)
            note = iou.note
            paidSomething = edit.spend != null
            if (edit.installments.isNotEmpty()) loadPlan(edit.installments)
            categoryId = edit.spend?.category_id
            when (val i = interestFrom(iou.interest_kind, iou.interest_value)) {
                Interest.None -> interestMode = InterestMode.None
                is Interest.Flat -> { interestMode = InterestMode.Flat; interestText = typedOf(i.centavos) }
                is Interest.Monthly -> { interestMode = InterestMode.Monthly; interestText = typedOf(i.basisPoints) }
            }
        }
    }
}
