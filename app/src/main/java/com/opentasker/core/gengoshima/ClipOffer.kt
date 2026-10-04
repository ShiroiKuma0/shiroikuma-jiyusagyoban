package com.opentasker.core.gengoshima

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.opentasker.core.logging.AppLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand captured sentences to kxkb — `voice_capture_offer` on its automation door
 * (docs/sister-app-contract-kxkb-gengoshima.md §1).
 *
 * A clip is ours until its uuid comes back in `OK:`; only then is it deleted, so the audio exists in
 * exactly one place at every instant. Ten per call, each as a read-only descriptor; a uuid kxkb
 * `rejected` is moved aside (never deleted, never retried automatically); anything else is re-offered
 * on the next mode end or engine start.
 */
object ClipOffer {
    private const val TAG = "OpenTasker"
    const val AUTHORITY = "shiroikuma.kxkb.automation"
    const val METHOD = "voice_capture_offer"
    const val BATCH = 10

    private val running = AtomicBoolean(false)

    /** Offer everything pending, off the caller's thread. Overlapping calls coalesce. */
    fun offerAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (SentenceCapture.pending(app).isEmpty()) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try { offerAll(app) } catch (e: Exception) {
                AppLogger.warn(TAG, "言語島 clip offer failed: ${e.message}")
            } finally { running.set(false) }
        }, "gengoshima-offer").start()
    }

    /** Synchronous: returns a one-line summary. */
    fun offerAll(app: Context): String {
        var delivered = 0
        var rejected = 0
        val skipped = HashSet<String>()
        while (true) {
            val batch = SentenceCapture.pending(app).filter { it.nameWithoutExtension !in skipped }.take(BATCH)
            if (batch.isEmpty()) break
            val reply = call(app, batch) ?: break
            val result = reply.getString("result").orEmpty()
            val okIds = if (result.startsWith("OK:")) result.removePrefix("OK:").split(',').filter { it.isNotBlank() }.toSet() else emptySet()
            val rejectedIds = parseRejected(reply.getString("rejected")) +
                (Regex("^ERROR:format:(.+)$").find(result)?.groupValues?.get(1)?.let { mapOf(it to "format") } ?: emptyMap())
            for (wav in batch) {
                val id = wav.nameWithoutExtension
                when {
                    id in okIds -> { wav.delete(); sidecar(wav).delete(); delivered++ }
                    id in rejectedIds -> { setAside(wav, rejectedIds.getValue(id)); rejected++ }
                    else -> skipped += id
                }
            }
            if (!result.startsWith("OK:") && rejectedIds.isEmpty()) {
                AppLogger.warn(TAG, "言語島 clip offer: kxkb answered $result — keeping ${batch.size} clip(s)")
                break
            }
        }
        val line = "言語島 clips: $delivered delivered to kxkb, $rejected rejected, ${SentenceCapture.pending(app).size} still pending"
        AppLogger.info(TAG, line)
        return line
    }

    private fun call(app: Context, batch: List<File>): Bundle? {
        val extras = Bundle()
        val items = JSONArray()
        val fds = ArrayList<ParcelFileDescriptor>()
        try {
            batch.forEachIndexed { i, wav ->
                val meta = runCatching { JSONObject(sidecar(wav).readText()) }.getOrDefault(JSONObject())
                val key = "fd_$i"
                val fd = ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
                fds += fd
                extras.putParcelable(key, fd)
                items.put(
                    JSONObject()
                        .put("uuid", wav.nameWithoutExtension)
                        .put("fd", key)
                        .put("capturedAt", meta.optLong("capturedAt", wav.lastModified()))
                        .put("durationMs", meta.optLong("durationMs", 0))
                        .put("language", meta.optString("language", "en"))
                        .put("sampleRate", SentenceCapture.SAMPLE_RATE)
                        .put("channels", SentenceCapture.CHANNELS)
                        .put("bitsPerSample", SentenceCapture.BITS)
                        .put("byteLength", wav.length())
                        .put("sha256", sha256(wav)),
                )
            }
            extras.putString("items", items.toString())
            return app.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD, null, extras)
        } catch (e: Exception) {
            // kxkb not installed, not visible, or its door threw — keep everything for next time.
            AppLogger.warn(TAG, "言語島 clip offer: call failed: ${e.message}")
            return null
        } finally {
            fds.forEach { runCatching { it.close() } }
        }
    }

    internal fun parseRejected(raw: String?): Map<String, String> =
        raw.orEmpty().split(',').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    private fun setAside(wav: File, reason: String) {
        val dir = File(wav.parentFile, "rejected").apply { mkdirs() }
        wav.renameTo(File(dir, wav.name))
        sidecar(wav).renameTo(File(dir, "${wav.nameWithoutExtension}.json"))
        AppLogger.warn(TAG, "言語島 clip ${wav.nameWithoutExtension} rejected by kxkb ($reason) — set aside in ${dir.path}")
    }

    private fun sidecar(wav: File) = File(wav.parentFile, "${wav.nameWithoutExtension}.json")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
