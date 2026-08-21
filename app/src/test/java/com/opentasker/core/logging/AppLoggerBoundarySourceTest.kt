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

    // AppLogger moved into core:common, so the scan has to follow production code across the
    // module boundary rather than stopping at the app source root.
    private val sourceRoots: List<Path> = listOf(
        repoRoot.resolve("app/src/main/java/com/opentasker"),
        repoRoot.resolve("core/common/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/model/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/storage/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("core/engine/src/main/kotlin/com/opentasker"),
        repoRoot.resolve("feature/automation/src/main/kotlin/com/opentasker"),
    ).filter(Files::isDirectory)

    @Test
    fun androidPlatformLoggingOnlyHappensInsideAppLogger() {
        val offenders = kotlinFiles()
            .filter { it.name != "AppLogger.kt" }
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
