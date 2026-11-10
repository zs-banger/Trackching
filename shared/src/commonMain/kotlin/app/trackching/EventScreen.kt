package app.trackching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * S10 Event: name and dates, the Flow summary of its tagged transactions,
 * then spending by category as ranked rows and a donut. No transaction list here.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun EventScreen(
    ledger: Ledger,
    eventId: Long,
    backTo: String,
    onBack: () -> Unit,
    onEdit: (EventRow) -> Unit,
    onOpenTransactions: (Bucket, TypeTab) -> Unit,
    onLog: () -> Unit,
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)

    val loadedEvent by remember(ledger, eventId) { ledger.event(eventId) }.collectAsState(initial = null)
    val loadedRows by remember(ledger) { ledger.history() }.collectAsState(initial = null)
    val loadedIds by remember(ledger, eventId) { ledger.eventTxnIds(eventId) }.collectAsState(initial = null)
    val loadedSpending by remember(ledger, eventId) { ledger.eventSpending(eventId) }.collectAsState(initial = null)
    val event = loadedEvent?.let(::eventRowOf) ?: return // loading, or just deleted
    val rows = loadedRows ?: return
    val ids = loadedIds ?: return
    val spending = loadedSpending ?: return

    val eventRows = remember(rows, ids) { rowsOfEvent(rows, ids.toSet()) }
    val flow = remember(eventRows) { flowOf(eventRows) }
    val bucket = remember(event, today) { eventBucket(event, today, zone) }
    val shares = remember(spending) { spending.map { Share(it.name, it.total, it.color) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackLink(backTo, onBack, Modifier.weight(1f))
            TextButton(onClick = { onEdit(event) }) {
                Text("✎ EDIT", color = c.orangeInk, fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(event.name)
            Text(rangeLabel(event.start, event.end), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        FlowCard(
            flow,
            running = bucket.running,
            onPickTab = { tab -> onOpenTransactions(bucket, tab) },
            onOpenAll = { onOpenTransactions(bucket, TypeTab.All) },
        )

        if (shares.none { it.centavos > 0 }) {
            EmptyState("Nothing spent on this event yet", "Log a transaction", onLog)
        } else {
            ChartCard("Spending", "by category") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RankedBars(shares)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Donut(shares) }
                }
            }
        }
        Box(Modifier.padding(bottom = 80.dp)) // room for the + button
    }
}
