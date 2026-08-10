package com.opentasker.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeDeliveryRecoveryContractTest {
    private val sourceRoot: Path = listOf(Path.of("src/main/java"), Path.of("app/src/main/java"))
        .first(Files::exists)

    @Test
    fun alarmReceiverRearmsBeforeDeliveringTickToForegroundEngine() {
        val source = source("com/opentasker/automation/receiver/TimeEventReceiver.kt")

        assertTrue(source.indexOf("scheduleNextMinute()") < source.indexOf("ACTION_TIME_TICK_TRIGGER"))
        assertTrue(source.contains("ContextCompat.startForegroundService("))
    }

    @Test
    fun servicePublishesTimePulseAfterReloadAndTimeoutLeavesRecoveryAlarmArmed() {
        val source = source("com/opentasker/core/engine/AutomationService.kt")
        val startCommand = source.substring(source.indexOf("override fun onStartCommand"), source.indexOf("override fun onTimeout"))
        val timeout = source.substring(source.indexOf("override fun onTimeout"), source.indexOf("override fun onDestroy"))
        val destroy = source.substring(source.indexOf("override fun onDestroy"), source.indexOf("private suspend fun reloadProfiles"))

        assertTrue(startCommand.indexOf("reloadProfiles()") < startCommand.indexOf("TimeContextEvents.publish()"))
        assertTrue(timeout.indexOf("scheduleRecovery()") < timeout.indexOf("stopSelf(startId)"))
        assertFalse(destroy.contains("timeEventScheduler.cancel()"))
    }

    @Test
    fun applicationInstallsPeriodicEngineWatchdog() {
        val source = source("com/opentasker/app/OpenTaskerApp_NoHilt.kt")
        val worker = source("com/opentasker/core/engine/EngineWatchdogWorker.kt")

        assertTrue(source.contains("EngineWatchdogWorker.enqueue(this)"))
        assertTrue(worker.contains("PeriodicWorkRequestBuilder<EngineWatchdogWorker>"))
        assertTrue(worker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertTrue(worker.contains("consumeMissed"))
        assertTrue(worker.contains("Standby consequence:"))
        assertTrue(worker.contains("Remediation:"))
    }

    private fun source(relative: String): String = sourceRoot.resolve(relative).readText()
}
