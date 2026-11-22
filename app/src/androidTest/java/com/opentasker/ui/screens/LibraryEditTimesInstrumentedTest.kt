package com.opentasker.ui.screens

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Creating a task through the screens' view model dates it for the Last edited order. */
@RunWith(AndroidJUnit4::class)
class LibraryEditTimesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DATABASE)
        db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun creatingATaskDatesItForLastEdited() = runBlocking<Unit> {
        // New ids start past this one, far above any real task, so the time can't land on one of them.
        db.taskDao().insert(Task(id = SEED_ID, name = "Seed").toEntity())
        db.taskDao().delete(db.taskDao().getAll().single())
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ActiveAutomationViewModelFactory(db, context))[ActiveAutomationViewModel::class.java]
        }
        var id: Long? = null
        try {
            withContext(Dispatchers.Main) { viewModel.createTask("Stamp check", 5, CollisionMode.ABORT_NEW) }
            val created = withTimeout(10_000) {
                var found: Long? = null
                while (found == null) {
                    found = db.taskDao().getAll().firstOrNull { it.name == "Stamp check" }?.id
                    if (found == null) delay(50)
                }
                found
            }
            id = created
            assertTrue(created > SEED_ID)

            val key = EditHistoryAvailabilityState.key(EditHistoryDao.TYPE_TASK, created)
            val times = withTimeout(10_000) { LibraryEditTimes.times(context).first { key in it } }
            assertTrue(times.getValue(key) > 0L)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            id?.let { LibraryEditTimes.forget(context, EditHistoryDao.TYPE_TASK, it) }
        }
    }

    private companion object {
        const val TEST_DATABASE = "opentasker-edit-times-test.db"
        const val SEED_ID = 9_000_000_000L
    }
}
