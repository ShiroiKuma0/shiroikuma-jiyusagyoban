package com.opentasker.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dictionary, CTC and reading-order conventions — each one a way to produce fluent nonsense. */
class OcrPipelineTest {

    // ---------------------------------------------------------------- dictionary

    @Test
    fun `charset is blank plus dictionary plus space`() {
        val charset = OcrCharset.parse("a\nb\nc\n")

        assertEquals(5, charset.size)              // 3 entries + blank + space
        assertEquals(OcrCharset.BLANK, charset[0])
        assertEquals("a", charset[1])
        assertEquals(" ", charset.last())
    }

    @Test
    fun `charset strips the carriage returns of a CRLF dictionary`() {
        // The mirror the Japanese dictionary comes from ships CRLF. Without the strip every recognised
        // character carries a stray '\r' — the text looks right in a log and is wrong in the clipboard.
        val charset = OcrCharset.parse("一\r\n乙\r\n二\r\n")

        assertEquals(listOf(OcrCharset.BLANK, "一", "乙", "二", " "), charset)
        assertTrue(charset.none { it.contains('\r') })
    }

    @Test
    fun `charset ignores only the final newline`() {
        // A dictionary whose last line is a real entry must not lose it, and a trailing blank line must
        // not become a phantom class — either way the class count stops matching the model.
        assertEquals(3, OcrCharset.parse("x").size)
        assertEquals(3, OcrCharset.parse("x\n").size)
        assertEquals(4, OcrCharset.parse("x\n\n").size)
    }

    // ---------------------------------------------------------------- CTC

    /**
     * One row per timestep, already a probability distribution — which is what the PP-OCRv5 recognition
     * graphs emit (every row sums to 1.0). Feeding logits here would not catch the bug that mattered:
     * softmaxing an already-softmaxed row pins every confidence at ~1/classes without touching the text.
     */
    private fun probabilities(vararg steps: Int, classes: Int, winner: Float = 0.97f): FloatArray {
        val out = FloatArray(steps.size * classes)
        val rest = (1f - winner) / (classes - 1)
        steps.forEachIndexed { step, best ->
            for (klass in 0 until classes) out[step * classes + klass] = rest
            out[step * classes + best] = winner
        }
        return out
    }

    @Test
    fun `decode collapses a character held across timesteps`() {
        val charset = OcrCharset.parse("a\nb\n")            // [blank, a, b, ' ']
        val decoded = CtcDecoder.decode(probabilities(1, 1, 1, 2, 2, classes = 4), 5, 4, charset)

        assertEquals("ab", decoded.text)
    }

    @Test
    fun `decode keeps a double letter separated by a blank`() {
        // 'aa' only survives because a blank sits between the two runs — this is the whole reason CTC
        // has a blank class, and dropping the rule silently turns "111" into "11".
        val charset = OcrCharset.parse("a\nb\n")
        val decoded = CtcDecoder.decode(probabilities(1, 0, 1, classes = 4), 3, 4, charset)

        assertEquals("aa", decoded.text)
    }

    @Test
    fun `decode drops blanks and reports a confidence`() {
        val charset = OcrCharset.parse("a\nb\n")
        val decoded = CtcDecoder.decode(probabilities(0, 0, 1, 0, classes = 4), 4, 4, charset)

        assertEquals("a", decoded.text)
        // Read straight off the graph, not softmaxed a second time.
        assertEquals(0.97f, decoded.confidence, 0.001f)
        assertEquals(0.97f, decoded.lowestCharacter, 0.001f)
    }

    @Test
    fun `decode of pure blanks yields nothing rather than a stray character`() {
        val charset = OcrCharset.parse("a\nb\n")
        val decoded = CtcDecoder.decode(probabilities(0, 0, 0, classes = 4), 3, 4, charset)

        assertEquals("", decoded.text)
        assertEquals(0f, decoded.confidence, 0.0001f)
        assertEquals(0f, decoded.lowestCharacter, 0.0001f)
    }

    // ---------------------------------------------------------------- reading order

    private fun box(left: Float, top: Float, width: Float, height: Float, text: String) =
        ReadingOrder.Candidate(
            text, 1f, 1f,
            listOf(
                OcrPoint(left, top), OcrPoint(left + width, top),
                OcrPoint(left + width, top + height), OcrPoint(left, top + height),
            ),
        )

