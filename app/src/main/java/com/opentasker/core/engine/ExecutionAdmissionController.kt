package com.opentasker.core.engine

import android.content.Context
import android.content.SharedPreferences
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileConcurrencyPolicy
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

/** Optional profile-level overrides; null fields inherit [ExecutionAdmissionLimits]. */
data class ExecutionAdmissionProfileLimits(
    val maxActive: Int? = null,
    val burstLimit: Int? = null,
) {
    init {
        maxActive?.let { require(it in ProfileConcurrencyPolicy.MIN_MAX_ACTIVE..ProfileConcurrencyPolicy.MAX_MAX_ACTIVE) }
        burstLimit?.let { require(it in ProfileConcurrencyPolicy.MIN_BURST_LIMIT..ProfileConcurrencyPolicy.MAX_BURST_LIMIT) }
    }
}

fun Profile.toExecutionAdmissionProfileLimits(): ExecutionAdmissionProfileLimits? {
    val maxActive = maxActiveExecutions?.takeIf {
        it in ProfileConcurrencyPolicy.MIN_MAX_ACTIVE..ProfileConcurrencyPolicy.MAX_MAX_ACTIVE
    }
    val burst = burstLimit?.takeIf {
        it in ProfileConcurrencyPolicy.MIN_BURST_LIMIT..ProfileConcurrencyPolicy.MAX_BURST_LIMIT
    }
    return if (maxActive == null && burst == null) null else ExecutionAdmissionProfileLimits(maxActive, burst)
}

data class ExecutionAdmissionCounts(
    val activeGlobal: Int,
    val activeProfile: Int,
    val globalBurst: Int,
    val profileBurst: Int,
)

enum class ExecutionAdmissionRejectionKind {
    CIRCUIT_OPEN,
    GLOBAL_ACTIVE,
    PROFILE_ACTIVE,
    GLOBAL_BURST,
    PROFILE_BURST,
    GLOBAL_AND_PROFILE_BURST,
}

/** Structured evidence retained alongside the human-readable admission reason. */
data class ExecutionAdmissionRejection(
    val kind: ExecutionAdmissionRejectionKind,
    val counts: ExecutionAdmissionCounts,
    val globalActiveLimit: Int? = null,
    val profileActiveLimit: Int? = null,
    val globalBurstLimit: Int? = null,
    val profileBurstLimit: Int? = null,
    val circuitOpenUntilMs: Long? = null,
    val circuitStrikeCount: Int = 0,
    val circuitReason: String? = null,
) {
    fun render(nowMs: Long): String {
        val countsDetail = "Counts: active global=${counts.activeGlobal}/${globalActiveLimit ?: "-"}, " +
            "profile=${counts.activeProfile}/${profileActiveLimit ?: "-"}; " +
            "burst global=${counts.globalBurst}/${globalBurstLimit ?: "-"}, " +
            "profile=${counts.profileBurst}/${profileBurstLimit ?: "-"}."
        val message = when (kind) {
            ExecutionAdmissionRejectionKind.CIRCUIT_OPEN -> buildString {
                val remaining = (circuitOpenUntilMs ?: nowMs).minus(nowMs).coerceAtLeast(0L)
                append("Execution circuit is open for ${remainingSeconds(remaining)} more seconds.")
                circuitReason?.takeIf(String::isNotBlank)?.let { append(" Trip reason: $it") }
            }
            ExecutionAdmissionRejectionKind.GLOBAL_ACTIVE ->
                "Global execution limit reached (${globalActiveLimit ?: "configured"} active)."
            ExecutionAdmissionRejectionKind.PROFILE_ACTIVE ->
                "Profile execution limit reached (${profileActiveLimit ?: "configured"} active)."
            ExecutionAdmissionRejectionKind.GLOBAL_BURST ->
                "Burst limit exceeded (the global window)."
            ExecutionAdmissionRejectionKind.PROFILE_BURST ->
                "Burst limit exceeded (the per-profile window)."
            ExecutionAdmissionRejectionKind.GLOBAL_AND_PROFILE_BURST ->
                "Burst limit exceeded (global and per-profile windows)."
        }
        return "$message $countsDetail"
    }

    private fun remainingSeconds(ms: Long): Long = (ms / 1_000L).coerceAtLeast(1L)
}

