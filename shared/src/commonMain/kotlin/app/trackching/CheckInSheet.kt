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
import app.trackching.db.DueInstallments
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
    val parts = remember { InstallmentCheckIn(ledger.dueInstallments(today)) } // P3-17
    var editingPart by remember { mutableStateOf<Int?>(null) }
    var partError by remember { mutableStateOf<Pair<Int, String>?>(null) }

    fun save(i: Int) {
        val due = checkIn.rows[i]
        val at = atTimeOfDay(due.date, Clock.System.now().toEpochMilliseconds())   // decision 3
        ledger.saveDue(due, at, checkIn.amountOf(i))
        checkIn.mark(i, RowState.Saved)
    }

    /** Pays an IOU part, dated on its due date. A part that can't be paid (money you're holding) says why. */
    fun savePart(i: Int) {
        val due = parts.rows[i]
        val at = atTimeOfDay(LocalDate.parse(due.due_on), Clock.System.now().toEpochMilliseconds())
        try {
            ledger.saveInstallment(due.id, parts.amountOf(i), at)
            parts.mark(i, RowState.Saved)
            if (partError?.first == i) partError = null
        } catch (e: IllegalArgumentException) {
            partError = i to (e.message ?: "Couldn't save this payment")
        }
    }

    TrackchingSheet(title = "Check in", onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (checkIn.rows.isEmpty() && parts.rows.isEmpty()) {
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
            if (parts.rows.isNotEmpty()) Tag("IOU payments", Modifier.padding(top = 8.dp))
            parts.rows.forEachIndexed { i, due ->
                val dueOn = LocalDate.parse(due.due_on)
                Column(
                    Modifier.fillMaxWidth().background(c.band).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(due.title(), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Payment ${due.n} of ${due.total_parts} · ${dayLabel(dueOn, today)}",
                                color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Amount(parts.amountOf(i), partKind(due), maxFontSize = 18.sp)
                    }
                    val blocker = parts.blockedBy(i)
                    when {
                        parts.stateOf(i) == RowState.Saved ->
                            Text("Saved on ${monthDay(dueOn)}", color = c.income, fontWeight = FontWeight.Bold)
                        parts.stateOf(i) == RowState.Skipped ->
                            Text("Skipped, nothing paid", color = c.ink2, fontWeight = FontWeight.Bold)
                        blocker != null ->
                            Text("Do ${monthDay(LocalDate.parse(blocker.due_on))} first", color = c.ink2, fontSize = 13.sp)
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Save", selected = true, onClick = { savePart(i) })
                            Chip("Skip", selected = false, onClick = {
                                ledger.skipInstallment(due.id)
                                parts.mark(i, RowState.Skipped)
                            })
                            Chip("Edit", selected = false, onClick = { editingPart = i })
                        }
                    }
                    partError?.takeIf { it.first == i }?.let { Text(it.second, color = c.expense, fontSize = 13.sp) }
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
            val waiting = checkIn.waiting + parts.waiting
            if (waiting > 0) {
                OffsetBlock(Modifier.weight(1f)) {
                    TextButton(onClick = {
                        // Oldest first, so each repeat's earlier date is always saved before its later one.
                        checkIn.rows.indices.forEach { if (checkIn.canAct(it)) save(it) }
                        parts.rows.indices.forEach { if (parts.canAct(it)) savePart(it) }
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
    val part = editingPart
    if (part != null) {
        EditDueSheet(
            current = parts.amountOf(part),
            kind = partKind(parts.rows[part]),
            onDone = { parts.setAmount(part, it) },
            onDismiss = { editingPart = null },
            message = "This changes only this payment. The rest of the plan stays the same.",
        )
    }
}

/** Money in when they pay me, money out when I pay. */
private fun partKind(due: DueInstallments): AmountKind =
    if (due.kind == IouKind.OWED.name) AmountKind.Income else AmountKind.Expense

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
private fun EditDueSheet(
    current: Long,
    kind: AmountKind,
    onDone: (Long) -> Unit,
    onDismiss: () -> Unit,
    message: String = "This changes only this time. To change the repeat itself, edit the transaction later.",
) {
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
                message,
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
