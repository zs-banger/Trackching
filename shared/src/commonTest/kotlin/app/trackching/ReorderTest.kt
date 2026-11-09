package app.trackching

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTest {
    private val abc = listOf("A", "B", "C")

    @Test fun moveDown() = assertEquals(listOf("B", "A", "C"), abc.moved(0, 1))
    @Test fun moveUp() = assertEquals(listOf("C", "A", "B"), abc.moved(2, 0))
    @Test fun sameSpotIsUnchanged() = assertEquals(abc, abc.moved(1, 1))
}
