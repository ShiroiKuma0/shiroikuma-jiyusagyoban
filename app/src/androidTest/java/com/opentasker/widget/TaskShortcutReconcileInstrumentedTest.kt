package com.opentasker.widget

import android.os.ParcelFileDescriptor
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A task's shortcut follows the task through the app's own database: renaming the task renames
 * the shortcut, and deleting it takes the shortcut out of use. Nothing here calls the helper's
 * reconcile; the app does that whenever the task list changes.
 */
@RunWith(AndroidJUnit4::class)
class TaskShortcutReconcileInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val db = OpenTaskerApp_NoHilt.db
    private val name = "shortcut probe ${System.nanoTime()}"
    private var taskId = 0L

    @After
    fun cleanUp() {
        runBlocking { db.taskDao().getById(taskId)?.let { db.taskDao().delete(it) } }
        ShortcutManagerCompat.removeDynamicShortcuts(app, listOf("task_$taskId"))
    }

    @Test
    fun theShortcutFollowsARenameAndADelete() = runBlocking {
        // Android limits how often an app in the background updates its shortcuts, and a test
        // process counts as one. Lift that so the rename isn't held back.
        shell("cmd shortcut reset-throttling")
        taskId = db.taskDao().insert(Task(name = name, actions = emptyList()).toEntity())
        val shortcutId = "task_$taskId"
        TaskShortcutHelper.publishShortcut(app, shortcutId, taskId, name, TaskShortcutHelper.PublishMode.DYNAMIC)

        val entity = requireNotNull(db.taskDao().getById(taskId))
        db.taskDao().update(entity.copy(name = "$name renamed"))
        assertEquals("$name renamed", awaitShortcut(shortcutId) { it?.shortLabel?.toString() == "$name renamed" }?.shortLabel?.toString())

        db.taskDao().delete(entity)
        // A shortcut that's only dynamic goes away when it's disabled. A pinned one would stay,
        // greyed out with the deleted message.
        val afterDelete = awaitShortcut(shortcutId) { it == null || !it.isEnabled }
        assertTrue("the shortcut still runs a deleted task", afterDelete == null || !afterDelete.isEnabled)
    }

    private suspend fun awaitShortcut(id: String, done: (ShortcutInfoCompat?) -> Boolean): ShortcutInfoCompat? {
        var current = shortcut(id)
        repeat(100) {
            if (done(current)) return current
            delay(100)
            current = shortcut(id)
        }
        return current
    }

    private fun shortcut(id: String): ShortcutInfoCompat? =
        ShortcutManagerCompat.getShortcuts(
            app,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
        ).firstOrNull { it.id == id }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
