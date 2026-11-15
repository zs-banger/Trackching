package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.CreditAccounts
import kotlinx.datetime.LocalDate

/** S14: the IOUs tab. I owe / Owed to me / Holding, each with its total and its people. */
@Composable
fun IousScreen(
    ledger: Ledger,
    today: LocalDate,
    onOpenPerson: (PersonLine, IouKind) -> Unit,
    onOpenDueSoon: () -> Unit,
    onNew: (IouKind) -> Unit,
    onOpenIou: (Long) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger, today) { ledger.ious(today) }.collectAsState(initial = null)
    val rows = loaded ?: return // still loading
    var kind by rememberSaveable { mutableStateOf(IouKind.OWE) }
    var showSettled by rememberSaveable { mutableStateOf(false) }
    val view = remember(rows, kind) { tabOf(rows, kind) }
    val credit by remember(ledger) { ledger.creditAccounts() }.collectAsState(initial = emptyList())
    var charging by remember { mutableStateOf<CreditAccounts?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "tabs") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedTabs(IouKind.entries, kind, { kind = it; showSettled = false }, { it.tabLabel() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Total ${kind.tabLabel().lowercase()}", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(shown(formatAmount(view.total, AmountKind.Transfer)), color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    TextButton(onClick = onOpenDueSoon) {
                        Text("DUE SOON ›", color = c.orangeInk, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (view.people.isEmpty() && !(kind == IouKind.OWE && credit.isNotEmpty())) {
            item(key = "empty") {
                EmptyState(emptyMessage(kind), newLabel(kind), { onNew(kind) }, Modifier.padding(horizontal = 16.dp))
            }
        }
        items(view.people, key = { "person-${it.personId}" }) { line ->
            PersonRow(line, onClick = { onOpenPerson(line, kind) })
        }
        // P3-12: what I owe cards and lenders, under I owe.
        if (kind == IouKind.OWE && credit.isNotEmpty()) {
            item(key = "credit-title") {
                Text(
                    "CREDIT",
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
            items(credit, key = { "credit-${it.id}" }) { CreditRow(it, today, onCharge = { charging = it }) }
        }
        if (view.settled.isNotEmpty()) {
            item(key = "settled") {
                Row(
                    Modifier.fillMaxWidth().clickable { showSettled = !showSettled }.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Settled (${view.settled.size})", color = c.ink2, fontWeight = FontWeight.Bold)
                    Text(if (showSettled) "▲" else "▼", color = c.ink2)
                }
            }
            if (showSettled) {
                items(view.settled, key = { "iou-${it.id}" }) { IouLine(it, showPerson = true, onClick = { onOpenIou(it.id) }) }
            }
        }
    }

    charging?.let { card -> ChargeSheet(ledger, card, onDismiss = { charging = null }) }
}

private fun emptyMessage(kind: IouKind) = when (kind) {
    IouKind.OWE -> "You don't owe anyone right now."
    IouKind.OWED -> "Nobody owes you right now."
    IouKind.HOLDING -> "You're not holding money for anyone."
}

private fun newLabel(kind: IouKind) = when (kind) {
    IouKind.OWE -> "I owe someone"
    IouKind.OWED -> "Someone owes me"
    IouKind.HOLDING -> "I'm holding money"
}

/** A card or lender: "₱X owed", when it's due, and the minimum. */
@Composable
private fun CreditRow(card: CreditAccounts, today: LocalDate, onCharge: () -> Unit) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.band).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(creditDueLine(card.due_day, today), color = c.ink2, fontSize = 13.sp)
            card.minimum_centavos?.let {
                Text("Minimum ${shown(formatAmount(it, AmountKind.Transfer))}", color = c.ink2, fontSize = 13.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${shown(formatAmount(card.owedNow, AmountKind.Transfer))} owed", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            TextButton(onClick = onCharge) { Text("+ ADD CHARGE", color = c.orangeInk, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PersonRow(line: PersonLine, onClick: () -> Unit) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(c.band).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.person, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (line.count == 1) "1 IOU" else "${line.count} IOUs", color = c.ink2, fontSize = 13.sp)
        }
        if (line.overdue) OverdueLabel()
        Text(shown(formatAmount(line.left, AmountKind.Transfer)), color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** One IOU: what it's for, its due date, and what's left. */
@Composable
fun IouLine(row: IouRow, showPerson: Boolean, onClick: (() -> Unit)? = null) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(c.band).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val title = listOfNotNull(row.person.takeIf { showPerson }, row.whatFor.ifBlank { null }).joinToString(" · ")
            Text(title.ifBlank { row.kind.tabLabel() }, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val due = row.due
            Text(
                when {
                    row.settled -> "Settled"
                    due != null -> "Due ${dateLabel(due)}"
                    else -> "No due date"
                },
                color = c.ink2, fontSize = 13.sp,
            )
        }
        if (row.overdue) OverdueLabel()
        if (!row.settled) {
            Text(shown(formatAmount(row.left, AmountKind.Transfer)), color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun OverdueLabel() {
    val c = TrackchingTheme.colors
    Text(
        "OVERDUE",
        Modifier.background(c.alert).padding(horizontal = 8.dp, vertical = 2.dp),
        color = c.onOrange, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
    )
}

/** One person's IOUs of one kind, opened from the IOUs tab. */
@Composable
fun PersonIousScreen(
    ledger: Ledger,
    today: LocalDate,
    personId: Long,
    name: String,
    kind: IouKind,
    onBack: () -> Unit,
    onOpenIou: (Long) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger, today) { ledger.ious(today) }.collectAsState(initial = null)
    val rows = loaded ?: return
    val mine = remember(rows) {
        rows.filter { it.personId == personId && it.kind == kind }.sortedWith(compareBy<IouRow> { it.settled }.thenByDescending { it.id })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 8.dp)) {
                BackLink("IOUs", onBack)
                Text(name, Modifier.padding(horizontal = 8.dp), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(kind.tabLabel(), Modifier.padding(start = 8.dp, bottom = 12.dp), color = c.ink2, fontWeight = FontWeight.Bold)
            }
        }
        items(mine, key = { it.id }) { IouLine(it, showPerson = false, onClick = { onOpenIou(it.id) }) }
    }
}

/** Everything with a due date, soonest first. Holding isn't here: it has no due date. */
@Composable
fun DueSoonScreen(ledger: Ledger, today: LocalDate, onBack: () -> Unit, onOpenIou: (Long) -> Unit = {}) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger, today) { ledger.ious(today) }.collectAsState(initial = null)
    val rows = loaded ?: return
    val due = remember(rows) { dueSoon(rows) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 8.dp)) {
                BackLink("IOUs", onBack)
                Text("Due soon", Modifier.padding(horizontal = 8.dp), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Holding isn't here: it has no due date.",
                    Modifier.padding(start = 8.dp, bottom = 12.dp), color = c.ink2, fontSize = 13.sp,
                )
            }
        }
        if (due.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing has a due date.", color = c.ink2)
                }
            }
        }
        items(due, key = { it.id }) { IouLine(it, showPerson = true, onClick = { onOpenIou(it.id) }) }
    }
}
