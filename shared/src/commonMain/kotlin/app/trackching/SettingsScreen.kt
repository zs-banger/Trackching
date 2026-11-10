package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DayOfWeek

const val PublicCodeUrl = "https://github.com/zs-banger/Trackching"

@Composable
fun SettingsScreen(
    ledger: Ledger,
    version: String,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onBack: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageCategories: () -> Unit,
    onErased: () -> Unit,
) {
    val c = TrackchingTheme.colors
    val uri = LocalUriHandler.current
    var weekStart by remember(ledger) { mutableStateOf(ledger.weekStart()) }
    var confirming by remember { mutableStateOf(false) }
    val accounts = remember(ledger) { ledger.accountCount() }
    val categories = remember(ledger) { ledger.categoryCount() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackLink("Home", onBack)
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)

        Group("Look")
        Text("Theme", color = c.ink2)
        SegmentedTabs(
            options = ThemeChoice.entries,
            selected = theme,
            onSelect = onTheme,
            label = { it.label },
        )
        Text("Week starts on", color = c.ink2)
        SegmentedTabs(
            options = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
            selected = weekStart,
            onSelect = {
                weekStart = it
                ledger.setWeekStart(it)
            },
            label = { if (it == DayOfWeek.SUNDAY) "Sunday" else "Monday" },
        )

        Group("Manage")
        LinkRow("Accounts", plural(accounts, "account"), onManageAccounts)
        LinkRow("Categories", plural(categories, "category", "categories"), onManageCategories)

        Group("About")
        Text("Your data never leaves this phone.", color = c.ink)
        Text("Version $version", color = c.ink2)
        LinkRow("Public code", "GitHub ›") { uri.openUri(PublicCodeUrl) }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { confirming = true }, Modifier.fillMaxWidth()) {
            Text("Erase all data", color = c.alert, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase everything on this phone?") },
            text = { Text("Accounts, transactions, events, categories and settings are deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    ledger.eraseAll()
                    onErased()
                }) { Text("Erase", color = c.alert, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(top = 16.dp),
        color = TrackchingTheme.colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = c.ink, fontWeight = FontWeight.Bold)
        Text(value, color = c.ink2)
    }
}

/** "1 account", "4 accounts". */
fun plural(n: Long, one: String, many: String = one + "s"): String = "$n ${if (n == 1L) one else many}"
