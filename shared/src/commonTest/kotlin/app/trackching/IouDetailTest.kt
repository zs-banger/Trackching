package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals

class IouDetailTest {
    @Test
    fun interestReadsLikeTheForm() {
        assertEquals("No interest", interestLabel(Interest.None))
        assertEquals("₱ 500.00 flat", interestLabel(Interest.Flat(50_000)))
        assertEquals("5.00% a month", interestLabel(Interest.Monthly(500)))
        assertEquals("2.50% a month", interestLabel(Interest.Monthly(250)))
    }

    @Test
    fun theButtonSaysWhichWayTheMoneyGoesBack() {
        assertEquals("Record payment", IouKind.OWE.actionLabel())
        assertEquals("Payment received", IouKind.OWED.actionLabel())
        assertEquals("Hand back", IouKind.HOLDING.actionLabel())
    }
}
