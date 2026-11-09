package app.trackching

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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** Events split into Active, Upcoming and Past, each with what was spent. */
@OptIn(ExperimentalTime::class)
@Composable
fun EventsScreen(ledger: Ledger, onNewEvent: () -> Unit) {
    val c = TrackchingTheme.colors
    val loaded by remember(ledger) { ledger.eventsWithTotals() }.collectAsState(initial = null)
    val rows = loaded ?: return // still loading
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val sections = remember(rows, today) { splitEvents(rows, today) }

    if (rows.isEmpty()) {
        EmptyScreen("No events yet. Group spending for a trip, a birthday or a big purchase.", "New event", onNewEvent)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp), // room for the + button
    ) {
        sections.forEach { (status, events) ->
            if (events.isNotEmpty()) {
                item(key = status.name) {
                    Box(Modifier.fillMaxWidth().background(c.band2).padding(start = 12.dp, top = 16.dp, bottom = 6.dp)) {
                        Tag(status.name)
                    }
                }
                items(events, key = { it.id }) { event ->
                    EventLine(event)
                }
            }
        }
    }
}

@Composable
private fun EventLine(event: EventRow) {
    val c = TrackchingTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.band).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(event.name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rangeLabel(event.start, event.end), color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            shown(formatAmount(event.spent, AmountKind.Transfer)),
            color = if (event.spent == 0L) c.ink2 else c.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
