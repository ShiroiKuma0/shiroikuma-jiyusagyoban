package com.opentasker.ui.charts.huawei

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HuaweiBpExportTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val readings = listOf(
        HuaweiBpReading(at(20, 8, 26), 137.0, 96.0, 70.0),
        HuaweiBpReading(at(21, 10, 35), 128.0, 82.0, 73.0),
        HuaweiBpReading(at(19, 10, 33), 132.0, 89.0, null),
    )

    @Test fun plainNewestFirstIsTheDefault() = assertEquals(
        "2026-10-21 10:35 - 128 / 82, 73\n2026-10-20 08:26 - 137 / 96, 70\n2026-10-19 10:33 - 132 / 89\n",
        HuaweiBpExport.render(readings, HuaweiBpExport.Format.PLAIN, zone = zone),
    )

    @Test fun clinicalOldestFirst() = assertEquals(
        "2026-10-19 10:33  BP 132/89 mmHg\n2026-10-20 08:26  BP 137/96 mmHg, pulse 70/min\n" +
            "2026-10-21 10:35  BP 128/82 mmHg, pulse 73/min\n",
        HuaweiBpExport.render(readings, HuaweiBpExport.Format.CLINICAL, newestFirst = false, zone = zone),
    )

    @Test fun csvHasAHeaderAndAnEmptyPulse() = assertEquals(
        "date,time,systolic_mmHg,diastolic_mmHg,pulse_per_min\n2026-10-21,10:35,128,82,73\n" +
            "2026-10-20,08:26,137,96,70\n2026-10-19,10:33,132,89,\n",
        HuaweiBpExport.render(readings, HuaweiBpExport.Format.CSV, zone = zone),
    )
}
