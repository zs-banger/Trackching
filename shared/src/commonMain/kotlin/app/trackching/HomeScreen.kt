package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import app.trackching.db.Balances
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** Home: balance and accounts, active events, top categories, today. Each section links to its tab. */
@OptIn(ExperimentalTime::class)
@Composable
fun HomeScreen(ledger: Ledger, onOpen: (Tab) -> Unit) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val (monthFrom, monthUntil) = remember(today) { monthRange(today, zone) }

    val balances by remember(ledger) { ledger.balances() }.collectAsState(initial = emptyList())
    val events by remember(ledger) { ledger.eventsWithTotals() }.collectAsState(initial = emptyList())
    val months by remember(ledger, monthFrom) { ledger.monthTotals("EXPENSE", monthFrom, monthUntil) }
        .collectAsState(initial = emptyList())
    val history by remember(ledger) { ledger.history() }.collectAsState(initial = emptyList())

    val active = remember(events, today) { splitEvents(events, today).getValue(EventStatus.Active) }
    val top = remember(months) { topCategories(groupCategories(months)) }
    val todays = remember(history, today) { todayRows(history, today, zone) }

    var addingAccount by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf<Balances?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 1. BALANCE + accounts
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Tag("Balance") }
                TextButton(onClick = { managing = true }) {
                    Text("MANAGE", color = c.orangeInk, fontWeight = FontWeight.Bold)
                }
            }
            val total = totalBalance(balances)
            Amount(total, if (total < 0) AmountKind.Expense else AmountKind.Transfer, maxFontSize = 36.sp)
            balances.forEach { account ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { adjusting = account },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(Color(account.color)))
                    Text(account.name, Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Amount(
                        account.balance,
                        if (account.balance < 0) AmountKind.Expense else AmountKind.Transfer,
                        maxFontSize = 20.sp,
                    )
                }
            }
            OffsetBlock(Modifier.fillMaxWidth().clickable(role = Role.Button) { addingAccount = true }) {
                Text("+ ADD ACCOUNT", Modifier.padding(12.dp), color = c.onOrange, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // 2. EVENTS
        Section("Events", onOpen = { onOpen(Tab.Events) }) {
            if (active.isEmpty()) {
                Quiet("No active event")
            }
            active.forEach { event ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.name, Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(shown(formatAmount(event.spent, AmountKind.Transfer)), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // 3. TOP CATEGORIES (bars relative to the biggest)
        Section("Top categories", onOpen = { onOpen(Tab.Categories) }) {
            if (top.isEmpty()) {
                Quiet("No spending this month yet")
            }
            val biggest = top.firstOrNull()?.total ?: 0L
            top.forEach { group ->
                Bar(
                    fraction = if (biggest == 0L) 0f else group.total.toFloat() / biggest,
                    label = "${group.main.icon} ${group.main.name}",
                ) {
                    Text(
                        shown(formatAmount(group.total, AmountKind.Transfer)),
                        Modifier.padding(horizontal = 10.dp),
                        color = c.ink,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }

        // 4. TODAY
        Section("Today", onOpen = { onOpen(Tab.Transactions) }) {
            if (todays.isEmpty()) {
                Quiet("Nothing logged today")
            }
            todays.forEach { row ->
                TxnRow(row, onClick = { onOpen(Tab.Transactions) })
            }
        }
    }

    if (addingAccount) AccountSheet(ledger, onDismiss = { addingAccount = false })
    if (managing) ManageAccountsSheet(ledger, onDismiss = { managing = false })
    adjusting?.let { account ->
        AdjustSheet(ledger, account.id, account.name, account.balance, onDismiss = { adjusting = null })
    }
}

/** A Home section: a tappable header ("EVENTS  SEE ALL ›") over its content. */
@Composable
private fun Section(title: String, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = TrackchingTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Tag(title) }
            Text("SEE ALL ›", color = c.orangeInk, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** A short grey line for an empty section. */
@Composable
private fun Quiet(text: String) {
    Text(text, color = TrackchingTheme.colors.ink2, fontSize = 15.sp)
}