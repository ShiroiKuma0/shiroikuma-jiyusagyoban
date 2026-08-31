package com.opentasker.core.ocr.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the bars, on the sample's own shape: 25 slices then 19, 44 in all.
 *
 * Worth its own test because the bars answer different questions and it is easy to make them answer
 * the same one — the page bar must reach full at the end of EVERY page, and the total only once.
 */
class ArticleProgressTest {

    private fun scanning(page: Int, slice: Int, inPage: Int, done: Int) = ArticleProgress(
        page = page, pages = 2, sliceInPage = slice, slicesInPage = inPage,
        slicesDone = done, slicesTotal = 45, lines = 0, phase = ArticleProgress.Phase.SCANNING,
    )

    @Test
    fun `the page bar fills once per page and the total bar only at the end`() {
        assertEquals(1f, scanning(0, 26, 26, 26).pageFraction, 0.001f)
        // …while the job is only 26 of 45 slices through, and the scan is not the whole job.
        assertTrue(scanning(0, 26, 26, 26).totalFraction in 0.5f..0.55f)
        assertEquals(1f, scanning(1, 19, 19, 45).pageFraction, 0.001f)
        assertTrue("the total bar filled before the figures were cropped",
            scanning(1, 19, 19, 45).totalFraction < 1f)
    }

    @Test
    fun `the tail phases advance the total bar rather than sitting at full`() {
        val scanned = scanning(1, 19, 19, 45).totalFraction
        val assembling = scanning(1, 19, 19, 45).copy(phase = ArticleProgress.Phase.ASSEMBLING)
        val figures = assembling.copy(phase = ArticleProgress.Phase.FIGURES)
        val done = assembling.copy(phase = ArticleProgress.Phase.DONE)
        assertEquals(scanned, assembling.totalFraction, 0.001f)
        assertTrue(figures.totalFraction > assembling.totalFraction)
        assertTrue(done.totalFraction > figures.totalFraction)
        assertEquals(1f, done.totalFraction, 0.001f)
        assertEquals(100, done.percent)
    }

    @Test
    fun `a page that yields no slices does not divide by zero`() {
        val empty = scanning(0, 0, 0, 0).copy(slicesTotal = 0)
        assertEquals(1f, empty.pageFraction, 0.001f)
        assertEquals(0f, empty.totalFraction, 0.001f)
    }

    @Test
    fun `the start state is at zero`() {
        val start = ArticleProgress.start(pages = 2, slicesTotal = 45)
        assertEquals(0f, start.totalFraction, 0.001f)
        assertEquals(0, start.percent)
        assertEquals(2, start.pages)
    }
}

/** The top bar and the clock: the parts added when two bars turned out to read as stalled. */
class SliceProgressTest {

    private fun mid(step: ArticleProgress.Step, within: Float, clock: SliceClock) = ArticleProgress(
        page = 0, pages = 2, sliceInPage = 4, slicesInPage = 25,
        slicesDone = 4, slicesTotal = 44, lines = 60,
        phase = ArticleProgress.Phase.SCANNING,
        step = step, sliceFraction = clock.fractionAt(step, within),
    )

    @Test
    fun `the slice bar advances through the steps of one slice`() {
        val clock = SliceClock()
        val seen = ArticleProgress.Step.entries.map { mid(it, 0f, clock).sliceProgress }
        assertEquals(0f, seen.first(), 0.001f)
        seen.zipWithNext { a, b -> assertTrue("steps must not go backwards: $seen", b > a) }
        assertTrue("the last step must not already be full: $seen", seen.last() < 1f)
    }

    @Test
    fun `recognition subdivides, so the longest step is not a stall`() {
        val clock = SliceClock()
        val quarter = mid(ArticleProgress.Step.RECOGNISE, 0.25f, clock).sliceProgress
        val half = mid(ArticleProgress.Step.RECOGNISE, 0.5f, clock).sliceProgress
        val full = mid(ArticleProgress.Step.RECOGNISE, 1f, clock).sliceProgress
        assertTrue(quarter < half && half < full)
    }

