package com.opentasker.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure lifecycle rules shared by the matcher, Inspector, editor validation, and simulations. */
object ProfileLifecyclePolicy {
    fun suppressionReason(
        profile: Profile,
        nowMs: Long,
        strings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    ): String? = when {
        profile.lifetime == ProfileLifetime.ONCE && profile.lifetimeConsumed ->
            strings.oneShotConsumed()
        profile.lifetime == ProfileLifetime.UNTIL_DATE && profile.expiresAtMs == null ->
            strings.missingExpiry()
        profile.lifetime == ProfileLifetime.UNTIL_DATE && nowMs >= requireNotNull(profile.expiresAtMs) ->
            strings.expired(formatExpiry(profile.expiresAtMs))
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

    fun isSuppressed(
        profile: Profile,
        nowMs: Long,
        strings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    ): Boolean = suppressionReason(profile, nowMs, strings) != null

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

    fun suppressionByPriority(
        profile: Profile,
        candidates: Collection<Profile>,
        strings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    ): String? = suppressor(profile, candidates)?.let { strings.suppressedByPriority(it.name) }

    /**
     * The profiles that become eligible when the matched set shrinks from [before] to [remaining]:
     * those something in [before] was outranking that nothing in [remaining] still outranks.
     *
     * Kept pure and here rather than inline in the service so it can be tested at all — the engine
     * that calls it is an Android Service. Only profiles still in [remaining] can be released: one
     * that stopped matching is not eligible for anything, whoever stopped outranking it.
     */
    fun released(before: Collection<Profile>, remaining: Collection<Profile>): List<Profile> =
        remaining.filter { candidate ->
            suppressor(candidate, before) != null && suppressor(candidate, remaining) == null
        }

    const val MIN_PRIORITY = -100
    const val MAX_PRIORITY = 100
    const val MAX_GRACE_PERIOD_SEC = 3_600
}
