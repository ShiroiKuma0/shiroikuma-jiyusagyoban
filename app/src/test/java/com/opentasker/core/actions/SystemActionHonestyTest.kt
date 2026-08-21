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
        assertTrue(source.contains("ctx.runShizukuAction(\"wake\""))
        assertTrue(source.contains("ctx.runShizukuAction(\"reboot\""))
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
