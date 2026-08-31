package com.opentasker.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which line gets marked, and why. Measured on the Phase 0 corpus: the mean confidence over a line is
 * near-useless as a discriminator (worst correct 0.918 vs worst wrong 0.932, one wrong line at 0.983),
 * so the marker reads the least sure single CHARACTER instead, at a threshold picked for recall.
 */
class OcrConfidenceTest {

    private fun block(line: Int, lowest: Float, mean: Float = 0.99f, text: String = "x") =
        OcrBlock(
            text = text,
            confidence = mean,
            lowestCharacter = lowest,
            quad = listOf(OcrPoint(0f, 0f), OcrPoint(1f, 0f), OcrPoint(1f, 1f), OcrPoint(0f, 1f)),
            lineIndex = line,
            start = 0,
            end = text.length,
        )

    private fun result(vararg blocks: OcrBlock) =
        OcrResult(blocks.toList(), "", OcrScript.JAPANESE, 0L)

    @Test
    fun `a line takes its weakest character, not its average`() {
        // The real case: "Bluetooth、NFC、キス卜、印刷" scored 0.964 on the MEAN and was wrong. The bad
        // word's weakest character is what gives it away.
        val line = result(
            block(0, lowest = 0.99f), block(0, lowest = 0.98f),
            block(0, lowest = 0.70f), block(0, lowest = 0.99f),
        ).lineConfidences()

        assertEquals(1, line.size)
        assertEquals(0.70f, line[0], 0.0001f)
        assertEquals(OcrTrust.CHECK, OcrTrust.of(line[0]))
    }

    @Test
    fun `the threshold sits above the worst wrong line seen`() {
        // 0.702 was the weakest character of the worst-scoring wrong line; 0.72 catches it.
        assertEquals(OcrTrust.CHECK, OcrTrust.of(0.702f))
        assertEquals(OcrTrust.CHECK, OcrTrust.of(0.536f))
        assertEquals(OcrTrust.SOLID, OcrTrust.of(OcrTrust.CHECK_BELOW))
        assertEquals(OcrTrust.SOLID, OcrTrust.of(0.99f))
    }

    @Test
    fun `confidences come back in line order regardless of block order`() {
        val confidences = result(
            block(2, lowest = 0.60f), block(0, lowest = 0.99f), block(1, lowest = 0.80f),
        ).lineConfidences()

        assertEquals(listOf(0.99f, 0.80f, 0.60f), confidences.map { "%.2f".format(it).toFloat() })
    }

    @Test
    fun `the count is what the status line reports`() {
        val counted = result(
            block(0, lowest = 0.99f),   // solid
            block(1, lowest = 0.60f),   // check
            block(2, lowest = 0.71f),   // check
            block(3, lowest = 0.97f),   // solid
        ).linesToCheck()

        assertEquals(2, counted)
    }

    @Test
    fun `a line the recogniser never claimed anything about is not marked`() {
        val confidences = result(block(0, lowest = 0.99f), block(2, lowest = 0.20f)).lineConfidences()

        assertEquals(3, confidences.size)
        assertEquals(1f, confidences[1], 0.0001f)
        assertEquals(OcrTrust.SOLID, OcrTrust.of(confidences[1]))
    }

    @Test
    fun `no blocks means nothing to shade`() {
        assertEquals(emptyList<Float>(), result().lineConfidences())
        assertEquals(0, result().linesToCheck())
    }
}