data class ExecutionAdmissionSnapshot(
    val activeGlobal: Int,
    val activeByProfile: Map<Long, Int>,
    val globalBurstCount: Int,
    val burstByProfile: Map<Long, Int>,
    val openCircuits: Map<Long?, Long>,
    val limits: ExecutionAdmissionLimits = ExecutionAdmissionLimits(),
    val circuits: Map<Long?, ExecutionCircuitState> = emptyMap(),
)

data class ExecutionAdmissionDecision(
    val accepted: Boolean,
    val reason: String? = null,
    val circuitOpened: Boolean = false,
    val lease: ExecutionAdmissionLease? = null,
    val rejection: ExecutionAdmissionRejection? = null,
)

/** Persists only circuit state; active jobs and burst timestamps are deliberately process-local. */
interface ExecutionCircuitStore {
    fun load(key: Long?): ExecutionCircuitState
    fun save(key: Long?, state: ExecutionCircuitState)

    /** Enumerates persisted circuit state for diagnostics; legacy stores may return no entries. */
    fun loadAll(): Map<Long?, ExecutionCircuitState> = emptyMap()
}

data class ExecutionCircuitState(
    val openUntilMs: Long = 0L,
    val strikeCount: Int = 0,
    val lastReason: String? = null,
)

class InMemoryExecutionCircuitStore : ExecutionCircuitStore {
    private val states = mutableMapOf<Long?, ExecutionCircuitState>()

    override fun load(key: Long?): ExecutionCircuitState = synchronized(states) {
        states[key] ?: ExecutionCircuitState()
    }

    override fun save(key: Long?, state: ExecutionCircuitState) = synchronized(states) {
        states[key] = state
    }

    override fun loadAll(): Map<Long?, ExecutionCircuitState> = synchronized(states) { states.toMap() }
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
            lastReason = preferences.getString(prefix + KEY_LAST_REASON, null),
        )
    }

    override fun save(key: Long?, state: ExecutionCircuitState) {
        val prefix = keyPrefix(key)
        val editor = preferences.edit()
            .putLong(prefix + KEY_OPEN_UNTIL, state.openUntilMs.coerceAtLeast(0L))
            .putInt(prefix + KEY_STRIKES, state.strikeCount.coerceAtLeast(0))
        if (state.lastReason.isNullOrBlank()) {
            editor.remove(prefix + KEY_LAST_REASON)
        } else {
            editor.putString(prefix + KEY_LAST_REASON, state.lastReason)
        }
        editor.apply()
    }

    override fun loadAll(): Map<Long?, ExecutionCircuitState> {
        val keys = mutableSetOf<Long?>()
        val allKeys = preferences.all.keys
        if (GLOBAL_PREFIX + KEY_OPEN_UNTIL in allKeys) keys += null
        allKeys.mapNotNull { key ->
            PROFILE_OPEN_UNTIL_PATTERN.matchEntire(key)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }.forEach(keys::add)
        return keys.associateWith(::load)
    }

    private fun keyPrefix(key: Long?): String = if (key == null) GLOBAL_PREFIX else "profile_${key}_"

    companion object {
        private const val PREFS_NAME = "opentasker_execution_admission"
        private const val GLOBAL_PREFIX = "global_"
        private const val KEY_OPEN_UNTIL = "open_until_ms"
        private const val KEY_STRIKES = "strikes"
        private const val KEY_LAST_REASON = "last_reason"
        private val PROFILE_OPEN_UNTIL_PATTERN = Regex("^profile_(-?\\d+)_open_until_ms$")
    }
}

