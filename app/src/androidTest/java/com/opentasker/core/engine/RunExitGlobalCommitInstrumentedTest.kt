package com.opentasker.core.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A run that ends without a report still keeps the globals it set. The JVM suite holds this only
 * as a source check (`ExecutionSemanticsContractTest.everyRunExitCommitsChangedGlobals`), since
 * [executeAndLogTask] needs Room.
 */
@RunWith(AndroidJUnit4::class)
class RunExitGlobalCommitInstrumentedTest {
    @After
    fun tearDown() {
        ExecutionCommandLedger.reset()
        ActiveExecutionRegistry.reset()
    }

    @Test
    fun aRunCancelledMidWaitKeepsTheGlobalItSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val task = storedTask(db, "Cancelled mid-wait", ActionSpec(type = "flow.wait", args = mapOf("millis" to "60000")))
            val execution = ExecutionEnvelope.create(task, "Manual", executionId = "cancelled-global-commit")
            val run = async(Dispatchers.Default) {
                runCatching { executeAndLogTask(context, db, task, "Manual", execution = execution) }
            }
            // Step 1 is the wait, so the global is set by the time the run reports it.
            val waiting = withTimeout(10_000) {
                ActiveExecutionRegistry.active.first { runs ->
                    runs.any { it.executionId == execution.executionId && it.stepIndex == 1 }
                }
            }.first { it.executionId == execution.executionId }

            assertTrue(ActiveExecutionRegistry.cancel(waiting.id))
            val outcome = withTimeout(10_000) { run.await() }

            assertTrue("a cancelled run rethrows its cancellation", outcome.exceptionOrNull() is CancellationException)
            assertEquals("7", db.variableDao().get("COUNTER")?.value)
            val log = db.runLogDao().getRecent().single().toDomain()
            assertFalse(log.success)
            assertTrue(log.message, log.message.contains("Decision: Cancelled"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aRunThatThrowsKeepsTheGlobalItSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            // The journal's step write runs after each step, outside the step's own error handling,
            // so refusing it throws out of the runner right after the global is set. The terminal
            // write sets other columns and still goes through.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_step_write BEFORE UPDATE OF lastStepIndex ON execution_journal " +
                    "BEGIN SELECT RAISE(ABORT, 'step journal write refused'); END",
            )
            val task = storedTask(db, "Throws after setting", ActionSpec(type = "log", args = mapOf("message" to "never reached")))
            val execution = ExecutionEnvelope.create(task, "Manual", executionId = "failed-global-commit")

            val outcome = runCatching { executeAndLogTask(context, db, task, "Manual", execution = execution) }

            val error = outcome.exceptionOrNull()
            assertTrue("the run ends by throwing, not with a report: $error", error != null && error !is CancellationException)
            assertEquals("7", db.variableDao().get("COUNTER")?.value)
            val log = db.runLogDao().getRecent().single().toDomain()
            assertFalse(log.success)
            assertTrue(log.message, log.message.contains("step journal write refused"))
        } finally {
            db.close()
        }
    }

    /** A stored task that sets the global %COUNTER to 7 and then does [then]. */
    private suspend fun storedTask(db: AppDatabase, name: String, then: ActionSpec): Task {
        val actions = listOf(
            ActionSpec(type = "var.set", args = mapOf("name" to "%COUNTER", "value" to "7")),
            then,
        )
        val id = db.taskDao().insert(Task(name = name, actions = actions).toEntity())
        return Task(id = id, name = name, actions = actions)
    }
}
