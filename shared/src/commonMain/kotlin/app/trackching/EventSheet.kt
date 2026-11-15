package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

private enum class Picking { Start, End }

/** Events repeat by the day, week or month. Never by the year. */
private val EventUnits = listOf(RepeatUnit.DAY, RepeatUnit.WEEK, RepeatUnit.MONTH)

/** "New event": name, start date, optional end date. */
@OptIn(ExperimentalTime::class)
@Composable
fun EventSheet(
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    editing: EventRow? = null,
    onDeleted: (DeletedEvent) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val form = remember {
        EventForm(Clock.System.todayIn(TimeZone.currentSystemDefault())).also { f -> editing?.let { f.loadFrom(it) } }
    }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var saved by remember { mutableStateOf(false) }

    TrackchingSheet(title = if (editing == null) "New event" else "Edit event", onDismiss = onDismiss) { close ->
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Baguio trip") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Starts")
            Chip(dateLabel(form.start), selected = true, onClick = { picking = Picking.Start })

            Label("Ends")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("No end date", selected = form.end == null, onClick = { form.end = null })
                Chip(
                    form.end?.let { dateLabel(it) } ?: "Pick a date",
                    selected = form.end != null,
                    onClick = { picking = Picking.End },
                )
            }

            Label("Repeat")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Doesn't repeat", selected = form.repeatUnit == null, onClick = { form.repeatUnit = null })
                EventUnits.forEach { u ->
                    Chip(u.many.replaceFirstChar { it.uppercase() }, selected = form.repeatUnit == u, onClick = { form.repeatUnit = u })
                }
            }
            form.repeatUnit?.let { unit ->
                Stepper(
                    label = "Every",
                    value = form.repeatEvery,
                    after = if (form.repeatEvery == 1) unit.one else unit.many,
                    onStep = form::stepEvery,
                )
                Text("Each ${unit.one} gets its own total.", color = c.ink2, fontSize = 13.sp)
            }
            Text(form.error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = form.canSave && !saved, role = Role.Button) {
                    if (saved) return@clickable
                    saved = true
                    if (editing == null) ledger.addEvent(form.name, form.start, form.end, form.repeat)
                    else ledger.updateEvent(editing.id, form.name, form.start, form.end, form.repeat)
                    onSaved()
                    close()
                },
                color = if (form.canSave) c.orange else c.line,
                edge = if (form.canSave) c.blue else c.line,
            ) {
                Text(
                    if (editing == null) "SAVE EVENT" else "SAVE CHANGES",
                    Modifier.padding(12.dp),
                    color = if (form.canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            if (editing != null) {
                TextButton(
                    onClick = {
                        ledger.deleteEvent(editing.id)?.let(onDeleted)
                        close()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("DELETE EVENT", color = c.expense, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    picking?.let { which ->
        PickDate(
            initial = if (which == Picking.Start) form.start else (form.end ?: form.start),
            onPicked = { date ->
                if (which == Picking.Start) form.start = date else form.end = date
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** The phone's calendar. Material reports the day as UTC midnight, so we read it back in UTC. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
internal fun PickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    onPicked(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                } else {
                    onDismiss()
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
