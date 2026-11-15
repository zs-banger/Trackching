package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.Balances
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * S5 Accounts: the total, each account's share, and Balance over time.
 * Tapping an account opens its transactions.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun AccountsScreen(
    ledger: Ledger,
    onBack: () -> Unit,
    onOpenAccount: (Balances) -> Unit,
) {
    val c = TrackchingTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)

    val loadedBalances by remember(ledger) { ledger.balances() }.collectAsState(initial = null)
    val loadedMoves by remember(ledger) { ledger.movements() }.collectAsState(initial = null)
    // Credit accounts aren't money I have: they get their own group, outside the total and the chart (P3-12).
    val balances = loadedBalances?.filterNot { it.isCredit } ?: return
    val moves = loadedMoves ?: return
    val credit by remember(ledger) { ledger.creditAccounts() }.collectAsState(initial = emptyList())
    val held by remember(ledger) { ledger.held() }.collectAsState(initial = emptyMap())
    val worth by remember(ledger, today) { ledger.netWorth(today) }.collectAsState(initial = null)

    var range by rememberSaveable { mutableStateOf(3) }           // 3, 6 or 12 months
    var focus by rememberSaveable { mutableStateOf<Long?>(null) } // an account's id, or null for "Total only"
    var managing by remember { mutableStateOf(false) }

    val total = totalBalance(balances)
    val shares = remember(balances) { accountShares(balances.map { it.balance }) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackLink("Home", onBack, Modifier.weight(1f))
            TextButton(onClick = { managing = true }) {
                Text("MANAGE", color = c.orangeInk, fontWeight = FontWeight.Bold)
            }
        }
        Tag("Accounts")

        // Total balance
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TOTAL BALANCE", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Amount(total, if (total < 0) AmountKind.Expense else AmountKind.Transfer, maxFontSize = 36.sp)
            // P3-10: how much of it is really mine. Accounts only, never Home.
            val hidden = LocalAmountsHidden.current
            heldLine(total, held.values.sum()) { masked(it, hidden) }?.let {
                Text(it, color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Your accounts: one bar each, sized by its share of the total
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tag("Your", second = "accounts")
            balances.forEachIndexed { i, account ->
                AccountShareRow(account, shares[i], total, held[account.id] ?: 0, onClick = { onOpenAccount(account) })
            }
            if (held.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(width = 22.dp, height = 12.dp).heldStripes(c.orange, c.track).border(1.dp, c.blue))
                    Text("Held for others", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (credit.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tag("Credit")
                credit.forEach { card ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).background(Color(card.color)))
                        Text(card.name, Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${shown(formatAmount(card.owedNow, AmountKind.Transfer))} owed",
                            color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }

        worth?.let { NetWorthCard(it) }

        // Balance over time
        val months = remember(today, range) { lastMonths(today, zone, range) }
        val monthStart = remember(today) { monthRange(today, zone).first }
        ChartCard(
            "Balance", "over time",
            action = {
                Row {
                    listOf(3, 6, 12).forEach { r ->
                        Chip("${r}M", selected = range == r, onClick = { range = r })
                    }
                }
            },
        ) {
            if (!hasHistoryBefore(moves, monthStart)) {
                Text(
                    "Your balance history starts this month. Check back next month to see the trend.",
                    Modifier.padding(12.dp),
                    color = c.ink2,
                    fontSize = 15.sp,
                )
            } else {
                val series = remember(balances, moves, months) {
                    balances.associate { it.id to balanceSeries(it.id, it.balance, moves, months) }
                }
                val lines = buildList {
                    add(ChartLine(c.orange, totalSeries(series.values.toList(), months.size)))
                    val picked = balances.firstOrNull { it.id == focus }
                    if (picked != null) add(ChartLine(Color(picked.color), series.getValue(picked.id), width = 2.5.dp))
                }
                LineChart(xLabels = months.map { it.label }, lines = lines)
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("Total only", selected = focus == null, onClick = { focus = null }, dot = c.orange)
            balances.forEach { account ->
                Chip(account.name, selected = focus == account.id, onClick = { focus = account.id }, dot = Color(account.color))
            }
        }
        Box(Modifier.padding(bottom = 80.dp)) // room for the + button
    }

    if (managing) ManageAccountsSheet(ledger, onDismiss = { managing = false })
}

/** Color, name and type over a bar showing the balance, then its % of the total. */
@Composable
private fun AccountShareRow(account: Balances, percent: Int, total: Long, held: Long, onClick: () -> Unit) {
    val c = TrackchingTheme.colors
    val type = AccountType.entries.firstOrNull { it.dbName == account.type }?.label ?: account.type
    val fraction = if (total <= 0L) 0f else account.balance.coerceAtLeast(0L).toFloat() / total
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.padding(bottom = 18.dp, end = 6.dp).size(12.dp).background(Color(account.color)))
        Bar(
            fraction = fraction,
            label = "${account.name} · $type",
            height = 26.dp,
            held = heldShare(account.balance, held),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                shown(formatAmount(account.balance, if (account.balance < 0) AmountKind.Expense else AmountKind.Transfer)),
                color = c.onOrange,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
        Text(
            "$percent%",
            Modifier.width(46.dp).padding(bottom = 8.dp),
            color = c.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.End,
        )
    }
}

/** P3-10: what I'm worth after debts, one line for each part. */
@Composable
private fun NetWorthCard(worth: NetWorth) {
    val c = TrackchingTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.band).border(1.5.dp, c.blue).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("NET WORTH", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Amount(worth.worth, if (worth.worth < 0) AmountKind.Expense else AmountKind.Transfer, maxFontSize = 30.sp)
        WorthLine("Total balance", worth.total, AmountKind.Transfer)
        WorthLine("Owed to me", worth.owedToMe, AmountKind.Income)
        WorthLine("I owe", worth.iOwe, AmountKind.Expense)
        WorthLine("Holding for others", worth.holding, AmountKind.Expense)
        WorthLine("Owed on credit", worth.onCredit, AmountKind.Expense)
    }
}

@Composable
private fun WorthLine(label: String, centavos: Long, kind: AmountKind) {
    val c = TrackchingTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        // + adds to net worth, − takes away. Nothing to add shows no sign; a negative total shows −.
        val sign = when {
            centavos == 0L -> AmountKind.Transfer
            kind == AmountKind.Transfer && centavos < 0 -> AmountKind.Expense
            else -> kind
        }
        Text(shown(formatAmount(centavos, sign)), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
