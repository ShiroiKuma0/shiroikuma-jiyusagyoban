package com.opentasker.core.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context as AndroidContext
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextSource
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automation loop end to end on a device: a context event reaches the matcher, the matcher
 * decides the profile activated, the task runs, and a run-log row appears.
 *
 * Every other engine test either starts at [executeAndLogTask] - after the decision to run - or
 * substitutes a fake Room-shaped store for the database. Nothing exercised the join between them
 * with a real context source and real Room, which is the seam where a matcher that never fires and
 * a task that never runs look identical.
 */
@RunWith(AndroidJUnit4::class)
class TriggerToRunLogInstrumentedTest {
    private val events = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 8)
    private var ranTaskNames = mutableListOf<String>()

    private val fakeSource = object : ContextSource {
        override val type = "state"
        override fun events(app: AndroidContext): Flow<ContextEvent> = events
    }

    @Before
    fun setUp() {
        ranTaskNames = mutableListOf()
        ContextSourceRegistry.register(fakeSource)
        ActionRegistry.register(object : Action {
            override val id = "test.e2e.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ranTaskNames += args["label"].orEmpty()
                return ActionResult.Success
            }
        })
    }

    @After
    fun tearDown() {
        ExecutionCommandLedger.reset()
    }

    @Test
    fun aMatchingContextEventRunsTheTaskAndWritesARunLogRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val task = Task(
                name = "Lower volume",
                actions = listOf(
                    ActionSpec(type = "test.e2e.record", args = mapOf("label" to "Lower volume")),
                ),
            )
            val taskId = db.taskDao().insert(task.toEntity())
            val storedTask = task.copy(id = taskId)
            val profile = Profile(
                id = 1L,
                name = "Headphones",
                enabled = true,
                enterTaskId = taskId,
                contexts = listOf(
                    ContextSpec(ContextType.STATE, mapOf("key" to "headphones", "value" to "true")),
                ),
            )

            val matcher = ProfileMatcher(context, profile)
            // Collection is started before anything is emitted: state changes are hot, so
            // subscribing after the emit would simply miss it and look like "never fired".
            val firstActivation = async { matcher.stateChanges().first() }
            matcher.awaitMonitorSubscriptions()
            // stateChanges() is cold, and a MutableSharedFlow with no subscriber discards what it
            // is given, so emitting before the matcher has actually subscribed loses the event and
            // reads exactly like a matcher that never fires.
            withTimeout(10_000) { events.subscriptionCount.first { it > 0 } }

            try {
                // A state the profile does not care about must not activate it.
                events.emit(ContextEvent(type = "state", matched = false, metadata = mapOf("headphones" to "false")))
                // ...and the one it does care about must.
                events.emit(ContextEvent(type = "state", matched = true, metadata = mapOf("headphones" to "true")))

                val change = withTimeout(10_000) { firstActivation.await() }
                assertTrue("the matcher must report an activation", change is ProfileStateChange.Activated)

                val result = executeAndLogTask(
                    appContext = context,
                    db = db,
                    task = storedTask,
                    source = "Profile: ${profile.name}",
                    profileId = profile.id,
                    profileName = profile.name,
                    execution = ExecutionEnvelope.create(storedTask, "Profile: ${profile.name}", profileId = profile.id),
                )

                assertTrue("the task must succeed", result.report.success)
                assertEquals(listOf("Lower volume"), ranTaskNames)

                val logged = db.runLogDao().getRecent()
                assertEquals(1, logged.size)
                assertEquals("Lower volume", logged.single().taskName)
                assertTrue("the run must be recorded as successful", logged.single().success)
            } finally {
                firstActivation.cancel()
            }
        } finally {
            db.close()
        }
    }
}
