package com.opentasker.core.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Turns the DB detector's probability map into text-line quads in ORIGINAL image coordinates.
 *
 * Pure arrays in, data classes out — no Android types, so the whole thing is unit-testable on the
 * JVM against fixtures taken from the Phase 0 reference pipeline.
 */
object DbPostProcess {

    /** Defaults are PaddleOCR's, and match what Phase 0 measured at 1.3% CER on the corpus. */
    const val BINARY_THRESHOLD = 0.3f
    const val BOX_SCORE_THRESHOLD = 0.6f
    const val UNCLIP_RATIO = 1.5f
    const val MIN_BOX_SIDE = 3f

    /** A detected text line: its quad in image space and the mean detector confidence inside it. */
    data class Box(val quad: OcrQuad, val score: Float)

    /**
     * @param probability the detector's `[1,1,H,W]` output, flattened row-major
     * @param scaleX/[scaleY] map detection-map coordinates back to the original image
     */
    fun boxes(
        probability: FloatArray,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
        originalWidth: Int,
        originalHeight: Int,
        binaryThreshold: Float = BINARY_THRESHOLD,
        boxScoreThreshold: Float = BOX_SCORE_THRESHOLD,
        unclipRatio: Float = UNCLIP_RATIO,
    ): List<Box> {
        val labels = labelComponents(probability, width, height, binaryThreshold)
        if (labels.count == 0) return emptyList()

        // One pass to bucket the pixels of every component, rather than rescanning per label.
        val points = Array(labels.count) { ArrayList<OcrPoint>() }
        val sums = FloatArray(labels.count)
        val counts = IntArray(labels.count)
        for (index in labels.ids.indices) {
            val label = labels.ids[index]
            if (label == 0) continue
            val slot = label - 1
            points[slot] += OcrPoint((index % width).toFloat(), (index / width).toFloat())
            sums[slot] += probability[index]
            counts[slot]++
        }

        val out = ArrayList<Box>(labels.count)
        for (slot in 0 until labels.count) {
            if (counts[slot] < 4) continue
            val rect = minAreaRect(points[slot])
            if (rect.size != 4) continue
            val sideA = distance(rect[0], rect[1])
            val sideB = distance(rect[1], rect[2])
            if (min(sideA, sideB) < MIN_BOX_SIDE) continue

            val score = sums[slot] / counts[slot]
            if (score < boxScoreThreshold) continue

            val quad = unclip(rect, unclipRatio).map { point ->
                OcrPoint(
                    (point.x * scaleX).coerceIn(0f, (originalWidth - 1).toFloat()),
                    (point.y * scaleY).coerceIn(0f, (originalHeight - 1).toFloat()),
                )
            }
            out += Box(quad, score)
        }
        return out
    }

    /** Labels 1..[count] per pixel (0 = background), parallel to the input. */
    data class Labels(val ids: IntArray, val count: Int) {
        // Identity equality is the sensible contract for a big scratch buffer; the generated
        // array-based one would compare megabytes and is never what a caller wants.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Two-pass scanline connected-component labelling, 4-connectivity, with union-find.
     *
     * Chosen over a flood fill because it is a pair of flat loops over an IntArray with no recursion
     * and no queue — on a 1600x2000 map that difference is the difference between smooth and janky.
     */
    fun labelComponents(
        probability: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = BINARY_THRESHOLD,
    ): Labels {
        val ids = IntArray(width * height)
        // parent[0] is the background sentinel so a real label is always >= 1.
        val parent = ArrayList<Int>(64).apply { add(0) }

        fun find(start: Int): Int {
            var root = start
            while (parent[root] != root) root = parent[root]
            var walk = start
            while (parent[walk] != root) {
                val next = parent[walk]
                parent[walk] = root
                walk = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[max(rootA, rootB)] = min(rootA, rootB)
        }

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                if (probability[index] <= threshold) continue
                val up = if (y > 0) ids[index - width] else 0
                val left = if (x > 0) ids[index - 1] else 0
                when {
                    up != 0 && left != 0 -> {
                        ids[index] = min(up, left)
                        union(up, left)
                    }
                    up != 0 -> ids[index] = up
                    left != 0 -> ids[index] = left
                    else -> {
                        parent.add(parent.size)
                        ids[index] = parent.size - 1
                    }
                }
            }
        }

        // Second pass: collapse every pixel onto its root and renumber the roots 1..n so callers can
        // index arrays by label without a map.
        val renumbered = HashMap<Int, Int>()
        for (index in ids.indices) {
            val label = ids[index]
            if (label == 0) continue
            val root = find(label)
            ids[index] = renumbered.getOrPut(root) { renumbered.size + 1 }
        }
        return Labels(ids, renumbered.size)
    }
}
