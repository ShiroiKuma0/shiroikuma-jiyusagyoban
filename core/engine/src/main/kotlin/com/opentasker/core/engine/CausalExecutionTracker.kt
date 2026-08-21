package com.opentasker.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Context selected for a profile-triggered child execution. */
data class CausalExecutionDecision(
    val parentExecutionId: String? = null,
    val depth: Int = 0,
    val profileChain: List<String> = emptyList(),
    val blockedReason: String? = null,
) {
    val allowed: Boolean
        get() = blockedReason == null
}

/** A user-visible explanation of the most recently stopped profile causal chain. */
data class CausalLoopDiagnostic(
    val profileChain: List<String>,
    val depth: Int,
    val reason: String,
    val recordedAtMs: Long,
)

/**
 * Best-effort causal attribution for profile-triggered executions.
 *
 * Context broadcasts do not carry an execution id, so the engine keeps the most recent admitted
 * profile chain and attaches it to the next profile run, making event-driven A -> B -> A loops
 * visible even when Android delivered each context through a different callback. The fixed depth
 * cap is deliberately lower than any process-wide rate limit: a loop is stopped before it can
 * become an admission storm.
 *
 * Attribution lasts only while the parent execution is still running ([forget] clears it on
 * completion). A runaway loop re-triggers from a side effect of work that is still in flight, so
 * that is the only window in which a later run can honestly be called a child. The wall-clock
 * window is a backstop for executions whose completion is never observed (process death), not the
 * primary lifetime: attributing every run within a fixed window made ordinary re-triggers and
 * exit tasks look like cycles.
 */
class CausalExecutionTracker(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val attributionWindowMs: Long = DEFAULT_ATTRIBUTION_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var latest: CausalSnapshot? = null

    init {
        require(maxDepth > 0) { "Causal depth limit must be positive." }
        require(attributionWindowMs >= 0) { "Causal attribution window cannot be negative." }
    }

    fun nextForProfile(
        profileId: Long? = null,
        profileName: String,
        isExit: Boolean = false,
        nowMs: Long = clock(),
    ): CausalExecutionDecision {
        val hop = ProfileHop(profileId, normalizeProfileName(profileName))
        val current = synchronized(lock) {
            latest?.takeIf { nowMs >= it.observedAtMs && nowMs - it.observedAtMs <= attributionWindowMs }
        }
        if (current == null) {
            return CausalExecutionDecision(profileChain = listOf(hop.name))
        }

        val proposed = current.hops + hop
        val cycleIndex = current.hops.indexOfFirst { it.sameProfileAs(hop) }
        // A profile's exit task is the counterpart of its own still-running enter task, not a loop
        // back into it: the context genuinely deactivated. Only the immediately preceding hop is
        // exempt, so a real A -> B -> A cycle that happens to end on an exit is still stopped.
        val parentIsOwnEnterTask = isExit && cycleIndex == current.hops.lastIndex
        val cycleDetected = cycleIndex >= 0 && !parentIsOwnEnterTask
        val depth = current.depth + 1
        val displayedChain = if (cycleDetected) {
            proposed.drop(cycleIndex)
        } else {
            proposed
        }.map(ProfileHop::name)
        val blockedReason = when {
            cycleDetected -> "Causal profile cycle stopped: ${displayedChain.joinToString(" -> ")}"
            depth > maxDepth -> "Causal profile depth limit ($maxDepth) exceeded: ${displayedChain.joinToString(" -> ")}"
            else -> null
        }
        return CausalExecutionDecision(
            parentExecutionId = current.executionId,
            depth = depth,
            profileChain = proposed.map(ProfileHop::name),
            blockedReason = blockedReason,
        )
    }

    /** Records only admitted profile work, so skipped deliveries cannot become causal parents. */
    fun remember(execution: ExecutionEnvelope, nowMs: Long = clock()) {
        if (execution.producer != ExecutionProducer.PROFILE || execution.profileId == null) return
        val chain = execution.causalProfileChain.map(::normalizeProfileName)
        if (chain.isEmpty()) return
        synchronized(lock) {
            latest = CausalSnapshot(
                executionId = execution.executionId,
                depth = execution.causalDepth,
                // The chain travels as display names, so only the executing profile's own id is
                // known here. Carrying it makes self-loop detection exact for the hop that matters
                // instead of relying on a truncated name match.
                hops = chain.mapIndexed { index, name ->
                    ProfileHop(execution.profileId.takeIf { index == chain.lastIndex }, name)
                },
                observedAtMs = nowMs,
            )
        }
    }

    /**
     * Ends attribution for a finished execution. Work that has already completed cannot be the
     * cause of a later trigger, so the next profile run starts a fresh chain.
     */
    fun forget(executionId: String) = synchronized(lock) {
        if (latest?.executionId == executionId) latest = null
    }

    internal fun reset() = synchronized(lock) { latest = null }

    private data class CausalSnapshot(
        val executionId: String,
        val depth: Int,
        val hops: List<ProfileHop>,
        val observedAtMs: Long,
    )

    private data class ProfileHop(val id: Long?, val name: String) {
        fun sameProfileAs(other: ProfileHop): Boolean = when {
            id != null && other.id != null -> id == other.id
            else -> name.equals(other.name, ignoreCase = true)
        }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 8
        const val DEFAULT_ATTRIBUTION_WINDOW_MS = 30_000L
        private const val MAX_PROFILE_NAME_CHARS = 80

        fun normalizeProfileName(name: String): String = name
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(MAX_PROFILE_NAME_CHARS)
            .ifBlank { "<unnamed profile>" }
    }
}

/** Process-local handoff between profile matchers and the service's execution dispatcher. */
object ExecutionCausality {
    private val tracker = CausalExecutionTracker()

    fun nextForProfile(profileId: Long, profileName: String, isExit: Boolean = false): CausalExecutionDecision =
        tracker.nextForProfile(profileId, profileName, isExit)

    fun remember(execution: ExecutionEnvelope) = tracker.remember(execution)

    fun forget(executionId: String) = tracker.forget(executionId)

    fun recordBlocked(decision: CausalExecutionDecision) {
        val reason = decision.blockedReason ?: return
        CausalLoopDiagnostics.record(
            CausalLoopDiagnostic(
                profileChain = decision.profileChain,
                depth = decision.depth,
                reason = reason,
                recordedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    // This source file is compiled into both the app and core:engine modules. Keep the
    // cross-module reset entry point public so Kotlin does not mangle it with a module suffix.
    fun reset() {
        tracker.reset()
        CausalLoopDiagnostics.reset()
    }
}

/** Shared observable state used by Diagnostics and the Context Inspector. */
object CausalLoopDiagnostics {
    private val _latest = MutableStateFlow<CausalLoopDiagnostic?>(null)
    val latest: StateFlow<CausalLoopDiagnostic?> = _latest.asStateFlow()

    fun record(diagnostic: CausalLoopDiagnostic) {
        _latest.value = diagnostic
    }

    internal fun reset() {
        _latest.value = null
    }
}
