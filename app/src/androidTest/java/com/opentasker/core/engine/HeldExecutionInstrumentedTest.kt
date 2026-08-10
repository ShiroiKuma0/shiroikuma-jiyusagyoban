package com.opentasker.core.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeldExecutionInstrumentedTest {
    @After
    fun tearDown() = ExecutionCommandLedger.reset()

    @Test
    fun rejectedExecutionIsHeldWithRedactedPayloadAndReplayCreatesLinkedRun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val storedId = db.taskDao().insert(
                Task(
                    name = "Replay task",
                    actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "replayed"))),
                ).toEntity(),
            )
            val task = Task(
                id = storedId,
                name = "Replay task",
                actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "replayed"))),
            )
            val admission = ExecutionAdmissionController(
                ExecutionAdmissionLimits(
                    globalMaxActive = 1,
                    perProfileMaxActive = 1,
                    globalBurstLimit = 1,
                    perProfileBurstLimit = 1,
                ),
            )
            val occupied = requireNotNull(admission.tryAcquire().lease)
            val heldResult = executeAndLogTask(
                appContext = context,
                db = db,
                task = task,
                source = "External intent",
                initialVariables = mapOf("eventType" to "push", "API_TOKEN" to "secret-value"),
                admissionController = admission,
                execution = ExecutionEnvelope.create(task, "External intent", executionId = "held-run-1"),
            )
            occupied.release()

            assertTrue(heldResult.held)
            val held = requireNotNull(db.runLogDao().getRecent().firstOrNull()).toDomain()
            assertTrue(held.held)
            assertEquals("held-run-1", held.executionId)
            assertTrue(held.heldPayload.orEmpty().contains("<redacted>"))
            assertFalse(held.heldPayload.orEmpty().contains("secret-value"))
            assertNotNull(held.heldPolicy)

            val replay = replayHeldExecution(context, db, held)
            val logs = db.runLogDao().getRecent()

            assertFalse(replay.held)
            assertEquals("held-run-1", replay.execution.replayOf)
            assertEquals(2, logs.size)
            assertEquals("held-run-1", logs.last().executionId)
            assertEquals("held-run-1", logs.first().replayOf)
            assertFalse(logs.first().held)
        } finally {
            db.close()
        }
    }

    @Test
    fun silentOverflowSkipsWithoutCreatingRunLogEntry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val task = Task(
                id = 91,
                name = "Silent task",
                actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "never runs"))),
            )
            val admission = ExecutionAdmissionController(
                ExecutionAdmissionLimits(
                    globalMaxActive = 1,
                    perProfileMaxActive = 1,
                    globalBurstLimit = 2,
                    perProfileBurstLimit = 1,
                ),
            )
            val occupied = requireNotNull(admission.tryAcquire().lease)
            val result = executeAndLogTask(
                appContext = context,
                db = db,
                task = task,
                source = "Profile: Silent",
                admissionController = admission,
                profileId = 91L,
                overflowPolicy = ProfileOverflowPolicy.SILENT,
                execution = ExecutionEnvelope.create(task, "Profile: Silent", profileId = 91L),
            )
            occupied.release()

            assertFalse(result.held)
            assertFalse(result.logInserted)
            assertTrue(result.skippedReason.orEmpty().contains("Counts:"))
            assertTrue(db.runLogDao().getRecent().isEmpty())
        } finally {
            db.close()
        }
    }
}
