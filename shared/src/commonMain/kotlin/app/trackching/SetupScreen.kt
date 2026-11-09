package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First launch only: 1 your first account, 2 starter categories, 3 done. */
@Composable
fun SetupScreen(ledger: Ledger, onDone: () -> Unit) {
    val c = TrackchingTheme.colors
    val form = remember { SetupForm() }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.band2)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("STEP ${form.step} OF 3", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            when (form.step) {
                1 -> "Where's your money?"
                2 -> "Pick starter categories"
                else -> "You're all set"
            },
            color = c.ink,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )

        // The step's content scrolls; the buttons stay at the bottom.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (form.step) {
                1 -> {
                    Text("Start with one account. You can add GCash, banks and more later.", color = c.ink2)
                    AccountFields(form.account)
                }
                2 -> {
                    Text("Untick anything you won't use. You can add your own later on the Categories tab.", color = c.ink2)
                    StarterList("Expenses", "EXPENSE", form)
                    StarterList("Income", "INCOME", form)
                    Text(form.categoryError ?: "", color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    val count = form.pickedCategories.sumOf { 1 + it.subs.size }
                    val starting = formatCentavos(form.account.startingCentavos ?: 0)
                    Text("${form.account.name.trim()}, starting at ₱ $starting", color = c.ink, fontSize = 18.sp)
                    Text("$count categories ready to use", color = c.ink, fontSize = 18.sp)
                    Text("Tap + on Home to log your first expense.", color = c.ink2)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (form.step > 1) {
                TextButton(onClick = { form.back() }) {
                    Text("BACK", color = c.ink2, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            OffsetBlock(
                Modifier.clickable(enabled = form.canContinue && !saved, role = Role.Button) {
                    if (form.step < 3) {
                        form.next()
                        return@clickable
                    }
                    if (saved) return@clickable
                    saved = true
                    val type = form.account.type
                    val starting = form.account.startingCentavos
                    if (type != null && starting != null) {
                        ledger.finishSetup(form.account.name, type, form.account.color, starting, form.pickedCategories)
                        onDone()
                    }
                },
                color = if (form.canContinue) c.orange else c.line,
                edge = if (form.canContinue) c.blue else c.line,
            ) {
                Text(
                    if (form.step < 3) "NEXT" else "START TRACKING",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = if (form.canContinue) c.onOrange else c.ink2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/** One kind's starter categories, each a full-width row you tap to tick or untick. */
@Composable
private fun StarterList(title: String, kind: String, form: SetupForm) {
    val c = TrackchingTheme.colors
    Column {
        Text(title.uppercase(), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        StarterCategories.filter { it.kind == kind }.forEach { category ->
            val ticked = category in form.picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { form.toggle(category) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(checked = ticked, onCheckedChange = null) // the row handles the tap
                Box(Modifier.size(12.dp).background(Color(category.color)))
                Column {
                    Text(category.name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (category.subs.isNotEmpty()) {
                        Text(category.subs.joinToString { it.first }, color = c.ink2, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
