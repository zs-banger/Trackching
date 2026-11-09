package app.trackching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDate

/** The New event sheet's state and rules. `today` is passed in so tests can pick any day. */
class EventForm(today: LocalDate) {
    var name by mutableStateOf("")
    var start by mutableStateOf(today)
    var end by mutableStateOf<LocalDate?>(null)

    val error: String?
        get() {
            val e = end
            return if (e != null && e < start) "The end date can't be before the start date" else null
        }

    val canSave: Boolean get() = name.isNotBlank() && error == null
}

/** "Nov 9, 2026" */
fun dateLabel(date: LocalDate): String {
    val month = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$month ${date.day}, ${date.year}"
}
