package com.opentasker.core.actions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.ocr.OcrEngine
import com.opentasker.core.ocr.OcrImage
import com.opentasker.core.ocr.OcrScript
import com.opentasker.core.ocr.OcrTuning
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.ui.ocr.OcrReviewActivity
import com.opentasker.ui.theme.ThemeStore
import java.io.File

/**
 * `Recognise Text (OCR)` — read the text in an image, entirely on-device.
 *
 * The Sharesheet tile 「文字認識」 is the everyday way in; this is the same engine for tasks, so a
 * screenshot saved by some other automation can be turned into text without a human in the loop.
 *
 * `show` opens the review window instead of returning quietly, which is the useful shape when a task
 * wants 白い熊 to check and correct the text before it goes anywhere.
 */
class OcrRecognizeAction : Action {
    override val id = "ocr.recognize"
    override val category = ActionCategory.VARIABLE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["image"]?.trim().orEmpty()
        if (path.isEmpty()) return ActionResult.Failure("missing image")

        val output = VariableNamePolicy.normalize(args["var"] ?: args["result"] ?: "OCR")
            ?: return ActionResult.Failure("invalid output variable")
        val script = OcrScript.fromId(args["script"])
        // The settings toggle is the default; an explicit `model` arg lets one task ask for the fast
        // model (a bulk sweep) or the accurate one (a photo) without changing the app-wide preference.
        val highAccuracy = when (args["model"]?.trim()?.lowercase()) {
            "server", "accurate", "high" -> true
            "mobile", "fast", "low" -> false
            else -> ThemeStore.state.value.ocrHighAccuracy
        }

        val bitmap = load(ctx, path)
            ?: return ActionResult.Failure("could not read an image from \"$path\"")

        if (args["show"]?.trim()?.lowercase() in SHOW_VALUES) {
            // The window needs a file it owns and may delete; hand it a copy rather than 白い熊's original.
            val copy = File(ctx.app.cacheDir, "ocr").apply { mkdirs() }
                .resolve("task-${System.currentTimeMillis()}.png")
            copy.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            OcrReviewActivity.open(ctx.app, copy)
            ctx.logger("Opened 文字認識 for $path")
            return ActionResult.Success
        }

        val result = runCatching {
            val prefs = ThemeStore.state.value
            OcrEngine.run(
                ctx.app, bitmap.toOcrImage(), script, highAccuracy,
                OcrTuning.from(
                    prefs.ocrDetectionLongSide, prefs.ocrBinarisePercent,
                    prefs.ocrBoxScorePercent, prefs.ocrUnclipTenths,
                ),
            )
        }.getOrElse { return ActionResult.Failure("recognition failed: ${it.message ?: it.javaClass.simpleName}") }

        // A screenshot can hold anything — a message, a code, an address — so the text goes in as
        // sensitive, the same as a clipboard read.
        ctx.variables.set(output, result.text, sensitive = true)
        ctx.variables.set("${output}_lines", result.blocks.size.toString())
        ctx.variables.set("${output}_script", result.script.id)
        ctx.variables.set("${output}_model", if (highAccuracy) "server" else "mobile")
        ctx.logger("OCR ${result.script.id}: ${result.blocks.size} blocks, ${result.text.length} characters in ${result.elapsedMs} ms")
        return ActionResult.Success
    }

    /** Accepts a plain path or any URI the resolver can open. */
    private fun load(ctx: ActionContext, path: String): Bitmap? = runCatching {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            ctx.app.contentResolver.openInputStream(Uri.parse(path)).use { BitmapFactory.decodeStream(it) }
        } else {
            BitmapFactory.decodeFile(File(path).absolutePath)
        }
    }.getOrNull()

    private fun Bitmap.toOcrImage(): OcrImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return OcrImage(pixels, width, height)
    }

    private companion object {
        val SHOW_VALUES = setOf("true", "1", "yes", "on")
    }
}
