package com.opentasker.ui.screens

import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTaskEditorMigrationTest {
    @Test
    fun uniqueLegacyNameIsWrittenIntoCurrentIdField() {
        val tasks = listOf(Task(id = 7, name = "Morning"))

        val value = existingActionArgValue(
            actionId = "notify.show",
            key = "button1_task_id",
            args = mapOf("button1_task" to "Morning"),
            tasks = tasks,
        )

        assertEquals("7", value)
        assertTrue(unresolvedNotificationTaskBindings("notify.show", mapOf("button1_task" to "Morning"), tasks).isEmpty())
    }

    @Test
    fun duplicateLegacyNameRequiresExplicitReselection() {
        val tasks = listOf(Task(id = 7, name = "Duplicate"), Task(id = 8, name = "Duplicate"))
        val args = mapOf("button2_task" to "Duplicate")

        val value = existingActionArgValue("notify.show", "button2_task_id", args, tasks)
        val issue = unresolvedNotificationTaskBindings("notify.show", args, tasks).getValue("button2_task_id")

        assertEquals("", value)
        assertEquals(NotificationTaskResolution.Ambiguous("Duplicate", 2), issue)
    }
}
