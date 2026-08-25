package com.opentasker.core.engine

import android.content.Context
import android.content.SharedPreferences

class CooldownStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(profileId: Long): Long =
        prefs.getLong(key(profileId), 0L)

    /** Returns the persisted cooldown time left without pruning or writing preferences. */
    fun remaining(profileId: Long, nowMs: Long = System.currentTimeMillis()): Long =
        (get(profileId) - nowMs).coerceAtLeast(0L)

    fun set(profileId: Long, deadlineMs: Long) {
        prefs.edit().putLong(key(profileId), deadlineMs).apply()
    }

    fun remove(profileId: Long) {
        prefs.edit().remove(key(profileId)).apply()
    }

    fun loadAll(): Map<Long, Long> {
        val stored = prefs.all
        val now = System.currentTimeMillis()
        val expired = expiredCooldownKeys(stored, now)
        if (expired.isNotEmpty()) {
            val editor = prefs.edit()
            expired.forEach { editor.remove(it) }
            editor.apply()
        }
        return liveCooldowns(stored, now)
    }

    fun pruneDeleted(activeProfileIds: Set<Long>) {
        val stale = staleCooldownKeys(prefs.all.keys, activeProfileIds)
        if (stale.isEmpty()) return
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun key(profileId: Long) = "$KEY_PREFIX$profileId"

    companion object {
        private const val PREFS_NAME = "opentasker_cooldowns"
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
