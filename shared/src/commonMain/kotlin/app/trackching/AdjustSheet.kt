package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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

/** "Adjust balance": type what's really there, save the difference. */
@Composable
fun AdjustSheet(ledger: Ledger, accountId: Long, name: String, current: Long, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val real = parseCentavos(text)
    val diff = real?.let { it - current }
    val canSave = diff != null && diff != 0L && !saved

    TrackchingSheet(title = "Adjust $name", onDismiss = onDismiss) { close ->
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("The app says ₱ ${formatCentavos(current)}", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Real balance (₱)") },
                supportingText = { Text("Count what's really there. The difference isn't income or spending.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    text.isBlank() -> ""
                    diff == null -> "Enter an amount like 1250.50"
                    diff == 0L -> "Already matches. Nothing to adjust."
                    diff > 0 -> "Adds + ₱ ${formatCentavos(diff)}"
                    else -> "Removes − ₱ ${formatCentavos(diff)}"
                },
                color = if (diff == null && text.isNotBlank()) c.expense else c.ink2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = canSave, role = Role.Button) {
                    if (saved || real == null) return@clickable
                    saved = true
                    ledger.adjust(accountId, real, Clock.System.now().toEpochMilliseconds())
                    close()
                },
                color = if (canSave) c.orange else c.line,
                edge = if (canSave) c.blue else c.line,
            ) {
                Text(
                    "SET BALANCE",
                    Modifier.padding(12.dp),
                    color = if (canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}