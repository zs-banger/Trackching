package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.PaymentRows
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * S15: one IOU. Who, which way, what for, the amount, interest, due date and what's left,
 * then its payments, newest first. Tap a payment to delete it (with Undo).
 */
@Composable
fun IouScreen(
    ledger: Ledger,
    iouId: Long,
    today: LocalDate,
    backTo: String,
    onBack: () -> Unit,
    onRecord: () -> Unit,
    onEdit: () -> Unit,
    onDeletedPayment: (DeletedPayment) -> Unit,
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val loadedIou by remember(ledger, iouId) { ledger.iou(iouId) }.collectAsState(initial = null)
    val loadedPayments by remember(ledger, iouId) { ledger.payments(iouId) }.collectAsState(initial = null)
    val iou = loadedIou ?: return // loading, or gone
    val payments = loadedPayments ?: return
    val parts by remember(ledger, iouId) { ledger.installments(iouId) }.collectAsState(initial = emptyList())
    val kind = IouKind.valueOf(iou.kind)
    val terms = remember(iou) { iou.terms() }
    val left = remember(iou, payments) { iou.leftAfter(payments) }
    val settled = left <= 0
    val overdue = isOverdue(terms.dueOn, left, today)
    var toDelete by remember { mutableStateOf<PaymentRows?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BackLink(backTo, onBack, Modifier.weight(1f))
                    TextButton(onClick = onEdit) {
                        Text("✎ EDIT", color = c.orangeInk, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kind.withPerson().uppercase(), color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(iou.person, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    if (iou.what_for.isNotBlank()) Text(iou.what_for, color = c.ink2, fontSize = 15.sp)
                }
            }
        }
        item(key = "left") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("What's left", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (settled) "Settled" else shown(formatAmount(left, AmountKind.Transfer)),
                            color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    if (overdue) {
                        Text(
                            "OVERDUE",
                            Modifier.background(c.alert).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = c.onOrange, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Fact("Amount", shown(formatAmount(iou.centavos, AmountKind.Transfer)))
                if (kind != IouKind.HOLDING) Fact("Interest", interestLabel(terms.interest))
                Fact("Started", dateLabel(terms.openedOn))
                if (kind != IouKind.HOLDING) Fact("Due", terms.dueOn?.let { dateLabel(it) } ?: "No due date")
                // P3-17: "3 of 6 payments left · next due Mar 10"
                if (!settled) installmentsLine(parts)?.let { Fact("Installments", it) }
                if (iou.note.isNotBlank()) Fact("Note", iou.note)

                if (!settled) {
                    OffsetBlock(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onRecord),
                        color = c.orange,
                        edge = c.blue,
                    ) {
                        Text(
                            kind.actionLabel().uppercase(),
                            Modifier.padding(12.dp),
                            color = c.onOrange, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
        item(key = "payments-title") {
            Text(
                "PAYMENTS",
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (payments.isEmpty()) {
            item(key = "no-payments") {
                Text("Nothing paid yet.", Modifier.padding(horizontal = 16.dp), color = c.ink2)
            }
        }
        items(payments, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { toDelete = p }
                    .background(c.band)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dateLabel(dayOf(p.occurred_at, zone)), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val extra = if (p.interest_centavos > 0) " · incl. ₱ ${formatCentavos(p.interest_centavos)} interest" else ""
                    Text(p.account + extra, color = c.ink2, fontSize = 13.sp)
                }
                Text(shown(formatAmount(p.total, AmountKind.Transfer)), color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this payment?") },
            text = { Text("${shown(formatAmount(p.total, AmountKind.Transfer))} on ${dateLabel(dayOf(p.occurred_at, zone))}. The money goes back to ${p.account}.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    ledger.deletePayment(p.id)?.let(onDeletedPayment)
                }) { Text("Delete", color = c.expense, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

/** One labelled line: "Due    Mar 15". */
@Composable
private fun Fact(label: String, value: String) {
    val c = TrackchingTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
