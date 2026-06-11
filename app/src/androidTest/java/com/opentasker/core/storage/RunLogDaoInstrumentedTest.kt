package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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

            val deleted = dao.pruneRetention(maxEntries = 2, minimumTimestamp = 3_000L)

            assertEquals(3, deleted)
            assertEquals(2, dao.count())
            assertEquals(listOf(5L, 4L), dao.getRecent().map { it.taskId })
        } finally {
            db.close()
        }
    }
}
