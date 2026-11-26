package com.sysadmindoc.ruledeck.widget

import com.opentasker.widget.TaskRunActivity
import com.opentasker.widget.TaskWidgetConfigActivity
import com.opentasker.widget.TaskWidgetProvider
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

        assertTrue("Widget picker should explain the assignment flow", source.contains("R.string.widget_choose_task_title"))
        assertTrue("Widget picker should keep a summary/header card", source.contains("WidgetConfigHeader"))
        assertTrue("Empty state should explain how to unlock widget assignment", source.contains("R.string.widget_empty_title"))
        assertTrue("Task rows should expose an explicit assign affordance", source.contains("R.string.widget_assign"))
        assertFalse("Widget picker should not regress to plain divider list rows", source.contains("HorizontalDivider"))
    }

    @Test
    fun homeScreenWidgetIsColouredFromTheRoleTable() {
        // The fork's widget carries no colour in XML: ground, frame, text and icon come from the
        // role table and are applied by the provider (白い熊, 2026-11-21: nothing pinned in XML).
        val layout = resRoot.resolve("layout/widget_task.xml").readText()
        val provider = sourceRoot.resolve("com/opentasker/widget/TaskWidgetProvider.kt").readText()

        assertTrue("Widget should include a visible secondary action cue", layout.contains("@string/widget_tap_to_run"))
        assertFalse("The widget layout must not pin a colour", Regex("#[0-9A-Fa-f]{6,8}|@color/").containsMatchIn(layout))
        listOf("WIDGET_GROUND", "WIDGET_FRAME", "WIDGET_TEXT", "WIDGET_ICON").forEach { role ->
            assertTrue("The provider should colour the widget from UiRole.$role", provider.contains("UiRole.$role"))
        }
    }

    @Test
    fun widgetAndShortcutRunnerFinishesAfterExecutionErrors() {
        val source = sourceRoot.resolve("com/opentasker/widget/TaskRunActivity.kt").readText()

        assertTrue("Runner should route all outcomes through a finish helper", source.contains("finishWithMessage"))
        assertTrue("Runner should catch task execution failures", source.contains("catch (e: Exception)"))
        assertTrue("Runner should log task execution failures", source.contains("Task run failed"))
    }
}
