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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.CreditAccounts
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * P3-13 Pay bill: what you're paying for (a plan, or Other charges), which payments,
 * and the account it comes from. [onPaid] gets the toast and what Undo takes back.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun PayBillSheet(
    ledger: Ledger,
    card: CreditAccounts,
    plans: List<CardPlanLine>,
    onDismiss: () -> Unit,
    onPaid: (String, BillPaid) -> Unit,
) {
    val c = TrackchingTheme.colors
    val other = remember { ledger.otherCharges(card.id) }
    // null = Other charges
    var planId by remember { mutableStateOf(plans.firstOrNull()?.planId?.takeIf { other == 0L }) }
    var count by remember { mutableStateOf(1) }
    var typed by remember { mutableStateOf(typedOf(other)) }
    val payFromAccounts = remember { ledger.accounts().filterNot { ledger.isCredit(it.id) } }
    var from by remember { mutableStateOf(ledger.cardPayFrom()) }
    var saved by remember { mutableStateOf(false) }

    val waiting = remember(planId) { planId?.let { ledger.waitingPayments(it) }.orEmpty() }
    val plan = plans.firstOrNull { it.planId == planId }
    val amount = if (plan != null) waiting.take(count).sumOf { it.centavos } else parseCentavos(typed) ?: 0L
    val charges = if (plan != null) waiting.sumOf { it.centavos } else other
    val fromName = payFromAccounts.firstOrNull { it.id == from }?.name
    val own = remember(from) { from?.let { ledger.ownMoneyOf(it) } }
    val error = if (amount > 0) billError(amount, charges, fromName, own) else null
    val canPay = amount > 0 && from != null && error == null && !saved

    TrackchingSheet(title = "Pay bill", onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("What are you paying for?")
            ChipRow {
                plans.forEach { p -> Chip(p.name, selected = planId == p.planId, onClick = { planId = p.planId; count = 1 }) }
                if (other > 0) Chip("Other charges", selected = planId == null, onClick = { planId = null })
            }
            if (plan != null) {
                Label("Which payments?")
                ChipRow {
                    (1..waiting.size).forEach { k ->
                        Chip(if (k == 1) "The next one" else "Next $k", selected = count == k, onClick = { count = k })
                    }
                }
            } else {
                Text("Purchases and fees with no installments: ${shown(formatAmount(other, AmountKind.Transfer))}", color = c.ink2, fontSize = 13.sp)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Amount (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Label("Pay from")
            ChipRow {
                payFromAccounts.forEach { a -> Chip(a.name, selected = from == a.id, onClick = { from = a.id }) }
            }
            Text(error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = canPay, role = Role.Button) {
                    val account = from ?: return@clickable
                    if (saved) return@clickable
                    saved = true
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (plan != null) {
                        val paid = ledger.payPlan(plan.planId, count, account, now)
                        val first = waiting.first().n
                        onPaid(planPaidMessage(plan.name, first, first + count - 1), paid)
                    } else {
                        val paid = ledger.payOtherCharges(card.id, amount, account, now)
                        onPaid("${formatAmount(amount, AmountKind.Transfer)} paid to ${card.name}", paid)
                    }
                    close()
                },
                color = if (canPay) c.orange else c.line,
                edge = if (canPay) c.blue else c.line,
            ) {
                Text(
                    "PAY ${shown(formatAmount(amount, AmountKind.Transfer))}",
                    Modifier.padding(12.dp),
                    color = if (canPay) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
