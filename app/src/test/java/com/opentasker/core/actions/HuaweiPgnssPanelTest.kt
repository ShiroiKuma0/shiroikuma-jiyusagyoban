package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.huawei.pgnss.PgnssProgress
import com.opentasker.core.huawei.pgnss.PgnssStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `huawei.pgnss`'s half of `docs/huawei-pgnss-progress-contract.md`.
 *
 * The panel and the build were written separately against that document, so a name changed on one
 * side is a blank box on the other and neither build fails. This test is the document, executed: it
 * pins the ten variable names, the four-state step list, the direction the list may move in, and
 * the rule that `PgnssCount` is a count of real things rather than a percentage dressed up as one.
 */
class HuaweiPgnssPanelTest {

    private val variables = VariableStore()
    private val ctx = ActionContext(ContextWrapper(null), variables)
    private val panel = HuaweiPgnssAction.Panel(ctx, "HUAWEI_")

    private fun v(name: String): String? = variables.get("HUAWEI_$name")

    private fun report(
        step: PgnssStep,
        phase: String,
        detail: String = "",
        done: Int = 0,
        total: Int = 0,
        fraction: Double = 0.0,
        line: String? = null,
    ) = PgnssProgress(step, phase, detail, done, total, fraction, 0L, line)

    @Test
    fun everyVariableTheContractNamesIsPublished() {
        panel.start()
        for (name in listOf(
            "PgnssSteps", "PgnssPhase", "PgnssDetail", "PgnssCount", "PgnssPct",
            "PgnssElapsed", "PgnssEta", "PgnssLog", "PgnssResult", "PgnssFailed",
        )) {
            assertTrue("$name was never written", variables.get("HUAWEI_$name") != null)
        }
        // Four states, comma-joined, and MixedCase after the prefix — VariableStore routes by the
        // first letter's case, so an all-lowercase name would never reach a scene at all.
        assertEquals("run,wait,wait,wait", v("PgnssSteps"))
    }

    @Test
    fun theStepListIsFourStatesAndOnlyEverMovesForwards() {
        panel.start()
        assertEquals("run,wait,wait,wait", v("PgnssSteps"))
        panel.publish(report(PgnssStep.DOWNLOAD, "Downloading", done = 3, total = 11, fraction = 0.2))
        assertEquals("run,wait,wait,wait", v("PgnssSteps"))
        panel.publish(report(PgnssStep.BUILD, "Fitting GPS", done = 1, total = 2196, fraction = 0.6))
        assertEquals("done,run,wait,wait", v("PgnssSteps"))
        // A late report from a download that had not finished reporting must not walk it back.
        panel.publish(report(PgnssStep.DOWNLOAD, "Downloading", fraction = 0.1, line = "late"))
        assertEquals("done,run,wait,wait", v("PgnssSteps"))
        // ...and neither may the bar. 0.6 of the BUILD reads 54 %, not 60: the last tenth of the
        // bar belongs to steps 3 and 4, which this action does not run.
        assertEquals("54", v("PgnssPct"))
    }

    @Test
    fun theCountIsRealUnitsAndTheBarIsTheWholeRun() {
        panel.start()
        panel.publish(report(PgnssStep.DOWNLOAD, "Downloading", "EGM96.gfc", 3, 11, 0.15, "one"))
        assertEquals("3/11", v("PgnssCount"))
        assertEquals("EGM96.gfc", v("PgnssDetail"))
        assertEquals("Downloading", v("PgnssPhase"))
        assertEquals("13", v("PgnssPct"))
        assertEquals("one", v("PgnssLog"))
        // No denominator, no count: "3/0" would be worse than nothing.
        panel.publish(report(PgnssStep.BUILD, "Writing GLONASS", fraction = 0.75))
        assertEquals("", v("PgnssCount"))
    }

    @Test
    fun theLogKeepsTheLastFewLinesNewestAtTheBottom() {
        panel.start()
        for (i in 1..HuaweiPgnssAction.MAX_LOG_LINES + 3) {
            panel.publish(report(PgnssStep.DOWNLOAD, "Downloading", fraction = 0.1, line = "line $i"))
        }
        val log = v("PgnssLog")!!.lines()
        assertEquals(HuaweiPgnssAction.MAX_LOG_LINES, log.size)
        assertEquals("line 4", log.first())
        assertEquals("line ${HuaweiPgnssAction.MAX_LOG_LINES + 3}", log.last())
    }

    @Test
    fun aFailureNamesTheStepThatBrokeAndLeavesTheRestAlone() {
        panel.start()
        panel.publish(report(PgnssStep.BUILD, "Fitting GPS", fraction = 0.6))
        val result = panel.fail("SUMMARY", 2, "the orbit product ends before the 72 h window does")

        assertTrue(result is ActionResult.Failure)
        assertEquals("done,fail,wait,wait", v("PgnssSteps"))
        assertTrue(v("PgnssFailed")!!.startsWith("Build: "))
        assertTrue("the reason has to survive into the message", "72 h window" in v("PgnssFailed")!!)
        assertEquals("Failed", v("PgnssPhase"))
        assertEquals("", v("PgnssEta"))
        assertEquals("the orbit product ends before the 72 h window does", variables.get("SUMMARY"))
    }

    @Test
    fun aDownloadThatNeverStartsFailsOnStepOne() {
        panel.start()
        panel.fail(null, 1, "no network")
        assertEquals("fail,wait,wait,wait", v("PgnssSteps"))
        assertTrue(v("PgnssFailed")!!.startsWith("Download: "))
    }

    /** Steps 3 and 4 are `huawei.gnss`'s to move; a finished build leaves them waiting. */
    @Test
    fun aFinishedBuildMarksBothOfItsOwnStepsDoneAndNeitherOfTheOthers() {
        panel.start()
        panel.publish(report(PgnssStep.BUILD, "Built", fraction = 1.0))
        panel.finish(
            com.opentasker.core.huawei.pgnss.PgnssBuildResult(
                files = emptyMap(),
                bytes = 799_112L,
                windowStartGps = 1_472_126_400L,
                windowEndGps = 1_472_378_400L,
                summary = "6 files, 780 KB",
                notes = listOf("BeiDou: 3 satellites"),
            ),
        )
        assertEquals("done,done,wait,wait", v("PgnssSteps"))
        assertEquals("6 files, 780 KB", v("PgnssResult"))
        assertEquals("", v("PgnssEta"))
        // NOT 100. The build being finished is not the run being finished — the band has not been
        // told yet, let alone taken anything, and a full bar there was simply a lie.
        assertEquals("90", v("PgnssPct"))
        assertTrue("BeiDou: 3 satellites" in v("PgnssLog")!!)
    }

    @Test
    fun theElapsedClockKeepsMovingWhenNothingElseDoes() {
        panel.start()
        val first = v("PgnssElapsed")
        Thread.sleep(1_100)
        panel.tick()
        assertTrue("elapsed must tick on the clock, not on progress", v("PgnssElapsed") != first)
    }
}
