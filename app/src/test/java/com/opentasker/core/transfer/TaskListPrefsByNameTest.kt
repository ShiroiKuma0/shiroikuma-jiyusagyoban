package com.opentasker.core.transfer

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task lists in preferences must travel as NAMES.
 *
 * 白い熊 restored this app onto a new phone on 2026-09-07 and **nothing ran on startup**. The cause
 * was not a setting they had missed: `auto_start_settings` had been carried across verbatim as
 * `task_ids = "1511"` — a Room row number from a database that had grown for months on the old
 * phone, matching no row on the new one, where the import had renumbered all 348 tasks from
 * scratch. Both phones' archives were read and both said `1511`.
 *
 * The engine looked the id up, found nothing, and ran nothing, because `?: continue` cannot tell a
 * dangling id from an empty list. That silence is why it survived a whole restore.
 *
 * This is the repository's oldest transport rule — **ids inside, names on the wire** — reaching the
 * one place that never saw it: these are preference strings, so they never passed through the
 * name-based DTO layer that already does this for everything else.
 *
 * Source gates, because the behaviour needs a real Room database and two of them at that; what has
 * to hold is a property of the transport.
 */
class TaskListPrefsByNameTest {

    private val backup by lazy {
        ProductionSources.read("com/opentasker/core/transfer/SettingsBackup.kt")
    }

    @Test
    fun `both task lists are carried, and both are named as id-bearing`() {
        assertTrue(
            "the run-on-start list must be exported",
            backup.contains("\"auto_start_settings\""),
        )
        // shutdown_settings was in no category at all, so the run-on-exit list never travelled —
        // the mirror of the same silence.
        assertTrue(
            "and so must the run-on-exit list",
            backup.contains("\"shutdown_settings\""),
        )
        val table = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "private val TASK_ID_PREFS = mapOf(",
            ")",
        )
        assertTrue(table.contains("auto_start_settings"))
        assertTrue(table.contains("shutdown_settings"))
    }

    @Test
    fun `the raw ids are never written into the archive`() {
        val body = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "private suspend fun exportPrefs(",
            "private fun typed(",
        )
        assertTrue("ids must be translated to names on the way out", body.contains("TASK_NAMES_KEY"))
        // Writing the ids as well would be worse than not translating at all: a reader that does
        // not understand the names restores them verbatim, which is precisely the original bug.
        assertTrue(
            "and the id key must be skipped, not merely supplemented",
            body.contains("if (idKey != null && key == idKey) return@forEach"),
        )
    }

    @Test
    fun `names are resolved against the importing database, and a miss is reported`() {
        val body = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "private suspend fun importPrefs(",
            "fun isZip(",
        )
        assertTrue(
            "names must be looked up in THIS database",
            body.contains("db.taskDao().getByNameIgnoreCase"),
        )
        assertTrue(
            "a name that no longer resolves must be said out loud, not silently dropped",
            body.contains("no such task:"),
        )
        assertFalse(
            "and the names key must not be written back into the preferences as a setting",
            body.contains("ed.putString(TASK_NAMES_KEY"),
        )
    }

    @Test
    fun `a dangling auto-start id is logged rather than skipped in silence`() {
        val body = ProductionSources.block(
            "com/opentasker/core/engine/AutomationService.kt",
            "private fun runAutoStartTasks(",
            "private fun applyContextSourceGating(",
        )
        assertTrue(
            "the engine must say which id it could not find",
            body.contains("Auto-start: no task with id"),
        )
        assertFalse(
            "the bare elvis-continue is what hid this for a whole restore",
            body.contains("?: continue"),
        )
    }
}
