package com.opentasker.core.ocr.article

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import com.opentasker.core.ocr.OcrEngine
import com.opentasker.core.ocr.OcrPoint
import com.opentasker.core.ocr.OcrScript
import com.opentasker.core.ocr.OcrTuning
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads whole articles out of scrolling screenshots.
 *
 * The engine underneath is the same PP-OCRv5 pair 文字認識 uses; what this adds is everything a page
 * 41 744 pixels tall needs and a screenshot-sized one does not. The detector caps its input at a
 * 1600 px long side, so handing it a page like that whole would squash 2048 px of column width down
 * to 62 px and read nothing at all. Instead the page is walked in slices, each detected and
 * recognised at very nearly the scale the tuning was measured at, with the boxes lifted back into
 * page coordinates as they come.
 */
object ArticleReader {

    data class Options(
        val script: OcrScript = OcrScript.DEFAULT,
        val highAccuracy: Boolean = true,
        val tuning: OcrTuning = OcrTuning.DEFAULT,
        /** Pixels ignored at the top and bottom of every page, over and above the chrome detection. */
        val cropTop: Int = 0,
        val cropBottom: Int = 0,
        val figures: Boolean = true,
        val figureWidth: Int = 1600,
        val figureQuality: Int = 82,
        /** Overrides the headline the title would otherwise be taken from. */
        val title: String? = null,
    )

    /** Where the work has got to. See [ArticleProgress] for why it is not one number. */
    fun interface Progress {
        fun report(progress: ArticleProgress)
    }

    class UnreadablePage(val file: File) :
        IllegalArgumentException("could not open \"${file.absolutePath}\" as an image")

