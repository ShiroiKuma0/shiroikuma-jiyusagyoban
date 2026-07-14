package com.opentasker.core.plugins.locale

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/** A revocable execution grant: a high-entropy token bound to a single task id. */
data class LocaleGrant(val token: String, val taskId: Long)

/**
 * Issues and validates revocable execution grants for the exported Locale fire receiver.
 *
 * A grant is created when the user configures the plugin ([LocaleSettingEditActivity]) and bound to
 * the chosen task. The fire receiver dispatches only when the incoming bundle carries a token that
 * is still stored and bound to that exact task, so an arbitrary app can no longer fire a chosen task
 * id at the exported receiver. Grants are revoked when their task is deleted.
 */
class LocaleGrantStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Create and persist a new grant for [taskId]; returns the opaque token to hand to the host. */
    fun issue(taskId: Long): String {
        val token = newToken()
        prefs.edit().putLong(KEY_PREFIX + token, taskId).apply()
        return token
    }

    /** True only when [token] is currently stored and bound to [taskId]. */
    fun isValid(token: String?, taskId: Long): Boolean {
        val stored = if (token.isNullOrBlank()) null
        else prefs.getLong(KEY_PREFIX + token, -1L).takeIf { it > 0L }
        return isGrantValid(stored, token, taskId)
    }

    fun revoke(token: String) {
        prefs.edit().remove(KEY_PREFIX + token).apply()
    }

    /** Revoke every grant bound to [taskId] (e.g. when the task is deleted). */
    fun revokeAllForTask(taskId: Long) {
        val toRemove = prefs.all
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && (value as? Long) == taskId }
            .keys
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }

    /** All currently issued grants, for inspection/revocation UIs. */
    fun grants(): List<LocaleGrant> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is Long) {
                LocaleGrant(key.removePrefix(KEY_PREFIX), value)
            } else {
                null
            }
        }.sortedBy { it.taskId }

    companion object {
        private const val PREFS = "locale_grants"
        private const val KEY_PREFIX = "grant:"

        /** 256-bit URL-safe token. Pure (no Android APIs) so it is unit-testable. */
        internal fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/**
 * Pure grant-validation decision. A dispatch is authorized only when a non-blank token was supplied,
 * the requested task id is positive, and the stored binding matches the requested task id. Forged
 * (unknown), missing/blank, mutated (bound to a different task), revoked, and deleted-task
 * (stored == null) grants all fail.
 */
internal fun isGrantValid(storedTaskId: Long?, requestedToken: String?, requestedTaskId: Long): Boolean =
    !requestedToken.isNullOrBlank() && requestedTaskId > 0L && storedTaskId == requestedTaskId
