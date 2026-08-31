package com.opentasker.core.ocr

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry behind DB text detection: convex hull, minimum-area rectangle, and the "unclip"
 * dilation. Deliberately free of Android types so it can be unit-tested on the JVM — this is where
 * the subtle bugs live, and they are invisible from the outside (they do not crash; they quietly
 * return slightly wrong boxes and the text comes out wrong).
 */

/** A point in detection-map or image pixel space. */
data class OcrPoint(val x: Float, val y: Float)

/** Four corners, in order, of a (possibly rotated) rectangle. */
typealias OcrQuad = List<OcrPoint>

internal fun distance(a: OcrPoint, b: OcrPoint): Float = hypot(b.x - a.x, b.y - a.y)

/**
 * Andrew's monotone chain, returning the hull counter-clockwise.
 *
 * Points arrive as whole-pixel coordinates from a connected component, so duplicates are common and
 * are removed first — the chain misbehaves on repeated points.
 */
fun convexHull(points: List<OcrPoint>): List<OcrPoint> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size <= 2) return sorted

    fun cross(o: OcrPoint, a: OcrPoint, b: OcrPoint): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    val lower = ArrayList<OcrPoint>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
            lower.removeAt(lower.size - 1)
        }
        lower += p
    }
    val upper = ArrayList<OcrPoint>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
            upper.removeAt(upper.size - 1)
        }
        upper += p
    }
    return lower.dropLast(1) + upper.dropLast(1)
}

/**
 * Minimum-area enclosing rectangle, by rotating calipers.
 *
 * Relies on the standard result that such a rectangle always has one side flush with a hull edge, so
 * testing every hull edge as the candidate axis is exhaustive.
 */
fun minAreaRect(points: List<OcrPoint>): OcrQuad {
    val hull = convexHull(points)
    if (hull.size < 3) {
        // Degenerate (a dot or a 1px line): fall back to the axis-aligned bounding box.
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return listOf(
            OcrPoint(minX, minY), OcrPoint(maxX, minY),
            OcrPoint(maxX, maxY), OcrPoint(minX, maxY),
        )
    }

    var bestArea = Float.MAX_VALUE
    var best: OcrQuad = emptyList()
    for (i in hull.indices) {
        val p = hull[i]
        val q = hull[(i + 1) % hull.size]
        val length = distance(p, q)
        if (length < 1e-6f) continue
        val ux = (q.x - p.x) / length
        val uy = (q.y - p.y) / length
        val vx = -uy
        val vy = ux

        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (pt in hull) {
            val u = pt.x * ux + pt.y * uy
            val v = pt.x * vx + pt.y * vy
            minU = min(minU, u); maxU = max(maxU, u)
            minV = min(minV, v); maxV = max(maxV, v)
        }
        val area = (maxU - minU) * (maxV - minV)
        if (area < bestArea) {
            bestArea = area
            fun corner(u: Float, v: Float) = OcrPoint(u * ux + v * vx, u * uy + v * vy)
            best = listOf(
                corner(minU, minV), corner(maxU, minV),
                corner(maxU, maxV), corner(minU, maxV),
            )
        }
    }
    return best
}

/**
 * PaddleOCR's "unclip": DB predicts a SHRUNK text region, so every box must be dilated back out by
 * `area * ratio / perimeter` before the crop is taken.
 *
 * This must expand the rectangle along its OWN axes — pushing the corners away from the centre is
 * wrong and the failure is silent. A text line is extremely elongated (a 504x14 box is typical), so
 * the centre-to-corner diagonal is very nearly horizontal; corner-wise expansion therefore adds
 * almost nothing to the HEIGHT. The crop keeps only the x-height band, which decapitates every
 * ascender and diacritic: measured on the Phase 0 corpus it turned 'ä' into 'a' and 'ř' into 'r',
 * and made thin Latin lines recognise as nothing at all (32% CER against 1.3% once fixed).
 */
fun unclip(rect: OcrQuad, ratio: Float = 1.5f): OcrQuad {
    if (rect.size != 4) return rect

    var twiceArea = 0f
    var perimeter = 0f
    for (i in rect.indices) {
        val a = rect[i]
        val b = rect[(i + 1) % rect.size]
        twiceArea += a.x * b.y - b.x * a.y
        perimeter += distance(a, b)
    }
    if (perimeter < 1e-6f) return rect
    val distanceOut = abs(twiceArea) / 2f * ratio / perimeter

    val centreX = rect.sumOf { it.x.toDouble() }.toFloat() / rect.size
    val centreY = rect.sumOf { it.y.toDouble() }.toFloat() / rect.size
    val lengthU = distance(rect[0], rect[1])
    val lengthV = distance(rect[1], rect[2])
    if (lengthU < 1e-6f || lengthV < 1e-6f) return rect

    val ux = (rect[1].x - rect[0].x) / lengthU
    val uy = (rect[1].y - rect[0].y) / lengthU
    val vx = (rect[2].x - rect[1].x) / lengthV
    val vy = (rect[2].y - rect[1].y) / lengthV
    val halfU = lengthU / 2f + distanceOut
    val halfV = lengthV / 2f + distanceOut

    fun at(su: Float, sv: Float) = OcrPoint(
        centreX + ux * halfU * su + vx * halfV * sv,
        centreY + uy * halfU * su + vy * halfV * sv,
    )
    return listOf(at(-1f, -1f), at(1f, -1f), at(1f, 1f), at(-1f, 1f))
}

/** Corners as (top-left, top-right, bottom-right, bottom-left), for the crop. */
fun orderQuad(quad: OcrQuad): OcrQuad {
    val byX = quad.sortedBy { it.x }
    val left = byX.take(2).sortedBy { it.y }
    val right = byX.drop(2).sortedBy { it.y }
    return listOf(left[0], right[0], right[1], left[1])
}
