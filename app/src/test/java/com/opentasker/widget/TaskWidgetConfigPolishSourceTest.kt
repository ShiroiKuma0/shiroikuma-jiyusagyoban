package com.opentasker.widget

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWidgetConfigPolishSourceTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)
    private val resRoot: Path = listOf(
        Path.of("src/main/res"),
        Path.of("app/src/main/res"),
    ).first(Files::exists)

    @Test
    fun widgetTaskPickerUsesPremiumCardsAndGuidedEmptyState() {
        val source = sourceRoot.resolve("com/opentasker/widget/TaskWidgetConfigActivity.kt").readText()

        assertTrue("Widget picker should explain the assignment flow", source.contains("Choose widget task"))
        assertTrue("Widget picker should keep a summary/header card", source.contains("WidgetConfigHeader"))
        assertTrue("Empty state should explain how to unlock widget assignment", source.contains("Create a task first"))
        assertTrue("Task rows should expose an explicit assign affordance", source.contains("\"Assign\""))
        assertFalse("Widget picker should not regress to plain divider list rows", source.contains("HorizontalDivider"))
    }

    @Test
    fun homeScreenWidgetUsesBrandedVisualTreatment() {
        val layout = resRoot.resolve("layout/widget_task.xml").readText()
        val background = resRoot.resolve("drawable/widget_task_background.xml").readText()
        val colors = resRoot.resolve("values/colors.xml").readText()

        assertTrue("Widget should use the branded rounded background drawable", layout.contains("@drawable/widget_task_background"))
        assertTrue("Widget should include a visible secondary action cue", layout.contains("Tap to run"))
        assertTrue("Widget background should have rounded corners", background.contains("android:radius=\"16dp\""))
        assertTrue("Widget colors should be centralized in resources", colors.contains("widget_primary"))
    }

    @Test
    fun widgetAndShortcutRunnerFinishesAfterExecutionErrors() {
        val source = sourceRoot.resolve("com/opentasker/widget/TaskRunActivity.kt").readText()

        assertTrue("Runner should route all outcomes through a finish helper", source.contains("finishWithMessage"))
        assertTrue("Runner should catch task execution failures", source.contains("catch (e: Exception)"))
        assertTrue("Runner should log task execution failures", source.contains("Task run failed"))
    }
}
