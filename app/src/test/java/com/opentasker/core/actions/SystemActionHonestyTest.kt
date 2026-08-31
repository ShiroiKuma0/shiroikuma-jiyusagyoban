package com.opentasker.core.actions

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemActionHonestyTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker"),
        Path.of("app/src/main/java/com/opentasker"),
    ).first(Files::exists)

    @Test
    fun wakeAndRebootDoNotReadArgsTheirMetadataCannotSupply() {
        val source = sourceRoot.resolve("core/actions/SystemActions.kt").readText()
        assertFalse("WakeAction must not read a duration it cannot honour", source.contains("args[\"duration_sec\"]"))
        assertFalse("RebootAction must not fail on leftover imported mode args", source.contains("args[\"mode\"]"))
        // Upstream routes both through ctx.runShizukuAction, an extension the fork's power layer
        // does not have; the fork calls ShizukuShell directly. The honesty contract above is the
        // part that matters, and it holds either way — so this half asserts the fork's own path,
        // including that neither action reports success it did not observe.
        assertTrue(source.contains("ShizukuShell.exec(\"input keyevent 224\")"))
        assertTrue(source.contains("ActionResult.Failure(\"Wake keyevent failed\")"))
    }

    @Test
    fun packageArchiveGuardsTheApi35CallInsteadOfSuppressingNewApi() {
        val source = sourceRoot.resolve("core/actions/PackageArchiveActions.kt").readText()
        // A suppression cannot tell a real API-level mismatch from an accepted one, so the API 35
        // call is gated on a check lint can prove rather than silenced.
        assertFalse(
            "PackageArchiveActions must not suppress NewApi anywhere",
            source.contains("@Suppress(\"NewApi\")"),
        )
        assertTrue(source.contains("@RequiresApi(35)"))
        assertTrue(
            "The API 35 request must sit behind a platform SDK_INT guard",
            source.contains("if (Build.VERSION.SDK_INT < ANDROID_15_API)"),
        )
    }
}
