package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * SP3 reading, against the reference reader's own numbers on a real CODE file.
 *
 * The failure this exists to prevent is the one that shipped: **adding leap seconds to an SP3
 * epoch.** SP3 epochs are already GPS time, and adding 18 s to them moved the whole truth timeline,
 * so every element set was fitted to satellites 18 seconds from where they were — about 53 km
 * along-track. It survived a full day because the generator graded itself against the same shifted
 * clock it had fitted to and reported 0.3 m. `the first epoch equals the file's own declared week
 * and seconds-of-week` is the check that cannot be fooled that way: it compares the parser's answer
 * against a number written in the file's second line by whoever produced it.
 *
 * The rest is the interpolator, which is the other thing that lies quietly: a 9-point Lagrange
 * polynomial across a hole does not interpolate, it invents, and two BeiDou satellites were once
 * graded at 37 km and 32 km against nothing but its imagination.
 */
class Sp3Test {

    @Test
    fun `the first epoch equals the file's own declared week and seconds-of-week`() {
        val lines = PgnssFixtures.text("mini.sp3").lines()
        val header = lines.first { it.startsWith("##") }.trim().split(Regex("\\s+"))
        val declared = header[1].toLong() * 604800L + header[2].toDouble()
        val parsed = Sp3.epochSeconds(lines.first { it.startsWith("*") })
        assertEquals(
            "SP3 epochs are already GPS time — never add LEAP when reading one",
            declared, parsed, 1e-6,
        )
    }

    @Test
    fun `every satellite in the trimmed file parses with a full arc`() {
        val sats = PgnssFixtures.sp3
        assertEquals(setOf("G01", "G02", "E02", "E03", "R02", "R03", "R04"), sats.keys)
        for ((name, arc) in sats) {
            assertEquals("$name epochs", 108, arc.size)
            assertEquals(3 * 108, arc.p.size)
            // 300 s cadence, strictly increasing, no duplicated epoch.
            for (i in 1 until arc.size) assertEquals(300.0, arc.t[i] - arc.t[i - 1], 1e-9)
            // Positions are metres, so a MEO satellite is 20-27 Mm from the centre.
            val r = Math.sqrt(arc.x(0) * arc.x(0) + arc.y(0) * arc.y(0) + arc.z(0) * arc.z(0))
            assertTrue("$name radius $r", r > 1.8e7 && r < 3.0e7)
            assertTrue("$name clock", abs(arc.clock[0]) < 1e-3)
        }
    }

    @Test
    fun `interpolation and the symmetric-difference velocity match the reference exactly`() {
        val p = DoubleArray(3)
        val v = DoubleArray(3)
        var checked = 0
        for (f in PgnssFixtures.rows("interp.txt")) {
            if (f[0] != "S") continue
            val arc = PgnssFixtures.sp3.getValue(f[1])
            val t = PgnssFixtures.d(f[2])
            Sp3.stateAt(arc, t, p, v)
            for (k in 0 until 3) {
                assertEquals("${f[1]} position $k at $t", PgnssFixtures.d(f[3 + k]), p[k], 1e-6)
                assertEquals("${f[1]} velocity $k at $t", PgnssFixtures.d(f[6 + k]), v[k], 1e-9)
            }
            val clock = Sp3.interpolate(arc.t, arc.clock, t)
            assertEquals("${f[1]} clock at $t", PgnssFixtures.d(f[9]), clock, 1e-18)
            checked++
        }
        assertEquals(70, checked)
    }

    @Test
    fun `the stencil is the reference's, at the edges as well as the middle`() {
        val arc = PgnssFixtures.sp3.getValue("G01")
        // Left edge: clamped to 0, not centred on a negative index.
        assertEquals(0, Sp3.stencilStart(arc.t, arc.t[0] - 1e6))
        assertEquals(0, Sp3.stencilStart(arc.t, arc.t[0]))
        assertEquals(0, Sp3.stencilStart(arc.t, arc.t[4]))
        assertEquals(1, Sp3.stencilStart(arc.t, arc.t[5]))
        // Right edge: clamped so the stencil still has nine samples.
        assertEquals(arc.size - 9, Sp3.stencilStart(arc.t, arc.t[arc.size - 1]))
        assertEquals(arc.size - 9, Sp3.stencilStart(arc.t, arc.t[arc.size - 1] + 1e6))
        // searchSorted is numpy's 'left': an exact hit returns the sample's own index.
        assertEquals(7, Sp3.searchSorted(arc.t, arc.t[7]))
        assertEquals(8, Sp3.searchSorted(arc.t, arc.t[7] + 1.0))
    }

    @Test
    fun `a hole in the series is refused instead of being interpolated across`() {
        val arc = PgnssFixtures.sp3.getValue("R02")
        val mid = arc.t[54]
        assertTrue("a contiguous stencil spans", Sp3.spanned(arc.t, mid))

        // Drop a day out of the middle, exactly as Wuhan drops a satellite from one issue.
        val keep = (0 until arc.size).filter { it < 40 || it > 60 }
        val holed = Sp3.Arc(
            DoubleArray(keep.size) { arc.t[keep[it]] },
            DoubleArray(3 * keep.size) { arc.p[3 * keep[it / 3] + it % 3] },
            DoubleArray(keep.size) { arc.clock[keep[it]] },
        )
        assertFalse("a stencil straddling the hole must not be trusted",
            Sp3.spanned(holed.t, mid))
        assertTrue("well clear of the hole it is fine", Sp3.spanned(holed.t, holed.t[10]))
    }

    @Test
    fun `merging keeps the later file where two overlap and honours the keep window`() {
        val arc = PgnssFixtures.sp3.getValue("G01")
        val early = Sp3.Arc(
            DoubleArray(20) { arc.t[it] },
            DoubleArray(60) { 1.0 },
            DoubleArray(20) { 0.0 },
        )
        val late = Sp3.Arc(
            DoubleArray(20) { arc.t[10 + it] },
            DoubleArray(60) { 2.0 },
            DoubleArray(20) { 0.0 },
        )
        val merged = Sp3.merge(listOf(mapOf("G01" to early), mapOf("G01" to late)))
        val g = merged.getValue("G01")
        assertEquals(30, g.size)
        assertEquals("the first file survives where the second does not reach", 1.0, g.x(0), 0.0)
        assertEquals("the later file wins the overlap", 2.0, g.x(10), 0.0)
        // keep: the second half of a 48 h product is that file's own prediction, not data.
        val trimmed = Sp3.merge(listOf(mapOf("G01" to early)), keepSeconds = 3000.0)
        assertEquals(10, trimmed.getValue("G01").size)
    }

    @Test
    fun `the civil calendar matches known epochs`() {
        assertEquals(0L, Sp3.daysFromCivil(1970, 1, 1))
        assertEquals(3657L, Sp3.daysFromCivil(1980, 1, 6))
        assertEquals("the GPS epoch, in seconds", 315_964_800L, 3657L * 86_400L)
        assertEquals(20_694L, Sp3.daysFromCivil(2026, 8, 29))
        assertEquals("a leap day", 1L, Sp3.daysFromCivil(2024, 3, 1) - Sp3.daysFromCivil(2024, 2, 29))
    }
}
