package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Account types. Credit (P3-12) is a card or a lender: what's in it is what I owe. */
enum class AccountType(val label: String, val dbName: String) {
    Cash("Cash", "CASH"),
    Bank("Bank", "BANK"),
    EWallet("E-wallet", "EWALLET"),
    Credit("Credit", "CREDIT"),
}

/** The prototype's account colors. The first one is the default. */
val AccountColors: List<Long> = listOf(
    0xFF9B7BFF, 0xFF2F7CF6, 0xFFFF5A5A, 0xFFFFAB00, 0xFF1D9BB3, 0xFFE07A00, 0xFF3DDC6A,
)

/** The New account sheet's state and rules. `takenNames` are existing names in lowercase. */
class AccountForm(
    private val takenNames: Set<String>,
    private val archivedNames: Set<String> = emptySet(),
) {
    var name by mutableStateOf("")
    var type by mutableStateOf<AccountType?>(null)
    var color by mutableStateOf(AccountColors.first())
    var starting by mutableStateOf("")

    /** Credit only: both optional. No limit field (the client, Nov 5). */
    var dueDay by mutableStateOf("")
    var minimum by mutableStateOf("")

    val isCredit: Boolean get() = type == AccountType.Credit

    /** What's saved as the starting balance. For credit you type what's already owed, so it's saved below zero. */
    val startingCentavos: Long?
        get() {
            if (starting.isBlank()) return if (isCredit) 0 else null
            val typed = parseCentavos(starting) ?: return null
            return if (isCredit) -typed else typed
        }

    val dueDayNumber: Int? get() = if (isCredit) dueDay.trim().toIntOrNull()?.takeIf { it in 1..31 } else null
    val minimumCentavos: Long? get() = if (isCredit) parseCentavos(minimum)?.takeIf { it >= 0 } else null

    /** One message at a time, shown under the fields. */
    val error: String?
        get() {
            val clean = name.trim()
            return when {
                clean.isNotEmpty() && clean.lowercase() in archivedNames ->
                    "You have an archived account called $clean. Restore it from Manage."
                clean.isNotEmpty() && clean.lowercase() in takenNames ->
                    "You already have an account called $clean"
                starting.isNotBlank() && parseCentavos(starting) == null ->
                    if (isCredit) "Already owed must be a number, like 1500.00" else "Starting balance must be a number, like 1500.00"
                isCredit && starting.isNotBlank() && (parseCentavos(starting) ?: 0) < 0 ->
                    "Already owed can't be below zero"
                isCredit && dueDay.isNotBlank() && dueDayNumber == null -> "The due day is a day of the month, 1 to 31"
                isCredit && minimum.isNotBlank() && minimumCentavos == null -> "The minimum due must be a number, like 500.00"
                else -> null
            }
        }

    val canSave: Boolean
        get() = name.isNotBlank() && type != null && startingCentavos != null && error == null
}
