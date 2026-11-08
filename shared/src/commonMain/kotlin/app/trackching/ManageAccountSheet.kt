package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.trackching.db.ManageList

private val RowHeight = 56.dp

/** Reorder (drag ≡), archive and restore accounts. */
@Composable
fun ManageAccountsSheet(ledger: Ledger, onDismiss: () -> Unit) {
    val c = TrackchingTheme.colors
    val all by remember(ledger) { ledger.manageList() }.collectAsState(initial = emptyList())
    val active = all.filter { it.archived == 0L }
    val archived = all.filter { it.archived != 0L }

    TrackchingSheet(title = "Manage accounts", onDismiss = onDismiss, swipeToClose = false) { _ ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Drag ≡ to reorder.", color = c.ink2, fontSize = 13.sp)
            ReorderableAccounts(
                accounts = active,
                canArchive = active.size > 1,
                onReorder = { ids -> ledger.reorder(ids) },
                onArchive = { id -> ledger.archive(id) },
            )
            if (archived.isNotEmpty()) {
                Text("ARCHIVED", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                archived.forEach { account ->
                    AccountLine(account, Modifier.alpha(0.6f)) {
                        TextButton(onClick = { ledger.restoreAccount(account.id) }) {
                            Text("Restore", color = c.orangeInk, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableAccounts(
    accounts: List<ManageList>,
    canArchive: Boolean,
    onReorder: (List<Long>) -> Unit,
    onArchive: (Long) -> Unit,
) {
    val c = TrackchingTheme.colors
    // A local copy we can shuffle while dragging; it resets whenever the database sends a new list.
    var list by remember(accounts) { mutableStateOf(accounts) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }

    Column {
        list.forEach { account ->
            key(account.id) {
                val dragged = draggingId == account.id
                AccountLine(
                    account,
                    Modifier
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer { translationY = if (dragged) offset else 0f }
                        .background(if (dragged) c.field else Color.Transparent),
                    handle = {
                        Box(
                            Modifier
                                .width(44.dp)
                                .fillMaxHeight()
                                .semantics { contentDescription = "Drag to reorder ${account.name}" }
                                .pointerInput(account.id) {
                                    detectDragGestures(
                                        onDragStart = { draggingId = account.id; offset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            offset += amount.y
                                            val from = list.indexOfFirst { it.id == account.id }
                                            if (offset > rowPx / 2 && from < list.lastIndex) {
                                                list = list.moved(from, from + 1)
                                                offset -= rowPx
                                            } else if (offset < -rowPx / 2 && from > 0) {
                                                list = list.moved(from, from - 1)
                                                offset += rowPx
                                            }
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            offset = 0f
                                            onReorder(list.map { it.id })
                                        },
                                        onDragCancel = { draggingId = null; offset = 0f },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("≡", color = c.ink2, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                ) {
                    if (canArchive) {
                        TextButton(onClick = { onArchive(account.id) }) {
                            Text("Archive", color = c.expense, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountLine(
    account: ManageList,
    modifier: Modifier = Modifier,
    handle: @Composable () -> Unit = {},
    action: @Composable () -> Unit,
) {
    val c = TrackchingTheme.colors
    Row(
        modifier.fillMaxWidth().height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        handle()
        Box(Modifier.size(12.dp).background(Color(account.color)))
        Text(account.name, Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        action()
    }
}