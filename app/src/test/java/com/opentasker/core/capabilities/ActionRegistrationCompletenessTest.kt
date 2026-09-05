package com.opentasker.core.capabilities

import com.opentasker.core.actions.ActionCatalog
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Registering an action in `ActionCatalog` is not enough to make it usable, and nothing said so.
 *
 * An action has to appear in three places: the catalogue (so it runs), `AutomationSensitivityRegistry`
 * (so a bundle carrying it can be imported), and — if it needs a permission — `ActionCapabilities`.
 * Miss the second and the action works perfectly when you build the task by hand, while every bundle
 * containing it is refused at import with "unknown unclassified actions". Miss the third and it is
 * refused as "unsupported".
 *
 * That is a bad failure to discover, because it happens on the phone rather than in the build, and
 * only on the import path — `band.scan` shipped, installed, ran and passed 1409 unit tests before the
 * bridge rejected the first bundle that used it. This test moves that discovery to the build.
 */
class ActionRegistrationCompletenessTest {

    private fun catalogIds(): Set<String> = ActionCatalog.all.map { it.id }.toSet()

    @Test
    fun everyCatalogActionIsClassifiedSoItsBundlesCanBeImported() {
        val unclassified = catalogIds()
            .filterNot(AutomationSensitivityRegistry::isKnown)
            .sorted()
        assertEquals(
            "these actions run but no bundle containing them can be imported — add each to a set in " +
                "AutomationSensitivity.kt",
            emptyList<String>(),
            unclassified,
        )
    }

    /**
     * An Unsupported action cannot travel in a bundle at all. A few are Unsupported on purpose and
     * those are named here, so that a NEW one is a decision someone wrote down rather than a
     * capability entry somebody forgot.
     */
    @Test
    fun onlyTheDeliberatelyUnsupportedActionsRefuseToImport() {
        val declaredUnsupported = setOf(
            // Per-task Quick Settings tiles are a planned feature; the action is a placeholder.
            "tile.set",
            // The landing pad for a Tasker action the importer could not map. Unsupported by
            // definition — it exists so a failed mapping is visible instead of silently dropped.
            "tasker.unsupported",
            // The same landing pad for a MacroDroid action the importer could not map (upstream
            // 0.2.88 added the MacroDroid importer beside the Tasker one).
            "macrodroid.unsupported",
            // Shell access is not enough: it wants device-owner or system-app privilege. (The fork's
            // polarity is the reverse of upstream's here — app.kill runs through Shizuku and is
            // addable, reboot is not.)
            "reboot",
            // Android 13+ blocks direct Bluetooth enable/disable for ordinary apps.
            "bluetooth.set",
        )
        val actual = catalogIds()
            .filter { ActionCapabilityRegistry.get(it).level == CapabilityLevel.Unsupported }
            .toSet()
        val unexpected = (actual - declaredUnsupported).sorted()
        assertEquals(
            "these actions resolve to Unsupported, so no bundle containing them can be imported — " +
                "give each a capability entry in ActionCapabilities.kt, or add it to the list above " +
                "with the reason",
            emptyList<String>(),
            unexpected,
        )
    }

    @Test
    fun theClassificationRegistryHasNoEntryForAnActionThatNoLongerExists() {
        // The engine interprets these itself rather than dispatching them through the catalogue, so
        // they are legitimately classified without appearing in it: the nine flow-control constructs
        // plus task.run, which is the sub-task call.
        val engineHandled = FlowControl.ALL + SUB_TASK_ACTION_ID
        val stale = (AutomationSensitivityRegistry.classifiedActionIds() - catalogIds() - engineHandled)
            .sorted()
        assertEquals(
            "these ids are classified but no longer in the catalogue — a rename left the old name behind",
            emptyList<String>(),
            stale,
        )
    }
}
