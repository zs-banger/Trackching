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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * P3-18 Borrow: a cash loan from [card], like GLoan or a cash advance.
 * Saved as a transfer from the card into a cash, bank or e-wallet account, plus a plan of payments.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BorrowSheet(ledger: Ledger, card: CreditAccounts, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val form = remember { BorrowForm().apply { into = ledger.cardPayFrom() } }
    val intoAccounts = remember { ledger.accounts().filterNot { ledger.isCredit(it.id) } }
    var pickingFirstDue by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val borrowed = form.borrowed
    val error = form.error()
    val loan = form.loan()
    val canSave = loan != null && !saved
    val hint = when {
        error != null -> error
        borrowed == 0L -> "Type the amount borrowed"
        intoAccounts.isEmpty() -> "Add a cash, bank or e-wallet account first"
        form.into == null -> "Pick where the money went"
        form.plan.payments == null -> "Type each payment"
        form.plan.firstDue == null -> "Pick the first payment's due date"
        else -> ""
    }

    TrackchingSheet(title = "Borrow from ${card.name}", onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.amount,
                onValueChange = { form.amount = it },
                label = { Text("Amount borrowed (₱)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Label("Went into")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                intoAccounts.forEach { a -> Chip(a.name, selected = form.into == a.id, onClick = { form.into = a.id }) }
            }
            OutlinedTextField(
                value = form.plan.name,
                onValueChange = { form.plan.name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text("Cash loan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Label("Payments")
            PaymentsFields(form.plan, LoanCounts, onPickFirst = { pickingFirstDue = true })
            val payments = form.plan.payments
            if (payments != null && borrowed > 0) {
                val hidden = LocalAmountsHidden.current
                Text(cardPlanPreview(payments, borrowed) { masked(it, hidden) }, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(LoanNote, color = c.ink2, fontSize = 13.sp)
            Text(hint, color = if (error != null) c.expense else c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = canSave, role = Role.Button) {
                    val plan = form.loan() ?: return@clickable
                    val into = form.into ?: return@clickable
                    if (saved) return@clickable
                    saved = true
                    ledger.borrow(card.id, form.borrowed, into, plan, Clock.System.now().toEpochMilliseconds())
                    close()
                },
                color = if (canSave) c.orange else c.line,
                edge = if (canSave) c.blue else c.line,
            ) {
                Text(
                    "BORROW ${shown(formatAmount(borrowed, AmountKind.Transfer))}",
                    Modifier.padding(12.dp),
                    color = if (canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        if (pickingFirstDue) {
            PickDate(
                initial = form.plan.firstDue ?: Clock.System.todayIn(TimeZone.currentSystemDefault()),
                onPicked = { day ->
                    form.plan.firstDue = day
                    pickingFirstDue = false
                },
                onDismiss = { pickingFirstDue = false },
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
