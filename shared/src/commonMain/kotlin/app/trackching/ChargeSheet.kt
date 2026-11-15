package app.trackching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import app.trackching.db.CreditAccounts
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * P3-14 Add charge: interest or a fee from the card's statement.
 * Saved as spending under Fees & Charges, so it adds to what's owed on [card].
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ChargeSheet(ledger: Ledger, card: CreditAccounts, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    var charge by remember { mutableStateOf(ChargeKind.Interest) }
    var typed by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val centavos = parseCentavos(typed) ?: 0L

    TrackchingSheet(title = "Add charge", onDismiss = onDismiss) { close ->
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChargeKind.entries.forEach { k -> Chip(k.label, selected = charge == k, onClick = { charge = k }) }
            }
            Text("AMOUNT FROM YOUR STATEMENT", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Amount(centavos, AmountKind.Expense, maxFontSize = 40.sp, alwaysShow = true)
            Text(chargeNote(card.name), color = c.ink2, fontSize = 13.sp)
        }
        Keypad(
            onKey = { typed = press(typed, it) },
            saveEnabled = centavos > 0 && !saved,
            onSave = {
                if (saved || centavos <= 0) return@Keypad
                saved = true
                ledger.addCharge(card.id, charge, centavos, Clock.System.now().toEpochMilliseconds())
                close()
            },
        )
    }
}
