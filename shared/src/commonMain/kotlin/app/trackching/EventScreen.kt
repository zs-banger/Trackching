package app.trackching

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
 * A repeating event (P3-4) shows one period at a time, with ‹ › and a "Past N" chart.
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
    onNewDates: () -> Unit = {},
    onChangeDates: (EventRun) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)

    val loadedEvent by remember(ledger, eventId) { ledger.event(eventId) }.collectAsState(initial = null)
    val loadedRows by remember(ledger) { ledger.history() }.collectAsState(initial = null)
    val loadedIds by remember(ledger, eventId) { ledger.eventTxnIds(eventId) }.collectAsState(initial = null)
    val event = loadedEvent?.let(::eventRowOf) ?: return // loading, or just deleted
    val rows = loadedRows ?: return
    val ids = loadedIds ?: return
    val eventRows = remember(rows, ids) { rowsOfEvent(rows, ids.toSet()) }
    val loadedRuns by remember(ledger, eventId) { ledger.runs(eventId) }.collectAsState(initial = null)
    val runs = loadedRuns ?: return
    // An occasional event shows one run: the one picked on the History card (P3-20),
    // else the active one, else the last finished, else the first upcoming (P3-19).
    var pickedRun by rememberSaveable(eventId) { mutableStateOf<Long?>(null) }
    val run = remember(runs, today, pickedRun) {
        if (!event.occasional) null else runs.firstOrNull { it.id == pickedRun } ?: runToShow(runs, today)
    }

    // A repeating event shows one period at a time; a one-off event shows all its dates.
    val weekStart = remember(ledger) { ledger.weekStart() }
    val periods = remember(event, weekStart) { event.repeat?.let { EventPeriods(event.start, it, weekStart) } }
    val current = remember(periods, today) { periods?.let { currentIndex(it, event.end, today) } }
    var picked by rememberSaveable(eventId) { mutableStateOf<Int?>(null) }
    val index = picked ?: current
    val bucket = remember(event, periods, index, run, today) {
        when {
            run != null -> eventBucket(event.copy(start = run.first, end = run.last), today, zone)
            periods != null && index != null -> periodBucket(periods.period(index), today, zone)
            else -> eventBucket(event, today, zone)
        }
    }

    val byDates = periods != null || run != null // only part of the event's spending
    val loadedSpending by remember(ledger, eventId, bucket) {
        if (!byDates) ledger.eventSpending(eventId).map { list -> list.map { Share(it.name, it.total, it.color) } }
        else ledger.eventSpendingBetween(eventId, bucket.from, bucket.until).map { list -> list.map { Share(it.name, it.total, it.color) } }
    }.collectAsState(initial = null)
    val shares = loadedSpending ?: return
    val periodRows = remember(eventRows, bucket, byDates) { if (byDates) rowsIn(bucket, eventRows) else eventRows }
    val flow = remember(periodRows) { flowOf(periodRows) }

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
            Text(
                when {
                    run != null -> "Occasional · ${periodLabel(EventPeriod(run.first, run.last))}"
                    else -> event.repeat?.let { "${repeatLabel(it)} · ${rangeLabel(event.start, event.end)}" } ?: rangeLabel(event.start, event.end)
                },
                color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            if (event.occasional) {
                Row {
                    TextButton(onClick = onNewDates) {
                        Text("+ SET NEW DATES", color = c.orangeInk, fontWeight = FontWeight.Bold)
                    }
                    if (run != null) {
                        TextButton(onClick = { onChangeDates(run) }) {
                            Text("✎ CHANGE THESE DATES", color = c.orangeInk, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (periods != null && index == null) {
            Text("The first period starts ${dateLabel(periods.period(0).first)}.", color = c.ink2)
        }
        if (periods != null && index != null && current != null) {
            // ‹ Nov 3–9 ›  You can go back, but not past the current period.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picked = index - 1 }, enabled = index > 0) { Text("‹", fontSize = 24.sp) }
                Text(
                    periodLabel(periods.period(index)),
                    Modifier.weight(1f),
                    color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                )
                TextButton(onClick = { picked = index + 1 }, enabled = index < current) { Text("›", fontSize = 24.sp) }
            }
        }

        FlowCard(
            flow,
            running = bucket.running,
            onPickTab = { tab -> onOpenTransactions(bucket, tab) },
            onOpenAll = { onOpenTransactions(bucket, TypeTab.All) },
        )

        if (periods != null && index != null) {
            val past = remember(periods, index) { pastPeriods(periods, index) }
            val bars = remember(past, eventRows, today) {
                upDowns(past.map { (_, p) -> periodBucket(p, today, zone) }, eventRows)
            }
            ChartCard("Past ${past.size}", "spending per ${periods.repeat.unit.one}") {
                UpDownChart(
                    bars,
                    selected = past.indexOfFirst { it.first == index },
                    onSelect = { picked = past[it].first },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (shares.none { it.centavos > 0 }) {
            EmptyState(
                when {
                    run != null -> "Nothing spent on these dates"
                    periods != null -> "Nothing spent in this period"
                    else -> "Nothing spent on this event yet"
                },
                "Log a transaction",
                onLog,
            )
        } else {
            ChartCard("Spending", "by category") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RankedBars(shares)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Donut(shares) }
                }
            }
        }
        if (event.occasional && runs.isNotEmpty()) {
            // P3-20: every run with its spending. Tap one to show it above.
            val history = remember(runs, eventRows, today) {
                historyOrder(runs, today).map { r ->
                    r to flowOf(rowsIn(eventBucket(event.copy(start = r.first, end = r.last), today, zone), eventRows)).expenses
                }
            }
            ChartCard("History", plural(runs.size.toLong(), "time")) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    history.forEach { (r, spent) ->
                        val showing = r.id == run?.id
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { pickedRun = r.id }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                runLabel(r, today),
                                Modifier.weight(1f),
                                color = if (showing) c.orangeInk else c.ink,
                                fontSize = 16.sp,
                                fontWeight = if (showing) FontWeight.ExtraBold else FontWeight.SemiBold,
                            )
                            Text(
                                shown(formatAmount(spent, AmountKind.Transfer)),
                                color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(bottom = 80.dp)) // room for the + button
    }
}
