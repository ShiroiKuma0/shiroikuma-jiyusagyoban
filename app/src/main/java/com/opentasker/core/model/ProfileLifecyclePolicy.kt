package com.opentasker.core.model

/** Pure lifecycle rules shared by the matcher, Inspector, editor validation, and simulations. */
object ProfileLifecyclePolicy {
    fun suppressionReason(profile: Profile, nowMs: Long): String? = when {
        profile.lifetime == ProfileLifetime.ONCE && profile.lifetimeConsumed ->
            "This one-shot profile has already run."
        profile.lifetime == ProfileLifetime.UNTIL_DATE && profile.expiresAtMs == null ->
            "This profile has no valid expiry date."
        profile.lifetime == ProfileLifetime.UNTIL_DATE && nowMs >= requireNotNull(profile.expiresAtMs) ->
            "This profile expired at ${profile.expiresAtMs}."
        else -> null
    }

    fun isSuppressed(profile: Profile, nowMs: Long): Boolean = suppressionReason(profile, nowMs) != null

    fun normalize(profile: Profile): Profile = profile.copy(
        priority = profile.priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY),
        gracePeriodSec = profile.gracePeriodSec.coerceIn(0, MAX_GRACE_PERIOD_SEC),
        expiresAtMs = profile.expiresAtMs.takeIf { profile.lifetime == ProfileLifetime.UNTIL_DATE },
        lifetimeConsumed = profile.lifetimeConsumed && profile.lifetime == ProfileLifetime.ONCE,
    )

    /** Returns the deterministic winner; lower IDs break equal-priority ties. */
    fun winner(profiles: Collection<Profile>): Profile? = profiles
        .asSequence()
        .filter(Profile::enabled)
        .sortedWith(compareByDescending<Profile> { it.priority }.thenBy { it.id })
        .firstOrNull()

    fun suppressionByPriority(profile: Profile, candidates: Collection<Profile>): String? {
        val winner = winner(candidates) ?: return null
        if (winner.id == profile.id) return null
        return if (winner.priority > profile.priority) {
            "Suppressed by higher-priority profile '${winner.name}'."
        } else {
            "Suppressed by equal-priority profile '${winner.name}' using the lower profile ID tie-break."
        }
    }

    const val MIN_PRIORITY = -100
    const val MAX_PRIORITY = 100
    const val MAX_GRACE_PERIOD_SEC = 3_600
}
