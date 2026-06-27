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

    private val allowedLoggerFile = sourceRoot.resolve("core/logging/AppLogger.kt").normalize()

    @Test
    fun androidPlatformLoggingOnlyHappensInsideAppLogger() {
        val offenders = kotlinFiles()
            .filter { it.normalize() != allowedLoggerFile }
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
