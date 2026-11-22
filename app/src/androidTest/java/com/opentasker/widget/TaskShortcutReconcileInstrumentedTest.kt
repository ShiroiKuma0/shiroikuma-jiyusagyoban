package com.opentasker.widget

import android.content.Intent
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
 * the shortcut Pin made, and deleting it takes the shortcut out of use. A shortcut published with
 * its own label keeps it. Nothing here calls the helper's reconcile; the app does that whenever
 * the task list changes.
 */
@RunWith(AndroidJUnit4::class)
class TaskShortcutReconcileInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val db = OpenTaskerApp_NoHilt.db
    private val name = "shortcut probe ${System.nanoTime()}"
    private val taskIds = mutableListOf<Long>()

    @After
    fun cleanUp() {
        runBlocking { taskIds.forEach { id -> db.taskDao().getById(id)?.let { db.taskDao().delete(it) } } }
        ShortcutManagerCompat.removeDynamicShortcuts(app, taskIds.map { "task_$it" })
    }

    @Test
    fun theShortcutFollowsARenameAndADelete() = runBlocking {
        // Android limits how often an app in the background updates its shortcuts, and a test
        // process counts as one. Lift that so the rename isn't held back.
        shell("cmd shortcut reset-throttling")
        val followed = db.taskDao().insert(Task(name = name, actions = emptyList()).toEntity()).also { taskIds += it }
        val picked = db.taskDao().insert(Task(name = "$name picked", actions = emptyList()).toEntity()).also { taskIds += it }
        val older = db.taskDao().insert(Task(name = "$name older", actions = emptyList()).toEntity()).also { taskIds += it }
        // Built the way Pin builds one, pushed as dynamic so no launcher has to confirm it.
        ShortcutManagerCompat.pushDynamicShortcut(
            app,
            TaskShortcutHelper.taskShortcut(app, "task_$followed", followed, name, TaskShortcutHelper.ShortcutKind.FOLLOWS, madeFor = name),
        )
        TaskShortcutHelper.publishShortcut(app, "task_$picked", picked, "Picked label", TaskShortcutHelper.PublishMode.DYNAMIC, "$name picked")
        // Published by a version from before shortcuts said what kind they are.
        val olderIntent = Intent(app, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, older)
        }
        ShortcutManagerCompat.pushDynamicShortcut(
            app,
            ShortcutInfoCompat.Builder(app, "task_$older").setShortLabel("Older label").setIntent(olderIntent).build(),
        )

        // The other two first, so by the time the followed one's new label shows, the app has seen all three.
        listOf(picked, older, followed).forEach { id ->
            val entity = requireNotNull(db.taskDao().getById(id))
            db.taskDao().update(entity.copy(name = entity.name + " renamed"))
        }
        assertEquals(
            "$name renamed",
            awaitShortcut("task_$followed") { it?.shortLabel?.toString() == "$name renamed" }?.shortLabel?.toString(),
        )
        assertEquals("Picked label", shortcut("task_$picked")?.shortLabel?.toString())
        assertEquals("Older label", shortcut("task_$older")?.shortLabel?.toString())

        listOf(followed, picked, older).forEach { id -> db.taskDao().getById(id)?.let { db.taskDao().delete(it) } }
        // A shortcut that's only dynamic goes away when it's disabled. A pinned one would stay,
        // grayed out with the deleted message.
        listOf(followed, picked, older).forEach { id ->
            val afterDelete = awaitShortcut("task_$id") { it == null || !it.isEnabled }
            assertTrue("shortcut task_$id still runs a deleted task", afterDelete == null || !afterDelete.isEnabled)
        }
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
