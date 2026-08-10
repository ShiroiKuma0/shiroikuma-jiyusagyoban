package com.opentasker.ui.screens

import com.opentasker.core.model.ProfileLifetime
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

    @Test
    fun profileEditorSaveRequiresBoundedPriorityGraceAndDateLifetimeExpiry() {
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                parsedPriority = 101,
            ),
        )
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                lifetime = ProfileLifetime.UNTIL_DATE,
            ),
        )
        assertTrue(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                parsedPriority = -5,
                parsedGracePeriod = 20,
                lifetime = ProfileLifetime.UNTIL_DATE,
                parsedExpiryDate = 1_700_000_000_000L,
            ),
        )
    }
}
