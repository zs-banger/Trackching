package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.MonthTotals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import androidx.compose.foundation.clickable

/** This month's categories: each main with its total, subs indented under it. */
@OptIn(ExperimentalTime::class)
@Composable
fun CategoriesScreen(ledger: Ledger) {
    val c = TrackchingTheme.colors
    var kind by rememberSaveable { mutableStateOf("EXPENSE") }
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val (from, until) = remember(today) { monthRange(today, zone) }
    val rows by remember(ledger, kind, from) { ledger.monthTotals(kind, from, until) }
        .collectAsState(initial = emptyList())
    val groups = remember(rows) { groupCategories(rows) }
    var deleting by remember { mutableStateOf<CategoryGroup?>(null) }
    var deletingSub by remember { mutableStateOf<MonthTotals?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp), // room for the + button
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Tag("${today.month.name} ${today.year}") }
            }
            SegmentedTabs(
                options = listOf("EXPENSE", "INCOME"),
                selected = kind,
                onSelect = { kind = it },
                label = { it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(groups, key = { it.main.id }) { group ->
            Box(Modifier.fillMaxWidth().background(c.band2)) {
                CategoryLine(group.main, group.total, onClick = { deleting = group }, bold = true, size = 17.sp)
            }
            // Money logged on a main *before* it got subcategories: show it, so the subs add up.
            if (group.subs.isNotEmpty() && group.main.total != 0L) {
                CategoryLine(group.main, group.main.total, onClick = { deleting = group }, indent = true, nameOverride = "${group.main.name} (directly)")
            }
            group.subs.forEach { sub ->
                CategoryLine(sub, sub.total, onClick = { deletingSub = sub }, indent = true)
            }
        }
    }

    deleting?.let { g ->
        DeleteCategorySheet(ledger, g.main.id, g.main.name, hasSubs = g.subs.isNotEmpty(), onDismiss = { deleting = null })
    }
    deletingSub?.let { s ->
        DeleteCategorySheet(ledger, s.id, s.name, hasSubs = false, onDismiss = { deletingSub = null })
    }
}

@Composable
private fun CategoryLine(
    row: MonthTotals,
    total: Long,
    onClick: () -> Unit,
    bold: Boolean = false,
    indent: Boolean = false,
    size: TextUnit = 15.sp,
    nameOverride: String? = null,
) {
    val c = TrackchingTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 40.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp).background(Color(row.color)))
        Text(row.icon, fontSize = size)
        Text(
            nameOverride ?: row.name,
            Modifier.weight(1f),
            color = c.ink,
            fontSize = size,
            fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatAmount(total, AmountKind.Transfer), // no sign: the tab already says Expense or Income
            color = if (total == 0L) c.ink2 else c.ink,
            fontSize = size,
            fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold,
        )
    }
}