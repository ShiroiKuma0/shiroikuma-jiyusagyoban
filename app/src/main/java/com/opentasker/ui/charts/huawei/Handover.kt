package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * A file that exists only while 地図 is reading or writing it.
 *
 * ## Why any file at all
 *
 * The workout archive is in the database now, and nothing this app owns lives on shared storage.
 * But 地図's contract is streams: it reads a GPX and it writes a base-map PNG, and neither fits in
 * a Binder transaction — a GPX is 150–220 KB for a half-hour walk and a cutout is about a megabyte,
 * against roughly one megabyte for everything in flight. So the bytes travel as a `content://` URI
 * from our own [FileProvider], backed by a file under `filesDir` that is created for the exchange
 * and deleted after it.
 *
 * ## Why `filesDir` and not `cacheDir`
 *
 * 地図's receiver calls `goAsync()` and a cold start waits up to **three minutes** for its own
 * initialization before it opens the stream. A cache directory the system may reclaim under memory
 * pressure is the wrong place to leave something for three minutes, and the failure would arrive as
 * an intermittent 地図 bug rather than as our file having vanished.
 *
 * ## Why the grant is explicit
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` on a broadcast does not survive that wait — Android's broadcast
 * timeout is on the order of ten seconds foreground and sixty background, so a flag-scoped grant is
 * long dead by the time 地図 looks. [grant] therefore calls `grantUriPermission` directly, which is
 * load-bearing rather than belt-and-braces, and [release] revokes it only once the reply has landed.
 */
object Handover {

    private const val DIR = "user_files"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** Write the bytes into a private file 地図 can be pointed at. Null if it cannot be written. */
    fun stage(context: Context, name: String, bytes: ByteArray): File? = runCatching {
        // One directory, swept on the way in rather than on the way out: a crash mid-exchange would
        // otherwise leave a file behind for ever, and nothing else ever reads this folder.
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { old ->
            if (System.currentTimeMillis() - old.lastModified() > STALE_MS) old.delete()
        }
        File(dir, name.replace('/', '_')).apply { writeBytes(bytes) }
    }.getOrNull()

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    fun grant(context: Context, uri: Uri, write: Boolean) {
        var mode = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) mode = mode or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.grantUriPermission(HuaweiChizu.PACKAGE, uri, mode) }
    }

    /** Revoke and remove. Called from a `finally`, so a thrown exchange cleans up as well. */
    fun release(context: Context, file: File) {
        runCatching {
            val uri = uriFor(context, file)
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        runCatching { file.delete() }
    }

    /**
     * How long a leftover handover file is allowed to sit before the next one sweeps it.
     *
     * Comfortably past 地図's three-minute initialization wait: a file swept while a slow exchange
     * is still in flight would look exactly like 地図 failing to read it.
     */
    private const val STALE_MS = 10 * 60 * 1000L
}
