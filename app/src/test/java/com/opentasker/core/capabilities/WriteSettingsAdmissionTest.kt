package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteSettingsAdmissionTest {
    @Test
    fun brightnessAndTimeoutRequireWriteSettings() {
        assertTrue(WriteSettingsAdmission.requiredBy(listOf(ActionSpec(type = "brightness.set"))))
        assertTrue(WriteSettingsAdmission.requiredBy(listOf(ActionSpec(type = "screen.timeout"))))
        assertFalse(WriteSettingsAdmission.requiredBy(listOf(ActionSpec(type = "notify.show"))))
    }

    @Test
    fun enableIsBlockedOnlyWhenTheGrantIsMissing() {
        val actions = listOf(ActionSpec(type = "brightness.set"))
        assertTrue(WriteSettingsAdmission.blocked(actions, canWriteSettings = false))
        assertFalse(WriteSettingsAdmission.blocked(actions, canWriteSettings = true))
        assertFalse(WriteSettingsAdmission.blocked(listOf(ActionSpec(type = "log")), canWriteSettings = false))
    }

    @Test
    fun profileEnableAndManualRunConsultTheAdmissionGate() {
        // Scanned across the screens package: the contract is that every write path consults the
        // gate, not that those paths share one file.
        val screensRoot = listOf(
            Path.of("src/main/java/com/opentasker/ui/screens"),
            Path.of("app/src/main/java/com/opentasker/ui/screens"),
        ).first { Files.exists(it) }
        val screens = Files.list(screensRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .toList()
                .joinToString(separator = System.lineSeparator()) { it.readText() }
        }
        assertTrue(screens.contains("requireWriteSettingsIfEnabled(reviewed)"))
        assertTrue(screens.contains("requireWriteSettingsIfEnabled(reviewedProfile)"))
        assertTrue(screens.contains("requireWriteSettingsIfEnabled(current.copy(enabled = true))"))
        assertTrue(screens.contains("requireWriteSettingsIfEnabled(restored)"))
        assertTrue(screens.contains("requireWriteSettingsIfEnabled(target)"))
        assertTrue(screens.contains("requireWriteSettingsReady(task.actions)"))
        assertTrue(screens.contains("profile.fallbackTaskId"))
        assertTrue(screens.contains("WriteSettingsAdmission.blocked"))
    }
}
