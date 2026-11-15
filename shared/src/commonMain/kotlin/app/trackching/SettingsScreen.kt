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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

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
    onRestored: () -> Unit,
) {
    val c = TrackchingTheme.colors
    val uri = LocalUriHandler.current
    var weekStart by remember(ledger) { mutableStateOf(ledger.weekStart()) }
    var confirming by remember { mutableStateOf(false) }
    val accounts = remember(ledger) { ledger.accountCount() }
    val categories = remember(ledger) { ledger.categoryCount() }
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(zone)
    var last by remember(ledger) { mutableStateOf(ledger.lastBackup()) }
    var message by remember { mutableStateOf<String?>(null) }      // a refused file, or a failed save
    var toRestore by remember { mutableStateOf<BackupFile?>(null) } // a good file, waiting for "Restore"
    var eraseAfter by remember { mutableStateOf(false) }           // "Back up first" was tapped

    val saveBackup = rememberSaveFile("application/json") { saved ->
        if (saved) {
            ledger.markBackedUp(today)
            last = today
        } else if (eraseAfter) {
            message = "The backup wasn't saved, so nothing was erased."
        }
        if (saved && eraseAfter) confirming = true // back to the erase question, now with a backup
        eraseAfter = false
    }
    fun backUp() = saveBackup(backupFileName(today), encodeBackup(ledger.backupFile(today)))

    val saveCsv = rememberSaveFile("text/csv") { saved ->
        if (!saved) message = "The CSV wasn't saved."
    }

    val openFile = rememberOpenFile { text ->
        when (val result = if (text == null) Opened.Refused(DamagedFile) else openBackup(text)) {
            is Opened.Ok -> toRestore = result.file
            is Opened.Refused -> message = result.why
        }
    }

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

        Group("Backup")
        LinkRow("Back up now", "Save ›") { backUp() }
        val lastInfo = lastBackupOf(last, today)
        Text(
            "Last backup: ${lastInfo.text}",
            color = if (lastInfo.overdue) c.orangeInk else c.ink2,
            fontWeight = if (lastInfo.overdue) FontWeight.Bold else FontWeight.Normal,
        )
        LinkRow("Restore", "Pick a file ›") { openFile() }
        LinkRow("Export CSV", "Save ›") {
            saveCsv("trackching-transactions-$today.csv", ledger.csv(zone))
        }

        if (remindersSupported) {
            Group("Reminders · Android only")
            Reminders(ledger, onRefused = { message = NotificationsOff })
        }

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
                Row {
                    TextButton(onClick = {
                        confirming = false
                        eraseAfter = true
                        backUp()
                    }) { Text("Back up first") }
                    TextButton(onClick = { confirming = false }) { Text("Cancel") }
                }
            },
        )
    }

    toRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { toRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Made on ${file.createdOn}. It has ${plural(file.accounts.size.toLong(), "account")} " +
                        "and ${plural(file.txns.size.toLong(), "transaction")}.\n\n" +
                        "This replaces everything on this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toRestore = null
                    try {
                        ledger.restore(file)
                        onRestored()
                    } catch (e: Exception) {
                        message = DamagedFile
                    }
                }) { Text("Restore", color = c.alert, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { toRestore = null }) { Text("Cancel") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

const val NotificationsOff =
    "Reminders need notifications. You can allow them for Trackching in your phone's settings."

/** P3-11: the three switches. Turning one on asks for notification permission the first time. */
@Composable
private fun Reminders(ledger: Ledger, onRefused: () -> Unit) {
    val control = rememberReminderControl()
    val on = remember(ledger) { mutableStateMapOf<ReminderSwitch, Boolean>().apply { ReminderSwitch.entries.forEach { put(it, ledger.reminderOn(it)) } } }
    ReminderSwitch.entries.forEach { switch ->
        SwitchRow(switch.title, switch.detail, on[switch] == true) { want ->
            if (!want) {
                on[switch] = false
                ledger.setReminder(switch, false)
                control.schedule(ledger.anyReminderOn())
            } else {
                control.ask { allowed ->
                    if (allowed) {
                        on[switch] = true
                        ledger.setReminder(switch, true)
                        control.schedule(true)
                    } else {
                        onRefused() // the switch stays off
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = c.ink, fontWeight = FontWeight.Bold)
            Text(detail, color = c.ink2, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
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
