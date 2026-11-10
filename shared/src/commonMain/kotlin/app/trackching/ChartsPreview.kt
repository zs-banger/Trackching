package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Made-up numbers, only for previews. Nothing here is real data.
private val sampleCategories = listOf(
    Share("Food & Beverages", 812_000, 0xFFE07A00),
    Share("Transport", 245_000, 0xFF1D9BB3),
    Share("Bills & Utilities", 389_900, 0xFF1663FF),
    Share("Shopping", 150_000, 0xFFC0569A),
    Share("Health", 60_000, 0xFF3DDC6A),
    Share("Entertainment", 45_000, 0xFF9B7BFF),
    Share("Gifts & Giving", 20_000, 0xFFFF3B30),
)

private val sampleMonths = listOf(
    UpDown("Jun", 2_400_000, 1_850_000),
    UpDown("Jul", 2_400_000, 2_610_000),
    UpDown("Aug", 2_650_000, 1_990_000),
    UpDown("Sep", 2_400_000, 2_200_000),
    UpDown("Oct", 2_900_000, 2_050_000),
    UpDown("Nov", 1_200_000, 980_000, running = true),
)

/**
 * Every chart part on one screen, with made-up numbers. It follows the real theme and HIDE ₱.
 * Used by the previews below, and for a few minutes on the phone in step 6 (never committed there).
 */
@Composable
internal fun ChartsSampler() {
    val c = TrackchingTheme.colors
    var selected by remember { mutableStateOf<Int?>(5) }
    Column(
        Modifier.background(c.band2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ChartCard(
            "Income", "vs expenses",
            legend = listOf(LegendItem(c.income, "Income"), LegendItem(c.expense, "Expenses"), LegendItem(c.orange, "Net")),
            note = "* So far, still running",
        ) {
            UpDownChart(sampleMonths, selected = selected, onSelect = { selected = it })
        }
        ChartCard("Balance", "over time") {
            LineChart(
                xLabels = listOf("Sep", "Oct", "Nov"),
                lines = listOf(
                    ChartLine(c.orange, listOf(3_120_000, 3_480_000, 3_655_000)),
                    ChartLine(Color(0xFF1D9BB3), listOf(450_000, 620_000, 510_000), width = 2.5.dp),
                ),
            )
        }
        RankedBars(sampleCategories.take(5))
        Donut(sampleCategories)
    }
}

@Preview
@Composable
private fun ChartsPreview() {
    TrackchingTheme { ChartsSampler() }
}

@Preview
@Composable
private fun ChartsHiddenPreview() {
    TrackchingTheme {
        CompositionLocalProvider(LocalAmountsHidden provides true) { ChartsSampler() }
    }
}
