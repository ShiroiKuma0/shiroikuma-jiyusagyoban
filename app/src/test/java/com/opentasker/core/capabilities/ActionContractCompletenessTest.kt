package com.opentasker.core.capabilities

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The action contract must be total: every action the app can run has exactly one metadata,
 * capability, and sensitivity classification, and the README's advertised counts are derived from
 * the registry rather than maintained by hand.
 *
 * The failure this locks out is a fail-open default — an action that is registered but never
 * reviewed used to report itself as Ready.
 */
class ActionContractCompletenessTest {

    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID) + FlowControl.ALL

    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun allActionIds(): Set<String> =
        ActionRegistry.allIds().toSet() + engineHandledActions + ActionMetadataRegistry.all().map { it.id }

    @Test
    fun everyActionHasAnExplicitCapabilityContract() {
        val uncontracted = allActionIds()
            .filterNot { it in ActionCapabilityRegistry.contractedActionIds() }
            .sorted()

        assertTrue(
            "Actions with no explicit capability contract (they now fail closed as unknown, which " +
                "is safe but wrong for a shipped action): $uncontracted",
            uncontracted.isEmpty(),
        )
    }

    @Test
    fun anUnreviewedActionFailsClosedInsteadOfReportingReady() {
        val capability = ActionCapabilityRegistry.get("some.brand.new.action")
        assertEquals(CapabilityLevel.Unsupported, capability.level)
        assertFalse(capability.canAdd)
    }

    @Test
    fun everyActionHasASensitivityClassification() {
        val unclassified = allActionIds()
            .filterNot(AutomationSensitivityRegistry::isKnown)
            .sorted()

        assertTrue("Actions with no sensitivity classification: $unclassified", unclassified.isEmpty())
    }

    @Test
    fun everyRegisteredActionHasAReviewedRetrySafetyClassification() {
        val metadataIds = ActionMetadataRegistry.all().map { it.id }.toSet()
        val registered = ActionRegistry.all().filter { it.id in metadataIds }
        val retryable = registered
            .filter { it.retrySafety == ActionRetrySafety.IDEMPOTENT }
            .map { it.id }
            .toSet()

        assertEquals("all built-in actions must remain registered", 74, registered.size)
        assertEquals(
            setOf(
                "app.archive", "app.unarchive", "brightness.set", "clipboard.get", "clipboard.set",
                "contacts.lookup", "data.read", "datetime.add", "datetime.format", "datetime.parse",
                "dnd.set", "download", "file.delete", "file.list", "file.read", "file.write",
                "http.get", "ime.info", "lock", "media.mute", "notify.cancel", "ping", "plugin.locale.query",
                "screen.off", "screen.timeout", "sound.pause", "sound.stop", "text.join", "text.match",
                "text.replace", "text.split", "text.substring", "tile.set", "var.persist", "var.set",
                "volume.set", "wake", "wol", "zen.rule.clear", "ringer.set",
            ),
            retryable,
        )
        assertTrue(registered.all { it.retrySafety in ActionRetrySafety.entries })
        assertEquals(ActionRetrySafety.IDEMPOTENT, ActionRegistry.get("http.request")?.retrySafetyFor(mapOf("method" to "GET")))
        assertEquals(ActionRetrySafety.NEVER, ActionRegistry.get("http.request")?.retrySafetyFor(mapOf("method" to "POST")))
    }

    @Test
    fun everyRegisteredBuiltInSourceDeclaresRetrySafety() {
        val runtime = repoRoot.resolve("app/src/main/java/com/opentasker/core/RuntimeRegistries.kt").readText()
        val actionSources = repoRoot.resolve("app/src/main/java/com/opentasker/core/actions")
            .toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val registeredCount = Regex("(?m)^\\s+[A-Za-z0-9]+Action\\(\\),").findAll(runtime).count()
        val classifiedCount = Regex("override val retrySafety = ActionRetrySafety\\.(?:NEVER|IDEMPOTENT)")
            .findAll(actionSources)
            .count()

        assertEquals(
            "Adding a registered action without an explicit retry classification must fail the source guard",
            registeredCount,
            classifiedCount,
        )
    }

    @Test
    fun theContractHasNoEntriesForActionsThatDoNotExist() {
        val stale = ActionCapabilityRegistry.contractedActionIds()
            .filterNot { it in allActionIds() }
            .sorted()

        assertTrue("Capability contract names actions that are not registered: $stale", stale.isEmpty())
    }

    @Test
    fun permanentStubsAreUnsupportedRatherThanAdvertisedAsWorking() {
        // These always fail at run time on any unprivileged Android build; the contract has to say
        // so up front instead of letting a user add them and discover it in a run log.
        listOf("app.kill", "wifi.toggle", "airplane.toggle", "mobile.toggle", "reboot", "lock", "screen.off", "wake", "screenshot.take")
            .forEach { actionId ->
                assertEquals(
                    "$actionId must be Unsupported",
                    CapabilityLevel.Unsupported,
                    ActionCapabilityRegistry.get(actionId).level,
                )
            }
    }

    @Test
    fun specialAccessActionsDeclareTheGrantTheyNeedAndTheAppRequestsIt() {
        listOf("brightness.set", "screen.timeout").forEach { actionId ->
            assertEquals(
                "$actionId must be gated on its special access, not silently Supported",
                CapabilityLevel.RequiresSetup,
                ActionCapabilityRegistry.get(actionId).level,
            )
        }

        // Settings.System.canWrite() can never become true without the manifest declaration, so an
        // action advertising "one grant away" would otherwise fail forever.
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "WRITE_SETTINGS must be declared for the brightness/screen-timeout grant path to exist",
            "android.permission.WRITE_SETTINGS" in manifest,
        )
        val setup = repoRoot.resolve("app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt").readText()
        assertTrue(
            "Setup must expose a working Modify system settings grant path",
            "Settings.ACTION_MANAGE_WRITE_SETTINGS" in setup && "Settings.System.canWrite(context)" in setup,
        )
    }

    @Test
    fun readmeActionCountsAreDerivedFromTheRegistry() {
        val registered = ActionRegistry.allIds().size
        val engineHandled = engineHandledActions.size
        val readme = repoRoot.resolve("README.md").readText()

        assertTrue(
            "README must advertise the registry-derived counts: expected " +
                "\"### Actions ($registered registered + $engineHandled engine-handled)\"",
            "### Actions ($registered registered + $engineHandled engine-handled)" in readme,
        )
        assertTrue(
            "README feature bullet must advertise the registry-derived count: expected " +
                "\"**$registered built-in actions**\"",
            "**$registered built-in actions**" in readme,
        )

        // The per-category table has to add up to the same number, so a new action cannot be
        // announced in the headline while its category row silently stays behind.
        val actionSection = readme
            .substringAfter("### Actions (")
            .substringBefore("\n#")
        val categoryTotal = Regex("""^\| [A-Za-z ]+ \| *(\d+)(?:\+\d+)? \|""", RegexOption.MULTILINE)
            .findAll(actionSection)
            .sumOf { it.groupValues[1].toInt() }
        assertEquals("README action category rows must sum to the registered count", registered, categoryTotal)
    }
}
