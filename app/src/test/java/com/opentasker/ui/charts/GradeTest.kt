package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One grade per number, whichever screen draws it (白い熊, 2026-10-25).
 *
 * The fixture is the shape of 白い熊's own last fortnight: nights from 3 to 10 hours, so the
 * within-person "usual" for sleep runs from about five hours to eleven and a 10-hour night sits
 * inside it. The night table graded that night a 4 and the strip and the 回復 card a blue "usual".
 */
class GradeTest {

    private val day = 86_400_000L
    private val sleeps = listOf(473.0, 343.0, 446.0, 185.0, 615.0, 335.0, 510.0, 579.0, 528.0, 520.0, 526.0, 573.0)
    private val hrvs = listOf(26.4, 32.6, 21.8, 23.6, 29.0, 40.0, 40.0, 60.5, 41.6, 44.4, 43.8, 34.6)

    private fun night(i: Int, sleep: Double, hrv: Double) = RecoverySource.NightMetrics(
        startMs = i * day,
        endMs = i * day + 9 * 3_600_000L,
        nocturnalHr = 71.0,
        sleepMinutes = sleep,
        skinTemp = null,
        hrvMs = hrv,
    )

    private val history = (0 until 3).flatMap { rep ->
        sleeps.indices.map { night(rep * sleeps.size + it, sleeps[it], hrvs[it]) }
    } + night(3 * sleeps.size, 614.0, 29.9)

    @Test
    fun `the 10-hour night is a 4 in the table, on the strip and on the card`() {
        val prior = history.dropLast(1).takeLast(Recovery.BASELINE_NIGHTS)
        val strip = RecoveryBuild.readingsFor(history.last(), prior) { null }
        val table = SessionRegister.readNights(history) { null }.last()

        // The within-person band still calls it usual — that is a fact about these nights…
        assertEquals(RecoveryBand.USUAL, strip.sleep.band)
        // …and no longer the grade: every screen reads the NSF ladder.
        assertEquals(4, strip.sleep.displayStep)
        assertEquals(4, table.sleep.displayStep)
        assertEquals("Bad", Grade.word(strip.sleep.displayStep).en)
        assertTrue(strip.sleep.adverse)
    }

    @Test
    fun `HRV is graded on the index's edges everywhere`() {
        val prior = history.dropLast(1).takeLast(Recovery.BASELINE_NIGHTS)
        val strip = RecoveryBuild.readingsFor(history.last(), prior) { null }
        val table = SessionRegister.readNights(history) { null }.last()
        val index = HealthIndex.compute(
            HealthIndexInputs(null, null, null, null, null, hrvMs = 29.9),
        ).components.single { it.key == "hrv" }

        assertEquals(4, strip.hrv.displayStep)
        assertEquals(4, table.hrvStep)
        assertEquals(4, index.step)
        // The four nights before it were 1s and 2s.
        assertEquals(listOf(2, 1, 1, 1), listOf(40.0, 41.6, 44.4, 43.8).map { Grade.ladder(RecoveryMarker.HRV, it) })
    }

    @Test
    fun `a within-person marker keeps its own band`() {
        assertEquals(null, Grade.ladder(RecoveryMarker.DEEP, 90.0))
        assertEquals(null, Grade.ladder(RecoveryMarker.TEMPERATURE, 33.0))
        assertTrue(Grade.isAbsolute(RecoveryMarker.SLEEP))
        assertTrue(!Grade.isAbsolute(RecoveryMarker.BEDTIME_HR))
    }

    /** 白い熊's night of 2026-10-24/25 — 614 min asleep, 48 awake — and a short nap after it. */
    private val min = 60_000L
    private val bed = 1_792_871_400_000L
    private val lastNight = SleepSession(
        bed, bed + 662 * min,
        listOf(SleepRun(bed, bed + 614 * min, '2'), SleepRun(bed + 614 * min, bed + 662 * min, '5')),
    )
    private val nap = (bed + 14 * 60 * min).let { SleepSession(it, it + 30 * min, listOf(SleepRun(it, it + 30 * min, '?'))) }

    @Test
    fun `the index grades the night's time asleep, not a later nap and not the time in bed`() {
        val utc = { ms: Long -> ((ms + 3_600_000L) / min % 1440).toDouble() }
        val latest = RecoverySource.nights(listOf(lastNight, nap), utc).last()
        assertEquals(lastNight, latest)
        val sleep = HealthIndexSource.compute(emptyList(), latest, emptySet()).components.single { it.key == "sleep" }
        assertEquals(614.0, sleep.measured!!, 0.0)
        assertEquals(4, sleep.step)
        assertEquals(RecoveryReference.sleepStep(RecoverySource.sleepMinutes(lastNight)), sleep.step)
    }

    @Test
    fun `the morning's blood pressure is the table's first reading, graded on the ESH ladder`() {
        val bp = history.mapIndexed { i, n ->
            when (i) {
                history.lastIndex -> n.copy(bpSystolic = 141.0, bpDiastolic = 82.0)
                history.lastIndex - 1 -> n.copy(bpSystolic = 128.0, bpDiastolic = 82.0)
                else -> n
            }
        }
        val rows = SessionRegister.readNights(bp) { null }
        assertEquals(2, rows[rows.lastIndex - 1].bpStep)
        assertEquals(4, rows.last().bpStep)
        assertEquals(null, rows.first().bpStep)
        // A bad reading counts in the row like any other cell.
        val without = SessionRegister.readNights(history) { null }.last().adverseCount
        assertEquals(without + 1, rows.last().adverseCount)
    }

    @Test
    fun `a reading taken in bed before the watch ends the night still belongs to it`() {
        // 2026-10-25: the cuff at 07:00 and the stiffness at 07:01, the session running to 07:39.
        val end = lastNight.endMs
        val watch = RecoverySource.WatchSeries(
            bpSystolic = listOf(ChartPoint(end - 39 * min, 125.0)),
            bpDiastolic = listOf(ChartPoint(end - 39 * min, 92.0)),
            pwv = listOf(ChartPoint(end - 38 * min, 6.65)),
        )
        val n = RecoverySource.metricsFor(lastNight, emptyList(), emptyList(), watch = watch)
        assertEquals(125.0, n.bpSystolic!!, 0.0)
        assertEquals(92.0, n.bpDiastolic!!, 0.0)
        assertEquals(6.65, n.pwv!!, 0.0)
        // …and the evening before is not "this morning".
        val evening = RecoverySource.WatchSeries(bpSystolic = listOf(ChartPoint(lastNight.startMs - 60 * min, 140.0)))
        assertEquals(null, RecoverySource.metricsFor(lastNight, emptyList(), emptyList(), watch = evening).bpSystolic)
    }

    @Test
    fun `the resting-heart-rate bars grade on the same ladder as the strip and the index`() {
        for (bpm in listOf(45.0, 55.0, 65.0, 72.0, 85.0)) {
            assertEquals(
                Grade.ladder(RecoveryMarker.RESTING_HR, bpm),
                com.opentasker.ui.charts.huawei.ValueBarsScale.RESTING_HR.grade(bpm),
            )
        }
    }
}
