package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrImage
import com.opentasker.core.ocr.OcrQuad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a recognised line LOOKS like, measured from its own pixels: how heavy the strokes are and
 * whether they lean.
 *
 * Both numbers are read off the line while its slice is still decoded, because that is the only
 * moment the pixels exist — see [LongPage]. Neither is a decision; the decision needs the whole page
 * (a line is bold relative to its neighbours, not in the abstract) and is made in [ArticleLayout].
 *
 * Measured on the two sample pages:
 *
 * | line                    | stroke | shear |
 * |-------------------------|--------|-------|
 * | body text (x3)          | 0.148–0.151 | 0.00 |
 * | deck                    | 0.155  | 0.00  |
 * | caption                 | 0.130  | 0.00  |
 * | "June 29, 2021" (roman) | 0.166  | 0.00  |
 * | "ESSAY" (bold)          | 0.203  | 0.00  |
 * | "By Timothy Snyder" (bold) | 0.189 | 0.00 |
 * | "Listen to This Article" (bold) | 0.275 | 0.00 |
 * | the Audm note (italic, 2 lines) | 0.153 / 0.141 | 0.27 |
 *
 * The shear separates completely — every italic line lands on 0.27 and every upright one on 0.00.
 * The stroke does not: the heaviest roman line here (0.166) is closer to the lightest bold (0.189)
 * than the spread within body text would suggest, which is why bold is decided against a cohort of
 * similarly-sized lines and with a deliberately wide margin rather than a fixed threshold.
 */
object LineStyle {

    /**
     * @param stroke mean stroke width divided by the line's ink height — a size-free weight
     * @param shear the lean that best straightens the line, positive for a forward (italic) slope
     * @param inkHeight top of the tallest glyph to the bottom of the lowest, in page pixels
     */
    data class Probe(val stroke: Float, val shear: Float, val inkHeight: Int)

    val NONE = Probe(0f, 0f, 0)

    /** Shears tried, as a fraction of the line's height. Italic type here measures about 0.27. */
    private const val SHEAR_MIN = -0.05f
    private const val SHEAR_MAX = 0.40f
    private const val SHEAR_STEP = 0.05f

    /** Ink is anything past halfway between the background and the line's own darkest/brightest. */
    private const val INK_OF_PEAK = 0.5f

    /** Runs longer than this fraction of the height are rules and serif bars, not stems. */
    private const val STROKE_CAP = 0.35f

    /** Rows sampled at most, for a headline 193 px tall. */
    private const val ROWS_SAMPLED = 48

    fun of(image: OcrImage, quad: OcrQuad, background: Int): Probe {
        val x0 = max(0, quad.minOf { it.x }.roundToInt())
        val x1 = min(image.width - 1, quad.maxOf { it.x }.roundToInt())
        val y0 = max(0, quad.minOf { it.y }.roundToInt())
        val y1 = min(image.height - 1, quad.maxOf { it.y }.roundToInt())
        val width = x1 - x0 + 1
        val height = y1 - y0 + 1
        if (width < 4 || height < 4) return NONE

        // Adaptive, per line: grey small print and white bold print are equally far past their own
        // halfway mark, where a single fixed cut-off would thin one and fatten the other.
        val histogram = IntArray(256)
        for (y in y0..y1) {
            val row = y * image.width
            for (x in x0..x1) {
                histogram[abs(luma(image.pixels[row + x]) - background).coerceIn(0, 255)]++
            }
        }
        val total = width * height
        var seen = 0
        var peak = 0
        for (level in 255 downTo 0) {
            seen += histogram[level]
            if (seen * 200 >= total) {   // the 99.5th percentile of the distance from background
                peak = level
                break
            }
        }
        if (peak < 24) return NONE
        val threshold = (peak * INK_OF_PEAK).toInt()

        val mask = BooleanArray(width * height)
        for (y in 0 until height) {
            val source = (y0 + y) * image.width
            val destination = y * width
            for (x in 0 until width) {
                mask[destination + x] =
                    abs(luma(image.pixels[source + x0 + x]) - background) > threshold
            }
        }

        var firstRow = -1
        var lastRow = -1
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x]) {
                    if (firstRow < 0) firstRow = y
                    lastRow = y
                    break
                }
            }
        }
        val span = if (firstRow < 0) 0 else lastRow - firstRow + 1

        return Probe(
            stroke = if (span > 0) stroke(mask, width, height, span) else 0f,
            shear = shear(mask, width, height),
            inkHeight = span,
        )
    }

    /** Mean horizontal ink run, against the rows the ink actually occupies. */
    private fun stroke(mask: BooleanArray, width: Int, height: Int, span: Int): Float {
        val cap = max(3, (height * STROKE_CAP).toInt())
        var runTotal = 0L
        var runCount = 0L

        for (y in 0 until height) {
            val base = y * width
            var run = 0
            for (x in 0 until width) {
                if (mask[base + x]) {
                    run++
                } else {
                    if (run in 1..cap) { runTotal += run; runCount++ }
                    run = 0
                }
            }
            if (run in 1..cap) { runTotal += run; runCount++ }
        }
        if (runCount == 0L) return 0f
        return (runTotal.toFloat() / runCount) / span
    }

    /**
     * The lean that makes the stems line up.
     *
     * Straightening italic type piles its stems into the same columns, so the column-ink profile gets
     * as peaky as it is going to get; upright type is already there and any shear only smears it. The
     * score is the profile's summed squared share, which is exactly that peakiness and needs no
     * normalising for how much ink the line has.
     */
    private fun shear(mask: BooleanArray, width: Int, height: Int): Float {
        val step = max(1, height / ROWS_SAMPLED)
        val pad = (SHEAR_MAX * height).toInt() + 2
        val profile = FloatArray(width + 2 * pad)

        var best = -1f
        var bestShear = 0f
        var lean = SHEAR_MIN
        while (lean <= SHEAR_MAX + 1e-4f) {
            java.util.Arrays.fill(profile, 0f)
            var ink = 0f
            var y = 0
            while (y < height) {
                // A forward italic leans its top to the RIGHT, so straightening shifts the top LEFT.
                val shift = pad - (lean * (height - 1 - y)).roundToInt()
                val base = y * width
                for (x in 0 until width) {
                    if (mask[base + x]) {
                        val at = x + shift
                        if (at >= 0 && at < profile.size) {
                            profile[at] += 1f
                            ink += 1f
                        }
                    }
                }
                y += step
            }
            if (ink > 0f) {
                var score = 0f
                for (value in profile) {
                    val share = value / ink
                    score += share * share
                }
                if (score > best) { best = score; bestShear = lean }
            }
            lean += SHEAR_STEP
        }
        return bestShear
    }
}
