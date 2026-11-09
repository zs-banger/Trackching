package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Account types you can pick today. Credit arrives with the credit stories. */
enum class AccountType(val label: String, val dbName: String) {
    Cash("Cash", "CASH"),
    Bank("Bank", "BANK"),
    EWallet("E-wallet", "EWALLET"),
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

    val startingCentavos: Long? get() = if (starting.isBlank()) null else parseCentavos(starting)

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
                    "Starting balance must be a number, like 1500.00"
                else -> null
            }
        }

    val canSave: Boolean
        get() = name.isNotBlank() && type != null && startingCentavos != null && error == null
}
