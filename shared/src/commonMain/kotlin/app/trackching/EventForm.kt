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

    /** Null = doesn't repeat. Days, weeks or months otherwise (never years). */
    var repeatUnit by mutableStateOf<RepeatUnit?>(null)
    var repeatEvery by mutableStateOf(1)

    fun stepEvery(by: Int) {
        repeatEvery = (repeatEvery + by).coerceIn(1, 99)
    }

    /** Happens now and then, with its own runs of dates (P3-19). It never repeats. */
    var occasional by mutableStateOf(false)

    /** What to save: null when it doesn't repeat. */
    val repeat: EventRepeat? get() = if (occasional) null else repeatUnit?.let { EventRepeat(repeatEvery, it) }

    val error: String?
        get() {
            val e = end
            return when {
                e != null && e < start -> "The end date can't be before the start date"
                occasional && e == null -> "An occasional event needs an end date"
                else -> null
            }
        }

    val canSave: Boolean get() = name.isNotBlank() && error == null
}

/** "Nov 9, 2026" */
fun dateLabel(date: LocalDate): String {
    val month = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$month ${date.day}, ${date.year}"
}
