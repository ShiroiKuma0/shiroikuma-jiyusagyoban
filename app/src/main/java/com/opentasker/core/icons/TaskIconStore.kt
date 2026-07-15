package com.opentasker.core.icons

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.drawable.IconCompat
import com.opentasker.app.R
import com.opentasker.core.model.Task
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the per-task custom shortcut icons under `filesDir/task_icons/`.
 *
 * When 白い熊 assigns an icon to a task (from an installed app, or a picture), we *snapshot* it to a PNG
 * here immediately. That snapshot is what survives the source picture being deleted or the source app
 * being frozen/uninstalled — and what we bake (as a bitmap) into the launcher shortcut so the icon keeps
 * rendering even if 自由作業盤 itself is later frozen.
 *
 * Filenames are timestamp-based (never task-id-based), so a saved path stays valid through the create flow
 * (no id yet) and through task re-imports (which re-id the task).
 */
object TaskIconStore {

    private const val DIR = "task_icons"
    private const val MAX_ICON_BYTES = 1_000_000  // ~1 MB ceiling per icon embedded in / read from a bundle

    // Cached so import (which only has a DB handle, no Context) can materialize embedded icon bytes.
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Snapshot a picked picture (content Uri) to a square PNG. Returns the absolute path, or null. */
    fun saveFromUri(context: Context, uri: Uri): String? = runCatching {
        val target = targetSize(context)
        val bmp = decodeScaledSquare(context, uri, target) ?: return null
        writePng(context, bmp)
    }.getOrNull()

    /** Snapshot an installed app's launcher icon to a square PNG. Returns the absolute path, or null. */
    fun saveFromApp(context: Context, pkg: String): String? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val target = targetSize(context)
        writePng(context, drawableToBitmap(drawable, target))
    }.getOrNull()

    /** [saveFromApp] using the cached application context — for non-UI callers (e.g. actions). */
    fun saveFromApp(pkg: String): String? = appContext?.let { saveFromApp(it, pkg) }

    /**
     * Snapshot an audio file's (mp3 / ogg / flac / m4a …) embedded album art to a square PNG. Returns the
     * path, or null if the file has no embedded artwork (caller can tell 白い熊 to pick another).
     */
    fun saveFromAudio(context: Context, uri: Uri): String? = runCatching {
        val mmr = MediaMetadataRetriever()
        val art: ByteArray? = try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture
        } finally {
            runCatching { mmr.release() }
        }
        if (art == null || art.isEmpty()) return null
        val decoded = BitmapFactory.decodeByteArray(art, 0, art.size) ?: return null
        writePng(context, squareScale(decoded, targetSize(context)))
    }.getOrNull()

    /**
     * Render an emoji (or a short glyph/character) centered on a transparent square PNG. Color emoji draw
     * in their own colors; plain characters use [glyphColor]. Returns the absolute path, or null.
     */
    fun saveFromText(context: Context, text: String, glyphColor: Int = 0xFFFFFFFF.toInt()): String? = runCatching {
        val glyph = text.trim()
        if (glyph.isEmpty()) return null
        val size = targetSize(context)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = glyphColor
            textSize = size * 0.72f
        }
        // Shrink to fit when the glyph cluster is wide (multi-char, flags, ZWJ sequences).
        val maxWidth = size * 0.9f
        var guard = 0
        while (paint.measureText(glyph) > maxWidth && paint.textSize > 8f && guard < 64) {
            paint.textSize -= size * 0.04f
            guard++
        }
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, size / 2f, baseline, paint)
        writePng(context, bmp)
    }.getOrNull()

    /** Decode a saved icon for in-app display (preview / list). Null if the file is gone or unreadable. */
    fun loadBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /**
     * The icon to bake into a shortcut for [task]: the task's saved bitmap when present and readable,
     * otherwise 自由作業盤's launcher icon. Bitmaps (not resource/Uri refs) are baked so the launcher keeps
     * the icon after our app is frozen.
     */
    fun iconCompatFor(context: Context, task: Task): IconCompat {
        loadBitmap(task.iconPath)?.let { return IconCompat.createWithBitmap(it) }
        return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    }

    /** Base64 (NO_WRAP) of the saved icon PNG at [iconPath], for embedding in an export bundle. Null if
     *  there's no file, it's unreadable, or it's larger than [MAX_ICON_BYTES]. */
    fun encodeIcon(iconPath: String?): String? {
        if (iconPath.isNullOrBlank()) return null
        val file = File(iconPath)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Decode bundle-embedded icon bytes into a fresh PNG in our icon dir; returns its path, or null.
     *  Used on import so an icon survives onto a different device. */
    fun materializeIcon(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        val context = appContext ?: return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "icon_${System.currentTimeMillis()}_${System.nanoTime()}.png")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    /** Remove a saved icon file (on replace, clear, or task delete). No-op for blank/foreign paths. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            // Only delete inside our own icon dir, never an arbitrary path that wandered in via import.
            if (file.parentFile?.name == DIR && file.exists()) file.delete()
        }
    }

    private fun writePng(context: Context, bmp: Bitmap): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "icon_${System.currentTimeMillis()}_${System.nanoTime()}.png")
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    private fun targetSize(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val size = am?.launcherLargeIconSize ?: 192
        return size.coerceIn(96, 256)
    }

    /** Center-crop [src] to a square and scale it to [target]px, recycling intermediates. */
    private fun squareScale(src: Bitmap, target: Int): Bitmap {
        val side = min(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        if (cropped != src) src.recycle()
        return if (cropped.width == target && cropped.height == target) cropped
        else Bitmap.createScaledBitmap(cropped, target, target, true).also { if (it != cropped) cropped.recycle() }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { return Bitmap.createScaledBitmap(it, size, size, true) }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** Decode [uri] with subsampling, then center-crop to a square scaled to [target]px. */
    private fun decodeScaledSquare(context: Context, uri: Uri, target: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val smaller = min(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(smaller, target) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // Center-crop to a square, then scale to the launcher size.
        val side = min(decoded.width, decoded.height)
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2
        val cropped = Bitmap.createBitmap(decoded, left, top, side, side)
        if (cropped != decoded) decoded.recycle()
        return if (cropped.width == target && cropped.height == target) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, target, target, true).also { if (it != cropped) cropped.recycle() }
        }
    }

    private fun sampleSize(srcSmaller: Int, target: Int): Int {
        var sample = 1
        var dim = srcSmaller
        while (dim / 2 >= target) {
            dim /= 2
            sample *= 2
        }
        return max(1, sample)
    }
}
