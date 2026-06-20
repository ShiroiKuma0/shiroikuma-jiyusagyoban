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
class ProfileDaoInstrumentedTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun insertAndQueryProfileById() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            val entity = ProfileEntity(
                name = "Test Profile",
                enabled = true,
                enterTaskId = 1,
                exitTaskId = null,
                cooldownSec = 60,
                contextsJson = "[]",
            )
            val id = dao.insert(entity)
            val fetched = dao.getById(id)
            assertNotNull(fetched)
            assertEquals("Test Profile", fetched!!.name)
            assertEquals(true, fetched.enabled)
        } finally {
            db.close()
        }
    }

    @Test
    fun updateProfileName() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            val id = dao.insert(ProfileEntity(name = "Original", enabled = false, enterTaskId = 1, exitTaskId = null, cooldownSec = 0, contextsJson = "[]"))
            val entity = dao.getById(id)!!
            dao.update(entity.copy(name = "Updated"))
            assertEquals("Updated", dao.getById(id)!!.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteProfileRemovesIt() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            val id = dao.insert(ProfileEntity(name = "Doomed", enabled = true, enterTaskId = 1, exitTaskId = null, cooldownSec = 0, contextsJson = "[]"))
            val entity = dao.getById(id)!!
            dao.delete(entity)
            assertNull(dao.getById(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun getAllEnabledFiltersDisabledProfiles() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            dao.insert(ProfileEntity(name = "Active", enabled = true, enterTaskId = 1, exitTaskId = null, cooldownSec = 0, contextsJson = "[]"))
            dao.insert(ProfileEntity(name = "Inactive", enabled = false, enterTaskId = 2, exitTaskId = null, cooldownSec = 0, contextsJson = "[]"))
            val enabled = dao.getAllEnabled()
            assertEquals(1, enabled.size)
            assertEquals("Active", enabled[0].name)
        } finally {
            db.close()
        }
    }
}
