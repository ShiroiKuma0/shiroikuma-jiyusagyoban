package com.opentasker.core.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

class AppLoggerBoundarySourceTest {
    private val platformLogImport = "import android.util." + "Log"
    private val platformLogCallPattern = Regex("""\b""" + "Log" + """\.""")

    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .map(Path::toAbsolutePath)
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    // Upstream splits production Kotlin across core/* modules; the fork keeps one tree (see
    // settings.gradle.kts). The absent roots filter themselves out, so this list also works
    // unchanged on a future sync that does adopt the split.
    private val sourceRoots: List<Path> = listOf(
        repoRoot.resolve("app/src/main/java/com/opentasker"),
        repoRoot.resolve("core/common/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/model/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/storage/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/engine/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("feature/automation/src/main/kotlin/com/opentasker"),
    ).filter(Files::isDirectory)

    /**
     * [AppLogger] itself — under either layout — plus the Shizuku key-grabber, which runs in a
     * **separate privileged process** spawned by Shizuku (uid 2000). AppLogger's value is its in-app
     * ring, and that ring lives in the app's process; logging there from the grabber would write into
     * a buffer nothing can read. Platform logging is the only channel it has.
     */
    private val allowedFiles = listOf(
        repoRoot.resolve("app/src/main/java/com/opentasker/core/logging/AppLogger.kt"),
        repoRoot.resolve("core/common/src/main/kotlin/com/opentasker/core/logging/AppLogger.kt"),
        repoRoot.resolve("app/src/main/java/com/opentasker/core/input/KeyGrabberService.kt"),
    ).map(Path::normalize)

    @Test
    fun androidPlatformLoggingOnlyHappensInsideAppLogger() {
        val offenders = kotlinFiles()
            .filter { it.normalize() !in allowedFiles }
            .filter { source ->
                val text = source.readText()
                text.contains(platformLogImport) || platformLogCallPattern.containsMatchIn(text)
            }
            .map { repoRoot.relativize(it).toString() }

        assertTrue("Direct android.util.Log usage must go through AppLogger: $offenders", offenders.isEmpty())
        assertTrue(
            "AppLogger must exist somewhere in production source",
            kotlinFiles().any { it.name == "AppLogger.kt" },
        )
    }

    private fun kotlinFiles(): List<Path> = sourceRoots.flatMap { root ->
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }
                .toList()
        }
    }
}
