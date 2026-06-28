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
            "R.string.scenes_move_left_content_description",
            "R.string.scenes_move_up_content_description",
            "R.string.scenes_move_down_content_description",
            "R.string.scenes_move_right_content_description",
        )
        val missingLabels = requiredNudgeLabels.filterNot(sceneSource::contains)

        assertFalse("Missing scene nudge accessibility labels: $missingLabels", missingLabels.isNotEmpty())
    }

    @Test
    fun criticalFlowsKeepAccessibilityContracts() {
        val requiredMarkersByFile = mapOf(
            "screens/PermissionOnboardingScreen.kt" to listOf(
                "role = Role.RadioButton",
                "stateDescription = selectionDescription",
                "R.string.a11y_option_selected",
                "R.string.status_granted",
                "R.string.status_optional",
                "R.string.status_required",
            ),
            "screens/EditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = if (enabled) onLabel else offLabel",
                "enabled = canSave",
                "R.string.ui_info_content_description",
                "R.string.delete_cannot_undo",
            ),
            "screens/ActionEditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = stateDescriptionLabel",
                "enabled = !missingRequired && capability.canAdd",
                "R.string.label_required",
            ),
            "screens/ContextEditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = if (invert) onLabel else offLabel",
                "enabled = !missingRequired",
                "R.string.context_invert_helper",
            ),
            "screens/SceneLibraryScreen.kt" to listOf(
                "R.string.scenes_empty_content_description",
                "R.string.scenes_remove_element_content_description",
                "R.string.scenes_move_left_content_description",
                "enabled = canSave",
            ),
            "screens/RunLogScreenContent.kt" to listOf(
                "R.string.empty_run_log_title",
                "R.string.empty_run_log_search_title",
                "R.string.run_log_share_diagnostic",
                "contentDescription = when (outcome)",
            ),
        )

        val missingMarkers = requiredMarkersByFile.flatMap { (relativePath, markers) ->
            val source = uiSourceRoot.resolve(relativePath).readText()
            markers.filterNot(source::contains).map { "$relativePath: $it" }
        }

        assertTrue("Missing critical-flow accessibility markers: $missingMarkers", missingMarkers.isEmpty())
    }

    @Test
    fun appShellAndSetupDoNotShipHardcodedSemanticLabels() {
        val checkedFiles = listOf(
            "screens/ActiveAutomationUi.kt",
            "screens/PermissionOnboardingScreen.kt",
        )
        val forbiddenPatterns = mapOf(
            "literal contentDescription" to Regex("""contentDescription\s*=\s*""" + "\""),
            "literal stateDescription" to Regex("""stateDescription\s*=\s*""" + "\""),
            "interpolated selected state" to Regex("""\${'$'}label\s+(not\s+)?selected"""),
            "literal create icon" to Regex("""Create (task|profile) icon"""),
        )

        val offenders = checkedFiles.flatMap { relativePath ->
            val source = uiSourceRoot.resolve(relativePath).readText()
            forbiddenPatterns.mapNotNull { (name, pattern) ->
                if (pattern.containsMatchIn(source)) "$relativePath: $name" else null
            }
        }

        assertTrue("Hardcoded accessibility semantic labels found: $offenders", offenders.isEmpty())
    }

    private fun kotlinFiles(): List<Path> =
        Files.walk(uiSourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        }
}
