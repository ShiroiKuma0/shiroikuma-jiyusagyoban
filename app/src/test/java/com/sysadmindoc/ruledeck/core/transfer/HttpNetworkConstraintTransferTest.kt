package com.sysadmindoc.ruledeck.core.transfer

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

/** The connection restriction has to survive a bundle round trip. */
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
    fun `an ruleDeck bundle keeps the connection restriction`() {
        val bundle = RuleDeckBundle(appVersion = "0.0.0", exportedAtEpochMs = 0L, tasks = listOf(restricted))

        val decoded = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle))

        assertEquals("wifi", decoded.tasks.single().actions.single().args["network"])
    }
}