    suspend fun read(
        context: Context,
        files: List<File>,
        options: Options,
        progress: Progress = Progress { },
    ): ArticleDocument = withContext(Dispatchers.Default) {
        require(files.isNotEmpty()) { "no pages" }

        val pages = ArrayList<LongPage>(files.size)
        try {
            files.forEach { file ->
                pages += LongPage.open(file) ?: throw UnreadablePage(file)
            }

            val totalSlices = max(1, pages.sumOf { sliceTops(it.height).size })
            var doneSlices = 0
            progress.report(ArticleProgress.start(pages.size, totalSlices))

            val clock = SliceClock()
            val scans = ArrayList<PageScan>(pages.size)
            pages.forEachIndexed { index, page ->
                val rows = RowProfile(page.height)
                val boxes = ArrayList<ScannedBox>()
                val tops = sliceTops(page.height)

                tops.forEachIndexed { sliceIndex, top ->
                    /**
                     * @param within how far into [step] this report is (recognition reports per batch)
                     * @param slot the share of the slice still to come before the next report — one
                     *   whole step normally, one batch of it while recognising
                     */
                    fun report(step: ArticleProgress.Step, within: Float = 0f, slot: Float = 1f) =
                        progress.report(
                            ArticleProgress(
                                page = index, pages = pages.size,
                                sliceInPage = sliceIndex, slicesInPage = tops.size,
                                slicesDone = doneSlices, slicesTotal = totalSlices,
                                lines = boxes.size, phase = ArticleProgress.Phase.SCANNING,
                                step = step, sliceFraction = clock.fractionAt(step, within),
                                stepShare = clock.shareOf(step) * slot,
                                stepExpectedMs = (clock.expectedMs(step) * slot).toLong(),
                            )
                        )

                    /** Runs one step of the slice, timing it so the bar learns what it costs. */
                    suspend fun <T> step(which: ArticleProgress.Step, body: suspend () -> T): T {
                        // Checked at every step rather than once a slice, so 「中止」 lands inside one
                        // inference rather than a whole detect-and-recognise pass.
                        coroutineContext.ensureActive()
                        report(which)
                        val began = SystemClock.elapsedRealtime()
                        return body().also {
                            clock.record(which, SystemClock.elapsedRealtime() - began)
                        }
                    }

                    val bottom = min(page.height, top + LongPage.SLICE)
                    val core = coreOf(top, bottom, page.height)
                    val image = step(ArticleProgress.Step.DECODE) { page.slice(top, bottom) }
                    if (image != null) {
                        step(ArticleProgress.Step.ROWS) {
                            rows.absorb(image, top, page.background, core.first, core.second)
                        }
                        val detected = step(ArticleProgress.Step.DETECT) {
                            OcrEngine.detect(context, image, options.tuning)
                        }
                        val recognised = step(ArticleProgress.Step.RECOGNISE) {
                            OcrEngine.recognise(
                                context, detected, options.script, options.highAccuracy,
                            ) { done, all ->
                                // Recognition is the longest step this can be split inside, and
                                // without this the top bar stands still through all of it.
                                val batches = max(1, all)
                                report(
                                    ArticleProgress.Step.RECOGNISE,
                                    within = done.toFloat() / batches,
                                    slot = 1f / batches,
                                )
                            }
                        }
                        step(ArticleProgress.Step.MEASURE) {
                            recognised.blocks.forEach { block ->
                                val centre = top +
                                    ((block.quad.minOf { it.y } + block.quad.maxOf { it.y }) / 2f).toInt()
                                // Cores tile the page exactly, so each line is claimed once only.
                                if (centre >= core.first && centre < core.second) {
                                    boxes += ScannedBox(
                                        text = block.text,
                                        confidence = block.confidence,
                                        quad = block.quad.map { OcrPoint(it.x, it.y + top) },
                                        // Measured here, while this slice's pixels still exist.
                                        probe = LineStyle.of(image, block.quad, page.background),
                                    )
                                }
                            }
                        }
                    }
                    doneSlices++
                    clock.sliceFinished()
                }

                scans += PageScan(
                    index = index,
                    path = files[index].absolutePath,
                    width = page.width,
                    height = page.height,
                    background = page.background,
                    rows = rows,
                    boxes = boxes,
                )
            }

            fun tail(phase: ArticleProgress.Phase) = progress.report(
                ArticleProgress(
                    page = pages.size - 1, pages = pages.size,
                    sliceInPage = 1, slicesInPage = 1,
                    slicesDone = totalSlices, slicesTotal = totalSlices,
                    lines = scans.sumOf { it.boxes.size }, phase = phase,
                )
            )

            tail(ArticleProgress.Phase.ASSEMBLING)
            var firstFigure = true
            val nodes = ArticleLayout.build(
                scans = scans,
                cropTop = options.cropTop,
                cropBottom = options.cropBottom,
                figureJpeg = { page, left, top, right, bottom ->
                    if (firstFigure) { firstFigure = false; tail(ArticleProgress.Phase.FIGURES) }
                    if (!options.figures) null
                    else pages.getOrNull(page)?.figureJpeg(
                        Rect(left, top, right, bottom),
                        options.figureWidth,
                        options.figureQuality,
                    )?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                },
            )

            tail(ArticleProgress.Phase.DONE)
            ArticleDocument(
                title = options.title?.takeIf { it.isNotBlank() } ?: titleOf(nodes),
                sources = files.map { it.absolutePath },
                nodes = nodes,
            )
        } finally {
            pages.forEach { runCatching { it.close() } }
        }
    }

    /** Where each slice starts. The last one is short rather than overhanging the page. */
    private fun sliceTops(height: Int): List<Int> {
        val step = LongPage.SLICE - LongPage.OVERLAP
        val tops = ArrayList<Int>(height / step + 2)
        var top = 0
        while (true) {
            tops += top
            if (top + LongPage.SLICE >= height) break
            top += step
        }
        return tops
    }

    /** The rows this slice owns: half the overlap is given away at each end that has a neighbour. */
    private fun coreOf(top: Int, bottom: Int, height: Int): Pair<Int, Int> = Pair(
        if (top == 0) 0 else top + LongPage.OVERLAP / 2,
        if (bottom >= height) height else bottom - LongPage.OVERLAP / 2,
    )

    /**
     * The article's title, which is also its filename.
     *
     * The biggest type on the page, if there is any — on the sample that is the headline, measured at
     * 4.11x body. Failing that the first thing that reads like a heading, and failing that the opening
     * of the first paragraph, which is at least recognisable in a directory listing.
     */
    private fun titleOf(nodes: List<ArticleNode>): String {
        val texts = nodes.filterIsInstance<ArticleText>()
        texts.firstOrNull { it.kind == ArticleKind.TITLE }?.let { return it.plain }
        texts.filter { it.kind.isHeading }.maxByOrNull { it.sizeRatio }?.let { return it.plain }
        texts.firstOrNull { it.kind == ArticleKind.PARAGRAPH }
            ?.let { return it.plain.take(60).trim() }
        return "記事"
    }
}
