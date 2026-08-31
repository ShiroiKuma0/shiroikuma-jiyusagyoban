package com.opentasker.core.ocr

import kotlin.math.exp

/**
 * Greedy CTC decoding of one recognised line.
 *
 * The models emit raw logits over `dictionary.size + 2` classes per timestep. Greedy decoding — take
 * the argmax, collapse runs of the same class, drop the blank — is what PaddleOCR itself ships; beam
 * search buys very little on printed text and costs a lot per line.
 */
object CtcDecoder {

    /**
     * A decoded line, with both summaries of how sure the model was.
     *
     * [confidence] is the mean over emitted characters. [lowestCharacter] is the least sure single
     * character in the line, which is the one worth surfacing: measured on the Phase 0 corpus the mean
     * barely separates a correct line from a wrong one (worst correct 0.918 against worst wrong 0.932,
     * and one wrong line scored 0.983), while the weakest character does far better at the median.
     */
    data class Decoded(val text: String, val confidence: Float, val lowestCharacter: Float)

    /**
     * @param probabilities one row of the batch, `[timesteps, classes]` flattened row-major. The
     *   PP-OCRv5 recognition graphs end in a softmax — measured: every timestep's row sums to 1.0 —
     *   so these are probabilities already and must NOT be softmaxed again.
     * @param charset from [OcrCharset.parse] — index 0 is the blank
     */
    fun decode(
        probabilities: FloatArray,
        timesteps: Int,
        classes: Int,
        charset: List<String>,
    ): Decoded {
        require(probabilities.size >= timesteps * classes) {
            "probabilities ${probabilities.size} < ${timesteps}x$classes"
        }

        val builder = StringBuilder()
        var confidenceSum = 0f
        var lowest = 1f
        var emitted = 0
        var previous = -1

        for (step in 0 until timesteps) {
            val base = step * classes
            var bestIndex = 0
            var bestProbability = Float.NEGATIVE_INFINITY
            for (klass in 0 until classes) {
                val value = probabilities[base + klass]
                if (value > bestProbability) {
                    bestProbability = value
                    bestIndex = klass
                }
            }

            // A repeat of the previous class is one character held across timesteps, not a second one;
            // the blank is the separator that lets a genuine double letter through.
            if (bestIndex != previous && bestIndex != 0) {
                builder.append(charset.getOrElse(bestIndex) { "" })
                // Taken straight from the graph. Applying softmax here as well — which this did until
                // 2026-08-08 — spreads a row that already sums to 1 across 18 385 classes and pins every
                // confidence at about 1/18385. It never touched the TEXT, because argmax survives any
                // monotonic transform, so it stayed invisible until the confidence was put on screen and
                // every single line came back marked as doubtful.
                val probability = bestProbability.coerceIn(0f, 1f)
                confidenceSum += probability
                if (probability < lowest) lowest = probability
                emitted++
            }
            previous = bestIndex
        }

        return Decoded(
            text = builder.toString(),
            confidence = if (emitted == 0) 0f else confidenceSum / emitted,
            lowestCharacter = if (emitted == 0) 0f else lowest,
        )
    }
}
