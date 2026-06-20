package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDaoInstrumentedTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun insertAndQueryTaskById() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.taskDao()
            val id = dao.insert(TaskEntity(name = "Morning Routine", priority = 5, collisionMode = "ABORT_NEW", actionsJson = "[]"))
            val fetched = dao.getById(id)
            assertNotNull(fetched)
            assertEquals("Morning Routine", fetched!!.name)
            assertEquals(5, fetched.priority)
        } finally {
            db.close()
        }
    }

    @Test
    fun getByNameReturnsSingleMatch() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.taskDao()
            dao.insert(TaskEntity(name = "Alpha", priority = 1, collisionMode = "ABORT_NEW", actionsJson = "[]"))
            dao.insert(TaskEntity(name = "Beta", priority = 2, collisionMode = "ABORT_NEW", actionsJson = "[]"))
            val result = dao.getByName("Beta")
            assertNotNull(result)
            assertEquals("Beta", result!!.name)
            assertNull(dao.getByName("Gamma"))
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteTaskRemovesIt() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.taskDao()
            val id = dao.insert(TaskEntity(name = "Temp", priority = 0, collisionMode = "ABORT_NEW", actionsJson = "[]"))
            val entity = dao.getById(id)!!
            dao.delete(entity)
            assertNull(dao.getById(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun updateTaskPriority() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.taskDao()
            val id = dao.insert(TaskEntity(name = "Task", priority = 3, collisionMode = "ABORT_NEW", actionsJson = "[]"))
            val entity = dao.getById(id)!!
            dao.update(entity.copy(priority = 9))
            assertEquals(9, dao.getById(id)!!.priority)
        } finally {
            db.close()
        }
    }
}
