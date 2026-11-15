package app.trackching

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.History
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@OptIn(ExperimentalFoundationApi::class, ExperimentalTime::class)
@Composable
fun TransactionsScreen(ledger: Ledger, onOpen: (txnId: Long) -> Unit, onLog: () -> Unit,
                       account: Long? = null,
                       onBack: (() -> Unit)? = null,
                       fixed: Bucket? = null,
                       startTab: TypeTab = TypeTab.All,
                       backTo: String = "Accounts",
                       onOpenReports: (() -> Unit)? = null,
                       event: Long? = null,
                       waiting: Int = 0,
                       onCheckIn: () -> Unit = {},) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger) { ledger.history() }.collectAsState(initial = null)
    val allRows = loaded ?: return // still loading: draw nothing for that split second
    val eventIds by remember(ledger, event) { ledger.eventTxnIds(event ?: -1) }.collectAsState(initial = null)
    val rows = when {
        account != null -> historyFor(allRows, account)
        event != null -> rowsOfEvent(allRows, (eventIds ?: return).toSet())
        else -> allRows
    }
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    var period by rememberSaveable { mutableStateOf(Period.ThisMonth) }
    var tab by rememberSaveable { mutableStateOf(startTab) }

    val range = fixed?.let { it.from to it.until } ?: periodRange(period, today, zone)
    val running = fixed?.running ?: period.running
    val inPeriod = remember(rows, range) { inRange(rows, range) }
    val flow = remember(inPeriod) { flowOf(inPeriod) }
    val days = remember(inPeriod, tab) { groupByDay(rowsFor(tab, inPeriod), zone) }

    if (rows.isEmpty()) {
        EmptyScreen("No transactions yet. Log what you spent or earned today.", "Log a transaction", onLog)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp), // room for the + button
    ) {
        if (waiting > 0) item {
            Row(
                Modifier.fillMaxWidth()
                    .background(c.orange)
                    .clickable(onClick = onCheckIn)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val repeats = if (waiting == 1) "1 repeat" else "$waiting repeats"
                Text("$repeats waiting for your OK", Modifier.weight(1f), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("›", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (onBack != null) item { BackLink(backTo, onBack) }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (fixed == null) {
                    PeriodChips(period, onSelect = { period = it })
                } else {
                    Text(fixed.title.uppercase(), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                FlowCard(flow, running, onPickTab = { tab = it })
                if (onOpenReports != null) ReportsCard(onOpenReports)
            }
        }
        item {
            SegmentedTabs(
                options = TypeTab.entries,
                selected = tab,
                onSelect = { tab = it },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (days.isEmpty()) {
            item {
                Text(
                    "Nothing here for ${fixed?.title ?: period.label.lowercase()}.",
                    Modifier.padding(16.dp),
                    color = c.ink2,
                    fontSize = 15.sp,
                )
            }
        }
        days.forEach { day ->
            stickyHeader(key = day.date.toString()) {
                DayHeader(dayLabel(day.date, today), day.net)
            }
            items(day.items, key = { it.id }) { row ->
                TxnRow(row, onClick = { onOpen(row.id) })
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, net: Long) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.band2).padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Tag(label)
        val kind = if (net < 0) AmountKind.Expense else if (net > 0) AmountKind.Income else AmountKind.Transfer
        Text(shown(formatAmount(net, kind)), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun TxnRow(row: History, onClick: () -> Unit) {
    val c = TrackchingTheme.colors
    val adjustment = row.kind == ADJUSTMENT
    val title = when {
        adjustment -> "Balance adjustment"
        else -> row.category_name ?: "Transfer"
    }
    val subtitle = if (row.to_account_name != null) "${row.account_name} → ${row.to_account_name}" else row.account_name
    val amountText = if (adjustment) {
        (if (row.centavos < 0) "− " else "+ ") + formatAmount(row.centavos, AmountKind.Transfer)
    } else {
        formatAmount(row.centavos, kindOf(row.kind))
    }
    val amountColor = if (adjustment) c.ink2 else when (kindOf(row.kind)) {
        AmountKind.Expense -> c.expense
        AmountKind.Income -> c.income
        AmountKind.Transfer -> c.ink2
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !adjustment, onClick = onClick)
            .background(c.band)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(shown(amountText), color = amountColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** "Reports" with its one link for now. Month in review and Pending join it later. */
@Composable
private fun ReportsCard(onOpenIncomeVsExpenses: () -> Unit) {
    val c = TrackchingTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tag("Reports")
        Row(
            Modifier.fillMaxWidth()
                .background(c.band)
                .clickable(onClick = onOpenIncomeVsExpenses)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Income vs Expenses", Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("›", color = c.orangeInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
