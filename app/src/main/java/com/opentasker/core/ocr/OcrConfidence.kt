package com.opentasker.core.ocr

/**
 * How sure the recogniser was, turned into something you can act on.
 *
 * The CTC decoder already reports a per-line confidence — the mean softmax over the characters it
 * actually emitted. On its own that number sits in a data class and helps nobody. What 白い熊 needs is
 * the answer to "which line should I check?", because the Latin and Cyrillic models are right about
 * 93–95 % of characters and finding the other 5 % by eye means re-reading everything.
 */
enum class OcrTrust {
    /** Read it and move on. */
    SOLID,

    /** Worth a glance against the image. */
    UNSURE,

    /** Probably wrong somewhere. */
    DOUBTFUL,
    ;

    companion object {
        /**
         * Thresholds, from the Phase 0 corpus rather than from taste: on the samples that scored 0.00 %
         * character error the per-line confidence sat above ~0.95, and every line that actually
         * contained a mistake — `キス卜` for `キャスト`, the stripped Czech and Polish diacritics — came
         * in under ~0.90. Two bands either side of that give a marker that means something.
         */
        const val SOLID_ABOVE = 0.95f
        const val UNSURE_ABOVE = 0.90f

        fun of(confidence: Float): OcrTrust = when {
            confidence >= SOLID_ABOVE -> SOLID
            confidence >= UNSURE_ABOVE -> UNSURE
            else -> DOUBTFUL
        }
    }
}

/**
 * The confidence of each output line, in line order.
 *
 * A line is the **worst** block on it, not the average. A line reading "Bluetooth、NFC、キス卜、印刷" is
 * one bad word in four; averaging hides exactly the thing the marker exists to point at, and there is
 * no cost to being pessimistic here — the marker only ever says "look at this".
 *
 * Indexed by [OcrBlock.lineIndex], so the result lines up with splitting [OcrResult.text] on newlines.
 */
fun OcrResult.lineConfidences(): List<Float> {
    if (blocks.isEmpty()) return emptyList()
    val worst = HashMap<Int, Float>(blocks.size)
    for (block in blocks) {
        val current = worst[block.lineIndex]
        if (current == null || block.confidence < current) worst[block.lineIndex] = block.confidence
    }
    val lastLine = worst.keys.max()
    // Any line with no block at all could only come from a caller-side edit; treat it as solid rather
    // than marking a line the recogniser never claimed anything about.
    return (0..lastLine).map { worst[it] ?: 1f }
}

/** How many output lines are worth checking — the count the status line reports. */
fun OcrResult.doubtfulLineCount(): Int =
    lineConfidences().count { OcrTrust.of(it) != OcrTrust.SOLID }
