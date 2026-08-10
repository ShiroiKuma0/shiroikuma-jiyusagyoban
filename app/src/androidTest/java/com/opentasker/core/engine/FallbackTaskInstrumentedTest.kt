package com.opentasker.core.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FallbackTaskInstrumentedTest {
    private var observedErrorJson: String? = null

    @Before
    fun setUp() {
        observedErrorJson = null
        ActionRegistry.register(object : Action {
            override val id = "test.fallback.fail"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
                ActionResult.Failure("fallback source failed")
        })
        ActionRegistry.register(object : Action {
            override val id = "test.fallback.observe"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                observedErrorJson = ctx.variables.get(TaskFailureVariables.JSON)
                return ActionResult.Success
            }
        })
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FallbackTaskSettings(context).saveTaskId(null)
        ExecutionCommandLedger.reset()
    }

    @Test
    fun profileFallbackRunsBeforeGlobalFallbackAndReceivesStructuredError() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val failingTask = insert(db, "Primary", "test.fallback.fail")
            val profileFallback = insert(db, "Profile recovery", "test.fallback.observe")
            val globalFallback = insert(db, "Global recovery", "test.fallback.observe")
            FallbackTaskSettings(context).saveTaskId(globalFallback.id)

            val result = executeAndLogTask(
                appContext = context,
                db = db,
                task = failingTask,
                source = "Profile: Work",
                profileId = 9L,
                profileName = "Work",
                profileFallbackTaskId = profileFallback.id,
                execution = ExecutionEnvelope.create(failingTask, "Profile: Work", profileId = 9L),
            )

            assertFalse(result.report.success)
            assertNotNull(result.report.structuredError)
            assertEquals(profileFallback.id, result.fallback?.taskId)
            assertEquals("profile", result.fallback?.source)
            assertTrue(result.fallback?.success == true)
            assertTrue(observedErrorJson.orEmpty().contains("test.fallback.fail"))
            assertTrue(observedErrorJson.orEmpty().contains("Work"))
            assertEquals(
                listOf("Profile recovery", "Primary"),
                db.runLogDao().getRecent().map { it.taskName },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun fallbackFailureDoesNotRecurse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val failingTask = insert(db, "Primary", "test.fallback.fail")
            val fallback = insert(db, "Broken recovery", "test.fallback.fail")

            val result = executeAndLogTask(
                appContext = context,
                db = db,
                task = failingTask,
                source = "Manual run",
                profileFallbackTaskId = fallback.id,
                execution = ExecutionEnvelope.create(failingTask, "Manual run"),
            )

            assertFalse(result.report.success)
            assertEquals(fallback.id, result.fallback?.taskId)
            assertFalse(result.fallback?.success == true)
            assertEquals(2, db.runLogDao().getRecent().size)
        } finally {
            db.close()
        }
    }

    private suspend fun insert(db: AppDatabase, name: String, actionType: String): Task {
        val draft = Task(name = name, actions = listOf(ActionSpec(type = actionType)))
        val id = db.taskDao().insert(draft.toEntity())
        return draft.copy(id = id)
    }
}
