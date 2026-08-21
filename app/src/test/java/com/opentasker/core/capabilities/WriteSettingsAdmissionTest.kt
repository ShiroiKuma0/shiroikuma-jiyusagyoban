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
        val viewModel = listOf(
            Path.of("src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt"),
            Path.of("app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt"),
        ).first { Files.exists(it) }.readText()
        assertTrue(viewModel.contains("requireWriteSettingsIfEnabled(reviewed)"))
        assertTrue(viewModel.contains("requireWriteSettingsIfEnabled(reviewedProfile)"))
        assertTrue(viewModel.contains("requireWriteSettingsIfEnabled(current.copy(enabled = true))"))
        assertTrue(viewModel.contains("requireWriteSettingsIfEnabled(restored)"))
        assertTrue(viewModel.contains("requireWriteSettingsIfEnabled(target)"))
        assertTrue(viewModel.contains("requireWriteSettingsReady(task.actions)"))
        assertTrue(viewModel.contains("profile.fallbackTaskId"))
        assertTrue(viewModel.contains("WriteSettingsAdmission.blocked"))
    }
}
