package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.diagnostics.RunLogExporter
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class RunLogDaoInstrumentedTest {
    @Test
    fun pruneRetentionDeletesRowsOutsideAgeOrCountLimits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.runLogDao()
            repeat(5) { index ->
                val run = index + 1L
                dao.insert(
                    RunLogEntity(
                        taskId = run,
                        taskName = "Task $run",
                        timestamp = run * 1_000L,
                        durationMs = 10,
                        success = true,
                        message = "Completed",
                    )
                )
            }

            assertEquals(3, dao.countPrunable(maxEntries = 2, minimumTimestamp = 3_000L))
            assertEquals(1_000L, dao.oldestTimestamp())
            val deleted = dao.pruneRetention(maxEntries = 2, minimumTimestamp = 3_000L)

            assertEquals(3, deleted)
            assertEquals(2, dao.count())
            assertEquals(listOf(5L, 4L), dao.getRecent().map { it.taskId })
        } finally {
            db.close()
        }
    }

    @Test
    fun pruneRetentionNeverDeletesHeldOrStarredRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.runLogDao()
            dao.insert(run(taskId = 1, timestamp = 1_000L, held = true))
            dao.insert(run(taskId = 2, timestamp = 2_000L, starred = true))
            dao.insert(run(taskId = 3, timestamp = 3_000L))
            dao.insert(run(taskId = 4, timestamp = 4_000L))
            dao.insert(run(taskId = 5, timestamp = 5_000L))

            assertEquals(3, dao.countPrunable(maxEntries = 1, minimumTimestamp = 10_000L))
            assertEquals(3, dao.pruneRetention(maxEntries = 1, minimumTimestamp = 10_000L))
            assertEquals(2, dao.count())
            assertEquals(listOf(2L, 1L), dao.getRecent().map { it.taskId })
        } finally {
            db.close()
        }
    }

    @Test
    fun keysetPagesStayStableWhenANewerRowArrives() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.runLogDao()
            // Identical timestamps force the id tie-breaker to carry the page boundary.
            repeat(6) { index -> dao.insert(run(taskId = index + 1L, timestamp = 1_000L)) }
            val snapshot = dao.openSnapshot(RunLogQuery())
            val first = dao.loadPage(snapshot, pageSize = 2)

            dao.insert(run(taskId = 99, timestamp = 99_000L))

            val second = dao.loadPage(snapshot, before = first.entries.last().key(), pageSize = 2)
            val third = dao.loadPage(snapshot, before = second.entries.last().key(), pageSize = 2)
            val taskIds = (first.entries + second.entries + third.entries).map { it.taskId }
            assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), taskIds)
            assertEquals(taskIds.size, taskIds.distinct().size)
            assertFalse(third.hasMore)
        } finally {
            db.close()
        }
    }

    @Test
    fun pagingFiltersRunInSqlAndTreatLikeCharactersLiterally() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.runLogDao()
            dao.insert(run(1, 1_000, taskName = "100% Complete", success = true))
            dao.insert(run(2, 2_000, taskName = "Ordinary", success = false, message = "Decision: Skipped"))
            dao.insert(run(3, 3_000, taskName = "Ordinary", success = false, message = "Decision: Cancelled"))
            dao.insert(run(4, 4_000, taskName = "Ordinary", success = false, message = "Failure"))
            dao.insert(run(4, 5_000, taskName = "Ordinary", success = true, message = "Later success"))
            dao.insert(run(5, 6_000, taskName = "Held", success = false, message = "Decision: Held", held = true))
            dao.insert(run(6, 7_000, taskName = "Interrupted", success = false, message = "Decision: Interrupted"))

            val literalPercent = dao.openSnapshot(
                RunLogQuery(escapedSearch = escapeRunLogLikeQuery("%")),
            )
            assertEquals(listOf(1L), dao.loadPage(literalPercent).entries.map { it.taskId })

            val skipped = dao.openSnapshot(RunLogQuery(status = RunLogStatusQuery.SKIPPED))
            val cancelled = dao.openSnapshot(RunLogQuery(status = RunLogStatusQuery.CANCELLED))
            val failed = dao.openSnapshot(RunLogQuery(status = RunLogStatusQuery.FAILED))
            val held = dao.openSnapshot(RunLogQuery(status = RunLogStatusQuery.HELD))
            val interrupted = dao.openSnapshot(RunLogQuery(status = RunLogStatusQuery.INTERRUPTED))
            val taskAndDate = dao.openSnapshot(RunLogQuery(taskId = 4, minimumTimestamp = 4_500))
            assertEquals(listOf(2L), dao.loadPage(skipped).entries.map { it.taskId })
            assertEquals(listOf(3L), dao.loadPage(cancelled).entries.map { it.taskId })
            assertEquals(listOf(4L), dao.loadPage(failed).entries.map { it.taskId })
            assertEquals(listOf(5L), dao.loadPage(held).entries.map { it.taskId })
            assertEquals(listOf(6L), dao.loadPage(interrupted).entries.map { it.taskId })
            assertEquals(listOf(5_000L), dao.loadPage(taskAndDate).entries.map { it.timestamp })
        } finally {
            db.close()
        }
    }

    @Test
    fun exportsEverySelectedRowWithRedactionAndCsvEscaping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.runLogDao()
            dao.insert(run(1, 1_000, taskName = "Quoted, task", message = "token=very-secret"))
            dao.insert(run(2, 2_000, message = "Completed"))
            val snapshot = dao.openSnapshot(RunLogQuery())

            val jsonOutput = ByteArrayOutputStream()
            val csvOutput = ByteArrayOutputStream()
            assertEquals(2, RunLogExporter(dao).export(snapshot, RunLogExportFormat.JSON, jsonOutput))
            assertEquals(2, RunLogExporter(dao).export(snapshot, RunLogExportFormat.CSV, csvOutput))
            val json = jsonOutput.toString(Charsets.UTF_8.name())
            val csv = csvOutput.toString(Charsets.UTF_8.name())

            assertTrue("[REDACTED]" in json)
            assertFalse("very-secret" in json)
            assertTrue("\"Quoted, task\"" in csv)
            assertFalse("very-secret" in csv)
            assertEquals(3, csv.lineSequence().filter(String::isNotBlank).count())
        } finally {
            db.close()
        }
    }

    private fun run(
        taskId: Long,
        timestamp: Long,
        taskName: String = "Task $taskId",
        success: Boolean = true,
        message: String = "Completed",
        held: Boolean = false,
        starred: Boolean = false,
    ) = RunLogEntity(
        taskId = taskId,
        taskName = taskName,
        timestamp = timestamp,
        durationMs = 10,
        success = success,
        message = message,
        held = held,
        starred = starred,
    )
}