    @Test
    fun `the clock learns the real split from the first slice`() {
        val clock = SliceClock()
        // A slice where recognition dominated — the 81 MB model does exactly this.
        clock.record(ArticleProgress.Step.DECODE, 100)
        clock.record(ArticleProgress.Step.ROWS, 100)
        clock.record(ArticleProgress.Step.DETECT, 800)
        clock.record(ArticleProgress.Step.RECOGNISE, 8000)
        clock.record(ArticleProgress.Step.MEASURE, 100)
        val before = clock.fractionAt(ArticleProgress.Step.RECOGNISE)
        clock.sliceFinished()
        val after = clock.fractionAt(ArticleProgress.Step.RECOGNISE)
        assertTrue("the opening guess should be replaced by measurement", after < before)
        assertTrue("recognition should now start early in the bar: $after", after < 0.15f)
    }

    @Test
    fun `the estimate waits until it is worth trusting, then falls as work is done`() {
        val early = ArticleProgress.start(2, 44)
        assertNull("an estimate from nothing is worse than none", early.remainingMs(3_000))

        val clock = SliceClock()
        val quarter = ArticleProgress(
            page = 0, pages = 2, sliceInPage = 11, slicesInPage = 25,
            slicesDone = 11, slicesTotal = 44, lines = 150,
            phase = ArticleProgress.Phase.SCANNING,
            step = ArticleProgress.Step.DETECT, sliceFraction = clock.fractionAt(ArticleProgress.Step.DETECT),
        )
        // 11 of 44 slices in 90 s → around 4½ more minutes, and the scan is 92 % of the job.
        val remaining = quarter.remainingMs(90_000)!!
        assertTrue("measured ${remaining / 1000}s", remaining in 240_000..320_000)

        val later = quarter.copy(sliceInPage = 22, slicesDone = 22)
        assertTrue(later.remainingMs(180_000)!! < remaining)
    }
}

/** Carrying a report forward against the clock — what keeps the bars moving inside a long step. */
class AdvancedProgressTest {

    private val clock = SliceClock()

    private fun at(step: ArticleProgress.Step) = ArticleProgress(
        page = 0, pages = 2, sliceInPage = 4, slicesInPage = 25,
        slicesDone = 4, slicesTotal = 44, lines = 60,
        phase = ArticleProgress.Phase.SCANNING,
        step = step,
        sliceFraction = clock.fractionAt(step),
        stepShare = clock.shareOf(step),
        stepExpectedMs = clock.expectedMs(step),
    )

    @Test
    fun `a report carried forward advances, and all three bars with it`() {
        val detect = at(ArticleProgress.Step.DETECT)
        val half = detect.advanced(0.5f)
        assertTrue(half.sliceProgress > detect.sliceProgress)
        assertTrue(half.pageFraction > detect.pageFraction)
        assertTrue(half.totalFraction > detect.totalFraction)
    }

    @Test
    fun `it never runs past the step it was told about`() {
        val detect = at(ArticleProgress.Step.DETECT)
        val next = at(ArticleProgress.Step.RECOGNISE)
        // Overrunning the expected time must not carry the bar into the following step's territory.
        assertTrue(detect.advanced(5f).sliceProgress <= next.sliceProgress + 1e-4f)
        assertEquals(detect.advanced(1f).sliceProgress, detect.advanced(9f).sliceProgress, 1e-4f)
    }

    @Test
    fun `the last step still cannot fill the bar before the slice is done`() {
        assertTrue(at(ArticleProgress.Step.MEASURE).advanced(1f).sliceProgress <= 1f)
    }

    @Test
    fun `a step with no expected time simply does not move`() {
        val unknown = at(ArticleProgress.Step.DETECT).copy(stepShare = 0f, stepExpectedMs = 0L)
        assertEquals(unknown.sliceProgress, unknown.advanced(1f).sliceProgress, 1e-4f)
    }
}
