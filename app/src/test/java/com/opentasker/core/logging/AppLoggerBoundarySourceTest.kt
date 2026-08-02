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

    private val sourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker"),
        Path.of("app/src/main/java/com/opentasker"),
    ).first(Files::exists)

    /**
     * [AppLogger] itself, plus the Shizuku key-grabber — which runs in a **separate privileged process**
     * spawned by Shizuku (uid 2000). AppLogger's value is its in-app ring, and that ring lives in the
     * app's process; logging there from the grabber would write into a buffer nothing can read. Platform
     * logging is the only channel it has.
     */
    private val allowedFiles = listOf(
        "core/logging/AppLogger.kt",
        "core/input/KeyGrabberService.kt",
    ).map { sourceRoot.resolve(it).normalize() }

    @Test
    fun androidPlatformLoggingOnlyHappensInsideAppLogger() {
        val offenders = kotlinFiles()
            .filter { it.normalize() !in allowedFiles }
            .filter { source ->
                val text = source.readText()
                text.contains(platformLogImport) || platformLogCallPattern.containsMatchIn(text)
            }
            .map { sourceRoot.relativize(it).toString() }

        assertTrue("Direct android.util.Log usage must go through AppLogger: $offenders", offenders.isEmpty())
    }

    private fun kotlinFiles(): List<Path> =
        Files.walk(sourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }
                .toList()
        }
}
