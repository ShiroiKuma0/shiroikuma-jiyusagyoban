package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The plan: [GnssRebuildSchedule.LEAD_MIN] before the first local sync slot after anchor + 24 h. */
class GnssRebuildScheduleTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    @Test fun takenAtTheMidnightSyncBuildsBeforeFourNextNight() {
        // 2026-10-20: the watch took a set at the 00:00 sync; 24.0 h later is not an ask, 04:00 is.
        assertEquals(at(2026, 10, 21, 3, 30), GnssRebuildSchedule.planFor(at(2026, 10, 20, 0, 0, 30), zone))
    }

    @Test fun takenAtEightBuildsBeforeMidnightNextDay() {
        assertEquals(at(2026, 10, 20, 23, 30), GnssRebuildSchedule.planFor(at(2026, 10, 19, 20, 0, 10), zone))
    }

    @Test fun aBuildTheWatchNeverTookIsRebuiltADayLater() {
        // Built at 03:40, never asked for: the next slot after 03:40 + 24 h is 04:00.
        assertEquals(at(2026, 10, 22, 3, 30), GnssRebuildSchedule.planFor(at(2026, 10, 21, 3, 40), zone))
    }

    @Test fun slotsStayOnTheLocalClockAcrossTheChangeToWinterTime() {
        // 2026-10-25 has 25 hours. 24 h after 04:00:10 CEST is 03:00:10 CET; the next slot is 04:00 CET.
        assertEquals(at(2026, 10, 25, 3, 30), GnssRebuildSchedule.planFor(at(2026, 10, 24, 4, 0, 10), zone))
    }
}
