package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
