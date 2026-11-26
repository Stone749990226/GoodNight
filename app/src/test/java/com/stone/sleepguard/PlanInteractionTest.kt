package com.stone.sleepguard

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlanInteractionTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test fun chineseRangeInheritsAfternoon() {
        assertEquals(QuickPlan(today.plusDays(1), 1020, 1080, "写作业"), quickPlan("明天下午五点到六点写作业", today, today.minusDays(3)))
    }
    @Test fun explicitClockUsesSelectedDate() {
        assertEquals(QuickPlan(today.minusDays(2), 525, null, "喝水"), quickPlan("08:45 喝水", today, today.minusDays(2)))
    }
    @Test fun malformedTimesAreLeftUntouched() {
        listOf("吃饭", "25:61 吃饭", "17:00–16:00 写作业").forEach { assertNull(quickPlan(it, today, today)) }
    }
    @Test fun restDaysAndUnfinishedTodayPreserveStreak() {
        val monday = LocalDate.of(2026, 11, 23)
        val h = Habit(title = "跑步", startDate = monday.toEpochDay().toInt(), frequency = FREQ_WEEKDAYS, weekdayMask = 21)
        val rows = listOf(0L, 2L).map { HabitDay(h.id, monday.plusDays(it).toEpochDay().toInt(), count = 3, completedAt = 1L) }
        assertEquals(2 to 2, streaks(h, rows, monday.plusDays(4)))
        assertEquals(0 to 2, streaks(h, rows, monday.plusDays(5)))
    }
}
