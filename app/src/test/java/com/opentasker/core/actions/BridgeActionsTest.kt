package com.opentasker.core.actions

import com.opentasker.ProductionSources
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic pieces the SMS-over-Jami bridge tasks are built on: provider.call, file.from_uri, sim.status, ussd.run. */
class BridgeActionsTest {
    private val provider: String by lazy { ProductionSources.read("com/opentasker/core/actions/ProviderActions.kt") }
    private val sim: String by lazy { ProductionSources.read("com/opentasker/core/actions/SimActions.kt") }

    @Test
    fun allBridgePiecesAreRegistered() {
        listOf("provider.call", "file.from_uri", "sim.status", "ussd.run", "dialog.form").forEach {
            assertNotNull("$it must be in the catalog", ActionCatalog.get(it))
        }
    }

    @Test
    fun telephonyActionsDeclareTheirPermission() {
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("sim.status").level)
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("ussd.run").level)
    }

    @Test
    fun resultKeysBecomeSafeVariableNames() {
        assertEquals("conversation_id", ProviderCallAction.varKey("conversation_id"))
        assertEquals("file_ready_uri", ProviderCallAction.varKey("file-ready.uri"))
        assertEquals("a_b", ProviderCallAction.varKey("a b"))
    }

    @Test
    fun anErrorAnswerFailsTheActionSoATryCatchesIt() {
        assertTrue("status=ERROR must fail", "status.equals(\"ERROR\", ignoreCase = true)" in provider)
        assertTrue("the legacy result=ERROR… must fail too", "legacy.startsWith(\"ERROR\")" in provider)
    }

    @Test
    fun timeoutsStayUnderTheEngineBudget() {
        assertTrue("provider.call must clamp its timeout below the 60 s action budget", "MAX_TIMEOUT_SEC = 55" in provider)
        assertTrue("ussd.run must clamp its timeout below the 60 s action budget", "coerceIn(1, 55)" in sim)
    }

    @Test
    fun simsAreAddressedBySlotNotSubscription() {
        assertTrue("sim.status must resolve the live subId from the slot", "DataSimSwitch.slots(ctx.app).firstOrNull { it.slotIndex == slot }" in sim)
    }
}
