package com.opentasker.core.ocr

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Everything needed to take an image out of a Sharesheet delivery, shared by both entry points.
 *
 * There are two, and on this phone that is not redundancy. EMUI's share UI is a paged "Open with" grid
 * that collapses to **one tile per package** (the finding that forced the per-target relay APKs in the
 * 共有アプリ工房 work), so we cannot rely on 「文字認識」 being the tile 白い熊 is offered — the older
 * generic every-MIME-type receiver may well be the one shown. The answer is to make BOTH handle an image
 * the same way, so whichever tile the OS surfaces, sharing a screenshot lands here.
 */
object OcrShareIntake {

    /**
     * The image being shared, wherever the sender chose to put it.
     *
     * `EXTRA_STREAM` is the documented place and what most apps use. Some send a single item through
     * `clipData` instead (and the screenshot editors on several OEM builds are in that group), and a few
     * put it in the intent's own data. Checking all three is the difference between "works from the
     * gallery" and "works from anywhere".
     */
    fun imageUri(intent: Intent): Uri? {
        @Suppress("DEPRECATION")
        val stream = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> null
        }
        if (stream != null) return stream

        val fromClip = (0 until (intent.clipData?.itemCount ?: 0))
            .asSequence()
            .mapNotNull { intent.clipData?.getItemAt(it)?.uri }
            .firstOrNull()
        return fromClip ?: intent.data
    }

    /** Whether this delivery is an image, by the declared type or by what the resolver says the URI is. */
    fun isImage(context: Context, intent: Intent, uri: Uri?): Boolean {
        intent.type?.let { if (it.startsWith("image/")) return true }
        val resolved = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        if (resolved?.startsWith("image/") == true) return true
        // A file:// URI carries no resolver type; fall back to the extension. Screenshot tooling that
        // hands over a plain path would otherwise be turned away.
        val name = uri?.lastPathSegment?.lowercase().orEmpty()
        return IMAGE_SUFFIXES.any(name::endsWith)
    }

    /**
     * Copies the shared bytes into `cacheDir/ocr` and returns the file.
     *
     * Must happen while the receiving Activity is still alive: a Sharesheet URI grant is scoped to it,
     * so passing the `content://` URI onward to be opened later fails as a permission error far from
     * here. The review window deletes the file when it closes.
     */
    fun copyToCache(context: Context, uri: Uri): File {
        val directory = File(context.cacheDir, "ocr").apply { mkdirs() }
        // Sweep anything an earlier run left behind (a crash, or the window killed by the system) so the
        // cache cannot grow without bound.
        directory.listFiles()?.forEach { stale ->
            if (System.currentTimeMillis() - stale.lastModified() > STALE_MS) stale.delete()
        }

        val destination = File(directory, "share-${System.currentTimeMillis()}.png")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "no stream for $uri" }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        check(destination.length() > 0) { "empty image" }
        return destination
    }

    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".heic", ".heif")
    private const val STALE_MS = 60 * 60 * 1000L
}
