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
 * profile chain for a short attribution window. The chain is attached to the next profile run,
 * making event-driven A -> B -> A loops visible even when Android delivered each context through
 * a different callback. The fixed depth cap is deliberately lower than any process-wide rate
 * limit: a loop is stopped before it can become an admission storm.
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
        val depth = current.depth + 1
        val displayedChain = if (cycleIndex >= 0) {
            proposed.drop(cycleIndex)
        } else {
            proposed
        }.map(ProfileHop::name)
        val blockedReason = when {
            cycleIndex >= 0 -> "Causal profile cycle stopped: ${displayedChain.joinToString(" -> ")}"
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
                hops = chain.map { ProfileHop(null, it) },
                observedAtMs = nowMs,
            )
        }
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

    fun nextForProfile(profileId: Long, profileName: String): CausalExecutionDecision =
        tracker.nextForProfile(profileId, profileName)

    fun remember(execution: ExecutionEnvelope) = tracker.remember(execution)

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

    internal fun reset() {
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
