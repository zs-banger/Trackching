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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * S16 New IOU / Edit IOU: kind, person, amount, what for, dates, where the money went
 * (or what it paid for), interest and a note. [editing] is the IOU's id.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun IouSheet(
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    startKind: IouKind = IouKind.OWE,
    editing: Long? = null,
) {
    val c = TrackchingTheme.colors
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val form = remember {
        val loaded = editing?.let { ledger.iouToEdit(it) }
        if (loaded != null) {
            IouForm.from(loaded, today).also { if (it.accountId == null) it.accountId = ledger.lastAccountUsed() }
        } else {
            IouForm(today, ledger.lastAccountUsed()).also { it.kind = startKind }
        }
    }
    val accounts = remember { ledger.accounts() }
    val categories = remember { ledger.quickCategories("EXPENSE", 100) }
    val names = remember(form.person) {
        if (form.person.isBlank()) emptyList() else ledger.personNames(form.person).filterNot { it.equals(form.person.trim(), ignoreCase = true) }
    }
    var picking by remember { mutableStateOf<String?>(null) } // "opened" or "due"
    var saved by remember { mutableStateOf(false) }

    TrackchingSheet(title = if (editing == null) "New IOU" else "Edit IOU", onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SegmentedTabs(IouKind.entries, form.kind, { form.kind = it }, { it.tabLabel() })

            OutlinedTextField(
                value = form.person,
                onValueChange = { form.person = it },
                label = { Text(if (form.kind == IouKind.HOLDING) "Whose money" else "Person") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (names.isNotEmpty()) {
                ChipRow { names.forEach { name -> Chip(name, selected = false, onClick = { form.person = name }) } }
            }

            OutlinedTextField(
                value = form.amount,
                onValueChange = { form.amount = it },
                label = { Text("Amount (₱)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.whatFor,
                onValueChange = { form.whatFor = it },
                label = { Text("What for (optional)") },
                placeholder = { Text("e.g. Lunch money") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            SheetLabel("Date")
            Chip(dateLabel(form.openedOn), selected = true, onClick = { picking = "opened" })
            if (form.kind != IouKind.HOLDING) {
                SheetLabel("Due")
                ChipRow {
                    Chip("No due date", selected = form.dueOn == null, onClick = { form.dueOn = null })
                    Chip(form.dueOn?.let { dateLabel(it) } ?: "Pick a date", selected = form.dueOn != null, onClick = { picking = "due" })
                }
            }

            // Where the money went. When I owe, someone may have paid for something instead (P3-8).
            if (form.kind == IouKind.OWE) {
                ChipRow {
                    Chip("Money moved", selected = !form.paidSomething, onClick = { form.paidSomething = false })
                    Chip("It paid for something", selected = form.paidSomething, onClick = { form.paidSomething = true })
                }
            }
            if (form.paysForSomething) {
                SheetLabel("What it paid for")
                Text("Saved as spending in this category. Your accounts don't change.", color = c.ink2, fontSize = 13.sp)
                ChipRow {
                    categories.forEach { cat ->
                        Chip(cat.name, selected = form.categoryId == cat.id, onClick = { form.categoryId = cat.id }, dot = Color(cat.color))
                    }
                }
            } else {
                SheetLabel(if (form.kind == IouKind.OWED) "Came out of" else "Went into")
                ChipRow {
                    accounts.forEach { a -> Chip(a.name, selected = form.accountId == a.id, onClick = { form.accountId = a.id }) }
                }
            }

            if (form.kind != IouKind.HOLDING) {
                SheetLabel("Interest")
                ChipRow {
                    InterestMode.entries.forEach { m ->
                        Chip(m.label, selected = form.interestMode == m, onClick = { form.interestMode = m; form.interestText = "" })
                    }
                }
                if (form.interestMode != InterestMode.None) {
                    OutlinedTextField(
                        value = form.interestText,
                        onValueChange = { form.interestText = it },
                        label = { Text(if (form.interestMode == InterestMode.Flat) "Interest (₱)" else "Interest (% a month)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OutlinedTextField(
                value = form.note,
                onValueChange = { form.note = it },
                label = { Text("Note (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(form.error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = form.canSave && !saved, role = Role.Button) {
                    val iou = form.toNewIou() ?: return@clickable
                    val account = form.accountId ?: return@clickable
                    if (saved) return@clickable
                    saved = true
                    val at = atTimeOfDay(form.openedOn, Clock.System.now().toEpochMilliseconds())
                    if (editing == null) ledger.addIou(iou, account, at) else ledger.updateIou(editing, iou, account, at)
                    onSaved()
                    close()
                },
                color = if (form.canSave) c.orange else c.line,
                edge = if (form.canSave) c.blue else c.line,
            ) {
                Text(
                    if (editing == null) "SAVE IOU" else "SAVE CHANGES",
                    Modifier.padding(12.dp),
                    color = if (form.canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }

    picking?.let { which ->
        PickDate(
            initial = if (which == "opened") form.openedOn else (form.dueOn ?: form.openedOn),
            onPicked = { day ->
                if (which == "opened") form.openedOn = day else form.dueOn = day
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
