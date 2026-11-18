package com.stone.sleepguard

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreTest {
    @Test fun stablePhoneAndBriefVibrationDoNotTrigger() {
        val detector = MotionDetector()
        for (t in 0L..1500L step 100) assertNull(detector.sample(0f, 0f, 9.81f, t, false))
        for (t in 1600L..5000L step 100) {
            val x = if (t == 2000L || t == 2100L) 2f else 0f
            assertNull(detector.sample(x, 0f, 9.81f, t, true))
        }
    }
    @Test fun sustainedTiltTriggers() {
        val detector = MotionDetector()
        for (t in 0L..1500L step 100) detector.sample(0f, 0f, 9.81f, t, false)
        val results = (1600L..4000L step 100).map { detector.sample(0f, 8.5f, 4.9f, it, true) }
        assertTrue(results.any { it != null })
    }
    @Test fun interruptedSleepIsNotInventedAndMorningMovementIsExcluded() {
        val night = Night(id = 1, date = "2026-10-19", firstSleepAt = 1000, lastSleepAt = 1000,
            targetBed = 1410, targetWake = 450, wakeAt = 10000, phase = Phase.AWAKE)
        val event = Interruption(id = 1, nightId = 1, detectedAt = 2000, reason = "背单词")
        assertNull(NightRecord(night, listOf(event), emptyList()).finalBedAt)
        assertEquals(1000L, NightRecord(night, listOf(event.copy(morning = true)), emptyList()).finalBedAt)
        assertEquals(3000L, NightRecord(night.copy(lastSleepAt = 3000), listOf(event), emptyList()).finalBedAt)
    }
    @Test fun midnightBelongsToPreviousEveningAndAveragesDoNotWrapToNoon() {
        val zone = ZoneId.of("Asia/Shanghai")
        val early = ZonedDateTime.of(2026, 10, 20, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-10-19", nightDate(early, zone))
        assertEquals(1470, bedGoal(30))
        assertEquals(1410, bedGoal(1410))
    }
}
