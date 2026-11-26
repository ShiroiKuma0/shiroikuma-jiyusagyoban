package com.sysadmindoc.ruledeck.core.engine

import android.content.Context
import android.content.SharedPreferences

class CooldownStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(profileId: Long): Long =
        prefs.getLong(key(profileId), 0L)

    /** Returns the persisted cooldown time left without pruning or writing preferences. */
    fun remaining(profileId: Long, cooldownSec: Int, nowMs: Long = System.currentTimeMillis()): Long =
        cooldownRemainingMs(get(profileId), cooldownSec, nowMs)

    fun set(profileId: Long, deadlineMs: Long) = synchronized(LOCK) {
        prefs.edit().putLong(key(profileId), deadlineMs).apply()
    }

    /**
     * Puts [previousMs] back for a reservation whose run never started, but only while the store
     * still holds [reservedMs]. A run that a service stop cancels gives its cooldown back when its
     * job ends, which can be after the next service reserved a newer deadline; that one stays.
     */
    fun restore(profileId: Long, reservedMs: Long, previousMs: Long) = synchronized(LOCK) {
        if (get(profileId) != reservedMs) return@synchronized
        val editor = prefs.edit()
        if (previousMs > 0L) editor.putLong(key(profileId), previousMs) else editor.remove(key(profileId))
        editor.apply()
    }

    fun remove(profileId: Long) = synchronized(LOCK) {
        prefs.edit().remove(key(profileId)).apply()
    }

    // Reading and removing happen under the lock set and restore use, so a deadline written in
    // between isn't removed for being expired, and a late restore can't bring back a deleted one.
    fun loadAll(): Map<Long, Long> = synchronized(LOCK) {
        val stored = prefs.all
        val now = System.currentTimeMillis()
        val expired = expiredCooldownKeys(stored, now)
        if (expired.isNotEmpty()) {
            val editor = prefs.edit()
            expired.forEach { editor.remove(it) }
            editor.apply()
        }
        liveCooldowns(stored, now)
    }

    fun pruneDeleted(activeProfileIds: Set<Long>) = synchronized(LOCK) {
        val stale = staleCooldownKeys(prefs.all.keys, activeProfileIds)
        if (stale.isEmpty()) return@synchronized
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun key(profileId: Long) = "$KEY_PREFIX$profileId"

    companion object {
        // Process-wide: an old service's late restore and the next service's reserve share the prefs.
        private val LOCK = Any()
        private const val PREFS_NAME = "ruledeck_cooldowns"
        internal const val KEY_PREFIX = "cd_"
    }
}

/**
 * Cooldown bookkeeping, separated from SharedPreferences so the semantics that survive process
 * death are testable. A lost or wrongly-kept deadline either re-arms a profile that should still
 * be cooling down or silences one that should not be, and neither is visible until it happens.
 */
internal fun profileIdForCooldownKey(key: String): Long? =
    if (key.startsWith(CooldownStore.KEY_PREFIX)) {
        key.removePrefix(CooldownStore.KEY_PREFIX).toLongOrNull()
    } else {
        null
    }

/**
 * Time left before [deadlineMs], counted the way [CooldownReservations.reserve] holds a profile:
 * never more than one current [cooldownSec] window, and nothing once there's no cooldown.
 */
internal fun cooldownRemainingMs(deadlineMs: Long, cooldownSec: Int, nowMs: Long): Long =
    (deadlineMs - nowMs).coerceIn(0L, cooldownSec.coerceAtLeast(0) * 1000L)

/** Deadlines that have passed, so they can be dropped from storage. */
internal fun expiredCooldownKeys(stored: Map<String, Any?>, nowMs: Long): List<String> =
    stored.entries.mapNotNull { (key, value) ->
        if (profileIdForCooldownKey(key) == null) return@mapNotNull null
        val deadline = value as? Long ?: return@mapNotNull null
        key.takeIf { deadline <= nowMs }
    }

/** Deadlines still in the future, keyed by profile. */
internal fun liveCooldowns(stored: Map<String, Any?>, nowMs: Long): Map<Long, Long> =
    stored.entries.mapNotNull { (key, value) ->
        val profileId = profileIdForCooldownKey(key) ?: return@mapNotNull null
        val deadline = value as? Long ?: return@mapNotNull null
        (profileId to deadline).takeIf { deadline > nowMs }
    }.toMap()

/**
 * Keys for profiles that no longer exist. [existingProfileIds] must be every profile in the table,
 * not the enabled ones: pruning against enabled profiles deleted the deadline of a profile the user
 * had merely switched off.
 */
internal fun staleCooldownKeys(keys: Set<String>, existingProfileIds: Set<Long>): List<String> =
    keys.filter { key ->
        val profileId = profileIdForCooldownKey(key)
        profileId != null && profileId !in existingProfileIds
    }
