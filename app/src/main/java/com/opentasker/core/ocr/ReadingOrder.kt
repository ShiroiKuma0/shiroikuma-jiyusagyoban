package com.opentasker.core.ocr

import kotlin.math.abs
import kotlin.math.max

/** One recognised piece of text and where it sat on the page. */
data class OcrBlock(
    val text: String,
    val confidence: Float,
    /** The least sure single character in this block — what the "worth checking" marker reads. */
    val lowestCharacter: Float,
    val quad: OcrQuad,
    /** Which output line this block belongs to. */
    val lineIndex: Int,
    /** Where this block's text starts in [OcrResult.text] — lets a tap on the image move the cursor. */
    val start: Int,
    val end: Int,
)

/** Everything one recognition pass produced. */
data class OcrResult(
    val blocks: List<OcrBlock>,
    val text: String,
    val script: OcrScript,
    val elapsedMs: Long,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    companion object {
        fun empty(script: OcrScript, elapsedMs: Long) = OcrResult(emptyList(), "", script, elapsedMs)
    }
}

/**
 * Orders recognised boxes the way the script is actually read, and joins them into one text.
 *
 * Horizontal: group by vertical overlap, lines top-to-bottom, boxes left-to-right.
 * Vertical (Japanese): group by HORIZONTAL overlap, columns RIGHT-to-left, boxes top-to-bottom.
 *
 * The vertical case is not a detail. Measured in Phase 0, the vertical sample recognised all three
 * columns perfectly and still scored 67% CER — entirely from being emitted left-to-right.
 */
object ReadingOrder {

    /** A recognised box before it has been placed in the reading order. */
    data class Candidate(
        val text: String,
        val confidence: Float,
        val lowestCharacter: Float,
        val quad: OcrQuad,
    )

    fun assemble(candidates: List<Candidate>, vertical: Boolean): Pair<List<OcrBlock>, String> {
        if (candidates.isEmpty()) return emptyList<OcrBlock>() to ""

        val measured = candidates.map { candidate ->
            val xs = candidate.quad.map { it.x }
            val ys = candidate.quad.map { it.y }
            Measured(
                candidate = candidate,
                left = xs.min(), top = ys.min(),
                centreX = (xs.min() + xs.max()) / 2f,
                centreY = (ys.min() + ys.max()) / 2f,
                width = xs.max() - xs.min(),
                height = ys.max() - ys.min(),
            )
        }

        // The grouping axis, the along-the-line axis and the sort direction all flip together.
        val grouped = measured
            .sortedBy { if (vertical) -it.centreX else it.centreY }
            .fold(mutableListOf<MutableList<Measured>>()) { lines, entry ->
                val current = lines.lastOrNull()
                val reference = current?.last()
                val sameLine = reference != null && run {
                    val extent = if (vertical) max(reference.width, entry.width) else max(reference.height, entry.height)
                    val delta = if (vertical) abs(entry.centreX - reference.centreX) else abs(entry.centreY - reference.centreY)
                    delta <= extent * LINE_GROUPING_TOLERANCE
                }
                if (sameLine) current!!.add(entry) else lines.add(mutableListOf(entry))
                lines
            }
            .map { line -> line.sortedBy { if (vertical) it.top else it.left } }

        val blocks = ArrayList<OcrBlock>(candidates.size)
        val text = StringBuilder()
        grouped.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) text.append('\n')
            line.forEach { entry ->
                val start = text.length
                text.append(entry.candidate.text)
                blocks += OcrBlock(
                    text = entry.candidate.text,
                    confidence = entry.candidate.confidence,
                    lowestCharacter = entry.candidate.lowestCharacter,
                    quad = entry.candidate.quad,
                    lineIndex = lineIndex,
                    start = start,
                    end = text.length,
                )
            }
        }
        return blocks to text.toString()
    }

    /** Fraction of the larger box's extent within which two boxes count as the same line. */
    private const val LINE_GROUPING_TOLERANCE = 0.5f

    private data class Measured(
        val candidate: Candidate,
        val left: Float,
        val top: Float,
        val centreX: Float,
        val centreY: Float,
        val width: Float,
        val height: Float,
    )
}
