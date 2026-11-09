package app.trackching

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import androidx.compose.foundation.clickable

@OptIn(ExperimentalFoundationApi::class, ExperimentalTime::class)
@Composable
fun TransactionsScreen(ledger: Ledger, onOpen: (txnId: Long) -> Unit, onLog: () -> Unit) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger) { ledger.history() }.collectAsState(initial = null)
    val rows = loaded ?: return // still loading: draw nothing for that split second
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val days = remember(rows) { groupByDay(rows, zone) }

    if (days.isEmpty()) {
        EmptyScreen("No transactions yet. Log what you spent or earned today.", "Log a transaction", onLog)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp), // room for the + button
    ) {
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