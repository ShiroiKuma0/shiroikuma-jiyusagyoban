package com.sysadmindoc.ruledeck.core.actions

import android.content.ContextWrapper
import android.location.LocationManager
import com.sysadmindoc.ruledeck.core.capabilities.ActionCapabilityRegistry
import com.sysadmindoc.ruledeck.core.capabilities.CapabilityLevel
import com.sysadmindoc.ruledeck.core.capabilities.SetupRequirement
import com.sysadmindoc.ruledeck.core.capabilities.SetupRequirementResolver
import com.sysadmindoc.ruledeck.core.contexts.enabledLocationProviders
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionRegistry
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import com.sysadmindoc.ruledeck.core.engine.actionTimeoutMs
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Get Location against a stand-in for Android's location service. */
class LocationGetActionTest {
    private val variables = VariableStore()
    private val logs = mutableListOf<String>()

    /** Records what the action asked of the platform, and delivers [fixes] as soon as updates start. */
    private class FakeReadings(
        private val providers: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER),
        private val lastKnown: Map<String, LocationReading> = emptyMap(),
        private val fixes: List<LocationReading> = emptyList(),
    ) : LocationReadings {
        val started = mutableListOf<List<String>>()
        var stopped = 0
        val lastKnownAsked = mutableListOf<String>()

        override fun enabledProviders(): List<String> = providers

        override fun lastKnown(provider: String): LocationReading? {
            lastKnownAsked += provider
            return lastKnown[provider]
        }

        override fun start(providers: List<String>, onFix: (LocationReading) -> Unit): () -> Unit {
            started += providers
            fixes.forEach(onFix)
            return { stopped++ }
        }
    }

    private var readingsLookedUp = 0

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun run(readings: FakeReadings, vararg args: Pair<String, String>, permitted: Boolean = true): ActionResult {
        val action = LocationGetAction(
            permitted = { permitted },
            readings = { readingsLookedUp++; readings },
        )
        return runBlocking { action.run(ActionContext(ContextWrapper(null), variables, logger = { logs += it }), mapOf(*args)) }
    }

    @Test
    fun aFreshFixSetsTheFourVariablesAndLogsNoCoordinates() {
        val readings = FakeReadings(fixes = listOf(fix(accuracy = 12.4, ageMs = 2_900L, provider = "gps")))

        assertEquals(ActionResult.Success, run(readings))

        assertEquals(NY_LAT.toString(), variables.get("location_lat"))
        assertEquals(NY_LON.toString(), variables.get("location_lon"))
        assertEquals("12", variables.get("location_accuracy"))
        assertEquals("2", variables.get("location_age"))
        // Where the phone is stays out of the run log and previews, like a contact lookup's results.
        assertTrue(variables.isSensitive("location_lat"))
        assertTrue(variables.isSensitive("location_lon"))
        assertFalse(variables.isSensitive("location_accuracy"))
        assertFalse(variables.isSensitive("location_age"))
        assertEquals(1, readings.started.size)
        assertEquals(1, readings.stopped)
        assertTrue(logs.toString(), logs.any { "%location_lat" in it && "gps" in it })
        assertTrue(logs.toString(), logs.none { "40.69" in it || "74.14" in it })
    }

    @Test
    fun withoutLocationAccessItFailsNamingTheSetupRowAndStartsNothing() {
        val readings = FakeReadings(fixes = listOf(fix()))

        val result = run(readings, permitted = false)

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, "Foreground location row in Setup" in message)
        assertEquals(0, readingsLookedUp)
        assertTrue(readings.started.isEmpty())
        assertTrue(readings.lastKnownAsked.isEmpty())
        assertNull(variables.get("location_lat"))
    }

    @Test
    fun noFixInTimeFailsInsteadOfHangingAndStopsTheUpdates() {
        val readings = FakeReadings()

        val startedAt = System.nanoTime()
        val result = run(readings, "timeout" to "1")
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, "no fix in 1 s" in message)
        assertTrue(message, "Background location row in Setup" in message)
        assertTrue("took $tookMs ms", tookMs in 900L..10_000L)
        assertEquals(1, readings.stopped)
        assertNull(variables.get("location_lat"))
        assertNull(variables.get("location_age"))
    }

    @Test
    fun aFixLooserThanTheAccuracyIsSkippedForABetterOne() {
        val readings = FakeReadings(
            fixes = listOf(
                fix(latitude = 1.0, accuracy = 800.0, provider = "network"),
                fix(accuracy = 9.0, provider = "gps"),
            ),
        )

        assertEquals(ActionResult.Success, run(readings, "accuracy" to "25"))

        assertEquals(NY_LAT.toString(), variables.get("location_lat"))
        assertEquals("9", variables.get("location_accuracy"))
    }

    // An API 35 emulator handed a new GPS listener the fix it took four minutes earlier, within 39 ms.
    @Test
    fun aFixAndroidHadCachedIsSkippedForOneTakenNow() {
        val readings = FakeReadings(
            fixes = listOf(
                fix(latitude = 1.0, ageMs = 243_000L, provider = "gps"),
                fix(ageMs = 1_200L, provider = "gps"),
            ),
        )

        assertEquals(ActionResult.Success, run(readings))

        assertEquals(NY_LAT.toString(), variables.get("location_lat"))
        assertEquals("1", variables.get("location_age"))
    }

    @Test
    fun onlyAStaleFixInTimeFailsAndPointsAtLastKnown() {
        val readings = FakeReadings(fixes = listOf(fix(ageMs = 243_000L, provider = "gps")))

        val result = run(readings, "timeout" to "1")

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, "no fix in 1 s" in message && "243 s ago" in message && "Last known only" in message)
        assertEquals(1, readings.stopped)
        assertNull(variables.get("location_lat"))
    }

    @Test
    fun onlyLooseFixesInTimeFailAndSayHowCloseTheyCame() {
        val readings = FakeReadings(fixes = listOf(fix(accuracy = 800.0), fix(accuracy = 140.2)))

        val result = run(readings, "accuracy" to "25", "timeout" to "1")

        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, "within 25 m in 1 s" in message && "the closest was 140 m" in message)
        assertEquals(1, readings.stopped)
        assertNull(variables.get("location_lat"))
    }

    @Test
    fun lastKnownOnlyTakesTheNewestFixAndNeverStartsAProvider() {
        val readings = FakeReadings(
            lastKnown = mapOf(
                LocationManager.GPS_PROVIDER to fix(latitude = 1.0, ageMs = 600_000L, provider = "gps"),
                LocationManager.NETWORK_PROVIDER to fix(ageMs = 45_000L, accuracy = 30.0, provider = "network"),
            ),
        )

        assertEquals(ActionResult.Success, run(readings, "mode" to "last_known", "var" to "%here"))

        assertTrue(readings.started.isEmpty())
        assertEquals(NY_LAT.toString(), variables.get("here_lat"))
        assertEquals("30", variables.get("here_accuracy"))
        assertEquals("45", variables.get("here_age"))
        assertNull(variables.get("location_lat"))
    }

    @Test
    fun lastKnownWithNothingCloseEnoughFailsWithoutStartingAProvider() {
        val readings = FakeReadings(lastKnown = mapOf(LocationManager.NETWORK_PROVIDER to fix(accuracy = 900.0)))

        val result = run(readings, "mode" to "last_known", "accuracy" to "50")

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message, "no last known location within 50 m" in result.message)
        assertTrue(readings.started.isEmpty())
    }

    @Test
    fun locationSwitchedOffFailsBeforeAskingForAFix() {
        val readings = FakeReadings(providers = listOf(LocationManager.PASSIVE_PROVIDER), fixes = listOf(fix()))

        val result = run(readings)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message, "Turn Location on" in result.message)
        assertTrue(readings.started.isEmpty())
    }

    @Test
    fun badArgumentsFailBeforeAnythingIsAsked() {
        listOf(
            arrayOf("mode" to "cached"),
            arrayOf("accuracy" to "close"),
            arrayOf("accuracy" to "0.5"),
            arrayOf("timeout" to "0"),
            arrayOf("timeout" to "121"),
            arrayOf("var" to "not a name"),
        ).forEach { args ->
            assertTrue(args.toList().toString(), run(FakeReadings(fixes = listOf(fix())), *args) is ActionResult.Failure)
        }
        assertEquals(0, readingsLookedUp)
    }

    @Test
    fun aCoordinateNearZeroIsWrittenWithoutAnExponent() {
        run(FakeReadings(fixes = listOf(fix(latitude = 0.00005, longitude = -0.0001))))

        assertEquals("0.000050", variables.get("location_lat"))
        assertEquals("-0.00010", variables.get("location_lon"))
    }

    @Test
    fun providersAreTheLocationContextsInItsOrder() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER),
            enabledLocationProviders { true },
        )
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER),
            enabledLocationProviders { it == LocationManager.NETWORK_PROVIDER },
        )
    }

    @Test
    fun theRegisteredActionNeedsLocationSetupAndOutlastsItsOwnTimeout() {
        assertTrue(ActionRegistry.get("location.get") is LocationGetAction)
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("location.get").level)
        assertEquals(
            setOf(SetupRequirement.FOREGROUND_LOCATION, SetupRequirement.BACKGROUND_LOCATION),
            SetupRequirementResolver.forAction(ActionSpec(type = "location.get"), sdkInt = 36),
        )
        assertTrue(actionTimeoutMs("location.get") > LocationGetAction.MAX_TIMEOUT_SECONDS * 1000L)
        assertEquals(
            listOf("location_lat", "location_lon", "location_accuracy", "location_age"),
            declaredActionOutputs("location.get").mapNotNull { it.resolveName(emptyMap()) },
        )
    }

    private fun fix(
        latitude: Double = NY_LAT,
        longitude: Double = NY_LON,
        accuracy: Double? = 10.0,
        ageMs: Long = 0L,
        provider: String = "gps",
    ) = LocationReading(latitude, longitude, accuracy, ageMs, provider)

    private companion object {
        const val NY_LAT = 40.6972846
        const val NY_LON = -74.1443129
    }
}
