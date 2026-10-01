package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationStatsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 1)
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aNewIslandIsDueAndReviewsPushItOut() {
        var i = GengoshimaIslandEntity(position = 1, nameEn = "Work", createdAt = 0)
        assertTrue(Rotation.isDue(i, today, zone))
        i = Rotation.review(i, "listen", today, zone)
        assertEquals(1.0, i.intervalDays, 0.0)
        assertFalse(Rotation.isDue(i, today, zone))
        assertTrue(Rotation.isDue(i, today.plusDays(1), zone))
        i = Rotation.review(i, "shadow", today.plusDays(1), zone)
        assertEquals(3.0, i.intervalDays, 0.0)
        assertEquals("shadowed", i.status)
        i = Rotation.review(i, "recall", today.plusDays(4), zone)
        assertTrue("grows by the ease: ${i.intervalDays}", i.intervalDays >= 7.0)
        assertEquals("rotating", i.status)
    }

    @Test
    fun listeningIsWeakerEvidenceThanRecallingSoTheEaseDrifts() {
        val base = GengoshimaIslandEntity(position = 1, nameEn = "x", createdAt = 0)
        assertTrue(Rotation.review(base, "listen", today, zone).ease < Rotation.review(base, "recall", today, zone).ease)
    }

    @Test
    fun minutesAreSplitOverTheHoursAndDaysInARowCount() {
        val y = today.minusDays(1)
        val sessions = listOf(
            // 07:50–08:10, 20 minutes: ten in hour 7, ten in hour 8.
            GengoshimaSessionEntity(startedAt = ms(today, 7, 50), endedAt = ms(today, 8, 10), mode = "listen", islandIds = "1", listenedMs = 20 * 60_000L),
            GengoshimaSessionEntity(startedAt = ms(y, 21), endedAt = ms(y, 21, 5), mode = "shadow", islandIds = "1", listenedMs = 5 * 60_000L),
            GengoshimaSessionEntity(startedAt = ms(today.minusDays(5), 12), endedAt = ms(today.minusDays(5), 12, 1), mode = "listen", islandIds = "1", listenedMs = 60_000L),
        )
        val s = ListeningStats.summarise(sessions, zone, today)
        assertEquals(10 * 60_000L, s.perHour[7])
        assertEquals(10 * 60_000L, s.perHour[8])
        assertEquals(2, s.streak)
        assertEquals(2, s.longest)
        assertEquals(26 * 60_000L, s.totalMs)
        assertEquals("1時間 5分", ListeningStats.duration(65 * 60_000L))
    }

    @Test
    fun aStreakSurvivesTodayNotYetListenedTo() {
        val y = today.minusDays(1)
        val s = ListeningStats.summarise(
            listOf(GengoshimaSessionEntity(startedAt = ms(y, 9), endedAt = ms(y, 9, 3), mode = "listen", islandIds = "1", listenedMs = 180_000L)),
            zone, today,
        )
        assertEquals(1, s.streak)
    }
}
