package app.trackching

import kotlin.math.abs

/** One row of a ranked list or one slice of a donut. `color` is 0xAARRGGBB, like the database. */
data class Share(val name: String, val centavos: Long, val color: Long)

/** The grey used for "Other". */
const val OTHER_COLOR = 0xFF5C616CL

/** Largest first. Rows at ₱0 or below are left out. */
fun ranked(items: List<Share>): List<Share> =
    items.filter { it.centavos > 0 }.sortedByDescending { it.centavos }

/** At most `max` slices, largest first. Everything after them is added up into one "Other" slice. */
fun donutSlices(items: List<Share>, max: Int = 5): List<Share> {
    val sorted = ranked(items)
    if (sorted.size <= max) return sorted
    val rest = sorted.drop(max)
    return sorted.take(max) + Share("Other", rest.sumOf { it.centavos }, OTHER_COLOR)
}

/**
 * Whole percentages that always add up to exactly 100.
 * Each value gets its rounded-down share. The points still missing go to the values
 * that lost the most when rounded down (the "largest remainder" method).
 */
fun wholePercents(values: List<Long>): List<Int> {
    val total = values.sum()
    if (total <= 0) return values.map { 0 }
    val percents = values.map { (it * 100 / total).toInt() }.toMutableList()
    val missing = 100 - percents.sum()
    values.indices
        .sortedByDescending { values[it] * 100 % total } // what rounding down threw away
        .take(missing)
        .forEach { percents[it] += 1 }
    return percents
}

/** Chart axis labels: ₱950, ₱1.2k, ₱12k, ₱1.2M. Takes centavos and drops them. Rounds down. */
fun shortPeso(centavos: Long): String {
    val sign = if (centavos < 0) "−" else ""
    val pesos = abs(centavos) / 100
    val text = when {
        pesos >= 1_000_000 -> oneDecimal(pesos, 1_000_000) + "M"
        pesos >= 1_000 -> oneDecimal(pesos, 1_000) + "k"
        else -> pesos.toString()
    }
    return "$sign₱$text"
}

/** 1,250 in thousands -> "1.2". Drops ".0", and drops the decimal from 10 up ("12", not "12.0"). */
private fun oneDecimal(value: Long, unit: Long): String {
    val tenths = value * 10 / unit
    return if (tenths >= 100 || tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** What a chart's side axis covers, in centavos, and the gap between its grid lines. */
data class Axis(val bottom: Long, val top: Long, val step: Long) {
    /** Where the grid lines go, bottom to top. Zero is always one of them. */
    val ticks: List<Long> get() = (bottom..top step step).toList()
}

/**
 * An axis that always includes zero and covers `min` to `max` with round steps:
 * 1, 2 or 5 times ₱1, ₱10, ₱100, and so on. About `lines` steps in total.
 */
fun axisFor(min: Long, max: Long, lines: Int = 4): Axis {
    val low = minOf(min, 0L)
    val high = maxOf(max, 0L)
    val step = niceStep(high - low, lines)
    val top = roundUp(high, step)
    val bottom = -roundUp(-low, step)
    return Axis(bottom, if (top == bottom) top + step else top, step)
}

/** The smallest round step (1, 2 or 5 × 10ⁿ pesos) where `lines` steps reach `range`. */
fun niceStep(range: Long, lines: Int = 4): Long {
    var magnitude = 100L // ₱1, in centavos
    while (true) {
        for (m in longArrayOf(1, 2, 5)) {
            val step = m * magnitude
            if (step * lines >= range) return step
        }
        magnitude *= 10
    }
}

/** Rounds a value of 0 or more up to the next multiple of `step`. */
private fun roundUp(value: Long, step: Long): Long = (value + step - 1) / step * step
