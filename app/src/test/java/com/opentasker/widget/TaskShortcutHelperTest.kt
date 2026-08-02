package com.opentasker.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskShortcutHelperTest {
    @Test
    fun dynamicShortcutAcceptsBoundedTaskAndLabel() {
        assertTrue(TaskShortcutHelper.validatePublish("morning", 42L, "Morning", "dynamic").isValid)
    }

    @Test
    fun pinnedShortcutRejectsInvalidArguments() {
        assertFalse(TaskShortcutHelper.validatePublish("bad id", 42L, "Pinned", "pinned").isValid)
        assertFalse(TaskShortcutHelper.validatePublish("pinned", 0L, "Pinned", "pinned").isValid)
        assertFalse(TaskShortcutHelper.validatePublish("pinned", 42L, "Pinned", "unknown").isValid)
    }
}
