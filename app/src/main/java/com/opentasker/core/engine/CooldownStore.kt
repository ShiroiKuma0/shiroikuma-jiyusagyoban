package com.opentasker.core.engine

import android.content.Context
import android.content.SharedPreferences

class CooldownStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(profileId: Long): Long =
        prefs.getLong(key(profileId), 0L)

    fun set(profileId: Long, deadlineMs: Long) {
        prefs.edit().putLong(key(profileId), deadlineMs).apply()
    }

    fun remove(profileId: Long) {
        prefs.edit().remove(key(profileId)).apply()
    }

    fun loadAll(): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(KEY_PREFIX)) continue
            val profileId = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: continue
            val deadline = v as? Long ?: continue
            if (deadline > now) {
                result[profileId] = deadline
            } else {
                toRemove.add(k)
            }
        }
        if (toRemove.isNotEmpty()) {
            val editor = prefs.edit()
            toRemove.forEach { editor.remove(it) }
            editor.apply()
        }
        return result
    }

    fun pruneDeleted(activeProfileIds: Set<Long>) {
        val editor = prefs.edit()
        var changed = false
        for (k in prefs.all.keys) {
            if (!k.startsWith(KEY_PREFIX)) continue
            val profileId = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: continue
            if (profileId !in activeProfileIds) {
                editor.remove(k)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun key(profileId: Long) = "$KEY_PREFIX$profileId"

    companion object {
        private const val PREFS_NAME = "opentasker_cooldowns"
        private const val KEY_PREFIX = "cd_"
    }
}
