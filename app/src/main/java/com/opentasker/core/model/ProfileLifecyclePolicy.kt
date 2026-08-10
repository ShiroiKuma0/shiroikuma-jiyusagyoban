package com.opentasker.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure lifecycle rules shared by the matcher, Inspector, editor validation, and simulations. */
object ProfileLifecyclePolicy {
    fun suppressionReason(profile: Profile, nowMs: Long): String? = when {
        profile.lifetime == ProfileLifetime.ONCE && profile.lifetimeConsumed ->
            "This one-shot profile has already run."
        profile.lifetime == ProfileLifetime.UNTIL_DATE && profile.expiresAtMs == null ->
            "This profile has no valid expiry date."
        profile.lifetime == ProfileLifetime.UNTIL_DATE && nowMs >= requireNotNull(profile.expiresAtMs) ->
            "This profile expired on ${formatExpiry(profile.expiresAtMs)}."
        else -> null
    }

    /**
     * The Inspector renders this reason verbatim, so an expired profile used to report a raw epoch
     * value ("expired at 1770693599999"). Matches the date the profile editor shows.
     */
    private fun formatExpiry(expiresAtMs: Long): String = runCatching {
        Instant.ofEpochMilli(expiresAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrDefault(expiresAtMs.toString())

    fun isSuppressed(profile: Profile, nowMs: Long): Boolean = suppressionReason(profile, nowMs) != null

    fun normalize(profile: Profile): Profile = profile.copy(
        priority = profile.priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY),
        gracePeriodSec = profile.gracePeriodSec.coerceIn(0, MAX_GRACE_PERIOD_SEC),
        expiresAtMs = profile.expiresAtMs.takeIf { profile.lifetime == ProfileLifetime.UNTIL_DATE },
        lifetimeConsumed = profile.lifetimeConsumed && profile.lifetime == ProfileLifetime.ONCE,
        maxActiveExecutions = ProfileConcurrencyPolicy.normalizeMaxActive(profile.maxActiveExecutions),
        burstLimit = ProfileConcurrencyPolicy.normalizeBurstLimit(profile.burstLimit),
        fallbackTaskId = profile.fallbackTaskId?.takeIf { it > 0L },
    )

    /**
     * Returns the enabled profile that outranks [profile], or null when nothing does.
     *
     * Priority expresses an explicit user preference, so only a *strictly* higher priority
     * suppresses. Profiles left at the default priority are independent and run concurrently:
     * arbitrating equal priorities by profile ID would make every automation mutually exclusive
     * with every other one by default.
     */
    fun suppressor(profile: Profile, candidates: Collection<Profile>): Profile? = candidates
        .asSequence()
        .filter(Profile::enabled)
        .filter { it.id != profile.id }
        .filter { it.priority > profile.priority }
        .sortedWith(compareByDescending<Profile> { it.priority }.thenBy { it.id })
        .firstOrNull()

    fun suppressionByPriority(profile: Profile, candidates: Collection<Profile>): String? =
        suppressor(profile, candidates)?.let { "Suppressed by higher-priority profile '${it.name}'." }

    const val MIN_PRIORITY = -100
    const val MAX_PRIORITY = 100
    const val MAX_GRACE_PERIOD_SEC = 3_600
}
