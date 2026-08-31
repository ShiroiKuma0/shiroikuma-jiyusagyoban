package com.opentasker.core.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A plain ARGB pixel buffer, laid out exactly like `Bitmap.getPixels` so the Android side is a single
 * copy at the edge and everything downstream — resizing, the perspective crop, both tensor encodings —
 * stays testable on the JVM without an emulator.
 */
class OcrImage(val pixels: IntArray, val width: Int, val height: Int) {

    init {
        require(pixels.size == width * height) { "pixels ${pixels.size} != ${width}x$height" }
    }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    /** Bilinear resample to an exact size. */
    fun resize(targetWidth: Int, targetHeight: Int): OcrImage {
        if (targetWidth == width && targetHeight == height) return this
        val out = IntArray(targetWidth * targetHeight)
        val scaleX = width.toFloat() / targetWidth
        val scaleY = height.toFloat() / targetHeight
        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (height - 1).toFloat())
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (width - 1).toFloat())
                out[y * targetWidth + x] = sampleBilinear(sourceX, sourceY)
            }
        }
        return OcrImage(out, targetWidth, targetHeight)
    }

    fun sampleBilinear(x: Float, y: Float): Int {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0

        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]

        fun blend(channel: (Int) -> Int): Int {
            val top = channel(p00) * (1 - fx) + channel(p10) * fx
            val bottom = channel(p01) * (1 - fx) + channel(p11) * fx
            return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (blend(::red) shl 16) or (blend(::green) shl 8) or blend(::blue)
    }

    /** Counter-clockwise quarter turn: the top edge becomes the left edge. */
    fun rotate90CounterClockwise(): OcrImage {
        val out = IntArray(pixels.size)
        val outWidth = height
        val outHeight = width
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                out[y * outWidth + x] = pixels[x * width + (width - 1 - y)]
            }
        }
        return OcrImage(out, outWidth, outHeight)
    }

    /** A text line lifted out of the page, upright and ready for recognition. */
    data class Line(val image: OcrImage, val wasVertical: Boolean)

    /**
     * Bilinear-sample a detected quad into an axis-aligned strip — a perspective warp, for four points.
     *
     * A strip taller than it is wide by 1.5x or more is a VERTICAL line (Japanese set top-to-bottom) and
     * is turned counter-clockwise, so that reading it left-to-right reproduces the original top-to-bottom
     * order. The recognition models only ever saw horizontal text.
     */
    fun cropQuad(quad: OcrQuad): Line {
        val (topLeft, topRight, bottomRight, bottomLeft) = orderQuad(quad)
        val outWidth = max(1, max(distance(topLeft, topRight), distance(bottomLeft, bottomRight)).roundToInt())
        val outHeight = max(1, max(distance(topLeft, bottomLeft), distance(topRight, bottomRight)).roundToInt())

        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val v = if (outHeight == 1) 0f else y.toFloat() / (outHeight - 1)
            for (x in 0 until outWidth) {
                val u = if (outWidth == 1) 0f else x.toFloat() / (outWidth - 1)
                val topX = topLeft.x + (topRight.x - topLeft.x) * u
                val topY = topLeft.y + (topRight.y - topLeft.y) * u
                val bottomX = bottomLeft.x + (bottomRight.x - bottomLeft.x) * u
                val bottomY = bottomLeft.y + (bottomRight.y - bottomLeft.y) * u
                out[y * outWidth + x] = sampleBilinear(
                    topX + (bottomX - topX) * v,
                    topY + (bottomY - topY) * v,
                )
            }
        }

        val crop = OcrImage(out, outWidth, outHeight)
        val vertical = outHeight.toFloat() / outWidth >= VERTICAL_ASPECT
        return Line(if (vertical) crop.rotate90CounterClockwise() else crop, vertical)
    }

    companion object {
        /** Height:width at which a detected strip is read as vertical text. PaddleOCR's own value. */
        const val VERTICAL_ASPECT = 1.5f

        /** Detection input height, in pixels, must be a multiple of this. */
        const val DETECTION_STRIDE = 32

        /** Recognition input height. The mirrors' config.json claims 32; the graphs say 48. */
        const val RECOGNITION_HEIGHT = 48

        private val DETECTION_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DETECTION_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    /** The detection input size for this image: long side capped, both sides a multiple of 32. */
    fun detectionSize(limitSideLength: Int): Pair<Int, Int> {
        val longest = max(width, height)
        val scale = if (longest > limitSideLength) limitSideLength.toFloat() / longest else 1f
        fun snap(value: Float) = max(DETECTION_STRIDE, (value / DETECTION_STRIDE).roundToInt() * DETECTION_STRIDE)
        return snap(width * scale) to snap(height * scale)
    }

    /** NCHW float32, scaled to [0,1] then ImageNet-normalised — what the DB detector expects. */
    fun toDetectionTensor(targetWidth: Int, targetHeight: Int): FloatArray {
        val resized = resize(targetWidth, targetHeight)
        val plane = targetWidth * targetHeight
        val out = FloatArray(3 * plane)
        for (index in 0 until plane) {
            val argb = resized.pixels[index]
            out[index] = (red(argb) / 255f - DETECTION_MEAN[0]) / DETECTION_STD[0]
            out[plane + index] = (green(argb) / 255f - DETECTION_MEAN[1]) / DETECTION_STD[1]
            out[2 * plane + index] = (blue(argb) / 255f - DETECTION_MEAN[2]) / DETECTION_STD[2]
        }
        return out
    }

    /**
     * Write this line into one row of a recognition batch: resized to height 48 keeping its aspect,
     * normalised to [-1,1], and zero-padded out to [batchWidth].
     *
     * @return the width actually occupied, before padding
     */
    fun writeRecognitionRow(destination: FloatArray, row: Int, batchWidth: Int): Int {
        val scaledWidth = ceil(RECOGNITION_HEIGHT.toDouble() * width / height).toInt()
            .coerceIn(1, batchWidth)
        val resized = resize(scaledWidth, RECOGNITION_HEIGHT)
        val plane = RECOGNITION_HEIGHT * batchWidth
        val base = row * 3 * plane
        for (y in 0 until RECOGNITION_HEIGHT) {
            for (x in 0 until scaledWidth) {
                val argb = resized.pixels[y * scaledWidth + x]
                val offset = y * batchWidth + x
                destination[base + offset] = (red(argb) / 255f - 0.5f) / 0.5f
                destination[base + plane + offset] = (green(argb) / 255f - 0.5f) / 0.5f
                destination[base + 2 * plane + offset] = (blue(argb) / 255f - 0.5f) / 0.5f
            }
        }
        return scaledWidth
    }
}
