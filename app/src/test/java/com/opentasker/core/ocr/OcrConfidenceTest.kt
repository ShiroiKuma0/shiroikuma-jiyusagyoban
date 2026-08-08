package com.opentasker.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which line gets marked, and why. The point of the marker is that 白い熊 can stop re-reading whole
 * screenshots to find the 5 % of characters the Latin and Cyrillic models get wrong, so a line that
 * contains a mistake must not be able to hide behind three good ones next to it.
 */
class OcrConfidenceTest {

    private fun block(line: Int, confidence: Float, text: String = "x") =
        OcrBlock(
            text = text,
            confidence = confidence,
            quad = listOf(OcrPoint(0f, 0f), OcrPoint(1f, 0f), OcrPoint(1f, 1f), OcrPoint(0f, 1f)),
            lineIndex = line,
            start = 0,
            end = text.length,
        )

    private fun result(vararg blocks: OcrBlock) =
        OcrResult(blocks.toList(), "", OcrScript.JAPANESE, 0L)

    @Test
    fun `a line takes its worst block, not its average`() {
        // "Bluetooth、NFC、キス卜、印刷" — one bad word in four. The mean would read as solid and the
        // marker would point at nothing, which is the whole failure this guards against.
        val line = result(
            block(0, 0.99f), block(0, 0.99f), block(0, 0.62f), block(0, 0.99f),
        ).lineConfidences()

        assertEquals(1, line.size)
        assertEquals(0.62f, line[0], 0.0001f)
        assertEquals(OcrTrust.DOUBTFUL, OcrTrust.of(line[0]))
    }

    @Test
    fun `confidences come back in line order regardless of block order`() {
        val confidences = result(
            block(2, 0.80f), block(0, 0.99f), block(1, 0.93f),
        ).lineConfidences()

        assertEquals(listOf(0.99f, 0.93f, 0.80f), confidences.map { "%.2f".format(it).toFloat() })
    }

    @Test
    fun `the bands sit either side of where real mistakes landed`() {
        // Measured on the Phase 0 corpus: clean lines above ~0.95, every line with an actual error
        // below ~0.90.
        assertEquals(OcrTrust.SOLID, OcrTrust.of(0.99f))
        assertEquals(OcrTrust.SOLID, OcrTrust.of(OcrTrust.SOLID_ABOVE))
        assertEquals(OcrTrust.UNSURE, OcrTrust.of(0.93f))
        assertEquals(OcrTrust.UNSURE, OcrTrust.of(OcrTrust.UNSURE_ABOVE))
        assertEquals(OcrTrust.DOUBTFUL, OcrTrust.of(0.89f))
        assertEquals(OcrTrust.DOUBTFUL, OcrTrust.of(0f))
    }

    @Test
    fun `the doubtful count is what the status line reports`() {
        val counted = result(
            block(0, 0.99f),   // solid
            block(1, 0.93f),   // unsure    -> counted
            block(2, 0.40f),   // doubtful  -> counted
            block(3, 0.97f),   // solid
        ).doubtfulLineCount()

        assertEquals(2, counted)
    }

    @Test
    fun `a line the recogniser never claimed anything about is not marked`() {
        // Only reachable through a caller-side edit. Marking a line we know nothing about would be
        // pointing at noise.
        val confidences = result(block(0, 0.99f), block(2, 0.20f)).lineConfidences()

        assertEquals(3, confidences.size)
        assertEquals(1f, confidences[1], 0.0001f)
        assertEquals(OcrTrust.SOLID, OcrTrust.of(confidences[1]))
    }

    @Test
    fun `no blocks means nothing to shade`() {
        assertEquals(emptyList<Float>(), result().lineConfidences())
        assertEquals(0, result().doubtfulLineCount())
    }
}
