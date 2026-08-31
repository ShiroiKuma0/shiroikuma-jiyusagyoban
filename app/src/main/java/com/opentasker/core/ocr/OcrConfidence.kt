package com.opentasker.core.ocr

/**
 * Which lines are worth checking against the image — a hint, and honest about being one.
 *
 * ## What was measured, on the Phase 0 corpus (48 lines, 5 of them containing a real error)
 *
 * The **mean** confidence over a line barely separates right from wrong at all: the worst correct line
 * scored 0.918 against the worst wrong line's 0.932, and one wrong line came in at 0.983. A marker
 * built on the mean is close to noise.
 *
 * The **least sure single character** in the line does better, but the tails still overlap — correct
 * lines routinely contain one character at 0.54. At a 0.72 cut it catches **all five** wrong lines and
 * also flags about six correct ones.
 *
 * So this is deliberately a *recall* instrument with one threshold, not a three-level verdict: it turns
 * "re-read all 48 lines" into "look at these 11, all five mistakes are in there". Roughly one false
 * alarm per real error is the price, and calling it anything more confident than a hint would be a lie
 * about what the numbers support.
 *
 * The reason a confident-and-wrong reading is possible at all: CTC confidence says how sure the model
 * was of the class it picked, not whether that class was right. 「キス卜」 for 「キャスト」 scored 0.964
 * on the mean — it was sure, and wrong.
 */
enum class OcrTrust {
    /** Nothing stood out. Not a guarantee it is right. */
    SOLID,

    /** Contains at least one character the model was unsure of — worth a glance at the image. */
    CHECK,
    ;

    companion object {
        /**
         * Below this, the line contains a character the model hesitated on.
         *
         * 0.72 because it sits just above the highest weakest-character seen in a wrong line (0.702,
         * the 「キス卜」 line) — chosen for recall, since a missed error costs more than a second look.
         */
        const val CHECK_BELOW = 0.72f

        fun of(lowestCharacter: Float): OcrTrust =
            if (lowestCharacter < CHECK_BELOW) CHECK else SOLID
    }
}

/**
 * Per output line, the least sure character anywhere on it.
 *
 * The worst block on the line, and within that block the worst character — a line is only as trustworthy
 * as its weakest point. Averaging hides exactly what the marker exists to point at: 「Bluetooth、NFC、
 * キス卜、印刷」 is one bad word in four, and its mean reads as solid.
 *
 * Indexed by [OcrBlock.lineIndex], so it lines up with splitting [OcrResult.text] on newlines.
 */
fun OcrResult.lineConfidences(): List<Float> {
    if (blocks.isEmpty()) return emptyList()
    val worst = HashMap<Int, Float>(blocks.size)
    for (block in blocks) {
        val current = worst[block.lineIndex]
        if (current == null || block.lowestCharacter < current) worst[block.lineIndex] = block.lowestCharacter
    }
    val lastLine = worst.keys.max()
    // A line with no block at all could only come from a caller-side edit; treat it as solid rather
    // than marking something the recogniser never claimed anything about.
    return (0..lastLine).map { worst[it] ?: 1f }
}

/** How many output lines are worth a look — the count the status line reports. */
fun OcrResult.linesToCheck(): Int =
    lineConfidences().count { OcrTrust.of(it) == OcrTrust.CHECK }
