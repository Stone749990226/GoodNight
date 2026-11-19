package com.stone.sleepguard

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.math.*

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
    @Test fun editedTimesStayOnTheirOwnNight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val bed = bedTimestamp("2026-11-19", 23 * 60 + 30, zone)
        val wake = wakeTimestamp("2026-11-19", 7 * 60, zone)
        assertEquals(LocalDate.of(2026, 11, 19), Instant.ofEpochMilli(bed).atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 20), Instant.ofEpochMilli(wake).atZone(zone).toLocalDate())
        assertEquals(420, Instant.ofEpochMilli(wake).atZone(zone).let { it.hour * 60 + it.minute })
        assertEquals(LocalDate.of(2026, 11, 20), Instant.ofEpochMilli(bedTimestamp("2026-11-19", 30, zone)).atZone(zone).toLocalDate())
        assertEquals(1410, parseClock("23:30") ?: -1)
        assertEquals(425, parseClock("7:05") ?: -1)
        assertNull(parseClock("24:00"))
        assertNull(parseClock("23:70"))
        assertTrue(wake > bed)
    }
    @Test fun editedBedLaterThanInterruptionLeavesNightIncomplete() {
        val night = Night(id = 2, date = "2026-11-19", firstSleepAt = 1000, lastSleepAt = 2000,
            targetBed = 1410, targetWake = 450, wakeAt = 10000, phase = Phase.AWAKE)
        val interruption = Interruption(id = 1, nightId = 2, detectedAt = 5000, reason = "测试")
        assertNull(NightRecord(night, listOf(interruption), emptyList()).finalBedAt)
        assertNotNull(NightRecord(night.copy(lastSleepAt = 6000), listOf(interruption), emptyList()).finalBedAt)
    }
    @Test fun fixedHighSensitivityDetectsFifteenDegreeTilt() {
        val detector = MotionDetector()
        for (t in 0L..1000L step 100) detector.sample(0f, 0f, 9.81f, t, false)
        val rad = Math.toRadians(15.0)
        val x = (9.81 * sin(rad)).toFloat()
        val z = (9.81 * cos(rad)).toFloat()
        val results = (1100L..4000L step 100).map { t -> detector.sample(x, 0f, z, t, true) }
        assertTrue(results.filterNotNull().any { it.startsWith("倾斜") })
    }
    @Test fun oversleepingPastConfiguredLimitIsNotValid() {
        val night = Night(id = 4, date = "2026-11-19", firstSleepAt = 0, lastSleepAt = 0,
            targetBed = 1410, targetWake = 450, wakeAt = 9 * 3_600_000L, phase = Phase.AWAKE, maxSleepMinutes = 600)
        assertTrue(NightRecord(night, emptyList(), emptyList()).valid)
        val long = night.copy(wakeAt = 10 * 3_600_000L + 60_000L)
        val record = NightRecord(long, emptyList(), emptyList())
        assertTrue(record.overslept)
        assertFalse(record.valid)
        assertTrue(NightRecord(night.copy(maxSleepMinutes = 480), emptyList(), emptyList()).overslept)
    }
    @Test fun emailConfigNeedsBothAddressesAndCode() {
        val partial = EmailSettings(enabled = true, from = "stone@example.com", partner = "fanny@example.com")
        assertFalse(partial.configured)
        assertTrue(partial.copy(authCode = "code").configured)
    }
    @Test fun rolesPairWithEachOther() {
        assertEquals(Role.FANNY, Role.STONE.partner)
        assertEquals(Role.STONE, Role.FANNY.partner)
        assertEquals("Fanny", partnerName(Role.STONE))
        assertEquals("Stone", partnerName(Role.FANNY))
    }
    @Test fun customRangeIncludesBothEndsAndKeepsOrder() {
        fun record(id: Long, date: String) = NightRecord(Night(id = id, date = date, firstSleepAt = id,
            lastSleepAt = id, targetBed = 1410, targetWake = 450, phase = Phase.AWAKE), emptyList(), emptyList())
        val records = listOf(record(3, "2026-11-18"), record(2, "2026-11-10"), record(1, "2026-11-02"))
        assertEquals(listOf(3L, 2L), recordsForRange(records, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-18")).map { it.night.id })
        assertEquals(listOf(2L, 1L), recordsForRange(records, LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-10")).map { it.night.id })
        assertEquals(3, recordsForRange(records, null, null).size)
    }
}
