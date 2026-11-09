package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyTest {
    @Test fun visibleByDefault() = assertEquals("− ₱ 120.00", masked("− ₱ 120.00", hidden = false))
    @Test fun hiddenHidesEverything() = assertEquals("₱ ••••", masked("− ₱ 120.00", hidden = true))
    @Test fun hiddenHidesTheSignToo() = assertEquals(masked("+ ₱ 5.00", true), masked("− ₱ 5.00", true))
}
