package com.opentasker.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationServiceCompatibilitySourceTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)

    @Test
    fun dynamicReceiversUseAndroidXCompatibilityRegistration() {
        val source = sourceRoot.resolve("com/opentasker/core/engine/AutomationService.kt").readText()

        assertTrue(
            "Dynamic receivers should use AndroidX compatibility registration for minSdk devices",
            source.contains("ContextCompat.registerReceiver("),
        )
        assertFalse(
            "Do not call the API 33 registerReceiver(receiver, filter, flags) overload directly",
            Regex("""(?m)^\s*registerReceiver\(\s*PackageContextEvents\.receiver""").containsMatchIn(source),
        )
        assertFalse(
            "Do not call the API 33 registerReceiver(receiver, filter, flags) overload directly",
            Regex("""(?m)^\s*registerReceiver\(\s*BluetoothContextEvents\.receiver""").containsMatchIn(source),
        )
    }

    @Test
    fun cameraMicActiveWatcherIsGuardedToAndroidElevenApis() {
        val source = sourceRoot.resolve("com/opentasker/core/contexts/CameraMicContextEvents.kt").readText()

        assertTrue(
            "startWatchingActive requires Android 11+ and must be guarded before use",
            source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.R"),
        )
        assertTrue(
            "stopWatchingActive should remain behind the same Android 11 API contract",
            source.contains("@RequiresApi(Build.VERSION_CODES.R)"),
        )
    }
}
