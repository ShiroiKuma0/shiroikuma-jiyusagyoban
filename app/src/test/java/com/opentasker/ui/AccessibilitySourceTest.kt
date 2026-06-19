package com.opentasker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AccessibilitySourceTest {
    private val uiSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui"),
        Path.of("app/src/main/java/com/opentasker/ui"),
    ).first(Files::exists)

    @Test
    fun uiSourceDoesNotShipNullContentDescriptions() {
        val offenders = kotlinFiles()
            .filter { it.readText().contains("contentDescription = null") }
            .map { uiSourceRoot.relativize(it).toString() }

        assertTrue("Null content descriptions found in $offenders", offenders.isEmpty())
    }

    @Test
    fun toggleRowsExposeSwitchRoleAndStateDescriptions() {
        val source = uiSourceRoot.resolve("screens/ActiveAutomationUi.kt").readText()

        val toggleableCount = Regex("""\.toggleable\s*\(""").findAll(source).count()
        val switchRoleCount = Regex("""role\s*=\s*Role\.Switch""").findAll(source).count()
        val stateDescriptionCount = Regex("""stateDescription\s*=""").findAll(source).count()

        assertEquals("Every toggleable row must expose Role.Switch", toggleableCount, switchRoleCount)
        assertTrue(
            "Every toggleable row must expose a stateDescription",
            stateDescriptionCount >= toggleableCount,
        )
    }

    @Test
    fun flowAndSceneScreensKeepScreenReaderAlternatives() {
        val flowSource = uiSourceRoot.resolve("screens/AutomationFlowScreen.kt").readText()
        assertTrue(flowSource.contains("graph.accessibilitySummary()"))
        assertTrue(flowSource.contains("node.accessibilityLabel()"))

        val sceneSource = uiSourceRoot.resolve("screens/SceneLibraryScreen.kt").readText()
        val requiredNudgeLabels = listOf(
            "Move element left 1 dp",
            "Move element up 1 dp",
            "Move element down 1 dp",
            "Move element right 1 dp",
        )
        val missingLabels = requiredNudgeLabels.filterNot(sceneSource::contains)

        assertFalse("Missing scene nudge accessibility labels: $missingLabels", missingLabels.isNotEmpty())
    }

    private fun kotlinFiles(): List<Path> =
        Files.walk(uiSourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        }
}
