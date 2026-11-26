package com.sysadmindoc.ruledeck.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Puts the demo automations the store screenshots show into a freshly cleared app. It runs only
 * when asked, so the connected suite skips it:
 *
 * `am instrument -w -e class com.sysadmindoc.ruledeck.store.StoreScreenshotStaging -e stageStoreScreenshots true
 * com.sysadmindoc.ruledeck.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotStaging {
    @Test
    fun stageDemoAutomations() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stageStoreScreenshots") == "true")
        val db = RuleDeckApp_NoHilt.db
        val batteryGuard = db.taskDao().insert(
            Task(
                name = "Battery guard",
                actions = listOf(
                    ActionSpec(type = "brightness.set", args = mapOf("brightness" to "60")),
                    ActionSpec(type = "notify.show", args = mapOf("title" to "Battery guard", "text" to "Screen dimmed to save power")),
                ),
            ).toEntity(),
        )
        val briefing = db.taskDao().insert(
            Task(
                name = "Morning briefing",
                actions = listOf(
                    ActionSpec(type = "notify.show", args = mapOf("title" to "Good morning", "text" to "Your briefing is ready")),
                    ActionSpec(type = "log", args = mapOf("message" to "Morning briefing shown")),
                ),
            ).toEntity(),
        )
        val focus = db.taskDao().insert(
            Task(
                name = "Focus mode",
                actions = listOf(
                    ActionSpec(type = "dnd.set", args = mapOf("mode" to "priority")),
                    ActionSpec(type = "notify.show", args = mapOf("title" to "Focus mode", "text" to "Do Not Disturb is on until noon")),
                ),
            ).toEntity(),
        )
        listOf(
            Profile(
                name = "Low battery guard",
                enterTaskId = batteryGuard,
                contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<=", "value" to "20"))),
            ),
            Profile(
                name = "Morning briefing",
                enterTaskId = briefing,
                contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "07:00", "end" to "07:30"))),
            ),
            Profile(
                name = "Workday focus",
                enterTaskId = focus,
                contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "12:00"))),
                cooldownSec = 30,
            ),
        ).forEach { profile ->
            // Off and waiting for review, the way an imported profile arrives.
            db.profileDao().insert(profile.copy(enabled = false, requiresRiskAcknowledgement = true).toEntity())
        }
    }
}
