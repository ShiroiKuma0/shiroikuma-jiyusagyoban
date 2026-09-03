package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection restriction has to survive an OpenTasker bundle round trip, and has to be
 * announced as lost on the way to Tasker, which has no equivalent field.
 */
class HttpNetworkConstraintTransferTest {

    private val restricted = Task(
        id = 1L,
        name = "Sync over Wi-Fi",
        actions = listOf(
            ActionSpec(
                id = 1L,
                type = "http.request",
                args = mapOf("url" to "https://example.test/sync", "network" to "wifi"),
            ),
        ),
    )

    @Test
    fun `an openTasker bundle keeps the connection restriction`() {
        val bundle = OpenTaskerBundle(appVersion = "0.0.0", exportedAtEpochMs = 0L, tasks = listOf(restricted))

        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))

        assertEquals("wifi", decoded.tasks.single().actions.single().args["network"])
    }

    /**
     * The item asked for an export warning saying Tasker has no equivalent field. It turns out no
     * warning is needed and none should be added: Tasker has no equivalent for the whole HTTP
     * Request action, so the exporter already drops the action and reports it as skipped. A
     * warning about one lost field on an action that is itself lost would be misleading.
     */
    @Test
    fun `exporting to Tasker drops the whole request, restriction included`() {
        val result = TaskerXmlExporter.export(profiles = emptyList(), tasks = listOf(restricted))

        val skipped = result.skippedActions.single()
        assertEquals("http.request", skipped.actionType)
        assertTrue(skipped.reason, "No Tasker equivalent" in skipped.reason)
        assertTrue("the action must not reach the XML", "http.request" !in result.xml)
    }
}
