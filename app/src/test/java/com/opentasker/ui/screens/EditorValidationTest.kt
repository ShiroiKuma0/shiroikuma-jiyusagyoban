package com.opentasker.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorValidationTest {
    @Test
    fun taskEditorSaveRequiresNameAndBoundedPriority() {
        assertFalse(taskEditorCanSave("", 5))
        assertFalse(taskEditorCanSave("Task", null))
        assertFalse(taskEditorCanSave("Task", 11))
        assertTrue(taskEditorCanSave("Task", 0))
        assertTrue(taskEditorCanSave("Task", 10))
    }

    @Test
    fun profileEditorSaveRequiresExistingTasksAndValidCooldown() {
        assertFalse(profileEditorCanSave("Work", 1, false, true, "", null))
        assertFalse(profileEditorCanSave("Work", 1, true, true, "later", null))
        assertTrue(profileEditorCanSave("Work", 1, true, true, "", null))
        assertTrue(profileEditorCanSave("Work", 1, true, true, "15", 15))
    }
}
