package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Today, This month, Last month, This year, All time. One is always picked. */
@Composable
fun PeriodChips(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Period.entries.forEach { p ->
            Chip(p.label, selected = p == selected, onClick = { onSelect(p) })
        }
    }
}

/**
 * The Flow summary: count and Net, Income and Expenses tiles, then Savings rate and Adjustments.
 * Tapping a tile picks the matching tab under it.
 */
@Composable
fun FlowCard(flow: Flow, running: Boolean, onPickTab: (TypeTab) -> Unit, modifier: Modifier = Modifier, onOpenAll: (() -> Unit)? = null,) {
    val c = TrackchingTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tag("Flow", second = "summary")
        Column(
            Modifier.fillMaxWidth().background(c.band).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // N transactions · Net
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    "${flow.count} ${if (flow.count == 1) "transaction" else "transactions"}" + if (onOpenAll != null) " ›" else "",
                    Modifier.weight(1f).then(if (onOpenAll != null) Modifier.clickable(onClick = onOpenAll) else Modifier),
                    color = c.ink2,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("NET", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        shown(formatAmount(flow.net, kindOfNet(flow.net))),
                        color = colorOfNet(flow.net),
                        fontSize = 26.sp,
                        fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                    )
                }
            }

            // Income and Expenses tiles
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowTile("Income", flow.income, AmountKind.Income, c.income, Modifier.weight(1f)) { onPickTab(TypeTab.Income) }
                FlowTile("Expenses", flow.expenses, AmountKind.Expense, c.expense, Modifier.weight(1f)) { onPickTab(TypeTab.Expenses) }
            }

            // Savings rate and Adjustments
            val rate = savingsRate(flow)
            FlowLine(
                "Savings rate" + if (running && rate != null) " so far" else "",
                rate?.let { "$it%" } ?: "—",
            )
            if (flow.adjustments != 0L) {
                FlowLine(
                    "Adjustments",
                    shown((if (flow.adjustments < 0) "− " else "+ ") + formatAmount(flow.adjustments, AmountKind.Transfer)),
                )
            }

            // P3-9: IOU money leaving, shown but never counted in Net.
            if (flow.debtPayments != 0L) FlowLine("Debt payments", shown("− " + formatAmount(flow.debtPayments, AmountKind.Transfer)))
            if (flow.handedBack != 0L) FlowLine("Handed back", shown("− " + formatAmount(flow.handedBack, AmountKind.Transfer)))

            if (flow.others > 0) {
                Text(
                    "The other ${flow.others}, like transfers, are in History but don't change Net.",
                    color = c.ink2,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun FlowTile(
    label: String,
    centavos: Long,
    kind: AmountKind,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = TrackchingTheme.colors
    Column(
        modifier
            .background(c.field)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${label.uppercase()} ›", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(shown(formatAmount(centavos, kind)), color = color, fontSize = 18.sp, fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun FlowLine(label: String, value: String) {
    val c = TrackchingTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = c.ink, fontSize = 15.sp, fontFamily = TrackchingTheme.fonts.condensed, fontWeight = FontWeight.ExtraBold)
    }
}

private fun kindOfNet(net: Long) = when {
    net < 0 -> AmountKind.Expense
    net > 0 -> AmountKind.Income
    else -> AmountKind.Transfer
}

@Composable
private fun colorOfNet(net: Long): Color {
    val c = TrackchingTheme.colors
    return when {
        net < 0 -> c.expense
        net > 0 -> c.income
        else -> c.ink
    }
}
