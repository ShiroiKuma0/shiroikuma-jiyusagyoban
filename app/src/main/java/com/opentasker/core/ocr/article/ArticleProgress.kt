package com.opentasker.core.ocr.article

/**
 * Where a read has got to, in enough detail to draw three bars.
 *
 * One number was not enough. A 44-slice read spends six minutes at "43 %", and a single bar creeping
 * across cannot say whether that is the first page nearly done or the second page barely begun. Nor
 * were two: a bar that only moves once every seven seconds looks stopped (白い熊, 2026-08-09). So the
 * window shows the slice in hand above the page in hand above the whole job, and the top one moves
 * every second or so because it is watching the steps INSIDE a slice.
 */
data class ArticleProgress(
    /** 0-based index of the page being read. */
    val page: Int,
    val pages: Int,
    /** Slices FINISHED on this page — the one in hand is `sliceInPage + 1`. */
    val sliceInPage: Int,
    val slicesInPage: Int,
    /** Slices finished across every page, and the total. */
    val slicesDone: Int,
    val slicesTotal: Int,
    /** Text lines recognised so far on this page. */
    val lines: Int,
    val phase: Phase,
    /** Which part of the current slice is running. */
    val step: Step = Step.DECODE,
    /** How far through the current slice the reported step BEGINS, 0..1. */
    val sliceFraction: Float = 0f,
    /** How much of the slice the reported step is worth — what [advanced] may add. */
    val stepShare: Float = 0f,
    /** How long that step is expected to take, from the slices already read. */
    val stepExpectedMs: Long = 0L,
) {
    enum class Phase { SCANNING, ASSEMBLING, FIGURES, DONE }

    /** The steps one slice goes through, in order, with what each is called on screen. */
    enum class Step(val label: String) {
        DECODE("切り出し"),
        ROWS("走査"),
        DETECT("行の検出"),
        RECOGNISE("文字の認識"),
        MEASURE("字形の測定"),
    }

    /** The top bar: the slice in hand. */
    val sliceProgress: Float get() = sliceFraction.coerceIn(0f, 1f)

    /**
     * This report, carried forward by [within] of the step it announced.
     *
     * Progress arrives when a step STARTS, and detection is one indivisible call into ONNX that runs
     * for seconds — so between reports every bar stands still, which is the thing three bars were
     * supposed to cure. The window therefore advances the last report against the clock, using how
     * long that step took on the slices already read. Nothing is invented: if the step overruns its
     * usual time the bar simply stops at the step's own boundary and waits, which is honest.
     */
    fun advanced(within: Float): ArticleProgress = copy(
        sliceFraction = (sliceFraction + stepShare * within.coerceIn(0f, 1f)).coerceIn(0f, 1f),
    )

    /** The middle bar: the page in hand, counting the part-finished slice. */
    val pageFraction: Float
        get() = if (slicesInPage <= 0) 1f
        else ((sliceInPage + sliceProgress) / slicesInPage).coerceIn(0f, 1f)

    /**
     * The bottom bar: the whole job.
     *
     * Counts the part-finished slice too, so it creeps rather than stepping — which is what makes the
     * remaining-time estimate above it stop lurching between slices.
     *
     * The scan is [SCAN_SHARE] of it; assembling the document and cropping the figures are the rest.
     * They are quick beside the scan, but they are not instant on a page this tall, and a bar that
     * sat at 100 % through them would be lying at exactly the moment 白い熊 is waiting for the file.
     */
    val totalFraction: Float
        get() = when (phase) {
            Phase.SCANNING ->
                if (slicesTotal <= 0) 0f
                else (slicesDone + sliceProgress) / slicesTotal * SCAN_SHARE
            Phase.ASSEMBLING -> SCAN_SHARE
            Phase.FIGURES -> SCAN_SHARE + (1f - SCAN_SHARE) / 2f
            Phase.DONE -> 1f
        }.coerceIn(0f, 1f)

    val percent: Int get() = (totalFraction * 100).toInt()

    /**
     * Seconds still to go, from how long [elapsedMs] bought of [totalFraction].
     *
     * Null until there is enough of the job behind us to divide by — early on the estimate swings by
     * minutes between slices, and a number that does that is worse than no number.
     */
    fun remainingMs(elapsedMs: Long): Long? {
        val fraction = totalFraction
        if (fraction < MIN_FRACTION_FOR_ETA || elapsedMs <= 0L) return null
        return ((elapsedMs * (1f - fraction) / fraction).toLong()).coerceAtLeast(0L)
    }

    companion object {
        private const val SCAN_SHARE = 0.92f

        /** Below this much of the job done, the remaining-time estimate is not worth showing. */
        private const val MIN_FRACTION_FOR_ETA = 0.02f

        fun start(pages: Int, slicesTotal: Int) = ArticleProgress(
            page = 0, pages = pages, sliceInPage = 0, slicesInPage = 0,
            slicesDone = 0, slicesTotal = slicesTotal, lines = 0, phase = Phase.SCANNING,
        )
    }
}

/**
 * How long each step of a slice takes, learned while reading.
 *
 * The top bar needs to know that detection is a bigger slice of the wait than the crop is, and the
 * honest source for that is the phone doing the work: the split moves with the model chosen (the
 * 81 MB recogniser shifts it a long way toward recognition), with the page, and with whatever else
 * the phone is doing. So the first slice is drawn with a rough guess and every slice after it with
 * the average of the ones before.
 */
internal class SliceClock {
    private val totals = LongArray(ArticleProgress.Step.entries.size)
    private var measured = 0

    /** A rough opening guess, replaced by measurement as soon as one slice has finished. */
    private val opening = floatArrayOf(0.06f, 0.09f, 0.50f, 0.28f, 0.07f)

    /** …and in milliseconds, for the same first slice. Roughly a 7 s slice on the fast model. */
    private val openingMs = longArrayOf(400, 600, 3_500, 2_000, 500)

    fun record(step: ArticleProgress.Step, durationMs: Long) {
        totals[step.ordinal] += durationMs
    }

    fun sliceFinished() {
        measured++
    }

    /** Share of a slice spent before [step] starts, plus [within] of [step] itself. */
    fun fractionAt(step: ArticleProgress.Step, within: Float = 0f): Float {
        val weights = weights()
        var before = 0f
        for (index in 0 until step.ordinal) before += weights[index]
        return (before + weights[step.ordinal] * within.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** How much of a slice [step] is worth. */
    fun shareOf(step: ArticleProgress.Step): Float = weights()[step.ordinal]

    /** How long [step] usually takes, averaged over the slices already read. */
    fun expectedMs(step: ArticleProgress.Step): Long =
        if (measured == 0) openingMs[step.ordinal] else totals[step.ordinal] / measured

    private fun weights(): FloatArray {
        if (measured == 0) return opening
        val sum = totals.sum()
        if (sum <= 0L) return opening
        return FloatArray(totals.size) { totals[it].toFloat() / sum }
    }
}
