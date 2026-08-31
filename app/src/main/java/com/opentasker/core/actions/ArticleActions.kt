package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.ocr.OcrModels
import com.opentasker.core.ocr.OcrScript
import com.opentasker.core.ocr.OcrTuning
import com.opentasker.core.ocr.article.ArticleProgress
import com.opentasker.core.ocr.article.ArticleReader
import com.opentasker.core.ocr.article.ArticleWriter
import com.opentasker.ui.article.ArticleBuildActivity
import com.opentasker.ui.article.ArticleEditActivity
import com.opentasker.ui.ocr.flash
import com.opentasker.ui.theme.ThemeStore
import java.io.File

/**
 * `Article to HTML (OCR)` — read a scrolling screenshot into a formatted HTML file.
 *
 * The difference from `ocr.recognize` is not the engine, it is the ambition. That action answers
 * "what does this say"; this one answers "what was this page", and keeps the answer: the headline as
 * a heading, the paragraphs as paragraphs, the photographs cropped out and inlined, italics and bold
 * where the pixels say there were italics and bold, and every block sized against the body text it
 * sat next to.
 *
 * It also handles pages that action cannot. A 2048x41744 screenshot is 342 MB as ARGB and would
 * reach the detector squashed to 62 px wide; [ArticleReader] walks it in slices instead.
 *
 * Every text run in the output carries the page and quad it was read from, which is what will let the
 * editor put a word back on the screenshot it came from.
 */
