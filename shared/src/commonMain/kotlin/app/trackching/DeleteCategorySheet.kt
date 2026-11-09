package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Delete a category; if it's used, pick where its transactions go (that's a merge). */
@Composable
fun DeleteCategorySheet(ledger: Ledger, categoryId: Long, name: String, hasSubs: Boolean, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val usage = remember(categoryId) { ledger.categoryUsage(categoryId) }
    val targets = remember(categoryId) { ledger.moveTargets(categoryId) }
    var moveTo by remember { mutableStateOf<Long?>(null) }
    var done by remember { mutableStateOf(false) }

    val lastOne = targets.isEmpty() && !hasSubs
    val canDelete = !done && !lastOne && (usage == 0L || moveTo != null)
    val label = if (usage == 0L) "DELETE" else "MOVE $usage & DELETE"

    TrackchingSheet(title = "Delete \"$name\"", onDismiss = onDismiss) { close ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasSubs) {
                Text("Its subcategories will be deleted too.", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            when {
                lastOne -> Text("This is your last category of its kind, so it can't be deleted.", color = c.expense, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                usage == 0L -> Text("No transactions use it.", color = c.ink2, fontSize = 15.sp)
                else -> {
                    Text(
                        "$usage ${if (usage == 1L) "transaction uses" else "transactions use"} it. Move them to:",
                        color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    Column(Modifier.selectableGroup()) {
                        targets.forEach { t ->
                            val selected = moveTo == t.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) c.field else Color.Transparent)
                                    .selectable(selected = selected, onClick = { moveTo = t.id }, role = Role.RadioButton)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    if (t.parent_name != null) "${t.parent_name} › ${t.name}" else t.name,
                                    color = c.ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            OffsetBlock(
                Modifier.fillMaxWidth().clickable(enabled = canDelete, role = Role.Button) {
                    if (done) return@clickable
                    done = true
                    ledger.deleteCategory(categoryId, moveTo)
                    close()
                },
                color = if (canDelete) c.expense else c.line,
                edge = if (canDelete) c.blue else c.line,
            ) {
                Text(
                    label,
                    Modifier.padding(12.dp),
                    color = if (canDelete) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}