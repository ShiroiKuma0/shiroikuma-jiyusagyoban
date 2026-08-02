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
    fun applicationInstallsPeriodicEngineWatchdog() {
        val source = source("com/opentasker/app/OpenTaskerApp_NoHilt.kt")
        val worker = source("com/opentasker/core/engine/EngineWatchdogWorker.kt")

        assertTrue(source.contains("EngineWatchdogWorker.enqueue(this)"))
        assertTrue(worker.contains("PeriodicWorkRequestBuilder<EngineWatchdogWorker>"))
        assertTrue(worker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
    }

    private fun source(relative: String): String = sourceRoot.resolve(relative).readText()
// RETIRED: asserted upstream's tick-delivery shape — reschedule before delivery, a recovery alarm on
// FGS timeout, and that onDestroy must NOT cancel the alarm. The fork's +92 model reschedules in a
// finally, resurrects a reaped service from the alarm itself, and DELIBERATELY cancels the alarm in
// onDestroy — that cancellation is what makes "Exit app fully" stick. EngineHeartbeat covers the
// surviving intent (the tick must recover after a reap).
}
