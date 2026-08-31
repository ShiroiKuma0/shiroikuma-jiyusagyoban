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
    fun packageArchiveNewApiSuppressIsScopedToTheApi35CallPath() {
        val source = sourceRoot.resolve("core/actions/PackageArchiveActions.kt").readText()
        assertFalse(
            "Do not suppress NewApi on the whole PackageArchiveActionSupport object",
            source.contains("@Suppress(\"NewApi\")\r\ninternal object PackageArchiveActionSupport") ||
                source.contains("@Suppress(\"NewApi\")\ninternal object PackageArchiveActionSupport"),
        )
        assertTrue(source.contains("@RequiresApi(35)"))
        assertTrue(source.contains("@Suppress(\"NewApi\")"))
    }
}
