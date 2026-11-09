package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.MostUsed
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import app.trackching.db.Active
import androidx.compose.material3.TextButton
import app.trackching.db.Txn
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/** "New transaction": Expense, Income or Transfer. */
@Composable
fun TxnSheet(
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSaved: (txnId: Long) -> Unit,
    editing: Txn? = null,
    onDeleted: (Txn) -> Unit = {},
) {
    val c = TrackchingTheme.colors
    val form = remember { editing?.let { TxnForm.from(it) } ?: TxnForm(ledger.lastAccountUsed()) }
    var saved by remember { mutableStateOf(false) }
    val accounts = remember { ledger.accountsFor(editing) }
    // When editing, show every category so the current one is visible.
    var showAll by remember { mutableStateOf(editing != null) }
    val categoryKind = if (form.kind == AmountKind.Income) "INCOME" else "EXPENSE"
    val categories = remember(showAll, categoryKind) {
        ledger.quickCategories(categoryKind, if (showAll) 100L else 8L)
    }

    val hint = when {
        form.centavos == 0L -> "Type the amount"
        form.kind == AmountKind.Transfer && accounts.size < 2 -> "Add a second account to transfer"
        form.kind == AmountKind.Transfer && form.toAccountId == null -> "Pick where it goes"
        form.kind != AmountKind.Transfer && form.categoryId == null -> "Pick a category"
        else -> ""
    }

    TrackchingSheet(
        title = if (editing != null) "Edit transaction" else "New transaction",
        onDismiss = onDismiss,
        action = { close ->
            if (editing != null) {
                TextButton(onClick = {
                    ledger.delete(editing.id)?.let(onDeleted)
                    close()
                }) {
                    Text("Delete", color = c.expense, fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { close ->
        SegmentedTabs(
            options = AmountKind.entries,
            selected = form.kind,
            onSelect = { form.selectKind(it); showAll = false },
            label = { it.name },
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
        )

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Amount(form.centavos, form.kind, maxFontSize = 40.sp)
            Text(hint, color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form.kind != AmountKind.Transfer) {
                Field("Category") {
                    CategoryGrid(
                        categories = categories,
                        selected = form.categoryId,
                        showMore = !showAll,
                        onPick = { form.categoryId = it },
                        onMore = { showAll = true },
                    )
                }
            }
            val fromLabel = when (form.kind) {
                AmountKind.Expense -> "Paid from"
                AmountKind.Income -> "Received into"
                AmountKind.Transfer -> "From"
            }
            Field(fromLabel) {
                AccountChips(accounts, selected = form.accountId, onPick = { form.pickFrom(it) })
            }
            if (form.kind == AmountKind.Transfer) {
                Field("To") {
                    AccountChips(
                        accounts.filter { it.id != form.accountId },
                        selected = form.toAccountId,
                        onPick = { form.toAccountId = it },
                    )
                }
            }
            Field("Date") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val originalLabel = form.originalAt?.let { dayLabelOf(it) }
                    if (originalLabel != null) {
                        Chip(originalLabel, selected = form.date == DateChoice.Original, onClick = { form.date = DateChoice.Original })
                    }
                    if (originalLabel != "Today") {
                        Chip(todayLabel(), selected = form.date == DateChoice.Today, onClick = { form.date = DateChoice.Today })
                    }
                    if (originalLabel != "Yesterday") {
                        Chip("Yesterday", selected = form.date == DateChoice.Yesterday, onClick = { form.date = DateChoice.Yesterday })
                    }
                }
            }
        }

        Keypad(
            onKey = form::onKey,
            saveEnabled = form.canSave && !saved,
            onSave = {
                if (saved) return@Keypad
                saved = true
                val now = nowMillis()
                val edited = form.edited(now)
                if (edited != null) {
                    ledger.update(edited)
                    close()
                    return@Keypad
                }
                val at = form.occurredAt(now)
                val account = form.accountId
                val category = form.categoryId
                val to = form.toAccountId
                val id = when {
                    account == null -> null
                    form.kind == AmountKind.Transfer && to != null ->
                        ledger.logTransfer(form.centavos, account, to, at)
                    form.kind == AmountKind.Income && category != null ->
                        ledger.logIncome(form.centavos, category, account, at)
                    form.kind == AmountKind.Expense && category != null ->
                        ledger.logExpense(form.centavos, category, account, at)
                    else -> null
                }
                if (id != null) {
                    onSaved(id)
                    close()
                }
            },
        )
    }
}

@Composable
private fun AccountChips(accounts: List<Active>, selected: Long?, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        accounts.forEach { account ->
            Chip(account.name, selected = account.id == selected, onClick = { onPick(account.id) })
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), color = TrackchingTheme.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/** 4 tiles per row. The last tile is More until everything is shown. */
@Composable
private fun CategoryGrid(
    categories: List<MostUsed>,
    selected: Long?,
    showMore: Boolean,
    onPick: (Long) -> Unit,
    onMore: () -> Unit,
) {
    // null stands for the More tile.
    val tiles: List<MostUsed?> = categories + if (showMore) listOf(null) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cat ->
                    if (cat == null) {
                        CategoryTile("More", "…", Color(0xFF5C616C), null, false, Modifier.weight(1f), onMore)
                    } else {
                        CategoryTile(
                            name = cat.name,
                            letter = cat.name.take(1),
                            color = Color(cat.color),
                            parent = cat.parent_name?.substringBefore(' '),
                            selected = cat.id == selected,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(cat.id) },
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    name: String,
    letter: String,
    color: Color,
    parent: String?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = TrackchingTheme.colors
    Column(
        modifier
            .padding(end = 4.dp, bottom = 3.dp)
            .then(if (selected) Modifier.offsetShadow(c.orange, x = 4.dp, y = 3.dp) else Modifier)
            .background(if (selected) c.blue else c.field)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 66.dp)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(28.dp).background(color), contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(
            name,
            color = if (selected) c.onBlue else c.ink,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (parent != null) {
            Text(parent, color = if (selected) c.onBlue else c.ink2, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "Today, Nov 7" */
@OptIn(ExperimentalTime::class)
private fun todayLabel(): String {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val month = today.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "Today, $month ${today.day}"
}

/** "Today", "Yesterday" or "Tue, Nov 3" for a saved moment. */
@OptIn(ExperimentalTime::class)
private fun dayLabelOf(millis: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
    return dayLabel(date, Clock.System.todayIn(zone))
}