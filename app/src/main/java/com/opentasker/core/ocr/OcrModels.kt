package com.opentasker.core.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.opentasker.core.logging.AppLogger
import com.opentasker.app.BuildConfig
import java.io.File

/**
 * Owns the ONNX sessions and the character dictionaries: extracts the models out of the APK once,
 * then hands out sessions that stay open for the life of the process.
 *
 * Extraction is not avoidable. ONNX Runtime's Java API wants a filesystem path or the whole model as
 * a byte array, and the Japanese recogniser is ~81 MB — a path costs a one-off copy, a byte array
 * costs 81 MB of heap on every load. The copy runs once and is stamped with the app version, so a new
 * build that ships different weights re-extracts rather than silently reusing the old ones.
 */
object OcrModels {

    private const val TAG = "OcrModels"
    private const val DIRECTORY = "ocr"
    private const val STAMP = "extracted.version"

    /** Recognition threads. Four is the sweet spot on the Mate XT's big cluster. */
    private const val THREADS = 4

    internal val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private val charsets = HashMap<OcrScript, List<String>>()

    @Volatile
    private var extractedTo: File? = null

    /** Session for the shared DB text detector. */
    @Synchronized
    fun detection(context: Context): OrtSession =
        session(context, OcrScript.DETECTION_MODEL_ASSET)

    /**
     * Session for one script's recogniser at the requested accuracy tier, created on first use and
     * cached thereafter. Sessions are keyed by asset, so switching the tier back and forth costs one
     * load each and nothing after that.
     */
    @Synchronized
    fun recognition(context: Context, script: OcrScript, highAccuracy: Boolean): OrtSession =
        session(context, script.modelAsset(highAccuracy))

    /**
     * The class list for [script], as `['blank'] + dictionary + [' ']`.
     *
     * Cross-checked against the model's own output width: a mismatch means the model and dictionary
     * have drifted apart, which would not crash — it would silently shift every character by a
     * constant and produce fluent-looking nonsense. Far better to fail loudly here.
     *
     * Cached per script, not per tier: both Japanese tiers index the same 18385-class dictionary, and
     * the check above is what would catch it if that ever stopped being true.
     */
    @Synchronized
    fun charset(context: Context, script: OcrScript, highAccuracy: Boolean): List<String> = charsets.getOrPut(script) {
        val file = extract(context).resolve(script.dictionaryAsset.substringAfterLast('/'))
        val parsed = OcrCharset.parse(file.readText(Charsets.UTF_8))
        val classes = recognition(context, script, highAccuracy).outputInfo.values.first()
            .info.let { it as? ai.onnxruntime.TensorInfo }?.shape?.lastOrNull()?.toInt()
        check(classes == null || classes == parsed.size) {
            "${script.id}: model expects $classes classes but the dictionary yields ${parsed.size}"
        }
        parsed
    }

    private fun session(context: Context, asset: String): OrtSession = sessions.getOrPut(asset) {
        val file = extract(context).resolve(asset.substringAfterLast('/'))
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        environment.createSession(file.absolutePath, options)
    }

    /** Copies every file under `assets/ocr` into filesDir once per app version. Safe to call repeatedly. */
    @Synchronized
    fun extract(context: Context): File {
        extractedTo?.let { return it }

        val target = File(context.filesDir, DIRECTORY)
        val stamp = File(target, STAMP)
        val current = BuildConfig.VERSION_NAME
        val assets = context.assets.list(DIRECTORY).orEmpty()
        check(assets.isNotEmpty()) { "no OCR models in assets/$DIRECTORY — did downloadOcrModels run?" }

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
            AppLogger.info(TAG, "extracted ${assets.size} OCR model files for $current")
        }

        extractedTo = target
        return target
    }

    /** Drops every open session. The next call re-creates whatever it needs. */
    @Synchronized
    fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        charsets.clear()
    }
}
