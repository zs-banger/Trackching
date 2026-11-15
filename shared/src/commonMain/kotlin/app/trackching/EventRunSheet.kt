package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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

/** "+ Set new dates" on an occasional event: one more run. Dates can't overlap its other runs. */
@OptIn(ExperimentalTime::class)
@Composable
fun EventRunSheet(ledger: Ledger, eventId: Long, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = TrackchingTheme.colors
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val loaded by remember(ledger, eventId) { ledger.runs(eventId) }.collectAsState(initial = null)
    val runs = loaded ?: return
    var first by remember { mutableStateOf(today) }
    var last by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf<String?>(null) } // "first" or "last"
    var saved by remember { mutableStateOf(false) }
    val error = runError(runs, first, last)

    TrackchingSheet(title = "New dates", onDismiss = onDismiss) { close ->
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
                    ledger.addRun(eventId, first, last)
                    onSaved()
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
                    fontWeight = FontWeight.ExtraBold,
                )
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
