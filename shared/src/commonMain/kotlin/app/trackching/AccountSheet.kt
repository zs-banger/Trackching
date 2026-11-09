package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** "New account": name, type, color, starting balance. */
@Composable
fun AccountSheet(ledger: Ledger, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val form = remember { AccountForm(ledger.accountNames(), ledger.archivedAccountNames()) }
    var saved by remember { mutableStateOf(false) }

    TrackchingSheet(title = "New account", onDismiss = onDismiss) { close ->
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountFields(form)

            OffsetBlock(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = form.canSave && !saved, role = Role.Button, onClick = {
                        if (saved) return@clickable
                        saved = true
                        val type = form.type
                        val starting = form.startingCentavos
                        if (type != null && starting != null) {
                            ledger.addAccount(form.name, type, form.color, starting)
                            close()
                        }
                    }),
                color = if (form.canSave) c.orange else c.line,
                edge = if (form.canSave) c.blue else c.line,
            ) {
                Text(
                    "SAVE ACCOUNT",
                    Modifier.padding(12.dp),
                    color = if (form.canSave) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
fun AccountFields(form: AccountForm) {
    val c = TrackchingTheme.colors
    OutlinedTextField(
        value = form.name,
        onValueChange = { form.name = it },
        label = { Text("Name") },
        placeholder = { Text("e.g. GCash") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )

    Label("Type")
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AccountType.entries.forEach { type ->
            Chip(type.label, selected = form.type == type, onClick = { form.type = type })
        }
    }

    Label("Color")
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountColors.forEach { color ->
            val selected = form.color == color
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .drawBehind {
                        if (selected) drawRect(c.ink) // a frame around the picked color
                    }
                    .padding(if (selected) 3.dp else 0.dp)
                    .background(Color(color))
                    .selectable(selected = selected, onClick = { form.color = color }, role = Role.RadioButton),
            )
        }
    }

    OutlinedTextField(
        value = form.starting,
        onValueChange = { form.starting = it },
        label = { Text("Starting balance (₱)") },
        supportingText = { Text("What's in it today. This isn't counted as income.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    Text(form.error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}