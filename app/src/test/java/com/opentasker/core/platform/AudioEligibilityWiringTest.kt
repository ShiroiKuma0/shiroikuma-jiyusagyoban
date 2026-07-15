package com.opentasker.core.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEligibilityWiringTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)

    @Test
    fun visibleUiStartMarksAutomationServiceWhileInUseEligible() {
        val activity = source("com/opentasker/app/MainActivity.kt")
        val service = source("com/opentasker/core/engine/AutomationService.kt")

        assertTrue(activity.contains("putExtra(AutomationService.EXTRA_STARTED_FROM_VISIBLE_UI, true)"))
        assertTrue(service.contains("AudioForegroundServiceEligibility.BACKGROUND_STARTED"))
        assertTrue(service.contains("audioForegroundServiceEligibility = AudioForegroundServiceEligibility.WHILE_IN_USE"))
        assertTrue(service.contains("audioForegroundService = audioForegroundServiceEligibility"))
    }

    @Test
    fun everyExecutionSamplesVisibilityAndExactAlarmPermission() {
        val helper = source("com/opentasker/core/engine/TaskExecutionHelper.kt")

        assertTrue(helper.contains("appVisible = visibleActivity"))
        assertTrue(helper.contains("audioEligibility = audioEligibility"))

        val hardening = source("com/opentasker/core/platform/AndroidAudioHardening.kt")
        assertTrue(hardening.contains("AppVisibilityTracker.isAppVisible"))
        assertTrue(hardening.contains("ExactAlarmSupport.canScheduleExactAlarms(ctx.app)"))

        val widgetActivity = source("com/opentasker/widget/TaskRunActivity.kt")
        assertTrue(widgetActivity.contains("visibleActivity = true"))
    }

    private fun source(relativePath: String): String = sourceRoot.resolve(relativePath).readText()
}
