package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun activityBridgesPredictiveBackAndKeepsRootFallback() {
        val source = repoRoot.resolve("app/src/main/java/com/opentasker/app/MainActivity.kt").readText()
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(source.contains("OnBackInvokedCallback"))
        assertTrue(source.contains("registerOnBackInvokedCallback"))
        assertTrue(source.contains("unregisterOnBackInvokedCallback"))
        assertTrue(source.contains("OnBackPressedCallback"))
        assertTrue(source.contains("onBackPressedDispatcher.onBackPressed()"))
        assertTrue(source.contains("Build.VERSION_CODES.TIRAMISU"))
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
    }
}
