package com.opentasker.core.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** The bounded admission policy applied before a top-level task starts doing work. */
data class ExecutionAdmissionLimits(
    val globalMaxActive: Int = DEFAULT_GLOBAL_MAX_ACTIVE,
    val perProfileMaxActive: Int = DEFAULT_PROFILE_MAX_ACTIVE,
    val globalBurstLimit: Int = DEFAULT_GLOBAL_BURST_LIMIT,
    val perProfileBurstLimit: Int = DEFAULT_PROFILE_BURST_LIMIT,
    val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    val circuitTripCount: Int = DEFAULT_CIRCUIT_TRIP_COUNT,
    val circuitOpenMs: Long = DEFAULT_CIRCUIT_OPEN_MS,
) {
    init {
        require(globalMaxActive > 0)
        require(perProfileMaxActive > 0)
        require(globalBurstLimit >= globalMaxActive)
        require(perProfileBurstLimit >= perProfileMaxActive)
        require(burstWindowMs > 0)
        require(circuitTripCount > 0)
        require(circuitOpenMs > 0)
    }

    companion object {
        const val DEFAULT_GLOBAL_MAX_ACTIVE = 8
        const val DEFAULT_PROFILE_MAX_ACTIVE = 2
        const val DEFAULT_GLOBAL_BURST_LIMIT = 32
        const val DEFAULT_PROFILE_BURST_LIMIT = 8
        const val DEFAULT_BURST_WINDOW_MS = 10_000L
        const val DEFAULT_CIRCUIT_TRIP_COUNT = 3
        const val DEFAULT_CIRCUIT_OPEN_MS = 60_000L
    }
}

data class ExecutionAdmissionSnapshot(
    val activeGlobal: Int,
    val activeByProfile: Map<Long, Int>,
    val globalBurstCount: Int,
    val burstByProfile: Map<Long, Int>,
    val openCircuits: Map<Long?, Long>,
)

data class ExecutionAdmissionDecision(
    val accepted: Boolean,
    val reason: String? = null,
    val circuitOpened: Boolean = false,
    val lease: ExecutionAdmissionLease? = null,
)

/** Persists only circuit state; active jobs and burst timestamps are deliberately process-local. */
interface ExecutionCircuitStore {
    fun load(key: Long?): ExecutionCircuitState
    fun save(key: Long?, state: ExecutionCircuitState)
}

data class ExecutionCircuitState(
    val openUntilMs: Long = 0L,
    val strikeCount: Int = 0,
)

class InMemoryExecutionCircuitStore : ExecutionCircuitStore {
    private val states = mutableMapOf<Long?, ExecutionCircuitState>()

    override fun load(key: Long?): ExecutionCircuitState = synchronized(states) {
        states[key] ?: ExecutionCircuitState()
    }

    override fun save(key: Long?, state: ExecutionCircuitState) = synchronized(states) {
        states[key] = state
    }
}

/** SharedPreferences-backed circuit state for the foreground service process. */
class SharedPreferencesExecutionCircuitStore(context: Context) : ExecutionCircuitStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(key: Long?): ExecutionCircuitState {
        val prefix = keyPrefix(key)
        return ExecutionCircuitState(
            openUntilMs = preferences.getLong(prefix + KEY_OPEN_UNTIL, 0L),
            strikeCount = preferences.getInt(prefix + KEY_STRIKES, 0),
        )
    }

    override fun save(key: Long?, state: ExecutionCircuitState) {
        val prefix = keyPrefix(key)
        preferences.edit()
            .putLong(prefix + KEY_OPEN_UNTIL, state.openUntilMs.coerceAtLeast(0L))
            .putInt(prefix + KEY_STRIKES, state.strikeCount.coerceAtLeast(0))
            .apply()
    }

    private fun keyPrefix(key: Long?): String = if (key == null) GLOBAL_PREFIX else "profile_${key}_"

    companion object {
        private const val PREFS_NAME = "opentasker_execution_admission"
        private const val GLOBAL_PREFIX = "global_"
        private const val KEY_OPEN_UNTIL = "open_until_ms"
        private const val KEY_STRIKES = "strikes"
    }
}

/**
 * Thread-safe global/per-profile admission with a persisted burst circuit breaker.
 *
 * The lease must be released in a finally block. A rejected request is side-effect free and can be
 * recorded as a skipped run by the caller. The controller intentionally does not queue work: the
 * existing profile mode queues remain the only place where user-requested order is retained.
 */
