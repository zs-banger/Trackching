package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "New category": kind, name, parent, color, icon. */
@Composable
fun CategorySheet(ledger: Ledger, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val form = remember { CategoryForm(takenNames = ledger::categoryNames) }
    val mains = remember(form.kind) { ledger.mainCategories(form.kind) }
    var saved by remember { mutableStateOf(false) }

    TrackchingSheet(title = "New category", onDismiss = onDismiss) { close ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Expense", selected = form.kind == "EXPENSE", onClick = { form.pickKind("EXPENSE") })
                Chip("Income", selected = form.kind == "INCOME", onClick = { form.pickKind("INCOME") })
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Health") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Under")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("None (main)", selected = form.parentId == null, onClick = { form.parentId = null })
                mains.forEach { main ->
                    Chip(
                        main.name,
                        selected = form.parentId == main.id,
                        onClick = {
                            form.parentId = main.id
                            form.color = main.color // a subcategory starts with its main's color
                        },
                        dot = Color(main.color),
                    )
                }
            }

            Label("Color")
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryColors.forEach { color ->
                    val selected = form.color == color
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .drawBehind { if (selected) drawRect(c.ink) }
                            .padding(if (selected) 3.dp else 0.dp)
                            .background(Color(color))
                            .selectable(selected = selected, onClick = { form.color = color }, role = Role.RadioButton),
                    )
                }
            }

            Label("Icon")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CategoryIcons.forEach { icon ->
                    val selected = form.icon == icon
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(if (selected) c.field else Color.Transparent)
                            .drawBehind { if (selected) drawRect(c.ink, style = Stroke(4f)) }
                            .selectable(selected = selected, onClick = { form.icon = icon }, role = Role.RadioButton),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, fontSize = 22.sp)
                    }
                }
            }

            Text(form.error ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            OffsetBlock(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = form.canSave && !saved, role = Role.Button) {
                        if (saved) return@clickable
                        saved = true
                        ledger.addCategory(form.name, form.kind, form.parentId, form.color, form.icon)
                        close()
                    },
                color = if (form.canSave) c.orange else c.line,
                edge = if (form.canSave) c.blue else c.line,
            ) {
                Text(
                    "SAVE CATEGORY",
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
private fun Label(text: String) {
    Text(text.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
