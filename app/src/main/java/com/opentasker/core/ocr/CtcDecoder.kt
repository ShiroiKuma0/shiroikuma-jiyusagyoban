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

    /** A decoded line and how sure the model was, averaged over the characters it actually emitted. */
    data class Decoded(val text: String, val confidence: Float)

    /**
     * @param logits one row of the batch, `[timesteps, classes]` flattened row-major
     * @param charset from [OcrCharset.parse] — index 0 is the blank
     */
    fun decode(logits: FloatArray, timesteps: Int, classes: Int, charset: List<String>): Decoded {
        require(logits.size >= timesteps * classes) {
            "logits ${logits.size} < ${timesteps}x$classes"
        }

        val builder = StringBuilder()
        var confidenceSum = 0f
        var emitted = 0
        var previous = -1

        for (step in 0 until timesteps) {
            val base = step * classes
            var bestIndex = 0
            var bestLogit = Float.NEGATIVE_INFINITY
            for (klass in 0 until classes) {
                val value = logits[base + klass]
                if (value > bestLogit) {
                    bestLogit = value
                    bestIndex = klass
                }
            }

            // A repeat of the previous class is one character held across timesteps, not a second one;
            // the blank is the separator that lets a genuine double letter through.
            if (bestIndex != previous && bestIndex != 0) {
                builder.append(charset.getOrElse(bestIndex) { "" })
                confidenceSum += softmaxAt(logits, base, classes, bestIndex, bestLogit)
                emitted++
            }
            previous = bestIndex
        }

        return Decoded(
            text = builder.toString(),
            confidence = if (emitted == 0) 0f else confidenceSum / emitted,
        )
    }

    /** Softmax of one class, computed with the max subtracted so a large logit cannot overflow. */
    private fun softmaxAt(logits: FloatArray, base: Int, classes: Int, index: Int, maxLogit: Float): Float {
        var total = 0f
        for (klass in 0 until classes) total += exp(logits[base + klass] - maxLogit)
        return if (total <= 0f) 0f else (exp(logits[base + index] - maxLogit) / total)
    }
}
