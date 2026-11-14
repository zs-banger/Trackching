package app.trackching

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The Repeat row on a new transaction. [start] is the transaction's date. */
@Composable
fun RepeatField(form: RepeatForm, start: LocalDate) {
    val c = TrackchingTheme.colors
    var pickingUntil by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Never", selected = !form.on, onClick = { form.unit = null })
            RepeatUnit.entries.forEach { u ->
                Chip(u.many.replaceFirstChar { it.uppercase() }, selected = form.unit == u, onClick = { form.unit = u })
            }
        }

        val unit = form.unit
        if (unit != null) {
            Stepper(
                label = "Every",
                value = form.every,
                after = if (form.every == 1) unit.one else unit.many,
                onStep = form::stepEvery,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Forever", selected = form.end == EndChoice.Forever, onClick = { form.end = EndChoice.Forever })
                Chip("${form.times} times", selected = form.end == EndChoice.Times, onClick = { form.end = EndChoice.Times })
                Chip(
                    form.until?.let { "Until ${monthDay(it)}" } ?: "Until a date",
                    selected = form.end == EndChoice.Until,
                    onClick = { pickingUntil = true },
                )
            }
            if (form.end == EndChoice.Times) {
                Stepper(label = "Times", value = form.times, after = "counting this one", onStep = form::stepTimes)
            }
            val rule = form.rule(start)
            Text(
                rule?.let { repeatSentence(it) } ?: "Pick an end date after ${monthDay(start)}",
                color = if (rule == null) c.expense else c.ink2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (pickingUntil) {
        PickDate(
            initial = form.until ?: start.plus(1, DateTimeUnit.MONTH),
            onPicked = { day ->
                form.until = day
                form.end = EndChoice.Until
                pickingUntil = false
            },
            onDismiss = { pickingUntil = false },
        )
    }
}

/** "Every [−] 2 [+] weeks". */
@Composable
private fun Stepper(label: String, value: Int, after: String, onStep: (Int) -> Unit) {
    val c = TrackchingTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Chip("−", selected = false, onClick = { onStep(-1) })
        Text("$value", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Chip("+", selected = false, onClick = { onStep(1) })
        Text(after, color = c.ink, fontSize = 14.sp)
    }
}
