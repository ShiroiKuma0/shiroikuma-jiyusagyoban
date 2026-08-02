package com.opentasker.core.actions

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ZenRuleActionsTest {
    @Test
    fun lowSdkUsesTransientDndFallbackAndRetainsModeMapping() {
        assertFalse(ZenRuleActionSupport.usesAutomaticRules(34))
        assertTrue(ZenRuleActionSupport.usesAutomaticRules(35))
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            ZenRuleActionSupport.interruptionFilterFor("priority"),
        )
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            ZenRuleActionSupport.interruptionFilterFor("off"),
        )
    }

    @Test
    fun setParserCoversEffectsAndRejectsUnboundedOrMalformedInput() {
        val parsed = ZenRuleActionSupport.parse(
            mapOf(
                "id" to "focus_evening",
                "name" to "Evening focus",
                "mode" to "total_silence",
                "enabled" to "true",
                "grayscale" to "true",
                "dim_wallpaper" to "false",
                "night_mode" to "true",
            ),
        ).getOrNull()

        assertNotNull(parsed)
        assertTrue(parsed!!.grayscale)
        assertFalse(parsed.dimWallpaper)
        assertTrue(parsed.nightMode)
        assertNull(ZenRuleActionSupport.parse(mapOf("id" to "bad id", "name" to "Rule")).getOrNull())
        assertNull(ZenRuleActionSupport.parse(mapOf("id" to "rule", "name" to "Rule", "enabled" to "yes")).getOrNull())
    }

    @Test
    fun sourceContainsOwnedRuleCreateUpdateActivateAndClearLifecycle() {
        val sourcePath = listOf(
            Path.of("src/main/java/com/opentasker/core/actions/ZenRuleActions.kt"),
            Path.of("app/src/main/java/com/opentasker/core/actions/ZenRuleActions.kt"),
        ).first(Files::exists)
        val source = sourcePath.readText()
        assertTrue(source.contains("addAutomaticZenRule"))
        assertTrue(source.contains("updateAutomaticZenRule"))
        assertTrue(source.contains("setAutomaticZenRuleState"))
        assertTrue(source.contains("removeAutomaticZenRule"))
        assertTrue(source.contains("Build.VERSION.SDK_INT < AUTOMATIC_ZEN_RULE_API"))
        assertTrue(source.contains("setInterruptionFilter(spec.interruptionFilter)"))
    }
}