class ExecutionAdmissionController(
    private val limits: ExecutionAdmissionLimits = ExecutionAdmissionLimits(),
    private val now: () -> Long = System::currentTimeMillis,
    private val circuitStore: ExecutionCircuitStore = InMemoryExecutionCircuitStore(),
) {
    private val lock = Any()
    private var activeGlobal = 0
    private val activeByProfile = mutableMapOf<Long, Int>()
    private val globalStarts = ArrayDeque<Long>()
    private val startsByProfile = mutableMapOf<Long, ArrayDeque<Long>>()
    private val circuitStates = mutableMapOf<Long?, ExecutionCircuitState>()

    fun tryAcquire(profileId: Long? = null): ExecutionAdmissionDecision = synchronized(lock) {
        val current = now()
        prune(current)

        val circuit = circuitState(profileId)
        if (circuit.openUntilMs > current) {
            return@synchronized rejected(
                "Execution circuit is open for ${remainingSeconds(circuit.openUntilMs - current)} more seconds.",
            )
        }
        if (circuit.openUntilMs != 0L && circuit.openUntilMs <= current) {
            val reset = ExecutionCircuitState()
            circuitStates[profileId] = reset
            circuitStore.save(profileId, reset)
        }

        if (activeGlobal >= limits.globalMaxActive) {
            return@synchronized rejected("Global execution limit reached (${limits.globalMaxActive} active).")
        }
        if (profileId != null && (activeByProfile[profileId] ?: 0) >= limits.perProfileMaxActive) {
            return@synchronized rejected(
                "Profile execution limit reached (${limits.perProfileMaxActive} active).",
            )
        }

        val profileStarts = profileId?.let { startsByProfile.getOrPut(it) { ArrayDeque() } }
        val globalBurst = globalStarts.size >= limits.globalBurstLimit
        val profileBurst = profileStarts != null && profileStarts.size >= limits.perProfileBurstLimit
        if (globalBurst || profileBurst) {
            val state = circuitState(profileId)
            val strikes = state.strikeCount + 1
            val opened = strikes >= limits.circuitTripCount
            val next = state.copy(
                openUntilMs = if (opened) current + limits.circuitOpenMs else state.openUntilMs,
                strikeCount = strikes,
            )
            circuitStates[profileId] = next
            circuitStore.save(profileId, next)
            val reason = if (opened) {
                "Burst limit exceeded; circuit breaker opened for ${limits.circuitOpenMs / 1_000}s."
            } else {
                "Burst limit exceeded (${burstLimitLabel(globalBurst, profileBurst)})."
            }
            return@synchronized rejected(reason, circuitOpened = opened)
        }

        activeGlobal += 1
        if (profileId != null) activeByProfile[profileId] = (activeByProfile[profileId] ?: 0) + 1
        globalStarts.addLast(current)
        profileStarts?.addLast(current)
        if (circuit.strikeCount != 0) {
            val reset = ExecutionCircuitState()
            circuitStates[profileId] = reset
            circuitStore.save(profileId, reset)
        }
        ExecutionAdmissionDecision(
            accepted = true,
            lease = ExecutionAdmissionLease(this, profileId),
        )
    }

    fun snapshot(): ExecutionAdmissionSnapshot = synchronized(lock) {
        prune(now())
        ExecutionAdmissionSnapshot(
            activeGlobal = activeGlobal,
            activeByProfile = activeByProfile.toMap(),
            globalBurstCount = globalStarts.size,
            burstByProfile = startsByProfile.mapValues { it.value.size },
            openCircuits = circuitStates
                .mapValues { it.value.openUntilMs }
                .filterValues { it > now() },
        )
    }

    /**
     * Checks the current admission budget without reserving a lease, recording a burst, opening a
     * circuit, or persisting anything. This is used by editor diagnostics that must be honest
     * about the next run while remaining side-effect free.
     */
    fun preview(profileId: Long? = null): ExecutionAdmissionDecision = synchronized(lock) {
        val current = now()
        val snapshot = snapshotLocked(current)
        val circuit = circuitStates[profileId] ?: circuitStore.load(profileId)
        if (circuit.openUntilMs > current) {
            return@synchronized rejected(
                "Execution circuit is open for ${remainingSeconds(circuit.openUntilMs - current)} more seconds.",
            )
        }
        if (snapshot.activeGlobal >= limits.globalMaxActive) {
            return@synchronized rejected("Global execution limit reached (${limits.globalMaxActive} active).")
        }
        if (profileId != null &&
            (snapshot.activeByProfile[profileId] ?: 0) >= limits.perProfileMaxActive
        ) {
            return@synchronized rejected(
                "Profile execution limit reached (${limits.perProfileMaxActive} active).",
            )
        }
        val globalBurst = snapshot.globalBurstCount >= limits.globalBurstLimit
        val profileBurst = profileId != null &&
            (snapshot.burstByProfile[profileId] ?: 0) >= limits.perProfileBurstLimit
        if (globalBurst || profileBurst) {
            return@synchronized rejected(
                "Burst limit exceeded (${burstLimitLabel(globalBurst, profileBurst)}).",
            )
        }
        ExecutionAdmissionDecision(
            accepted = true,
            reason = "Admission budget is available (preview only).",
        )
    }

    internal fun reset() = synchronized(lock) {
        activeGlobal = 0
        activeByProfile.clear()
        globalStarts.clear()
        startsByProfile.clear()
        circuitStates.clear()
    }

    internal fun release(profileId: Long?) = synchronized(lock) {
        activeGlobal = (activeGlobal - 1).coerceAtLeast(0)
        if (profileId != null) {
            val remaining = (activeByProfile[profileId] ?: 1) - 1
            if (remaining <= 0) activeByProfile.remove(profileId) else activeByProfile[profileId] = remaining
        }
    }

    private fun snapshotLocked(current: Long): ExecutionAdmissionSnapshot {
        val cutoff = current - limits.burstWindowMs
        return ExecutionAdmissionSnapshot(
            activeGlobal = activeGlobal,
            activeByProfile = activeByProfile.toMap(),
            globalBurstCount = globalStarts.count { it > cutoff },
            burstByProfile = startsByProfile.mapValues { (_, starts) -> starts.count { it > cutoff } },
            openCircuits = circuitStates
                .mapValues { it.value.openUntilMs }
                .filterValues { it > current },
        )
    }

    private fun circuitState(key: Long?): ExecutionCircuitState =
        circuitStates.getOrPut(key) { circuitStore.load(key) }

    private fun prune(current: Long) {
        val cutoff = current - limits.burstWindowMs
        while (globalStarts.firstOrNull()?.let { it <= cutoff } == true) globalStarts.removeFirst()
        startsByProfile.values.forEach { starts ->
            while (starts.firstOrNull()?.let { it <= cutoff } == true) starts.removeFirst()
        }
        startsByProfile.entries.removeIf { it.value.isEmpty() }
    }

    private fun rejected(reason: String, circuitOpened: Boolean = false) = ExecutionAdmissionDecision(
        accepted = false,
        reason = reason,
        circuitOpened = circuitOpened,
    )

    private fun remainingSeconds(ms: Long): Long = (ms / 1_000L).coerceAtLeast(1L)

    private fun burstLimitLabel(global: Boolean, profile: Boolean): String = when {
        global && profile -> "global and per-profile windows"
        global -> "the global window"
        else -> "the per-profile window"
    }

    companion object {
        /** Process-local fallback used by manual/UI calls that do not have a service Context. */
        val Default = ExecutionAdmissionController()

        fun persisted(context: Context): ExecutionAdmissionController = ExecutionAdmissionController(
            circuitStore = SharedPreferencesExecutionCircuitStore(context),
        )
    }
}

class ExecutionAdmissionLease internal constructor(
    private val controller: ExecutionAdmissionController,
    private val profileId: Long?,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) controller.release(profileId)
    }

    override fun close() = release()
}

/** Shares the live service controller with editor diagnostics when the engine is running. */
object ExecutionAdmissionRegistry {
    @Volatile
    private var activeController: ExecutionAdmissionController? = null

    fun attach(controller: ExecutionAdmissionController) {
        activeController = controller
    }

    fun detach(controller: ExecutionAdmissionController) {
        if (activeController === controller) activeController = null
    }

    fun preview(context: Context, profileId: Long): ExecutionAdmissionDecision =
        activeController?.preview(profileId)
            ?: ExecutionAdmissionController.persisted(context.applicationContext).preview(profileId)
}
