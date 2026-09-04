package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/**
 * The workout decoders, run over bytes the band actually sent.
 *
 * Everything in `HuaweiWorkout` used to be written from published descriptions, and for the sample
 * and pace streams it had never been sent at all. On 2026-09-03 the probe asked 白い熊's band about
 * ten recorded workouts and dumped every tag whole; these fixtures are two of them — walk 8, which
 * has a track and three blocks of samples, and workout 20, the strength-training session that the
 * sync used to throw away for having no track.
 *
 * The point of the file is that the stream decode is CHECKABLE. The band states a duration, a step
 * count and a distance in the summary, and the per-sample stream reaches all three independently:
 * if the record layout were wrong, the sample count would not land on the duration and the steps
 * would not sum to the total. That is what these assertions are — not "does it parse", but "does
 * what it parsed agree with what the band said about itself".
 */
class HuaweiWorkoutStreamTest {

    private fun reply(name: String) = listOf(
        HuaweiProtocol.Tlv(
            0x81,
            requireNotNull(javaClass.classLoader?.getResourceAsStream("huawei/$name")) {
                "fixture huawei/$name missing"
            }.readBytes(),
        ),
    )

    private fun walkStream(): HuaweiWorkout.SampleBlock {
        val blocks = (0..2).map { requireNotNull(HuaweiWorkout.parseSamples(reply("workout-8-samples-$it.bin"))) }
        return blocks.first().copy(
            heart = blocks.flatMap { it.heart },
            speedDmS = blocks.flatMap { it.speedDmS },
            steps = blocks.flatMap { it.steps },
        )
    }

    /** The walk the sync already knew about, to establish that nothing existing moved. */
    @Test
    fun `walk 8 summary reads what the band's own screen showed`() {
        val s = requireNotNull(HuaweiWorkout.parseSummary(reply("workout-8-summary.bin")))
        assertEquals(8, s.number)
        assertEquals(2, s.type)
        assertEquals("walk", s.kind)
        assertEquals(1765L, s.durationSeconds)
        assertEquals(2270, s.distanceMetres)
        assertEquals(135, s.calories)
        assertEquals(2892, s.steps)
    }

    /**
     * The three figures that make the record layout provable rather than plausible.
     *
     * The stream says nothing about duration, steps or distance; it is a wall of bytes whose stride
     * comes off the header. Reading it the wrong way — the heart rate anywhere but the first byte,
     * the speed as one byte instead of two — breaks at least one of these three, and all three come
     * from a summary this test never shows the decoder.
     */
    @Test
    fun `walk 8 samples agree with the summary on duration, steps and distance`() {
        val all = walkStream()
        assertEquals("five seconds per sample", 5, all.intervalSeconds)
        assertEquals("353 samples of 5 s is the stated 1765 s", 1765, all.heart.size * all.intervalSeconds)

        // Every reading but the first is a real pulse: the band publishes a zero for the sample
        // taken as the workout starts, before the optical sensor has anything to average.
        val live = all.heart.filter { it > 0 }
        assertEquals(352, live.size)
        assertEquals(91, live.min())
        assertEquals(125, live.max())

        // The band said 2892; the per-interval counts sum to 2910. Within a step per minute of
        // agreement, which is the summary rounding, not a misread record.
        assertEquals(2910, all.steps.sum())
        assertTrue("steps must land within 1 % of the stated 2892", Math.abs(2910 - 2892) < 2892 / 100)

        // Speed is decimetres per second, so a five-second interval covers speed / 2 metres.
        val metres = all.speedDmS.sumOf { it } * all.intervalSeconds / 10.0
        assertEquals("integrated speed is the stated 2270 m", 2270.0, metres, 5.0)
    }

    /** The band's mean-speed tag, checked against the distance and duration it also states. */
    @Test
    fun `mean speed is decimetres per second`() {
        val s = requireNotNull(HuaweiWorkout.parseSummary(reply("workout-8-summary.bin")))
        assertEquals(12, s.meanSpeedDmS)
        val computed = s.distanceMetres!! * 10.0 / s.durationSeconds!!
        assertEquals(computed, s.meanSpeedDmS!!.toDouble(), 1.0)
    }

