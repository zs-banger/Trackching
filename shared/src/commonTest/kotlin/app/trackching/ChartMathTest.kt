package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals

class ChartMathTest {

    private fun share(name: String, pesos: Long) = Share(name, pesos * 100, 0xFF1663FF)

    @Test
    fun rankedIsLargestFirstWithoutEmptyRows() {
        val rows = listOf(share("Food", 300), share("Fun", 0), share("Bills", 900), share("Health", 100))
        assertEquals(listOf("Bills", "Food", "Health"), ranked(rows).map { it.name })
    }

    @Test
    fun fiveOrFewerSlicesHaveNoOther() {
        val rows = (1..5L).map { share("C$it", it * 100) }
        assertEquals(5, donutSlices(rows).size)
        assertEquals("C5", donutSlices(rows).first().name)
    }

    @Test
    fun theRestIsAddedUpIntoOther() {
        val rows = (1..8L).map { share("C$it", it * 100) } // ₱100 to ₱800
        val slices = donutSlices(rows)
        assertEquals(listOf("C8", "C7", "C6", "C5", "C4", "Other"), slices.map { it.name })
        assertEquals((100L + 200 + 300) * 100, slices.last().centavos) // C1 + C2 + C3
        assertEquals(OTHER_COLOR, slices.last().color)
        assertEquals(rows.sumOf { it.centavos }, slices.sumOf { it.centavos }) // nothing lost
    }

    @Test
    fun percentsAlwaysAddUpTo100() {
        assertEquals(listOf(34, 33, 33), wholePercents(listOf(1, 1, 1)))
        assertEquals(100, wholePercents(listOf(1, 1, 1)).sum())
        assertEquals(100, wholePercents(listOf(12_345, 67_890, 3, 999, 450_000)).sum())
        assertEquals(listOf(50, 50), wholePercents(listOf(250, 250)))
    }

    @Test
    fun percentsOfNothingAreZero() {
        assertEquals(listOf(0, 0), wholePercents(listOf(0, 0)))
        assertEquals(emptyList(), wholePercents(emptyList()))
    }

    @Test
    fun shortPesoForAxes() {
        assertEquals("₱0", shortPeso(0))
        assertEquals("₱950", shortPeso(95_000))
        assertEquals("₱1k", shortPeso(100_000))
        assertEquals("₱1.2k", shortPeso(123_200))   // ₱1,232.00
        assertEquals("₱12k", shortPeso(1_250_000))  // ₱12,500
        assertEquals("₱1.2M", shortPeso(120_000_000))
        assertEquals("−₱5k", shortPeso(-500_000))   // a true minus sign, like everywhere else
    }

    @Test
    fun axisCoversTheDataWithRoundSteps() {
        val axis = axisFor(min = 0, max = 325_000) // up to ₱3,250
        assertEquals(Axis(bottom = 0, top = 400_000, step = 100_000), axis) // ₱0 to ₱4k, every ₱1k
        assertEquals(listOf(0L, 100_000, 200_000, 300_000, 400_000), axis.ticks)
    }

    @Test
    fun axisGoesBelowZeroForSpending() {
        val axis = axisFor(min = -150_000, max = 500_000) // −₱1,500 to ₱5,000
        assertEquals(200_000L, axis.step) // ₱2k
        assertEquals(-200_000L, axis.bottom)
        assertEquals(600_000L, axis.top)
        assertEquals(true, 0L in axis.ticks)
    }

    @Test
    fun axisWithNoDataStillHasHeight() {
        val axis = axisFor(0, 0)
        assertEquals(0L, axis.bottom)
        assertEquals(axis.step, axis.top)
    }
}
