package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** Which run sheet is open: a new run for an event, or changing one of its runs (P3-20). */
data class RunSheetFor(val eventId: Long, val editing: EventRun? = null)

/**
 * "+ Set new dates" on an occasional event: one more run. Dates can't overlap its other runs.
 * With [editing] (P3-20) it changes that run's dates instead, and offers Delete.
 * [onSaved] gets what Undo needs when a run was changed, or null for a new run.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun EventRunSheet(
    ledger: Ledger,
    eventId: Long,
    onDismiss: () -> Unit,
    onSaved: (RunUndo?) -> Unit,
    editing: EventRun? = null,
    onDeleted: (RunUndo) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.todayIn(zone) }
    val loaded by remember(ledger, eventId) { ledger.runs(eventId) }.collectAsState(initial = null)
    val runs = loaded ?: return
    var first by remember { mutableStateOf(editing?.first ?: today) }
    var last by remember { mutableStateOf(editing?.last ?: today) }
    var picking by remember { mutableStateOf<String?>(null) } // "first" or "last"
    var saved by remember { mutableStateOf(false) }
    val error = runError(runs, first, last, except = editing?.id)

    TrackchingSheet(title = if (editing == null) "New dates" else "Change dates", onDismiss = onDismiss) { close ->
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("STARTS", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Chip(dateLabel(first), selected = true, onClick = { picking = "first" })
            Text("ENDS", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Chip(dateLabel(last), selected = true, onClick = { picking = "last" })

            Text(error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            val canSave = error == null && !saved
            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = canSave, role = Role.Button) {
                    saved = true
                    if (editing == null) {
                        ledger.addRun(eventId, first, last)
                        onSaved(null)
                    } else {
                        onSaved(ledger.changeRun(editing.id, first, last, zone))
                    }
                    close()
                },
                color = if (canSave) c.orange else c.line,
                edge = if (canSave) c.blue else c.line,
            ) {
                Text(
                    "SAVE DATES",
                    Modifier.padding(12.dp),
                    color = if (canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold,
                )
            }

            if (editing != null && runs.size > 1) {
                TextButton(
                    onClick = {
                        onDeleted(ledger.deleteRun(editing.id, zone))
                        close()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("DELETE THESE DATES", color = c.expense, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            } else if (editing != null) {
                // An occasional event always keeps one run (its own dates come from its runs).
                Text("This is the only time. To remove it, delete the event.", color = c.ink2, fontSize = 13.sp)
            }
        }
    }

    picking?.let { which ->
        PickDate(
            initial = if (which == "first") first else last,
            onPicked = { day: LocalDate ->
                if (which == "first") {
                    first = day
                    if (last < day) last = day // keep the end on or after the start
                } else {
                    last = day
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}