/**
 * Thread-safe global/per-profile admission with a persisted burst circuit breaker.
 *
 * The lease must be released in a finally block. A rejected request is side-effect free and can be
 * recorded as a skipped or held run by the caller. The controller intentionally does not queue
 * work: the existing profile mode queues remain the only place where user-requested order is
 * retained.
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

    fun tryAcquire(
        profileId: Long? = null,
        profileLimits: ExecutionAdmissionProfileLimits? = null,
    ): ExecutionAdmissionDecision = synchronized(lock) {
        val current = now()
        prune(current)
        val snapshot = snapshotLocked(current)
        val (profileMaxActive, profileBurstLimit) = effectiveProfileLimits(profileLimits)
        var circuit = circuitState(profileId)
        if (circuit.openUntilMs > current) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.CIRCUIT_OPEN,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                    circuitOpenUntilMs = circuit.openUntilMs,
                    circuitStrikeCount = circuit.strikeCount,
                    circuitReason = circuit.lastReason,
                ),
                current,
            )
        }
        if (circuit.openUntilMs != 0L && circuit.openUntilMs <= current) {
            circuit = ExecutionCircuitState()
            circuitStates[profileId] = circuit
            circuitStore.save(profileId, circuit)
        }

        val activeProfile = snapshot.activeByProfile[profileId] ?: 0
        if (snapshot.activeGlobal >= limits.globalMaxActive) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.GLOBAL_ACTIVE,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                ),
                current,
            )
        }
        if (profileId != null && activeProfile >= profileMaxActive) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.PROFILE_ACTIVE,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive,
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit,
                ),
                current,
            )
        }

        val profileStarts = profileId?.let { startsByProfile.getOrPut(it) { ArrayDeque() } }
        val globalBurst = snapshot.globalBurstCount >= limits.globalBurstLimit
        val profileBurst = profileStarts != null &&
            (snapshot.burstByProfile[profileId] ?: 0) >= profileBurstLimit
        if (globalBurst || profileBurst) {
            val kind = when {
                globalBurst && profileBurst -> ExecutionAdmissionRejectionKind.GLOBAL_AND_PROFILE_BURST
                globalBurst -> ExecutionAdmissionRejectionKind.GLOBAL_BURST
                else -> ExecutionAdmissionRejectionKind.PROFILE_BURST
            }
            val strikes = circuit.strikeCount + 1
            val opened = strikes >= limits.circuitTripCount
            val tripReason = "Burst limit exceeded (${burstLimitLabel(globalBurst, profileBurst)})."
            val next = circuit.copy(
                openUntilMs = if (opened) current + limits.circuitOpenMs else circuit.openUntilMs,
                strikeCount = strikes,
                lastReason = tripReason,
            )
            circuitStates[profileId] = next
            circuitStore.save(profileId, next)
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = kind,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                    circuitOpenUntilMs = next.openUntilMs.takeIf { opened },
                    circuitStrikeCount = strikes,
                    circuitReason = tripReason,
                ),
                current,
                circuitOpened = opened,
            )
        }

        activeGlobal += 1
        if (profileId != null) activeByProfile[profileId] = activeProfile + 1
        globalStarts.addLast(current)
        profileStarts?.addLast(current)
        if (circuit.strikeCount != 0 || circuit.lastReason != null) {
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
        val current = now()
        prune(current)
        circuitStore.loadAll().forEach { (key, state) -> circuitStates.putIfAbsent(key, state) }
        snapshotLocked(current)
    }

    /**
     * Checks the current admission budget without reserving a lease, recording a burst, opening a
     * circuit, or persisting anything. This is used by editor diagnostics that must be honest
     * about the next run while remaining side-effect free.
     */
    fun preview(
        profileId: Long? = null,
        profileLimits: ExecutionAdmissionProfileLimits? = null,
    ): ExecutionAdmissionDecision = synchronized(lock) {
        val current = now()
        prune(current)
        val snapshot = snapshotLocked(current)
        val (profileMaxActive, profileBurstLimit) = effectiveProfileLimits(profileLimits)
        val circuit = circuitStates[profileId] ?: circuitStore.load(profileId)
        if (circuit.openUntilMs > current) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.CIRCUIT_OPEN,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                    circuitOpenUntilMs = circuit.openUntilMs,
                    circuitStrikeCount = circuit.strikeCount,
                    circuitReason = circuit.lastReason,
                ),
                current,
            )
        }
        if (snapshot.activeGlobal >= limits.globalMaxActive) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.GLOBAL_ACTIVE,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                ),
                current,
            )
        }
        if (profileId != null && (snapshot.activeByProfile[profileId] ?: 0) >= profileMaxActive) {
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = ExecutionAdmissionRejectionKind.PROFILE_ACTIVE,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive,
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit,
                ),
                current,
            )
        }
        val globalBurst = snapshot.globalBurstCount >= limits.globalBurstLimit
        val profileBurst = profileId != null &&
            (snapshot.burstByProfile[profileId] ?: 0) >= profileBurstLimit
        if (globalBurst || profileBurst) {
            val kind = when {
                globalBurst && profileBurst -> ExecutionAdmissionRejectionKind.GLOBAL_AND_PROFILE_BURST
                globalBurst -> ExecutionAdmissionRejectionKind.GLOBAL_BURST
                else -> ExecutionAdmissionRejectionKind.PROFILE_BURST
            }
            return@synchronized rejected(
                ExecutionAdmissionRejection(
                    kind = kind,
                    counts = snapshot.counts(profileId),
                    globalActiveLimit = limits.globalMaxActive,
                    profileActiveLimit = profileMaxActive.takeIf { profileId != null },
                    globalBurstLimit = limits.globalBurstLimit,
                    profileBurstLimit = profileBurstLimit.takeIf { profileId != null },
                ),
                current,
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
        val circuits = circuitStates.toMap()
        return ExecutionAdmissionSnapshot(
            activeGlobal = activeGlobal,
            activeByProfile = activeByProfile.toMap(),
            globalBurstCount = globalStarts.count { it > cutoff },
            burstByProfile = startsByProfile.mapValues { (_, starts) -> starts.count { it > cutoff } },
            openCircuits = circuits
                .mapValues { it.value.openUntilMs }
                .filterValues { it > current },
            limits = limits,
            circuits = circuits,
        )
    }

    private fun circuitState(key: Long?): ExecutionCircuitState =
        circuitStates.getOrPut(key) { circuitStore.load(key) }

    private fun effectiveProfileLimits(profileLimits: ExecutionAdmissionProfileLimits?): Pair<Int, Int> =
        (profileLimits?.maxActive ?: limits.perProfileMaxActive) to
            (profileLimits?.burstLimit ?: limits.perProfileBurstLimit)

    private fun prune(current: Long) {
        val cutoff = current - limits.burstWindowMs
        while (globalStarts.firstOrNull()?.let { it <= cutoff } == true) globalStarts.removeFirst()
        startsByProfile.values.forEach { starts ->
            while (starts.firstOrNull()?.let { it <= cutoff } == true) starts.removeFirst()
        }
        startsByProfile.entries.removeIf { it.value.isEmpty() }
    }

    private fun rejected(
        rejection: ExecutionAdmissionRejection,
        current: Long,
        circuitOpened: Boolean = false,
    ) = ExecutionAdmissionDecision(
        accepted = false,
        reason = rejection.render(current),
        circuitOpened = circuitOpened,
        rejection = rejection,
    )

    private fun burstLimitLabel(global: Boolean, profile: Boolean): String = when {
        global && profile -> "global and per-profile windows"
        global -> "the global window"
        else -> "the per-profile window"
    }

    private fun ExecutionAdmissionSnapshot.counts(profileId: Long?): ExecutionAdmissionCounts =
        ExecutionAdmissionCounts(
            activeGlobal = activeGlobal,
            activeProfile = activeByProfile[profileId] ?: 0,
            globalBurst = globalBurstCount,
            profileBurst = burstByProfile[profileId] ?: 0,
        )

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

    fun preview(
        context: Context,
        profileId: Long,
        profileLimits: ExecutionAdmissionProfileLimits? = null,
    ): ExecutionAdmissionDecision = activeController?.preview(profileId, profileLimits)
        ?: ExecutionAdmissionController.persisted(context.applicationContext).preview(profileId, profileLimits)

    fun snapshot(context: Context): ExecutionAdmissionSnapshot = activeController?.snapshot()
        ?: ExecutionAdmissionController.persisted(context.applicationContext).snapshot()
}
