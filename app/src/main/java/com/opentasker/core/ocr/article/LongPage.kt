package com.opentasker.core.ocr.article

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.opentasker.core.ocr.OcrImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Luma, integer, from a `Bitmap.getPixels` ARGB word. */
internal fun luma(argb: Int): Int =
    (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

/**
 * A scrolling screenshot, read one horizontal slice at a time.
 *
 * The two pages this was built against are 2048x41744 and 2048x30964 — 342 MB and 254 MB as ARGB.
 * Decoding either whole is not a tuning question, it is an OutOfMemoryError, so every pixel this
 * class hands out comes through [BitmapRegionDecoder] and nothing bigger than one slice is ever live.
 *
 * The page background is measured separately from a heavily subsampled decode of the WHOLE page,
 * not from the first slice: page 1 opens on a full-bleed photograph, so a first-slice estimate would
 * report that photograph's dominant tone as the page colour and every later measurement taken
 * against it would be nonsense.
 */
class LongPage private constructor(
    private val decoder: BitmapRegionDecoder,
    val width: Int,
    val height: Int,
    /** Modal luma over the whole page — what "not ink" means everywhere downstream. */
    val background: Int,
) : Closeable {

    /** Pixels for `[top, bottom)`, or null if the region will not decode. */
    fun slice(top: Int, bottom: Int): OcrImage? {
        val clampedTop = top.coerceIn(0, height)
        val clampedBottom = bottom.coerceIn(clampedTop, height)
        if (clampedBottom - clampedTop <= 0) return null
        val bitmap = runCatching {
            decoder.decodeRegion(
                Rect(0, clampedTop, width, clampedBottom),
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        }.getOrNull() ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            OcrImage(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** One figure, re-decoded at its own rectangle and compressed. Null if it will not decode. */
    fun figureJpeg(rect: Rect, maxWidth: Int, quality: Int): ByteArray? {
        val bitmap = runCatching {
            decoder.decodeRegion(
                rect,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        }.getOrNull() ?: return null
        return try {
            val scaled = if (bitmap.width > maxWidth) {
                val h = max(1, (bitmap.height.toLong() * maxWidth / bitmap.width).toInt())
                Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream(64 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 100), out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        runCatching { decoder.recycle() }
    }

    companion object {
        /**
         * Slice height. Chosen so that a 2048 px-wide screenshot reaches the detector at very nearly
         * the 1600 px long side [com.opentasker.core.ocr.OcrTuning] was measured at.
         */
        const val SLICE = 2048

        /**
         * How much consecutive slices share.
         *
         * A box is kept by the slice whose CORE contains its centre, and cores tile exactly, so a line
         * is read once and only once. The overlap has to exceed the tallest text line on the page or a
         * headline sitting on a core boundary is clipped by both neighbours: 384 leaves 192 px of margin
         * either side, against the 193 px headline these pages actually contain.
         */
        const val OVERLAP = 384

        /** Long side of the subsampled decode the background is measured from. */
        private const val BACKGROUND_LONG_SIDE = 4096

        fun open(file: File): LongPage? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decoder: BitmapRegionDecoder? = runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    BitmapRegionDecoder.newInstance(file.absolutePath)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(file.absolutePath, false)
                }
            }.getOrNull()
            if (decoder == null) return null

            return LongPage(
                decoder = decoder,
                width = bounds.outWidth,
                height = bounds.outHeight,
                background = measureBackground(file, bounds.outWidth, bounds.outHeight),
            )
        }

        /** Modal luma of a subsampled whole-page decode; mid-grey if it will not decode at all. */
        private fun measureBackground(file: File, width: Int, height: Int): Int {
            var sample = 1
            while (max(width, height) / sample > BACKGROUND_LONG_SIDE) sample *= 2
            val bitmap = runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }.getOrNull() ?: return 128
            return try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val histogram = IntArray(256)
                for (pixel in pixels) histogram[luma(pixel).coerceIn(0, 255)]++
                var best = 0
                for (level in 1 until 256) if (histogram[level] > histogram[best]) best = level
                best
            } finally {
                bitmap.recycle()
            }
        }
    }
}

/**
 * Per-row ink statistics for a whole page, filled in slice by slice.
 *
 * Three numbers a row: how much of it is not background, and how far left and right that ink reaches.
 * Everything the layout pass knows about pictures comes from these — a photograph is an unbroken run
 * of inked rows, and a column of body text is not, because printed text has blank leading between
 * every line. Sized for the page, so ~600 kB on the tallest screenshot here rather than 342 MB.
 */
class RowProfile(val height: Int) {
    /** Fraction of the row differing from the page background. */
    val ink = FloatArray(height)
    val left = IntArray(height) { -1 }
    val right = IntArray(height) { -1 }

    /** True when this row holds essentially nothing — the leading between two lines of text. */
    fun blank(y: Int): Boolean = ink[y] < BLANK_INK

    /**
     * Absorb rows `[from, to)` of a slice whose top edge sits at [sliceTop] on the page.
     *
     * The caller passes each row exactly once — the slices overlap, and counting a row twice would be
     * harmless for [ink] but would make the timings lie about how much work was done.
     */
    fun absorb(image: OcrImage, sliceTop: Int, background: Int, from: Int, to: Int) {
        val start = max(from, sliceTop)
        val end = min(to, sliceTop + image.height)
        for (y in start until end) {
            val rowStart = (y - sliceTop) * image.width
            var inked = 0
            var first = -1
            var last = -1
            for (x in 0 until image.width) {
                val delta = luma(image.pixels[rowStart + x]) - background
                if (delta > INK_DELTA || delta < -INK_DELTA) {
                    inked++
                    if (first < 0) first = x
                    last = x
                }
            }
            ink[y] = inked.toFloat() / image.width
            left[y] = first
            right[y] = last
        }
    }

    private companion object {
        /** How far from the background a pixel must be to count as ink. */
        const val INK_DELTA = 24

        /** Below this the row is leading, not content. Measured: text leading reads 0.000–0.001. */
        const val BLANK_INK = 0.002f
    }
}

/** Rounds a float to a whole pixel, for the geometry that ends up in the HTML. */
internal fun Float.px(): Int = roundToInt()
