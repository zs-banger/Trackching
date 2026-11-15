package app.trackching

import app.trackching.db.HeldByAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetWorthTest {
    @Test
    fun heldMoneyIsWhatsNotHandedBackYet() {
        val rows = listOf(HeldByAccount(1, 500_000, 0), HeldByAccount(1, 100_000, 40_000), HeldByAccount(2, 20_000, 20_000))
        assertEquals(mapOf(1L to 560_000L), heldPerAccount(rows)) // account 2 handed it all back
    }

    @Test
    fun theHeldLineSaysWhatsYours() = assertEquals(
        "₱ 5,000.00 of this is held for others · Yours ₱ 5,000.00",
        heldLine(1_000_000, 500_000),
    )

    @Test
    fun spendingHeldMoneyMakesYoursNegative() = assertEquals(
        "₱ 5,000.00 of this is held for others · Yours − ₱ 1,000.00",
        heldLine(400_000, 500_000),
    )

    @Test
    fun theHeldLineHidesAmounts() = assertEquals(
        "₱ •••• of this is held for others · Yours ₱ ••••",
        heldLine(1_000_000, 500_000) { masked(it, hidden = true) },
    )

    @Test
    fun nothingHeldNoLine() = assertNull(heldLine(1_000_000, 0))

    @Test
    fun theStripedPartIsTheHeldShareOfTheBalance() {
        assertEquals(0.25f, heldShare(400_000, 100_000))
        assertEquals(1f, heldShare(100_000, 500_000))
        assertEquals(0f, heldShare(-100, 500))
    }

    private fun row(kind: IouKind, left: Long) = IouRow(1, kind, 1, "Juan", "", left, null, false)

    @Test
    fun netWorthAddsWhatsOwedToMeAndTakesAwayTheRest() {
        val worth = netWorthOf(
            1_000_000,
            listOf(row(IouKind.OWED, 200_000), row(IouKind.OWE, 50_000), row(IouKind.HOLDING, 300_000), row(IouKind.OWE, 0)),
        )
        assertEquals(NetWorth(1_000_000, 200_000, 50_000, 300_000, 0), worth)
        assertEquals(850_000, worth.worth)
    }

    @Test
    fun owedOnCreditTakesAwayToo() = assertEquals(900_000, NetWorth(1_000_000, 0, 0, 0, onCredit = 100_000).worth)
}
