package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditHistoryDaoInstrumentedTest {
    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun pruneOldOnlyRemovesRowsForRequestedEntity() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.editHistoryDao()
            repeat(7) { index ->
                dao.insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_TASK,
                        entityId = 10,
                        previousJson = "task-a-$index",
                        timestamp = index.toLong(),
                    ),
                )
            }
            repeat(3) { index ->
                dao.insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_TASK,
                        entityId = 20,
                        previousJson = "task-b-$index",
                        timestamp = index.toLong(),
                    ),
                )
            }
            repeat(2) { index ->
                dao.insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = 10,
                        previousJson = "profile-$index",
                        timestamp = index.toLong(),
                    ),
                )
            }
            dao.insert(
                EditHistoryEntity(
                    entityType = EditHistoryDao.TYPE_SCENE,
                    entityId = 10,
                    previousJson = "scene-0",
                    timestamp = 0,
                ),
            )

            dao.pruneOld(EditHistoryDao.TYPE_TASK, entityId = 10, keep = 2)

            assertEquals(
                listOf("task-a-6", "task-a-5"),
                dao.getForEntity(EditHistoryDao.TYPE_TASK, 10).map { it.previousJson },
            )
            assertEquals(
                listOf("task-b-2", "task-b-1", "task-b-0"),
                dao.getForEntity(EditHistoryDao.TYPE_TASK, 20).map { it.previousJson },
            )
            assertEquals(
                listOf("profile-1", "profile-0"),
                dao.getForEntity(EditHistoryDao.TYPE_PROFILE, 10).map { it.previousJson },
            )
            assertEquals(
                listOf("scene-0"),
                dao.getForEntity(EditHistoryDao.TYPE_SCENE, 10).map { it.previousJson },
            )
        } finally {
            db.close()
        }
    }
}