    /**
     * The workout the sync used to discard, and the reason 白い熊 asked for any of this.
     *
     * No distance, no steps, no satellites — and 43 minutes of heart rate, 217 kcal and a sport
     * code nothing in the published list covers.
     */
    @Test
    fun `workout 20 is a strength session with no track and a full heart rate stream`() {
        val s = requireNotNull(HuaweiWorkout.parseSummary(reply("workout-20-summary.bin")))
        assertEquals(HuaweiWorkout.STRENGTH, s.type)
        assertEquals("strength", s.kind)
        assertTrue("a lift is not an outdoor kind", !s.isOutdoor)
        assertTrue(s.isStrength)
        assertEquals(2584L, s.durationSeconds)
        assertEquals(217, s.calories)
        assertEquals(0, s.distanceMetres)
        assertEquals(0, s.steps)

        val block = requireNotNull(HuaweiWorkout.parseSamples(reply("workout-20-samples-0.bin")))
        assertEquals(5, block.intervalSeconds)
        assertEquals(516, block.heart.size)
        assertEquals("2580 s of samples against a stated 2584", 4L, s.durationSeconds!! - 516 * 5)
        val live = block.heart.filter { it > 0 }
        assertEquals(86, live.min())
        assertEquals(134, live.max())
        // A lift has neither, and the field bitmap says so rather than the parser assuming it.
        assertTrue("no speed in a strength stream", block.speedDmS.isEmpty())
        assertTrue("no steps in a strength stream", block.steps.isEmpty())
    }

    /**
     * The cooldown 白い熊 asked for, and the evidence that it IS a cooldown.
     *
     * Tag `0x66` is twenty-five readings that the summary has always carried and nothing ever read.
     * Its first value is the workout's LAST heart rate — in both fixtures, and in all ten workouts
     * the probe dumped — and it falls away from there. That is what makes it the recovery curve
     * rather than a downsample of the workout: a downsample of a session that ran between 86 and
     * 134 bpm would not begin exactly where the session ended and then decline monotonically.
     */
    @Test
    fun `the recovery curve begins where the workout's heart rate ended`() {
        val lift = requireNotNull(HuaweiWorkout.parseSummary(reply("workout-20-summary.bin")))
        val stream = requireNotNull(HuaweiWorkout.parseSamples(reply("workout-20-samples-0.bin")))
        assertEquals(25, lift.recovery.size)
        assertEquals("the curve starts at the last pulse of the session", stream.heart.last(), lift.recovery.first())
        assertEquals(96, lift.recovery.last())
        assertEquals(20, lift.recoveryDrop)

        val walk = requireNotNull(HuaweiWorkout.parseSummary(reply("workout-8-summary.bin")))
        assertEquals(25, walk.recovery.size)
        assertEquals(119, walk.recovery.first())
        assertEquals(7, walk.recoveryDrop)
        // Within a beat of walk 8's final sample, which is the whole claim.
        assertTrue(Math.abs(walkStream().heart.last() - walk.recovery.first()) <= 1)
    }

    /** A workout with no recovery reading must report no drop, not a drop of zero. */
    @Test
    fun `no recovery curve is not a drop of zero`() {
        assertEquals(null, HuaweiWorkout.Summary(number = 1).recoveryDrop)
        assertEquals(null, HuaweiWorkout.Summary(number = 1, recovery = listOf(0, 0)).recoveryDrop)
    }

    /**
     * The pace table, in both units the band keeps.
     *
     * Walk 8 is 2.27 km, so it has two kilometre rows and two mile rows, and the partial row of
     * each carries how far that unfinished unit actually got: 2700 dm is the last 270 m, 6610 dm
     * the last 0.41 of a mile.
     */
    @Test
    fun `pace splits carry both units and the partial distance`() {
        val splits = HuaweiWorkout.parsePace(reply("workout-8-pace.bin"))
        assertEquals(5, splits.size)
        val km = splits.filter { !it.mile }
        val mi = splits.filter { it.mile }
        assertEquals(3, km.size)
        assertEquals(2, mi.size)
        assertEquals(821, km.first().seconds)
        assertEquals(2700, km.last().partialDecimetres)
        assertEquals(6610, mi.last().partialDecimetres)
        // The partial rows both close on the workout's own duration.
        assertTrue(splits.mapNotNull { it.cumulativeSeconds.takeIf { c -> c > 1700 } }.all { it == 1764 })
    }

    /** A stride the body cannot hold is a decode we do not understand, and must not half-draw. */
    @Test
    fun `a stream whose stride does not fit is refused`() {
        val head = byteArrayOf(0, 8, 0, 0, 0x6A, 0x8B.toByte(), 0x1F, 0xA5.toByte(), 5, 0, 100, 4, 0, 7)
        val bad = listOf(
            HuaweiProtocol.Tlv(
                0x81,
                HuaweiProtocol.tlv(4, head) + HuaweiProtocol.tlv(5, ByteArray(40)),
            ),
        )
        assertEquals(null, HuaweiWorkout.parseSamples(bad))
        // The same header with a body that does fit is read.
        val good = listOf(
            HuaweiProtocol.Tlv(
                0x81,
                HuaweiProtocol.tlv(4, head) + HuaweiProtocol.tlv(5, ByteArray(400)),
            ),
        )
        assertNotNull(HuaweiWorkout.parseSamples(good))
    }
}