class ArticleToHtmlAction : Action {
    override val id = "ocr.article"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val requested = (args["images"] ?: args["image"]).orEmpty()
            .split('\n', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val directoryPath = args["out"]?.trim()?.takeIf { it.isNotEmpty() } ?: ArticleWriter.DEFAULT_DIRECTORY

        // 「記事編集」 opened on its own: the springboard, where an article already written is opened
        // from the menu together with the screenshots it came from.
        if (args["edit"]?.trim()?.lowercase() in SHOW_VALUES) {
            val existing = args["html"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
            ArticleEditActivity.open(ctx.app, existing, directoryPath)
            ctx.logger("Opened 記事編集${if (existing != null) " on ${existing.name}" else ""}")
            return ActionResult.Success
        }

        // With "show" on this is the way 白い熊 reaches 「記事変換」 — with pages already queued, or with
        // none at all, which is the task that just puts the window on a launcher shortcut.
        if (args["show"]?.trim()?.lowercase() in SHOW_VALUES) {
            val staged = requested.mapNotNull { path -> stage(ctx, path) }
            ArticleBuildActivity.open(ctx.app, staged.map { it.absolutePath }, directoryPath)
            ctx.logger("Opened 記事変換 with ${staged.size} page(s)")
            return ActionResult.Success
        }

        if (requested.isEmpty()) return ActionResult.Failure("missing image")

        val prefix = VariableNamePolicy.normalize(args["var"] ?: args["prefix"] ?: "ART")
            ?: return ActionResult.Failure("invalid output variable")

        val pages = ArrayList<File>(requested.size)
        val temporary = ArrayList<File>()
        try {
            requested.forEach { path ->
                val resolved = resolve(ctx, path, temporary)
                    ?: return ActionResult.Failure("could not read an image from \"$path\"")
                pages += resolved
            }

            val preferences = ThemeStore.state.value
            val options = ArticleReader.Options(
                script = OcrScript.fromId(args["script"]),
                // Unlike `ocr.recognize`, this does NOT follow the app-wide accuracy toggle: it
                // defaults to the fast 16 MB recogniser. The difference is volume. One screenshot is
                // a single pass and can afford the accurate 81 MB model; an article is 44 of them —
                // measured, 15.2 minutes for the two sample pages — and the whole point of the
                // output is that it is then corrected by hand. `model=server` still asks for the
                // accurate one, and stage 2's window will make the choice before each run.
                highAccuracy = when (args["model"]?.trim()?.lowercase()) {
                    "server", "accurate", "high" -> true
                    "mobile", "fast", "low" -> false
                    "settings", "ui", "default" -> preferences.ocrHighAccuracy
                    else -> false
                },
                tuning = OcrTuning.from(
                    preferences.ocrDetectionLongSide, preferences.ocrBinarisePercent,
                    preferences.ocrBoxScorePercent, preferences.ocrUnclipTenths,
                ),
                cropTop = args["crop_top"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                cropBottom = args["crop_bottom"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                figures = args["figures"]?.trim()?.lowercase() !in NO_FIGURES,
                figureWidth = args["figure_width"]?.trim()?.toIntOrNull()?.coerceIn(320, 4096) ?: 1600,
                figureQuality = args["figure_quality"]?.trim()?.toIntOrNull()?.coerceIn(40, 100) ?: 82,
                title = args["title"]?.trim()?.takeIf { it.isNotEmpty() },
            )

            val directory = File(directoryPath)

            // This runs for minutes on a page like the sample. Say so immediately, and keep saying so
            // — dead air with nothing on screen is indistinguishable from a task that did nothing.
            flash(ctx.app, preferences, "記事を読み取り中…")
            val notifier = ProgressNotifier(ctx, pages.size)
            val started = SystemClock.elapsedRealtime()

            val document = try {
                ArticleReader.read(ctx.app, pages, options) { report ->
                    val line = describe(report, SystemClock.elapsedRealtime() - started)
                    ctx.variables.set("${prefix}_Phase", line)
                    ctx.variables.set("${prefix}_Pct", report.percent.toString())
                    notifier.update(line, report.percent)
                }
            } catch (missing: OcrModels.MissingModel) {
                return ActionResult.Failure(
                    "${missing.slot.label}のモデルが未設定です (${missing.slot.fileName})"
                )
            } catch (unreadable: ArticleReader.UnreadablePage) {
                return ActionResult.Failure(unreadable.message ?: "unreadable page")
            } catch (failure: Throwable) {
                return ActionResult.Failure(
                    "article failed: ${failure.message ?: failure.javaClass.simpleName}", failure
                )
            } finally {
                // Cancelled on both branches: a waiting notification that outlives its wait is worse
                // than none at all.
                notifier.dismiss()
            }

            val output = try {
                ArticleWriter.write(document, directory)
            } catch (failure: ArticleWriter.Failed) {
                return ActionResult.Failure(failure.message ?: "could not write the article")
            }

            val elapsed = SystemClock.elapsedRealtime() - started
            // The title comes off a screenshot, which can hold anything — sensitive, like the text
            // `ocr.recognize` returns.
            ctx.variables.set("${prefix}_Title", document.title, sensitive = true)
            ctx.variables.set("${prefix}_File", output.absolutePath)
            ctx.variables.set("${prefix}_Blocks", document.blocks.toString())
            ctx.variables.set("${prefix}_Figures", document.figures.toString())
            ctx.variables.set("${prefix}_Chars", document.characters.toString())
            ctx.variables.set("${prefix}_Pages", pages.size.toString())
            ctx.variables.set("${prefix}_Ms", elapsed.toString())
            ctx.variables.set("${prefix}_Phase", "完了")
            ctx.variables.set("${prefix}_Pct", "100")
            ctx.logger(
                "Article: ${document.blocks} blocks, ${document.figures} figures, " +
                    "${document.characters} characters from ${pages.size} page(s) in ${elapsed} ms " +
                    "-> ${output.absolutePath} (${output.length() / 1024} kB)"
            )
            flash(ctx.app, preferences, "記事を書き出しました")
            return ActionResult.Success
        } finally {
            temporary.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * One line of progress, for the notification and for `<prefix>_Phase`.
     *
     * Carries the estimate too. A headless run has no window to watch, so the notification is the
     * only place that can say how much longer this is going to take.
     */
    private fun describe(report: ArticleProgress, elapsedMs: Long): String {
        val head = when (report.phase) {
            ArticleProgress.Phase.SCANNING ->
                "ページ ${report.page + 1}/${report.pages} · " +
                    "スライス ${report.sliceInPage + 1}/${report.slicesInPage} · ${report.lines} 行"
            ArticleProgress.Phase.ASSEMBLING -> "組み立て中"
            ArticleProgress.Phase.FIGURES -> "図を切り出し中"
            ArticleProgress.Phase.DONE -> "完了"
        }
        val remaining = report.remainingMs(elapsedMs) ?: return head
        val seconds = remaining / 1000
        return "$head · 残り 約 %d:%02d".format(seconds / 60, seconds % 60)
    }

    /**
     * A page for the WINDOW, which outlives this action and must not be handed a file we then delete.
     *
     * A plain path goes through untouched — it is 白い熊's own screenshot and the window only reads it.
     * A URI has to be copied, and that copy becomes the window's to clean up rather than ours.
     */
    private fun stage(ctx: ActionContext, path: String): File? =
        if (path.startsWith("content://") || path.startsWith("file://")) copy(ctx, path)
        else File(path).takeIf { it.isFile }

    /** A plain path is used as-is; a URI is copied out first, because the decoder needs a real file. */
    private fun resolve(ctx: ActionContext, path: String, temporary: MutableList<File>): File? {
        if (!path.startsWith("content://") && !path.startsWith("file://")) {
            return File(path).takeIf { it.isFile }
        }
        return copy(ctx, path)?.also { temporary += it }
    }

    private fun copy(ctx: ActionContext, path: String): File? = runCatching {
        val directory = File(ctx.app.cacheDir, "ocr").apply { mkdirs() }
        val out = File(directory, "article-${System.currentTimeMillis()}-${path.hashCode()}.img")
        ctx.app.contentResolver.openInputStream(Uri.parse(path)).use { input ->
            checkNotNull(input) { "no stream for $path" }
            out.outputStream().use { sink -> input.copyTo(sink) }
        }
        check(out.length() > 0) { "empty image" }
        out
    }.getOrNull()

    /**
     * The ongoing notification that says the phone is still working.
     *
     * Rate-limited, because the scan reports after every slice — twenty-six of them on the sample's
     * first page — and a notification rebuilt that fast is a flicker rather than a progress bar.
     */
    private class ProgressNotifier(private val ctx: ActionContext, private val pages: Int) {
        private val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        private var lastPost = 0L

        init {
            if (allowed) {
                runCatching {
                    ctx.app.getSystemService(NotificationManager::class.java)
                        ?.createNotificationChannel(
                            NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                        )
                }
                post("読み取りを開始しました", 0)
            }
        }

        fun update(phase: String, percent: Int) {
            if (!allowed) return
            val now = SystemClock.elapsedRealtime()
            if (percent < 100 && now - lastPost < MIN_INTERVAL_MS) return
            lastPost = now
            post(phase, percent)
        }

        fun dismiss() {
            if (!allowed) return
            runCatching { NotificationManagerCompat.from(ctx.app).cancel(ID) }
        }

        private fun post(phase: String, percent: Int) {
            runCatching {
                NotificationManagerCompat.from(ctx.app).notify(
                    ID,
                    NotificationCompat.Builder(ctx.app, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_menu_upload)
                        .setContentTitle(if (pages > 1) "記事を読み取り中 ($pages ページ)" else "記事を読み取り中")
                        .setContentText(phase)
                        .setProgress(100, percent.coerceIn(0, 100), false)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .setSilent(true)
                        .build(),
                )
            }
        }

        private companion object {
            const val CHANNEL = "opentasker.article"
            const val CHANNEL_NAME = "白い熊 自由作業盤 記事"
            const val ID = 60792
            const val MIN_INTERVAL_MS = 1_200L
        }
    }

    private companion object {
        val NO_FIGURES = setOf("none", "no", "off", "false", "0")
        val SHOW_VALUES = setOf("true", "1", "yes", "on")
    }
}
