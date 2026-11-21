package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.power.ShellResult
import com.opentasker.core.power.ShizukuCommandPolicy
import com.opentasker.core.power.ShizukuPowerBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnDisplayActionTest {
    @Test
    fun theActionIsRegisteredAsAnElevatedSettingsWrite() {
        assertNotNull("aod.set must be in the catalog", ActionCatalog.get("aod.set"))
        assertTrue("aod.set must be an elevated action", "aod.set" in ShizukuPowerBackend.elevatedActionIds)
        assertEquals(
            "Two writes for each switch and Samsung's read are allowed, in the order the action picks them",
            listOf(
                listOf("settings", "put", "secure", "doze_always_on", "1"),
                listOf("settings", "put", "secure", "doze_always_on", "0"),
                listOf("settings", "put", "system", "aod_mode", "1"),
                listOf("settings", "put", "system", "aod_mode", "0"),
                listOf("settings", "get", "system", "aod_mode"),
            ),
            (0 until ShizukuCommandPolicy.variantCount("aod.set")).map { ShizukuCommandPolicy.command("aod.set", it) },
        )
        AlwaysOnDisplaySwitch.entries.forEach { switch ->
            assertEquals(
                listOf("settings", "put", switch.table, switch.key, "1"),
                ShizukuCommandPolicy.command("aod.set", switch.onVariant),
            )
            assertEquals(
                listOf("settings", "put", switch.table, switch.key, "0"),
                ShizukuCommandPolicy.command("aod.set", switch.offVariant),
            )
        }
        assertEquals(
            listOf("settings", "get", "system", "aod_mode"),
            ShizukuCommandPolicy.command("aod.set", AlwaysOnDisplaySwitch.SAMSUNG_READ_VARIANT),
        )
        assertFalse(
            "The write must not accept another secure key",
            ShizukuCommandPolicy.isExact("aod.set", listOf("settings", "put", "secure", "doze_enabled", "1")),
        )
        assertFalse(
            "Only aod_mode may be read",
            ShizukuCommandPolicy.isExact("aod.set", listOf("settings", "get", "secure", "android_id")),
        )
    }

    @Test
    fun itDeclaresItselfUnsupportedUntilTheElevatedTransportIsReady() {
        // Same level the other elevated writes carry: the transport has to be set up first.
        assertEquals(
            ActionCapabilityRegistry.get("airplane.toggle").level,
            ActionCapabilityRegistry.get("aod.set").level,
        )
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("aod.set").level)
    }

    @Test
    fun theSwitchFollowsTheMakerAndTheKeysThatAreThere() {
        // One UI: doze_always_on reads null and aod_mode is the switch.
        assertEquals(AlwaysOnDisplaySwitch.SAMSUNG, AlwaysOnDisplaySwitch.choose("samsung", null, 0))
        // A Samsung where something once wrote doze_always_on still follows aod_mode.
        assertEquals(AlwaysOnDisplaySwitch.SAMSUNG, AlwaysOnDisplaySwitch.choose("Samsung", 1, 0))
        // A rebadged build with Samsung's key and no AOSP one.
        assertEquals(AlwaysOnDisplaySwitch.SAMSUNG, AlwaysOnDisplaySwitch.choose("Acme", null, 1))
        // AOSP, and an AOSP build on a Samsung phone, keep doze_always_on.
        assertEquals(AlwaysOnDisplaySwitch.AOSP, AlwaysOnDisplaySwitch.choose("Google", 0, null))
        assertEquals(AlwaysOnDisplaySwitch.AOSP, AlwaysOnDisplaySwitch.choose("Google", 1, 1))
        assertEquals(AlwaysOnDisplaySwitch.AOSP, AlwaysOnDisplaySwitch.choose("samsung", 0, null))
        // Neither key.
        assertNull(AlwaysOnDisplaySwitch.choose("samsung", null, null))
        assertNull(AlwaysOnDisplaySwitch.choose("Google", null, null))

        assertTrue(AlwaysOnDisplaySwitch.needsSamsungKey(" samsung ", 1))
        assertFalse(AlwaysOnDisplaySwitch.needsSamsungKey("Google", 0))
        assertTrue(AlwaysOnDisplaySwitch.needsSamsungKey("Google", null))
        assertEquals(1, AlwaysOnDisplaySwitch.parseSettingsGet("1\n"))
        assertNull(AlwaysOnDisplaySwitch.parseSettingsGet("null"))
    }

    @Test
    fun onASamsungItWritesAndReadsBackAodMode() {
        val device = FakeDevice(manufacturer = "samsung", doze = null, aodMode = 0)

        assertEquals(ActionResult.Success, device.run("on"))
        assertEquals(1, device.aodMode)
        assertEquals(listOf(GET, PUT_AOD_ON, GET), device.commands)

        assertEquals(ActionResult.Success, device.run("toggle"))
        assertEquals(0, device.aodMode)
        assertNull("doze_always_on is never written on a Samsung", device.doze)
    }

    @Test
    fun anAospDeviceStillWritesDozeAlwaysOnAndNeverReadsAodMode() {
        val device = FakeDevice(manufacturer = "Google", doze = 0, aodMode = null)

        assertEquals(ActionResult.Success, device.run("on"))
        assertEquals(1, device.doze)
        assertEquals(listOf(PUT_DOZE_ON), device.commands)

        assertEquals(ActionResult.Success, device.run("off"))
        assertEquals(0, device.doze)
        assertEquals(listOf(PUT_DOZE_ON, PUT_DOZE_OFF), device.commands)
    }

    @Test
    fun aWriteTheBuildAcceptsAndIgnoresStillFails() {
        listOf(
            FakeDevice(manufacturer = "Google", doze = 0, aodMode = null, ignoresWrites = true),
            FakeDevice(manufacturer = "samsung", doze = null, aodMode = 0, ignoresWrites = true),
        ).forEach { device ->
            val result = device.run("on")
            assertTrue(device.manufacturer, result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message, "accepted the write and ignored it" in result.message)
        }
    }

    @Test
    fun neitherKeyStillRefusesWithoutWriting() {
        listOf(
            FakeDevice(manufacturer = "samsung", doze = null, aodMode = null),
            FakeDevice(manufacturer = "Google", doze = null, aodMode = null),
        ).forEach { device ->
            val result = device.run("on")
            assertTrue(device.manufacturer, result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message, "does not expose the always-on display setting" in result.message)
            assertTrue(device.commands.toString(), device.commands.none { it != GET })
        }
    }

    @Test
    fun aSamsungWithoutShizukuSaysSoInsteadOfClaimingThereIsNoSwitch() {
        val device = FakeDevice(manufacturer = "samsung", doze = null, aodMode = 1, shizukuUp = false)

        val result = device.run("off")

        assertEquals(ActionResult.Failure(SHIZUKU_DOWN), result)
        assertEquals(1, device.aodMode)
    }

    /** A phone's two possible switches behind aod.set's allowlisted commands. */
    private class FakeDevice(
        val manufacturer: String,
        var doze: Int?,
        var aodMode: Int?,
        private val ignoresWrites: Boolean = false,
        private val shizukuUp: Boolean = true,
    ) {
        val commands = mutableListOf<List<String>>()

        fun run(state: String): ActionResult {
            val action = AlwaysOnDisplayAction(
                manufacturer = { manufacturer },
                readDozeAlwaysOn = { doze },
                shell = ::shell,
            )
            return runBlocking { action.run(ActionContext(ContextWrapper(null), VariableStore()), mapOf("state" to state)) }
        }

        private fun shell(variant: Int): ShellResult {
            if (!shizukuUp) return ShellResult.Failure(SHIZUKU_DOWN)
            val argv = ShizukuCommandPolicy.command("aod.set", variant) ?: return ShellResult.Failure("not allowlisted")
            commands += argv
            if (argv[1] == "get") return ShellResult.Success(aodMode?.toString() ?: "null", 0)
            if (!ignoresWrites) {
                val value = argv[4].toInt()
                if (argv[3] == "aod_mode") aodMode = value else doze = value
            }
            return ShellResult.Success("", 0)
        }
    }

    private companion object {
        const val SHIZUKU_DOWN = "Can't reach Shizuku, so nothing ran."
        val GET = listOf("settings", "get", "system", "aod_mode")
        val PUT_AOD_ON = listOf("settings", "put", "system", "aod_mode", "1")
        val PUT_DOZE_ON = listOf("settings", "put", "secure", "doze_always_on", "1")
        val PUT_DOZE_OFF = listOf("settings", "put", "secure", "doze_always_on", "0")
    }
}
