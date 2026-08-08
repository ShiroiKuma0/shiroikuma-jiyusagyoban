package com.opentasker.core.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import com.opentasker.app.BuildConfig
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.theme.ThemeStore
import java.io.File

/**
 * Owns the ONNX sessions and the character dictionaries.
 *
 * The dictionaries ship in the APK; the ~100 MB of weights do not, and are read from wherever 白い熊
 * put them (see [ModelSlot]). Missing weights are a normal, expected state on a fresh install — this
 * raises [MissingModel] so the caller can say which file to go and fetch, rather than failing with
 * something that reads like a broken app.
 */
object OcrModels {

    private const val TAG = "OcrModels"
    private const val DIRECTORY = "ocr"
    private const val STAMP = "extracted.version"

    /** Recognition threads. Four is the sweet spot on the Mate XT's big cluster. */
    private const val THREADS = 4

    /** Raised when a weight file has not been chosen yet, or no longer opens. */
    class MissingModel(val slot: ModelSlot, message: String) : IllegalStateException(message)

    internal val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private val charsets = HashMap<OcrScript, List<String>>()

    @Volatile
    private var extractedTo: File? = null

    /** Session for the shared DB text detector. */
    @Synchronized
    fun detection(context: Context): OrtSession = session(context, ModelSlot.DETECTION)

    /**
     * Session for one script's recogniser at the requested tier.
     *
     * Falls back to the other tier when the requested one has not been supplied: with only the fast
     * Japanese model on the device, turning the accuracy switch on should read the screenshot slightly
     * differently, not refuse to read it at all.
     */
    @Synchronized
    fun recognition(context: Context, script: OcrScript, highAccuracy: Boolean): OrtSession {
        val wanted = script.slot(highAccuracy)
        val fallback = script.slot(!highAccuracy)
        return if (pathFor(context, wanted) != null || fallback == wanted) {
            session(context, wanted)
        } else {
            AppLogger.warn(TAG, "${wanted.id} is not set; falling back to ${fallback.id}")
            session(context, fallback)
        }
    }

    /**
     * The class list for [script], as `['blank'] + dictionary + [' ']`.
     *
     * Cross-checked against the model's own output width: a mismatch would not crash, it would shift
     * every character by a constant and produce fluent-looking nonsense. Now that the dictionaries ship
     * and the weights do not, this is also what catches the wrong .onnx being chosen for a slot.
     */
    @Synchronized
    fun charset(context: Context, script: OcrScript, highAccuracy: Boolean): List<String> =
        charsets.getOrPut(script) {
            val file = extract(context).resolve(script.dictionaryAsset.substringAfterLast('/'))
            val parsed = OcrCharset.parse(file.readText(Charsets.UTF_8))
            val classes = recognition(context, script, highAccuracy).outputInfo.values.first()
                .info.let { it as? ai.onnxruntime.TensorInfo }?.shape?.lastOrNull()?.toInt()
            check(classes == null || classes == parsed.size) {
                "the model chosen for ${script.id} expects $classes classes but its dictionary yields " +
                    "${parsed.size} — that is the wrong .onnx for this script"
            }
            parsed
        }

    /** Where a slot's weights are, or null when it has not been set. */
    fun pathFor(context: Context, slot: ModelSlot): String? {
        val stored = ThemeStore.state.value.ocrModelPath(slot).trim()
        if (stored.isEmpty()) return null
        // A plain path when 白い熊 typed one, a document URI when picked. Only a real path can be handed
        // to ONNX Runtime, so a picked document is materialised into app storage once.
        if (!stored.startsWith("content://")) return stored.takeIf { File(it).isFile }

        val uri = Uri.parse(stored)
        // A picked document is usually a file on the card that this app can already read directly —
        // it holds MANAGE_EXTERNAL_STORAGE. Resolving the URI to that path avoids duplicating ~100 MB
        // of weights into app storage for no reason. The copy stays as the fallback for providers whose
        // documents have no real path (a cloud provider, a ZIP).
        realPath(uri)?.let { return it }
        return cachedCopy(context, slot, uri)?.absolutePath
    }

    /** `content://com.android.externalstorage.documents/document/primary%3Aa%2Fb` -> `/sdcard/a/b`. */
    private fun realPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        val (volume, relative) = documentId.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        if (!volume.equals("primary", ignoreCase = true)) return null
        return File("/storage/emulated/0/$relative").takeIf { it.isFile }?.absolutePath
    }

    /** True when everything 「文字認識」 needs for [script] is present. */
    fun ready(context: Context, script: OcrScript, highAccuracy: Boolean): Boolean =
        pathFor(context, ModelSlot.DETECTION) != null &&
            (pathFor(context, script.slot(highAccuracy)) != null ||
                pathFor(context, script.slot(!highAccuracy)) != null)

    private fun session(context: Context, slot: ModelSlot): OrtSession = sessions.getOrPut(slot.id) {
        val path = pathFor(context, slot)
            ?: throw MissingModel(slot, "${slot.label}のモデル未設定")
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        environment.createSession(path, options)
    }

    /**
     * A picked document copied into app storage once, because ONNX Runtime wants a path and a
     * `content://` URI has none. Keyed by size, so re-picking the same file does not copy again while
     * replacing it with a different one does.
     */
    private fun cachedCopy(context: Context, slot: ModelSlot, uri: Uri): File? {
        val target = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, "${slot.id}.onnx")
        val expected = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (target.isFile && expected > 0 && target.length() == expected) return target

        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "cannot open $uri" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppLogger.info(TAG, "copied ${slot.id} (${target.length() / 1_000_000} MB) into app storage")
            target
        }.getOrElse {
            AppLogger.warn(TAG, "could not read the ${slot.id} model", it)
            target.delete()
            null
        }
    }

    /** Copies the bundled dictionaries into filesDir once per app version. Safe to call repeatedly. */
    @Synchronized
    fun extract(context: Context): File {
        extractedTo?.let { return it }

        val target = File(context.filesDir, DIRECTORY)
        val stamp = File(target, STAMP)
        val current = BuildConfig.VERSION_NAME
        val assets = context.assets.list(DIRECTORY).orEmpty()
        check(assets.isNotEmpty()) { "no OCR dictionaries in assets/$DIRECTORY — did downloadOcrModels run?" }

        val upToDate = stamp.isFile && stamp.readText() == current &&
            assets.all { File(target, it).isFile }
        if (!upToDate) {
            target.mkdirs()
            assets.forEach { name ->
                val destination = File(target, name)
                context.assets.open("$DIRECTORY/$name").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
            stamp.writeText(current)
            AppLogger.info(TAG, "extracted ${assets.size} OCR dictionaries for $current")
        }

        extractedTo = target
        return target
    }

    /** Drops every open session — call after the model paths change. */
    @Synchronized
    fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        charsets.clear()
    }
}
