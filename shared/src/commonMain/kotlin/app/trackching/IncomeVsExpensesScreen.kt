package app.trackching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Income vs Expenses: income up, spending down and Net as a line, by month, quarter or year.
 * Tapping a bar shows that period's Flow summary. Its tiles open Transactions for that period.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun IncomeVsExpensesScreen(
    ledger: Ledger,
    onBack: () -> Unit,
    onOpenPeriod: (Bucket, TypeTab) -> Unit,
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val loaded by remember(ledger) { ledger.history() }.collectAsState(initial = null)
    val rows = loaded ?: return

    var grouping by rememberSaveable { mutableStateOf(Grouping.Monthly) }
    val buckets = remember(grouping, today, rows) {
        bucketsFor(grouping, today, zone, firstYearOf(rows, zone) ?: today.year)
    }
    val bars = remember(buckets, rows) { upDowns(buckets, rows) }
    // Starts on the running period, and again whenever the grouping changes.
    var selected by rememberSaveable(grouping) { mutableStateOf(buckets.indexOfFirst { it.running }) }
    val bucket = buckets.getOrNull(selected)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackLink("Transactions", onBack)
        Tag("Income", second = "vs expenses")
        SegmentedTabs(
            options = Grouping.entries,
            selected = grouping,
            onSelect = { grouping = it },
            label = { it.label },
        )
        ChartCard(
            "Flow", "by ${grouping.unit}",
            legend = listOf(
                LegendItem(c.income, "Income"),
                LegendItem(c.expense, "Expenses"),
                LegendItem(c.orange, "Net"),
            ),
            note = if (buckets.any { it.running }) "* So far, still running" else null,
        ) {
            UpDownChart(bars, selected = selected, onSelect = { selected = it })
        }
        if (bucket != null) {
            Text(bucket.title.uppercase(), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowCard(
                flowOf(rowsIn(bucket, rows)),
                running = bucket.running,
                onPickTab = { tab -> onOpenPeriod(bucket, tab) },
            )
        }
        Box(Modifier.padding(bottom = 80.dp)) // room for the + button
    }
}
