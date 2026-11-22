package com.opentasker.core.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A profile's cooldown is reserved at dispatch, before admission runs. A run admission refuses never
 * started, so it gives the cooldown back; before, every trigger for the rest of the window logged
 * "on cooldown" though nothing had run.
 */
@RunWith(AndroidJUnit4::class)
class CooldownAdmissionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var task: Task

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        task = Task(id = db.taskDao().insert(Task(name = "Cooldown task").toEntity()), name = "Cooldown task")
    }

    @After
    fun tearDown() {
        db.close()
        ExecutionCommandLedger.reset()
    }

    @Test
    fun everyAdmissionRefusalGivesTheCooldownBack() = runBlocking {
        val refusals = mapOf(
            ExecutionAdmissionRejectionKind.CIRCUIT_OPEN to circuitOpen(),
            ExecutionAdmissionRejectionKind.GLOBAL_ACTIVE to occupied(limits(globalMaxActive = 1), OTHER_PROFILE),
            ExecutionAdmissionRejectionKind.PROFILE_ACTIVE to occupied(limits(perProfileMaxActive = 1), PROFILE),
            ExecutionAdmissionRejectionKind.GLOBAL_BURST to oneRunSoFar(limits(globalBurstLimit = 1), OTHER_PROFILE),
            ExecutionAdmissionRejectionKind.PROFILE_BURST to oneRunSoFar(limits(perProfileBurstLimit = 1), PROFILE),
            ExecutionAdmissionRejectionKind.GLOBAL_AND_PROFILE_BURST to
                oneRunSoFar(limits(globalBurstLimit = 1, perProfileBurstLimit = 1), PROFILE),
        )
        refusals.forEach { (kind, admission) ->
            assertEquals(kind, admission.preview(profileId = PROFILE).rejection?.kind)
            assertFalse("A run refused for $kind kept its cooldown", coolingDownAfterARun(admission, "refused-$kind"))
        }
    }

    /** Admission let it in, but the task was already running and Abort new skipped it. */
    @Test
    fun aRunTheCollisionPolicySkipsGivesTheCooldownBack() = runBlocking {
        assertEquals(CollisionMode.ABORT_NEW, task.collisionMode)
        val finish = CompletableDeferred<Unit>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            TaskCollisionCoordinator.Default.execute(task) { finish.await() }
        }
        try {
            assertFalse(coolingDownAfterARun(ExecutionAdmissionController(limits()), "collision"))
        } finally {
            finish.complete(Unit)
            running.join()
        }
    }

    @Test
    fun anAdmittedRunKeepsTheCooldown() = runBlocking {
        assertTrue(coolingDownAfterARun(ExecutionAdmissionController(limits()), "admitted"))
    }

    /** Dispatch reserves, the run goes through admission, then the claim settles as `runTask` does. */
    private suspend fun coolingDownAfterARun(admission: ExecutionAdmissionController, executionId: String): Boolean {
        val cooldowns = CooldownReservations()
        val claim = CooldownClaim(cooldowns, PROFILE, cooldowns.reserve(PROFILE, cooldownSec = 60))
        executeAndLogTask(
            appContext = context,
            db = db,
            task = task,
            source = SOURCE,
            admissionController = admission,
            profileId = PROFILE,
            execution = ExecutionEnvelope.create(task, SOURCE, profileId = PROFILE, executionId = executionId),
            onAdmitted = claim::admitted,
        )
        claim.releaseUnlessAdmitted()
        return !cooldowns.reserve(PROFILE, cooldownSec = 60).accepted
    }

    private fun limits(
        globalMaxActive: Int = 8,
        perProfileMaxActive: Int = 8,
        globalBurstLimit: Int = 100,
        perProfileBurstLimit: Int = 100,
    ) = ExecutionAdmissionLimits(
        // A burst limit can't sit under its active limit.
        globalMaxActive = globalMaxActive.coerceAtMost(globalBurstLimit),
        perProfileMaxActive = perProfileMaxActive.coerceAtMost(perProfileBurstLimit),
        globalBurstLimit = globalBurstLimit,
        perProfileBurstLimit = perProfileBurstLimit,
        circuitTripCount = 100,
    )

    private fun circuitOpen(): ExecutionAdmissionController {
        val store = InMemoryExecutionCircuitStore()
        store.save(PROFILE, ExecutionCircuitState(openUntilMs = System.currentTimeMillis() + 600_000L, strikeCount = 3))
        return ExecutionAdmissionController(limits(), circuitStore = store)
    }

    /** A run that is still going holds the slot. */
    private fun occupied(limits: ExecutionAdmissionLimits, profileId: Long) =
        ExecutionAdmissionController(limits).also { assertTrue(it.tryAcquire(profileId = profileId).accepted) }

    /** A run that already finished still counts toward the burst window. */
    private fun oneRunSoFar(limits: ExecutionAdmissionLimits, profileId: Long) =
        ExecutionAdmissionController(limits).also { requireNotNull(it.tryAcquire(profileId = profileId).lease).release() }

    private companion object {
        const val PROFILE = 7L
        const val OTHER_PROFILE = 8L
        const val SOURCE = "Profile: Cooldown"
    }
}