    @Test
    fun `horizontal text reads top to bottom then left to right`() {
        val (_, text) = ReadingOrder.assemble(
            listOf(
                box(200f, 100f, 80f, 20f, "world"),
                box(10f, 100f, 80f, 20f, "hello "),
                box(10f, 200f, 80f, 20f, "second"),
            ),
            vertical = false,
        )
        assertEquals("hello world\nsecond", text)
    }

    @Test
    fun `vertical japanese reads columns right to left`() {
        // Three columns of vertical text. Left-to-right ordering recognises every column perfectly and
        // still reverses the sentence — measured at 67% CER in Phase 0 with a flawless recogniser.
        val (_, text) = ReadingOrder.assemble(
            listOf(
                box(10f, 10f, 26f, 180f, "どこで生れたか"),
                box(100f, 10f, 26f, 180f, "吾輩は猫である"),
                box(55f, 10f, 26f, 180f, "名前はまだ無い"),
            ),
            vertical = true,
        )
        assertEquals("吾輩は猫である\n名前はまだ無い\nどこで生れたか", text)
    }

    @Test
    fun `blocks carry the offsets that map a tap to the cursor`() {
        val (blocks, text) = ReadingOrder.assemble(
            listOf(box(10f, 10f, 50f, 20f, "abc"), box(100f, 10f, 50f, 20f, "de")),
            vertical = false,
        )
        assertEquals("abcde", text)
        assertEquals(0, blocks[0].start)
        assertEquals(3, blocks[0].end)
        assertEquals(3, blocks[1].start)
        assertEquals(5, blocks[1].end)
        blocks.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
    }

    @Test
    fun `a new line starts when the vertical gap exceeds the tolerance`() {
        val (blocks, _) = ReadingOrder.assemble(
            listOf(box(10f, 10f, 50f, 20f, "one"), box(10f, 60f, 50f, 20f, "two")),
            vertical = false,
        )
        assertNotEquals(blocks[0].lineIndex, blocks[1].lineIndex)
    }

    // ---------------------------------------------------------------- image

    @Test
    fun `rotate90 counter clockwise puts the top edge on the left`() {
        // 2x1 image, distinct pixels: [A B] becomes a 1x2 column reading B over A, which is what makes
        // a vertical Japanese column read left-to-right after the turn.
        val a = 0xFF112233.toInt()
        val b = 0xFF445566.toInt()
        val rotated = OcrImage(intArrayOf(a, b), width = 2, height = 1).rotate90CounterClockwise()

        assertEquals(1, rotated.width)
        assertEquals(2, rotated.height)
        assertEquals(b, rotated.pixels[0])
        assertEquals(a, rotated.pixels[1])
    }

    @Test
    fun `detection size caps the long side and snaps to the stride`() {
        val (width, height) = OcrImage(IntArray(2048 * 1100), 2048, 1100).detectionSize(1600)

        assertEquals(0, width % OcrImage.DETECTION_STRIDE)
        assertEquals(0, height % OcrImage.DETECTION_STRIDE)
        assertTrue("long side $width should be near 1600", width in 1568..1600)
    }

    @Test
    fun `a small image is not upscaled by the detection limit`() {
        val (width, height) = OcrImage(IntArray(300 * 100), 300, 100).detectionSize(1600)

        assertTrue("$width x $height should stay near its own size", width in 288..320 && height in 96..128)
    }

    @Test
    fun `labelComponents separates two blobs and merges one U shape`() {
        // 5x3 map. Left blob is a U (two columns joined along the bottom row) which naive one-pass
        // labelling splits in two; the right blob is genuinely separate.
        val w = 5
        val h = 3
        val probability = FloatArray(w * h)
        fun set(x: Int, y: Int) { probability[y * w + x] = 1f }
        set(0, 0); set(0, 1); set(0, 2); set(1, 2); set(2, 2); set(2, 0); set(2, 1)
        set(4, 0)

        val labels = DbPostProcess.labelComponents(probability, w, h, threshold = 0.3f)

        assertEquals(2, labels.count)
        assertEquals(labels.ids[0], labels.ids[2])           // (0,0) and (2,0) joined via the bottom
        assertNotEquals(labels.ids[0], labels.ids[4])        // the lone pixel at (4,0) is its own blob
    }
}
