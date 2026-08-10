package com.opentasker.core.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledJobDiagnosticsContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }

    @Test
    fun platformReadersAreGuardedAtThePlatformBoundaries() {
        val source = repoRoot.resolve("src/main/java/com/opentasker/core/diagnostics/EngineHealthReader.kt").readText()

        assertTrue("API 36 history must be read", "getPendingJobReasonsHistory" in source)
        assertTrue("API 36 history must be guarded", "Build.VERSION.SDK_INT >= 36" in source)
        assertTrue("API 37 aggregate stats must be read", "getPendingJobReasonStats" in source)
        assertTrue("API 37 aggregate stats must be guarded", "Build.VERSION.SDK_INT >= 37" in source)
        assertTrue("Android 16 abandoned timeout must be explained", "STOP_REASON_TIMEOUT_ABANDONED" in source)
        assertTrue("standby consequences must be surfaced", "standbyConsequence" in source)
    }

    @Test
    fun diagnosticsAndExportExposeAllSchedulerEvidence() {
        val screen = repoRoot.resolve("src/main/java/com/opentasker/ui/screens/DiagnosticsScreen.kt").readText()
        val export = repoRoot.resolve("src/main/java/com/opentasker/core/diagnostics/DiagnosticExport.kt").readText()

        assertTrue("UI must show pending-job history", "pendingScheduledJobs.history" in screen)
        assertTrue("UI must show aggregate pending time", "pendingScheduledJobs.aggregateStats" in screen)
        assertTrue("export must show pending-job history", "Pending job reason history" in export)
        assertTrue("export must show aggregate pending time", "Pending job duration stats" in export)
    }
}
