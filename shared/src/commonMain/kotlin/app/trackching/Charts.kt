package app.trackching

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A hidden axis label or tooltip amount. Shorter than ₱ •••• so it fits next to a chart. */
const val MaskedShort = "₱••"

/** One entry of a chart's legend: a colored square and a word. */
data class LegendItem(val color: Color, val label: String)

/** One period of an up-and-down bar chart. `up` is income and `down` is spending, both 0 or more. */
data class UpDown(val label: String, val up: Long, val down: Long, val running: Boolean = false)

/** One line of a line chart: one value per x label, in centavos. */
data class ChartLine(val color: Color, val values: List<Long>, val width: Dp = 3.dp)

// Room around the plot for the axis labels.
private val AxisWidth = 46.dp
private val LabelHeight = 24.dp
private val TopRoom = 12.dp
private val RightRoom = 12.dp

/**
 * The frame every chart sits in: a two-line tag, the chart in a blue box, then the legend.
 * `action` sits at the top right, for range tabs like 3M 6M 12M.
 */
@Composable
fun ChartCard(
    title: String,
    second: String,
    modifier: Modifier = Modifier,
    legend: List<LegendItem> = emptyList(),
    note: String? = null,
    action: @Composable () -> Unit = {},
    chart: @Composable () -> Unit,
) {
    val c = TrackchingTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Tag(title, second = second)
            action()
        }
        Box(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(c.band)
                .border(1.5.dp, c.blue)
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
        ) { chart() }
        if (legend.isNotEmpty() || note != null) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    legend.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(item.color))
                            Text(
                                item.label,
                                Modifier.padding(start = 5.dp),
                                color = c.ink2,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                if (note != null) Text(note, color = c.ink2, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Name, bar, amount and % for each row, largest first. Each bar is sized against the
 * largest row, so the top one is always full. Used for categories, accounts and subcategories.
 */
@Composable
fun RankedBars(
    rows: List<Share>,
    modifier: Modifier = Modifier,
    onClick: ((Share) -> Unit)? = null,
) {
    val c = TrackchingTheme.colors
    val sorted = ranked(rows)
    val biggest = sorted.firstOrNull()?.centavos ?: 0L
    val percents = wholePercents(sorted.map { it.centavos })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sorted.forEachIndexed { i, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable { onClick(row) } else Modifier),
                verticalAlignment = Alignment.Bottom,
            ) {
                Bar(
                    fraction = row.centavos.toFloat() / biggest,
                    label = row.name,
                    height = 26.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        shown(formatAmount(row.centavos, AmountKind.Transfer)),
                        color = c.onOrange,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                Text(
                    "${percents[i]}%",
                    Modifier.width(46.dp).padding(bottom = 8.dp),
                    color = c.ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** A donut of at most 5 slices plus Other, with a legend of names and % beside it. */
@Composable
fun Donut(items: List<Share>, modifier: Modifier = Modifier) {
    val c = TrackchingTheme.colors
    val slices = donutSlices(items)
    val total = slices.sumOf { it.centavos }
    if (total == 0L) return // nothing to draw: the screen shows its empty state instead
    val percents = wholePercents(slices.map { it.centavos })
    val description = slices.indices.joinToString { "${slices[it].name} ${percents[it]}%" }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(116.dp).semantics { contentDescription = "Share by category: $description" }) {
            val ring = 22.dp.toPx()
            var start = -90f // 12 o'clock
            slices.forEach { slice ->
                val sweep = 360f * slice.centavos / total
                drawArc(
                    color = Color(slice.color),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(ring / 2, ring / 2), // the stroke is centered on the path
                    size = Size(size.width - ring, size.height - ring),
                    style = Stroke(width = ring),
                )
                start += sweep
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            slices.forEachIndexed { i, slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).background(Color(slice.color)))
                    Text(
                        slice.name,
                        Modifier.padding(start = 6.dp).weight(1f),
                        color = c.ink2,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${percents[i]}%", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Income bars up from zero, spending bars down, and Net as an orange line with a dot per bar.
 * Tapping a bar calls `onSelect`; when one is `selected`, the others are dimmed.
 * A period that's still running gets a * after its label.
 */
@Composable
fun UpDownChart(
    bars: List<UpDown>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val c = TrackchingTheme.colors
    val hidden = LocalAmountsHidden.current
    val measurer = rememberTextMeasurer()
    val axis = axisFor(min = -(bars.maxOfOrNull { it.down } ?: 0L), max = bars.maxOfOrNull { it.up } ?: 0L)
    // The tap handler below starts once and keeps running. rememberUpdatedState lets it
    // always call the newest onSelect and see the newest bars (lesson from the P1-9 audit).
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentCount by rememberUpdatedState(bars.size)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Income and spending, ${bars.size} periods" }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    if (currentCount == 0) return@detectTapGestures
                    val slot = (size.width - AxisWidth.toPx() - RightRoom.toPx()) / currentCount
                    val i = ((tap.x - AxisWidth.toPx()) / slot).toInt().coerceIn(0, currentCount - 1)
                    currentOnSelect(i)
                }
            },
    ) {
        val plot = PlotArea(this, axis)
        drawGrid(plot, axis, measurer, hidden, c.line, c.ink2)
        if (bars.isEmpty()) return@Canvas

        val slot = plot.width / bars.size
        val barWidth = minOf(30.dp.toPx(), slot * 0.46f)
        val zero = plot.y(0)
        val netPoints = mutableListOf<Offset>()
        bars.forEachIndexed { i, bar ->
            val center = plot.left + slot * i + slot / 2
            val alpha = if (selected != null && selected != i) 0.4f else 1f
            drawRect(
                c.income.copy(alpha = alpha),
                topLeft = Offset(center - barWidth / 2, plot.y(bar.up)),
                size = Size(barWidth, zero - plot.y(bar.up)),
            )
            drawRect(
                c.expense.copy(alpha = alpha),
                topLeft = Offset(center - barWidth / 2, zero),
                size = Size(barWidth, plot.y(-bar.down) - zero),
            )
            val label = measurer.measure(
                bar.label + if (bar.running) "*" else "",
                TextStyle(
                    color = if (selected == i) c.ink else c.ink2,
                    fontSize = 13.sp,
                    fontWeight = if (selected == i) FontWeight.ExtraBold else FontWeight.Bold,
                ),
            )
            drawText(label, topLeft = Offset(center - label.size.width / 2, size.height - label.size.height))
            netPoints += Offset(center, plot.y(bar.up - bar.down))
        }
        drawPath(lineThrough(netPoints), c.orange, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        netPoints.forEach { point ->
            drawCircle(c.band, radius = 6.dp.toPx(), center = point)
            drawCircle(c.orange, radius = 4.dp.toPx(), center = point)
        }
    }
}

/**
 * One or more lines over the same months, for Balance over time.
 * While I touch it, a dashed crosshair and a tooltip show that month and each line's amount.
 * The touch doesn't consume anything, so the page can still scroll over the chart.
 */
@Composable
fun LineChart(
    xLabels: List<String>,
    lines: List<ChartLine>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val c = TrackchingTheme.colors
    val hidden = LocalAmountsHidden.current
    val measurer = rememberTextMeasurer()
    val values = lines.flatMap { it.values }
    val axis = axisFor(min = values.minOrNull() ?: 0L, max = values.maxOrNull() ?: 0L)
    var touched by remember { mutableStateOf<Int?>(null) } // which x the finger is on
    val count by rememberUpdatedState(xLabels.size)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Line chart, ${xLabels.size} points" }
            .pointerInput(Unit) {
                fun indexAt(x: Float): Int? {
                    if (count < 2) return if (count == 1) 0 else null
                    val width = size.width - AxisWidth.toPx() - RightRoom.toPx()
                    return ((x - AxisWidth.toPx()) / width * (count - 1)).roundToInt().coerceIn(0, count - 1)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touched = indexAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { touched = indexAt(it.position.x) }
                    } while (event.changes.any { it.pressed })
                    touched = null // finger lifted: hide the crosshair
                }
            },
    ) {
        val plot = PlotArea(this, axis)
        drawGrid(plot, axis, measurer, hidden, c.line, c.ink2)
        val n = xLabels.size
        if (n == 0) return@Canvas
        fun x(i: Int) = if (n == 1) plot.left + plot.width / 2 else plot.left + plot.width * i / (n - 1)

        // Month labels along the bottom.
        xLabels.forEachIndexed { i, text ->
            val label = measurer.measure(text, TextStyle(color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val left = (x(i) - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(left, size.height - label.size.height))
        }
        lines.forEach { line ->
            val points = line.values.mapIndexed { i, v -> Offset(x(i), plot.y(v)) }
            drawPath(lineThrough(points), line.color, style = Stroke(line.width.toPx(), join = StrokeJoin.Round))
            points.lastOrNull()?.let { drawCircle(line.color, radius = 5.dp.toPx(), center = it) }
        }

        val i = touched ?: return@Canvas
        drawLine(
            c.ink2,
            start = Offset(x(i), plot.top),
            end = Offset(x(i), plot.bottom),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        lines.forEach { line ->
            line.values.getOrNull(i)?.let { drawCircle(line.color, radius = 5.dp.toPx(), center = Offset(x(i), plot.y(it))) }
        }
        val amounts = lines.mapNotNull { it.values.getOrNull(i) }
            .map { if (hidden) MaskedShort else formatAmount(it, AmountKind.Transfer) }
        drawTooltip(measurer, (listOf(xLabels[i].uppercase()) + amounts).joinToString("\n"), x(i), c.ink, c.band)
    }
}

/** Where the plot sits inside the canvas, and how to turn centavos into a y position. */
private class PlotArea(scope: DrawScope, private val axis: Axis) {
    val left = with(scope) { AxisWidth.toPx() }
    val top = with(scope) { TopRoom.toPx() }
    val bottom = scope.size.height - with(scope) { LabelHeight.toPx() }
    val width = scope.size.width - left - with(scope) { RightRoom.toPx() }
    fun y(centavos: Long): Float = top + (axis.top - centavos).toFloat() / (axis.top - axis.bottom) * (bottom - top)
}

/** A thin line at each tick, a stronger one at zero, and ₱1.2k labels on the left. */
private fun DrawScope.drawGrid(plot: PlotArea, axis: Axis, measurer: TextMeasurer, hidden: Boolean, line: Color, ink2: Color) {
    // Show at most 5 labels, so a tall axis doesn't get crowded.
    val every = (axis.ticks.size + 4) / 5
    axis.ticks.forEachIndexed { index, tick ->
        val y = plot.y(tick)
        drawLine(
            if (tick == 0L) ink2 else line,
            start = Offset(plot.left, y),
            end = Offset(plot.left + plot.width, y),
            strokeWidth = (if (tick == 0L) 1.5.dp else 1.dp).toPx(),
        )
        if (index % every != 0 && tick != 0L) return@forEachIndexed
        val label = measurer.measure(
            if (hidden) MaskedShort else shortPeso(tick),
            TextStyle(color = ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        drawText(label, topLeft = Offset(plot.left - 8.dp.toPx() - label.size.width, y - label.size.height / 2))
    }
}

/** A path through the points in order, as straight segments. */
private fun lineThrough(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

/** A dark box with the text in it, near the top of the chart, kept inside the edges. */
private fun DrawScope.drawTooltip(measurer: TextMeasurer, text: String, atX: Float, background: Color, ink: Color) {
    val layout = measurer.measure(text, TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
    val pad = 8.dp.toPx()
    val boxWidth = layout.size.width + pad * 2
    val boxHeight = layout.size.height + pad * 1.5f
    val left = (atX + pad).let { if (it + boxWidth > size.width) atX - pad - boxWidth else it }.coerceAtLeast(0f)
    drawRect(background, topLeft = Offset(left, 4.dp.toPx()), size = Size(boxWidth, boxHeight))
    drawText(layout, topLeft = Offset(left + pad, 4.dp.toPx() + pad * 0.75f))
}
