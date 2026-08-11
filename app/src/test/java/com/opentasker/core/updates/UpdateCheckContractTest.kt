package com.opentasker.core.updates

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun workerIsPeriodicAndFroidGatedWithoutAnArtifactInstallPath() {
        val worker = read("app/src/main/java/com/opentasker/core/updates/UpdateCheckWorker.kt")
        val settings = read("app/src/main/java/com/opentasker/core/updates/UpdateCheckSettings.kt")
        assertTrue(worker.contains("class UpdateCheckWorker"))
        assertTrue(worker.contains("PeriodicWorkRequestBuilder<UpdateCheckWorker>"))
        assertTrue(worker.contains("UpdateCheckAvailability.isAvailable()"))
        assertTrue(worker.contains("FDROID_DISTRIBUTION = \"fdroid\""))
        assertTrue(worker.contains("cancelUniqueWork"))
        assertFalse(worker.contains("DownloadManager"))
        assertFalse(worker.contains("PackageInstaller"))
        assertTrue(settings.contains("enabled: Boolean = false"))
        assertTrue(settings.contains("getBoolean(KEY_ENABLED, false)"))
    }

    @Test
    fun setupCardAndStartupSyncAreExplicitlyDistributionGated() {
        val setup = read("app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt")
        val application = read("app/src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt")
        assertTrue(setup.contains("UpdateCheckAvailability.isAvailable()"))
        assertTrue(setup.contains("UpdateCheckSetupCard"))
        assertTrue(application.contains("UpdateCheckWorker.enqueueIfEnabled(this)"))
    }

    private fun read(path: String): String = repoRoot.resolve(path).readText()
}
