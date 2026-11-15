package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun CheckInSheet(ledger: Ledger, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val checkIn = remember { CheckIn(ledger.dueRepeats(today)) }       // decision 1: read once
    val upcoming = remember { ledger.upcomingRepeats(today) }
    val labels = remember { ledger.repeatLabels() }
    var editingRow by remember { mutableStateOf<Int?>(null) }

    fun save(i: Int) {
        val due = checkIn.rows[i]
        val at = atTimeOfDay(due.date, Clock.System.now().toEpochMilliseconds())   // decision 3
        ledger.saveDue(due, at, checkIn.amountOf(i))
        checkIn.mark(i, RowState.Saved)
    }

    TrackchingSheet(title = "Check in", onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (checkIn.rows.isEmpty()) {
                Text("Nothing is due right now.", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            checkIn.rows.forEachIndexed { i, due ->
                val label = labels[due.rule.id] ?: RepeatLabel("Repeat", "")
                Column(
                    Modifier.fillMaxWidth().background(c.band).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DueLine(label, due, checkIn.amountOf(i), today)
                    val blocker = checkIn.blockedBy(i)
                    when {
                        checkIn.stateOf(i) == RowState.Saved ->
                            Text("Saved on ${monthDay(due.date)}", color = c.income, fontWeight = FontWeight.Bold)
                        checkIn.stateOf(i) == RowState.Skipped ->
                            Text("Skipped, nothing saved", color = c.ink2, fontWeight = FontWeight.Bold)
                        blocker != null ->
                            Text("Do ${monthDay(blocker.date)} first", color = c.ink2, fontSize = 13.sp)
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Save", selected = true, onClick = { save(i) })
                            Chip("Skip", selected = false, onClick = {
                                ledger.skipDue(due)
                                checkIn.mark(i, RowState.Skipped)
                            })
                            Chip("Edit", selected = false, onClick = { editingRow = i })
                        }
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                Tag("Coming up this week", Modifier.padding(top = 8.dp))
                upcoming.forEach { due ->
                    val label = labels[due.rule.id] ?: RepeatLabel("Repeat", "")
                    DueLine(label, due, due.rule.centavos, today, Modifier.background(c.band).padding(12.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val waiting = checkIn.waiting
            if (waiting > 0) {
                OffsetBlock(Modifier.weight(1f)) {
                    TextButton(onClick = {
                        // Oldest first, so each repeat's earlier date is always saved before its later one.
                        checkIn.rows.indices.forEach { if (checkIn.canAct(it)) save(it) }
                    }) { Text("SAVE ALL ($waiting)", color = c.ink, fontWeight = FontWeight.ExtraBold) }
                }
                TextButton(onClick = close) { Text("Later", fontWeight = FontWeight.Bold) }
            } else {
                OffsetBlock(Modifier.weight(1f)) {
                    TextButton(onClick = close) { Text("DONE", color = c.ink, fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }

    val row = editingRow
    if (row != null) {
        EditDueSheet(
            current = checkIn.amountOf(row),
            kind = kindOf(checkIn.rows[row].rule.kind),
            onDone = { checkIn.setAmount(row, it) },
            onDismiss = { editingRow = null },
        )
    }
}

/** "Meals  − ₱150.00" over "GCash · Fri, Oct 31". */
@Composable
private fun DueLine(label: RepeatLabel, due: DueRepeat, centavos: Long, today: LocalDate, modifier: Modifier = Modifier) {
    val c = TrackchingTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label.title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${label.subtitle} · ${dayLabel(due.date, today)}", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Amount(centavos, kindOf(due.rule.kind), maxFontSize = 18.sp)
    }
}

/** Edit: a different amount for this time only. */
@Composable
private fun EditDueSheet(current: Long, kind: AmountKind, onDone: (Long) -> Unit, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    var typed by remember { mutableStateOf(typedOf(current)) }
    val centavos = parseCentavos(typed) ?: 0L
    TrackchingSheet(title = "Edit amount", onDismiss = onDismiss) { close ->
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Amount(centavos, kind, maxFontSize = 40.sp, alwaysShow = true)
            Text(
                "This changes only this time. To change the repeat itself, edit the transaction later.",
                color = c.ink2,
                fontSize = 13.sp,
            )
        }
        Keypad(
            onKey = { typed = press(typed, it) },
            saveEnabled = centavos > 0,
            onSave = {
                onDone(centavos)
                close()
            },
        )
    }
}
