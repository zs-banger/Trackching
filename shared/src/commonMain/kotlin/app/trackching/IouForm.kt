package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    val centavos: Long? get() = parseCentavos(amount)?.takeIf { it > 0 }

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
                due != null && due < openedOn -> "The due date can't be before the date it started"
                kind != IouKind.HOLDING && interestMode == InterestMode.Monthly && due == null ->
                    "Interest by the month needs a due date"
                else -> null
            }
        }

    val canSave: Boolean
        get() = error == null && person.isNotBlank() && centavos != null && interest != null &&
            accountId != null && (!paysForSomething || categoryId != null) &&
            (kind == IouKind.HOLDING || interestMode == InterestMode.None || interestText.isNotBlank())

    /** What the Ledger saves, or null while something's missing. */
    fun toNewIou(): NewIou? {
        if (!canSave) return null
        return NewIou(
            kind, person.trim(), centavos ?: return null, whatFor.trim(), openedOn,
            dueOn = if (kind == IouKind.HOLDING) null else dueOn,
            interest = interest ?: return null,
            note = note.trim(),
            paidFor = if (paysForSomething) categoryId else null,
        )
    }

    companion object {
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
            categoryId = edit.spend?.category_id
            when (val i = interestFrom(iou.interest_kind, iou.interest_value)) {
                Interest.None -> interestMode = InterestMode.None
                is Interest.Flat -> { interestMode = InterestMode.Flat; interestText = typedOf(i.centavos) }
                is Interest.Monthly -> { interestMode = InterestMode.Monthly; interestText = typedOf(i.basisPoints) }
            }
        }
    }
}
