package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The band's firmware defines a mode that ERASES its stored history. This app must not be able to
 * express it — not "we are careful not to call it", but unrepresentable.
 *
 * These assertions read the source as text, in this repo's established source-guard style (see
 * LocalizationSourceTest, SecretVariableUiSourceTest). They fail the build if the property stops
 * holding, which is the only way a structural guarantee stays true after the person who made it has
 * moved on.
 */
class BandSafetyGuardTest {

    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }

    private val bandDir: Path = moduleRoot.resolve("src/main/java/com/opentasker/core/band")

    private fun bandSources(): List<Path> =
        Files.list(bandDir).use { stream -> stream.filter { it.name.endsWith(".kt") }.toList() }

    @Test
    fun theEraseModeIsNotExpressible() {
        assertEquals(
            "BandReadMode must contain exactly START and CONTINUE",
            listOf("START", "CONTINUE"),
            BandReadMode.entries.map { it.name },
        )
        assertEquals(listOf(0x00, 0x02), BandReadMode.entries.map { it.raw })
        assertEquals(setOf(0x00, 0x02), BandProtocol.ALLOWED_MODE_BYTES)
    }

    @Test
    fun theEraseOpcodeAppearsNowhereButItsOneWarning() {
        val offenders = mutableListOf<String>()
        for (file in bandSources()) {
            file.readText().lines().forEachIndexed { index, line ->
                val mentionsErase = line.contains("0x99", ignoreCase = true) ||
                    Regex("\\b153\\b").containsMatchIn(line) ||
                    line.contains("0b10011001")
                if (!mentionsErase) return@forEachIndexed
                // The single allowed mention: the KDoc on BandReadMode explaining the absence.
                val isTheDocumentedWarning = file.name == "BandProtocol.kt" && line.trimStart().startsWith("*")
                if (!isTheDocumentedWarning) offenders += "${file.name}:${index + 1}: ${line.trim()}"
            }
        }
        assertTrue("the erase mode must not appear in core/band: $offenders", offenders.isEmpty())
    }

    @Test
    fun exactlyOneFileTouchesBluetooth() {
        val touching = bandSources().filter { it.readText().contains("import android.bluetooth") }.map { it.name }
        assertEquals(
            "all protocol and parsing logic must stay Android-free so it can be JVM-tested",
            listOf("BandGattClient.kt"),
            touching,
        )
    }

    @Test
    fun thereIsNoIntTakingFrameBuilder() {
        val offenders = bandSources().filter { file ->
            Regex("fun\\s+encode\\s*\\([^)]*mode\\s*:\\s*Int").containsMatchIn(file.readText())
        }.map { it.name }
        assertTrue(
            "encode() must take a BandCommand, so a caller cannot name a raw mode byte: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun nothingInTheBandPackageSpeaksOfErasing() {
        val banned = listOf("erase", "factoryReset", "clearBand")
        val offenders = mutableListOf<String>()
        for (file in bandSources()) {
            val text = file.readText()
            for (word in banned) {
                // BandProtocol's KDoc explains why the erase mode is absent; that one is allowed.
                val allowedExplanation = file.name == "BandProtocol.kt" && word == "erase"
                if (text.contains(word, ignoreCase = true) && !allowedExplanation) {
                    offenders += "${file.name}: $word"
                }
            }
        }
        assertTrue("core/band must not contain: $offenders", offenders.isEmpty())
    }

    @Test
    fun theSyncActionHasNoClearOrResetArgument() {
        val action = moduleRoot
            .resolve("src/main/java/com/opentasker/core/actions/BandSyncAction.kt")
            .readText()
        listOf("clear", "erase", "reset", "delete").forEach { word ->
            assertTrue(
                "band.sync must have no $word argument",
                !Regex("args\\[\"[^\"]*$word[^\"]*\"\\]", RegexOption.IGNORE_CASE).containsMatchIn(action),
            )
        }
    }
}
