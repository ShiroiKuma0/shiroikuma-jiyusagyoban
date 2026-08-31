package com.opentasker.core.ocr

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry that decides what pixels reach the recogniser. Every case here is a bug that was
 * actually made and measured during the Phase 0 spike, not a hypothetical.
 */
class OcrGeometryTest {

    private fun extents(quad: OcrQuad): Pair<Float, Float> {
        val width = quad.maxOf { it.x } - quad.minOf { it.x }
        val height = quad.maxOf { it.y } - quad.minOf { it.y }
        return width to height
    }

    @Test
    fun `unclip grows a long thin line in BOTH directions`() {
        // A typical detected text line: very wide, very short. DB predicts a shrunk region, so the
        // dilation has to restore the line's full height — that is where the ascenders and diacritics
        // live. Expanding corners away from the centre (the wrong implementation) moves almost
        // entirely sideways here and leaves the height nearly untouched.
        val rect = listOf(
            OcrPoint(0f, 0f), OcrPoint(504f, 0f),
            OcrPoint(504f, 14f), OcrPoint(0f, 14f),
        )
        val expected = 504f * 14f * 1.5f / (2f * (504f + 14f))   // area * ratio / perimeter

        val (width, height) = extents(unclip(rect, 1.5f))

        assertEquals(504f + 2 * expected, width, 0.01f)
        assertEquals(14f + 2 * expected, height, 0.01f)
        // The point of the whole test: the box gets appreciably TALLER, not just wider.
        assertTrue("unclip must raise the height, got $height", height > 14f * 2f)
    }

    @Test
    fun `unclip expands a square symmetrically`() {
        val rect = listOf(
            OcrPoint(0f, 0f), OcrPoint(100f, 0f),
            OcrPoint(100f, 100f), OcrPoint(0f, 100f),
        )
        val expected = 100f * 100f * 1.5f / 400f

        val (width, height) = extents(unclip(rect, 1.5f))

        assertEquals(100f + 2 * expected, width, 0.01f)
        assertEquals(100f + 2 * expected, height, 0.01f)
    }

    @Test
    fun `unclip keeps the centre put`() {
        val rect = listOf(
            OcrPoint(10f, 20f), OcrPoint(110f, 20f),
            OcrPoint(110f, 40f), OcrPoint(10f, 40f),
        )
        val dilated = unclip(rect, 1.5f)

        assertEquals(60f, dilated.map { it.x }.average().toFloat(), 0.01f)
        assertEquals(30f, dilated.map { it.y }.average().toFloat(), 0.01f)
    }

    @Test
    fun `minAreaRect hugs an axis-aligned box`() {
        val points = buildList {
            for (x in 0..40) for (y in 0..10) add(OcrPoint(x.toFloat(), y.toFloat()))
        }
        val (width, height) = extents(minAreaRect(points))

        assertEquals(40f, width, 0.5f)
        assertEquals(10f, height, 0.5f)
    }

    @Test
    fun `minAreaRect beats the bounding box on a diagonal line`() {
        // A 45-degree strip: the axis-aligned bounding box is 30x30, the true minimum-area rectangle
        // is a thin diagonal sliver. Getting this wrong would crop a rotated line as a huge square.
        val points = buildList {
            for (step in 0..30) {
                add(OcrPoint(step.toFloat(), step.toFloat()))
                add(OcrPoint(step.toFloat(), step + 2f))
            }
        }
        val rect = minAreaRect(points)
        val sideA = distance(rect[0], rect[1])
        val sideB = distance(rect[1], rect[2])
        val shortSide = minOf(sideA, sideB)

        assertTrue("expected a thin sliver, got ${sideA}x$sideB", shortSide < 3f)
    }

    @Test
    fun `convexHull drops interior points`() {
        val hull = convexHull(
            listOf(
                OcrPoint(0f, 0f), OcrPoint(10f, 0f), OcrPoint(10f, 10f), OcrPoint(0f, 10f),
                OcrPoint(5f, 5f),   // strictly inside
            )
        )
        assertEquals(4, hull.size)
        assertTrue(hull.none { abs(it.x - 5f) < 0.01f && abs(it.y - 5f) < 0.01f })
    }

    @Test
    fun `orderQuad returns corners clockwise from the top left`() {
        val scrambled = listOf(
            OcrPoint(100f, 40f),   // bottom-right
            OcrPoint(0f, 0f),      // top-left
            OcrPoint(0f, 40f),     // bottom-left
            OcrPoint(100f, 0f),    // top-right
        )
        val (topLeft, topRight, bottomRight, bottomLeft) = orderQuad(scrambled)

        assertEquals(OcrPoint(0f, 0f), topLeft)
        assertEquals(OcrPoint(100f, 0f), topRight)
        assertEquals(OcrPoint(100f, 40f), bottomRight)
        assertEquals(OcrPoint(0f, 40f), bottomLeft)
    }
}
