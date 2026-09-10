package com.opentasker.core.external

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every answer from the automation bridge must be readable as TEXT, not only as a Bundle.
 *
 * The bridge is driven from a shell — `am broadcast` is how the workspace-mirror workflow documents
 * running a task headlessly — and `am` prints the result CODE and the result DATA, never extras.
 * Refusals lived only in extras and a log line, so a refused run and a run that never happened
 * looked identical: `result=0` and silence. Four attempts on 2026-09-09 went into chasing a bridge
 * that had been answering all along.
 *
 * A source gate, because the behaviour needs a real broadcast and a real Room database, while what
 * has to hold is a property of every response the receiver can build.
 */
class BridgeAnswersInTextTest {

    private val src by lazy {
        ProductionSources.read("com/opentasker/core/external/AutomationTargetReceiver.kt")
    }

    @Test
    fun `the result data is published alongside the code and the extras`() {
        assertTrue(
            "am broadcast prints resultData — it must be set",
            src.contains("pending.setResultData(response.data)"),
        )
    }

    @Test
    fun `a refusal carries its reason in the text`() {
        val body = ProductionSources.block(
            "com/opentasker/core/external/AutomationTargetReceiver.kt",
            "private fun failure(",
            "companion object {",
        )
        assertTrue("the reason belongs in the text answer", body.contains("data = \"ERROR:\$message\""))
        assertTrue("and still in the log", body.contains("AppLogger.warn(TAG, message)"))
    }

    @Test
    fun `every response type supplies a text answer`() {
        // `data` has no default on purpose: a new response that forgets it will not compile, rather
        // than silently reintroducing a silent answer.
        // Read from the whole file rather than a sliced block: the declaration's own KDoc contains
        // a ")", which is what a naive block would stop at.
        assertTrue("the field must exist", src.contains("val data: String,"))
        assertFalse("and must not be defaultable", src.contains("val data: String = "))
    }

    @Test
    fun `an accepted run returns its execution id in the text`() {
        // Without it a shell caller cannot poll ACTION_QUERY_EXECUTION at all: the id is only ever
        // handed back in extras it cannot read.
        assertTrue(src.contains("data = \"OK:\$executionId\""))
    }
}
