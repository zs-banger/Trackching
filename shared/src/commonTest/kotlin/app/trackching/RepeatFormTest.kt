package app.trackching

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatFormTest {
    private fun d(m: Int, day: Int) = LocalDate(2026, m, day)
    private fun monthly(start: LocalDate, end: RepeatEnd = RepeatEnd.Never) = RepeatRule(1, RepeatUnit.MONTH, start, end)

    @Test
    fun neverMeansNoRule() {
        val form = RepeatForm()
        assertFalse(form.on)
        assertNull(form.rule(d(11, 14)))
    }

    @Test
    fun pickingAUnitTurnsItOn() {
        val form = RepeatForm().apply { unit = RepeatUnit.MONTH }
        assertTrue(form.on)
        assertEquals(monthly(d(11, 14)), form.rule(d(11, 14)))
    }

    @Test
    fun stepsStayInRange() {
        val form = RepeatForm()
        form.stepEvery(-1)
        assertEquals(1, form.every)
        repeat(20) { form.stepTimes(-1) }
        assertEquals(2, form.times)
    }

    @Test
    fun timesCountsThisOne() {
        val form = RepeatForm().apply { unit = RepeatUnit.MONTH; end = EndChoice.Times }
        assertEquals(RepeatEnd.After(12), form.rule(d(11, 14))!!.end)
    }

    @Test
    fun untilNeedsADateAfterTheStart() {
        val form = RepeatForm().apply { unit = RepeatUnit.WEEK; end = EndChoice.Until }
        assertNull(form.rule(d(11, 14)))                // no date yet
        form.until = d(11, 14)
        assertNull(form.rule(d(11, 14)))                // same day isn't after
        form.until = d(12, 31)
        assertEquals(RepeatEnd.Until(d(12, 31)), form.rule(d(11, 14))!!.end)
    }

    @Test
    fun lastDayOfEachMonth() =   // the issue's example
        assertEquals("Next: Nov 30, then the last day of each month", repeatSentence(monthly(d(10, 31))))

    @Test
    fun the30thSaysWhatHappensInFebruary() =
        assertEquals("Next: Dec 30, then the 30th (or the last day) of each month", repeatSentence(monthly(d(11, 30))))

    @Test
    fun everyTwoMonths() =
        assertEquals(
            "Next: Jan 14, then the 14th of every 2 months",
            repeatSentence(RepeatRule(2, RepeatUnit.MONTH, d(11, 14))),
        )

    @Test
    fun weekly() =
        assertEquals("Next: Nov 21, then every Saturday", repeatSentence(RepeatRule(1, RepeatUnit.WEEK, d(11, 14))))

    @Test
    fun everyThreeDays() =
        assertEquals("Next: Nov 17, then every 3 days", repeatSentence(RepeatRule(3, RepeatUnit.DAY, d(11, 14))))

    @Test
    fun yearly() =
        assertEquals("Next: Nov 14, then every Nov 14", repeatSentence(RepeatRule(1, RepeatUnit.YEAR, d(11, 14))))

    @Test
    fun nTimesSaysHowManyMore() =
        assertEquals(
            "Next: Dec 14, then the 14th of each month · 11 more",
            repeatSentence(monthly(d(11, 14), RepeatEnd.After(12))),
        )

    @Test
    fun untilSaysTheEnd() =
        assertEquals(
            "Next: Dec 14, then the 14th of each month · until Jun 30",
            repeatSentence(monthly(d(11, 14), RepeatEnd.Until(LocalDate(2027, 6, 30)))),
        )

    @Test
    fun aRuleThatsOverSaysSo() =
        assertEquals("No more after this one", repeatSentence(monthly(d(11, 14), RepeatEnd.After(3)), done = 3))

    @Test
    fun ordinals() =
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map { ordinal(it) },
        )
}
