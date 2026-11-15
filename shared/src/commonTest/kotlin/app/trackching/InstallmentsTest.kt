package app.trackching

import app.trackching.db.Iou_installment
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallmentsTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)

    @Test
    fun monthlyDatesCountFromTheFirstOne() =
        assertEquals(listOf(d(1, 31), d(2, 28), d(3, 31)), installmentDates(d(1, 31), 3, RepeatUnit.MONTH))

    @Test
    fun weeklyDatesAreSevenDaysApart() =
        assertEquals(listOf(d(3, 2), d(3, 9)), installmentDates(d(3, 2), 2, RepeatUnit.WEEK))

    @Test
    fun theLastPartTakesTheLeftoverCentavos() {
        assertEquals(listOf(33_333L, 33_333L, 33_334L), evenAmounts(100_000, 3))
        assertEquals(listOf(25_000L, 25_000L, 25_000L, 25_000L), evenAmounts(100_000, 4))
    }

    @Test
    fun eachPaymentCarriesTheSameShareOfInterest() {
        // ₱1,000 + ₱200 interest in 3: every ₱400 payment is ₱66.66 or so interest.
        val shares = interestShares(evenAmounts(120_000, 3), 20_000)
        assertEquals(listOf(6_666L, 6_666L, 6_668L), shares)
        assertEquals(20_000, shares.sum())
    }

    @Test
    fun differentAmountsGetInterestByTheirSize() {
        assertEquals(listOf(5_000L, 15_000L), interestShares(listOf(30_000, 90_000), 20_000))
    }

    @Test
    fun noInterestMeansNoShares() = assertEquals(listOf(0L, 0L), interestShares(listOf(1, 2), 0))

    @Test
    fun aPlanPairsDatesAmountsAndShares() {
        val plan = planOf(listOf(d(4, 1), d(5, 1)), listOf(60_000, 60_000), 10_000)
        assertEquals(PlannedPayment(d(5, 1), 60_000, 5_000), plan.last())
    }

    private fun part(n: Long, due: String, state: String = "WAITING") =
        Iou_installment(n, 1, n, due, 10_000, 0, state, null)

    @Test
    fun theDetailLineCountsWhatsLeftAndTheNextDate() {
        val parts = listOf(part(1, "2026-03-10", "SAVED"), part(2, "2026-04-10"), part(3, "2026-05-10"))
        assertEquals("2 of 3 payments left · next due Apr 10, 2026", installmentsLine(parts))
    }

    @Test
    fun noPartsLeftHasNoNextDate() =
        assertEquals("0 of 1 payments left", installmentsLine(listOf(part(1, "2026-03-10", "SKIPPED"))))

    @Test
    fun noInstallmentsNoLine() = assertNull(installmentsLine(emptyList()))
}
