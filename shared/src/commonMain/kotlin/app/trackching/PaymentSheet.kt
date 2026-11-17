package app.trackching

import androidx.compose.foundation.background
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * S17: Record payment (I owe), Payment received (owed to me) or Hand back (holding).
 * One amount and one account; the IOU and the account both update. [onSaved] gets the payment's id, for Undo.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun PaymentSheet(ledger: Ledger, iouId: Long, onDismiss: () -> Unit, onSaved: (paymentId: Long) -> Unit) {
    val c = TrackchingTheme.colors
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val form = remember {
        PaymentForm(ledger.paymentContext(iouId), today, ledger.lastAccountUsed(), ledger.ownMoney())
    }
    val context = form.context
    val accounts = remember { ledger.accounts() }
    var picking by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    TrackchingSheet(title = context.kind.actionLabel(), onDismiss = onDismiss) { close ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${context.kind.withPerson()} ${context.person} · ₱ ${formatCentavos(context.left)} left",
                color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = form.amount,
                onValueChange = { form.amount = it },
                label = { Text("Amount (₱)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(
                    "All of it · ₱ ${formatCentavos(context.left)}",
                    selected = form.centavos == context.left,
                    onClick = { form.amount = typedOf(context.left); form.interestText = "" },
                )
            }

            // With interest: the split, interest first unless you change it.
            if (context.interestDue > 0) {
                val split = form.split
                Text(
                    split?.let { "₱ ${formatCentavos(it.principal)} principal · ₱ ${formatCentavos(it.interest)} interest" }
                        ?: "Interest is paid first",
                    color = c.ink2, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = form.interestText,
                    onValueChange = { form.interestText = it },
                    label = { Text("Interest part (₱)") },
                    placeholder = { Text(split?.let { typedOf(it.interest) } ?: "Interest first") },
                    supportingText = { Text("₱ ${formatCentavos(context.interestDue)} of interest is due. Leave blank to pay interest first.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // P3-16: more than what's left. Holding never gets here (it's an error instead).
            if (form.extra > 0) {
                Column(
                    Modifier.fillMaxWidth().background(c.band).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(form.extraQuestion, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (context.kind == IouKind.OWED) "Yes saves it as income under Interest › From IOUs."
                        else "Yes saves it under Fees & Charges › Interest.",
                        color = c.ink2, fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Yes, it's interest", selected = form.countExtra == true, onClick = { form.countExtra = true })
                        Chip("No, pay what's left", selected = false, onClick = { form.capAtLeft() })
                    }
                }
            }

            Text(context.kind.paymentAccountLabel().uppercase(), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a -> Chip(a.name, selected = form.accountId == a.id, onClick = { form.accountId = a.id }) }
            }

            Text("DATE", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Chip(dateLabel(form.date), selected = true, onClick = { picking = true })

            Text(form.error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = form.canSave && !saved, role = Role.Button) {
                    val total = form.onIou ?: return@clickable
                    val split = form.split ?: return@clickable
                    val account = form.accountId ?: return@clickable
                    if (saved) return@clickable
                    saved = true
                    val at = atTimeOfDay(form.date, Clock.System.now().toEpochMilliseconds())
                    val extra = if (form.countExtra == true) form.extra else 0
                    onSaved(ledger.recordPayment(iouId, total, split.interest, account, at, extraAsInterest = extra))
                    close()
                },
                color = if (form.canSave) c.orange else c.line,
                edge = if (form.canSave) c.blue else c.line,
            ) {
                Text(
                    "SAVE",
                    Modifier.padding(12.dp),
                    color = if (form.canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }

    if (picking) {
        PickDate(
            initial = form.date,
            onPicked = { form.date = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}
