package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Structural guarantees for `core/huawei`, in this repo's established source-guard style (see
 * BandSafetyGuardTest, LocalizationSourceTest). They read the source as text and fail the build if
 * a property stops holding — the only way a structural guarantee survives the person who made it.
 *
 * Two properties matter here. One keeps the protocol testable at all. The other keeps the Huawei
 * work from reaching into the Hume band, which 白い熊 requires to stay untouched while both devices
 * run in parallel for comparison.
 */
class HuaweiSafetyGuardTest {

    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app"))
        .first { Files.isDirectory(it.resolve("src/main")) }

    private val huaweiDir: Path = moduleRoot.resolve("src/main/java/com/opentasker/core/huawei")

    private fun sources(): List<Path> =
        Files.list(huaweiDir).use { s -> s.filter { it.name.endsWith(".kt") }.toList() }

    @Test
    fun exactlyOneFileTouchesBluetooth() {
        val touching = sources()
            .filter { it.readText().contains("import android.bluetooth") }
            .map { it.name }
        assertEquals(
            "exactly one file in core/huawei may import android.bluetooth, so the rest stays " +
                "JVM-testable; found $touching",
            listOf("HuaweiRfcommClient.kt"),
            touching,
        )
    }

    @Test
    fun theProtocolAndCryptoLayersAreAndroidFree() {
        // These are the files the unit tests exercise. An android.* import in any of them would
        // make them untestable, exactly as it would in core/band.
        val mustBePure = listOf(
            "HuaweiProtocol.kt", "HuaweiCrypto.kt", "HuaweiCommands.kt", "HuaweiSession.kt",
            // HuaweiSyncEngine holds the record-to-sample conversion, which is the layer that can
            // silently corrupt data; the orchestration that needs a Context lives in the runner
            // precisely so this file can stay testable.
            "HuaweiSyncEngine.kt",
            "HuaweiSyncArgs.kt", "HuaweiStatus.kt", "HuaweiHiChain.kt", "HuaweiRecords.kt",
        )
        val offenders = sources()
            .filter { it.name in mustBePure }
            .filter { it.readText().contains("import android.") }
            .map { it.name }
        assertTrue("must not import android.*: $offenders", offenders.isEmpty())
    }

    @Test
    fun theHuaweiClientDoesNotReachIntoTheHumeBandPackage() {
        // While both bands run in parallel the Hume path must stay undisturbed and the two data
        // sets must stay separable. Imports only: a KDoc cross-reference to BandProtocol is useful
        // context and creates no dependency, but an import is the first step towards merging the
        // two by accident.
        val offenders = sources()
            .filter { file ->
                file.readText().lines().any { it.trimStart().startsWith("import com.opentasker.core.band") }
            }
            .map { it.name }
        assertTrue(
            "core/huawei must not depend on core/band while the two bands run in parallel: " +
                "$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theRunnerStaysConnectedAfterConfiguring() {
        // The band is NOT finished when the configuration set is: it keeps asking questions for well
        // over a minute, and a companion that hangs up during that conversation is treated as no
        // companion at all — the band returns to its out-of-box wizard with every command it
        // received having returned success. That failure is invisible from this side, which is why
        // it is pinned structurally rather than left to whoever next edits the runner.
        val runner = huaweiDir.resolve("HuaweiSyncRunner.kt").readText()
        val configureAt = runner.indexOf("api.configure(")
        // Either entry point counts: serve() answers one round, pump() also reports what arrived so
        // the caller can stop once the band goes quiet. What must not happen is neither.
        val serveAt = listOf("api.serve(", "api.pump(")
            .map { runner.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        assertTrue("the runner must call configure()", configureAt >= 0)
        assertTrue("the runner must serve the band after configuring it", serveAt > configureAt)
    }

    @Test
    fun fireAndForgetCommandsAreDeclared() {
        // A timeout on these is CORRECT — the band never answers them. Blocking on one burns the
        // seconds it gives us before abandoning its own pairing flow, which cost a whole evening.
        assertTrue(
            "country code must be fire-and-forget",
            HuaweiCommands.SVC_ACCOUNT to HuaweiCommands.ACC_COUNTRY_CODE
                in HuaweiCommands.FIRE_AND_FORGET,
        )
        assertTrue(
            "SetUpDeviceStatus must be fire-and-forget",
            HuaweiCommands.SVC_DEVICE_CONFIG to HuaweiCommands.CMD_SETUP_DEVICE_STATUS
                in HuaweiCommands.FIRE_AND_FORGET,
        )
    }
}
