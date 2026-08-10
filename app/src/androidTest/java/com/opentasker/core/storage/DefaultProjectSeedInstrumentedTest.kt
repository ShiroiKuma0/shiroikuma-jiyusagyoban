package com.opentasker.core.storage

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fresh install creates the database through Room's generated `createAllTables` and never runs a
 * migration, so the default workspace has to be seeded by the same callback the application
 * installs. Without it `projects` starts empty and, because the id is AUTOINCREMENT, the first
 * project a user creates silently becomes the undeletable Default that owns everything.
 */
@RunWith(AndroidJUnit4::class)
class DefaultProjectSeedInstrumentedTest {

    private fun buildFreshDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    )
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(DatabaseMigrations.SEED_DEFAULT_PROJECT)
            }
        })
        .allowMainThreadQueries()
        .build()

    @Test
    fun freshDatabaseContainsTheDefaultProject() = runBlocking {
        val db = buildFreshDb()
        try {
            val projects = db.projectDao().getAll()
            assertEquals(1, projects.size)
            assertEquals(DEFAULT_PROJECT_ID, projects.single().id)
            assertEquals("Default", projects.single().name)
        } finally {
            db.close()
        }
    }

    @Test
    fun firstUserCreatedProjectDoesNotBecomeTheDefault() = runBlocking {
        val db = buildFreshDb()
        try {
            val dao = db.projectDao()
            val newId = dao.insert(ProjectEntity(name = "Work", position = 1))

            assertNotNull(dao.getAll().firstOrNull { it.id == DEFAULT_PROJECT_ID })
            assert(newId != DEFAULT_PROJECT_ID) {
                "a user-created project must not take the reserved default id"
            }
            assertEquals(1, dao.deleteIfNotDefault(newId))
        } finally {
            db.close()
        }
    }
}
