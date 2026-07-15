package com.opentasker.core.scenes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri

internal object SceneImageLoader {
    const val MAX_SOURCE_CHARS = 2_048
    const val MAX_DECODE_PIXELS = 2_097_152

    fun load(
        context: Context,
        source: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? {
        if (!isSupportedSource(source)) return null
        return runCatching {
            val uri = source.trim().toUri()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    targetWidth = targetWidthPx,
                    targetHeight = targetHeightPx,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
    }

    fun isSupportedSource(source: String): Boolean {
        val trimmed = source.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SOURCE_CHARS) return false
        return trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("android.resource://", ignoreCase = true)
    }

    fun computeInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight.coerceAtLeast(1)
        var sample = 1
        while (sample < 1 shl 15) {
            val decodedWidth = sourceWidth.coerceAtLeast(1) / sample
            val decodedHeight = sourceHeight.coerceAtLeast(1) / sample
            val decodedPixels = decodedWidth.toLong() * decodedHeight.toLong()
            if (
                decodedWidth <= safeTargetWidth &&
                decodedHeight <= safeTargetHeight &&
                decodedPixels <= MAX_DECODE_PIXELS
            ) {
                break
            }
            sample = sample shl 1
        }
        return sample
    }
}
