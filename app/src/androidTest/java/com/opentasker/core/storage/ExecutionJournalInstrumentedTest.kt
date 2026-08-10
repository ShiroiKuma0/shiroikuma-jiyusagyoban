package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ExecutionJournal
import com.opentasker.core.engine.ExecutionJournalState
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.reconcileExecutionJournal
import com.opentasker.core.engine.outcome
import com.opentasker.core.engine.toRunLogDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionJournalInstrumentedTest {
    @Test
    fun processDeathRecoveryWritesOneInterruptedRowWithLineageAndLastStep() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val startedAt = 10_000L
            db.executionJournalDao().insert(
                ExecutionJournalEntity(
                    executionId = "execution-1",
                    taskId = 7,
                    taskName = "Night upload",
                    source = "Profile: Night",
                    sourceLabel = "Night",
                    profileId = 42L,
                    replayOf = "held-1",
                    parentExecutionId = "parent-1",
                    producer = "profile",
                    startedAtMs = startedAt,
                    updatedAtMs = startedAt,
                    lastStepIndex = null,
                    lastStepLabel = null,
                    state = ExecutionJournalState.ACTIVE.wireValue,
                    terminalReason = null,
                    terminalAtMs = null,
                ),
            )
            db.executionJournalDao().recordStep("execution-1", 2, "Upload", startedAt + 100)

            val recovered = reconcileExecutionJournal(db, nowMs = 20_000L)

            assertEquals(1, recovered.inspected)
            assertEquals(1, recovered.interrupted)
            assertEquals(1, recovered.logsWritten)
            assertEquals(0, db.executionJournalDao().active().size)
            val journal = requireNotNull(db.executionJournalDao().getByExecutionId("execution-1"))
            assertEquals(ExecutionJournalState.INTERRUPTED.wireValue, journal.state)
            assertTrue(journal.runLogWritten)
            assertEquals(0, db.executionJournalDao().markTerminal(
                "execution-1",
                ExecutionJournalState.SUCCEEDED.wireValue,
                "COMPLETED",
                21_000L,
            ))

            val log = requireNotNull(db.runLogDao().getByExecutionId("execution-1"))
            assertEquals("profile", log.source)
            assertEquals("Night", log.sourceLabel)
            assertEquals(RunLogOutcome.Interrupted, log.toDomain().outcome())
            val diagnostics = log.message.toRunLogDiagnostics()
            assertEquals("parent-1", diagnostics.parentExecutionId)
            assertEquals("held-1", diagnostics.replayOf)
            assertTrue(log.message.contains("Profile ID: 42"))
            assertTrue(log.message.contains("Last known step: 3. Upload"))
            assertTrue(log.message.contains("no automatic retry was attempted"))

            val repeated = reconcileExecutionJournal(db, nowMs = 30_000L)
            assertEquals(0, repeated.inspected)
            assertEquals(0, repeated.interrupted)
            assertEquals(0, repeated.logsWritten)
            assertEquals(1, db.runLogDao().count())
            assertFalse(db.executionJournalDao().unloggedTerminal(100).isNotEmpty())
        } finally {
            db.close()
        }
    }

    /**
     * Recovery is launched alongside engine startup, so a boot-triggered execution can journal
     * itself before recovery queries. It must survive: marking it INTERRUPTED writes a recovery
     * run-log row for a run that is still going, and then blocks its real terminal state.
     */
    @Test
    fun recoveryLeavesExecutionsStartedByThisProcessRunning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val processStart = 50_000L
            db.executionJournalDao().insert(journalRow("stale-execution", startedAtMs = processStart - 1))
            db.executionJournalDao().insert(journalRow("boot-execution", startedAtMs = processStart + 5))

            val recovered = reconcileExecutionJournal(
                db,
                nowMs = processStart + 10,
                processStartedAtMs = processStart,
            )

            assertEquals(1, recovered.inspected)
            assertEquals(1, recovered.interrupted)
            assertEquals(listOf("boot-execution"), db.executionJournalDao().active().map { it.executionId })
            assertEquals(null, db.runLogDao().getByExecutionId("boot-execution"))

            // The live execution still owns its terminal transition.
            assertEquals(
                1,
                db.executionJournalDao().markTerminal(
                    "boot-execution",
                    ExecutionJournalState.SUCCEEDED.wireValue,
                    null,
                    processStart + 20,
                ),
            )
        } finally {
            db.close()
        }
    }

    /** A long flow used to issue one row UPDATE per action, up to 100k of them in a single run. */
    @Test
    fun stepJournalingIsRateBoundedNotOncePerAction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.executionJournalDao().insert(journalRow("long-run", startedAtMs = 0L))

            var writes = 0
            // 500 actions completing 1 ms apart.
            repeat(500) { step ->
                if (ExecutionJournal.recordStep(db, "long-run", step, "Step $step", nowMs = step.toLong())) {
                    writes++
                }
            }

            assertEquals("the first step always writes, then at most one per second", 1, writes)

            // A later action past the window writes again, so the recovery record stays useful.
            assertTrue(ExecutionJournal.recordStep(db, "long-run", 500, "Step 500", nowMs = 2_000L))
            val row = requireNotNull(db.executionJournalDao().getByExecutionId("long-run"))
            assertEquals(500, row.lastStepIndex)
        } finally {
            ExecutionJournal.markTerminal(
                db,
                "long-run",
                ExecutionJournalState.SUCCEEDED,
                null,
                nowMs = 3_000L,
            )
            db.close()
        }
    }

    private fun journalRow(executionId: String, startedAtMs: Long) = ExecutionJournalEntity(
        executionId = executionId,
        taskId = 1,
        taskName = "Task",
        source = "Profile: Boot",
        sourceLabel = "Boot",
        profileId = 1L,
        replayOf = null,
        parentExecutionId = null,
        producer = "profile",
        startedAtMs = startedAtMs,
        updatedAtMs = startedAtMs,
        lastStepIndex = null,
        lastStepLabel = null,
        state = ExecutionJournalState.ACTIVE.wireValue,
        terminalReason = null,
        terminalAtMs = null,
    )
}
