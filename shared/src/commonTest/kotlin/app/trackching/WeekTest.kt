package app.trackching

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekTest {
    // Wed 11 Nov 2026
    @Test fun mondayStart() = assertEquals(LocalDate(2026, 11, 9), startOfWeek(LocalDate(2026, 11, 11), DayOfWeek.MONDAY))
    @Test fun sundayStart() = assertEquals(LocalDate(2026, 11, 8), startOfWeek(LocalDate(2026, 11, 11), DayOfWeek.SUNDAY))
    @Test fun sundayInMondayWeek() = assertEquals(LocalDate(2026, 11, 9), startOfWeek(LocalDate(2026, 11, 15), DayOfWeek.MONDAY))
    @Test fun startDayIsItself() = assertEquals(LocalDate(2026, 11, 8), startOfWeek(LocalDate(2026, 11, 8), DayOfWeek.SUNDAY))
    @Test fun crossesNewYear() = assertEquals(LocalDate(2025, 12, 29), startOfWeek(LocalDate(2026, 1, 1), DayOfWeek.MONDAY))
}
