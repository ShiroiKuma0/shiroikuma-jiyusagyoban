package com.opentasker.core.plugins.locale

import android.content.Context

/**
 * Issues and validates revocable read grants for the exported Locale condition receiver.
 *
 * `QUERY_CONDITION` is exported without a permission because the Locale/Tasker contract requires
 * it, and its bundle is entirely attacker-controlled. Without a grant, any app with no permissions
 * at all could name an arbitrary variable and an expected value, read Satisfied/Unsatisfied off the
 * ordered-broadcast result code, and extract the variable's contents a comparison at a time.
 *
 * A grant is minted when the user picks a variable in [LocaleConditionEditActivity] and is bound to
 * that exact variable, so a query about anything the user never exposed is refused. Secret
 * variables are refused separately and are never selectable here.
 */
class LocaleConditionGrantStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Create and persist a grant for [variableKey]; returns the opaque token for the bundle. */
    fun issue(variableKey: String): String {
        val token = LocaleGrantStore.newToken()
        prefs.edit().putString(KEY_PREFIX + token, variableKey).apply()
        return token
    }

    /** True only when [token] is currently stored and bound to [variableKey]. */
    fun isValid(token: String?, variableKey: String): Boolean {
        val stored = if (token.isNullOrBlank()) null else prefs.getString(KEY_PREFIX + token, null)
        return isConditionGrantValid(stored, token, variableKey)
    }

    fun revoke(token: String) {
        prefs.edit().remove(KEY_PREFIX + token).apply()
    }

    /** Revoke every grant bound to [variableKey] (e.g. when the variable is deleted). */
    fun revokeAllForVariable(variableKey: String) {
        val toRemove = prefs.all
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value == variableKey }
            .keys
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }

    /** All currently issued grants, for inspection/revocation UIs. */
    fun grants(): List<LocaleConditionGrant> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                LocaleConditionGrant(key.removePrefix(KEY_PREFIX), value)
            } else {
                null
            }
        }.sortedBy { it.variableKey }

    companion object {
        private const val PREFS = "locale_condition_grants"
        private const val KEY_PREFIX = "grant:"

        /** Stable identity of the variable a grant authorizes reading. */
        fun variableKey(projectId: Long, variableName: String): String = "$projectId:$variableName"
    }
}

/** A revocable read grant: a high-entropy token bound to a single variable. */
data class LocaleConditionGrant(val token: String, val variableKey: String)

/**
 * Pure grant-validation decision. A query is authorized only when a non-blank token was supplied
 * and the stored binding names the same variable. Missing, forged, revoked, and
 * bound-to-another-variable tokens all fail.
 */
internal fun isConditionGrantValid(
    storedVariableKey: String?,
    requestedToken: String?,
    requestedVariableKey: String,
): Boolean = !requestedToken.isNullOrBlank() &&
    requestedVariableKey.isNotBlank() &&
    storedVariableKey == requestedVariableKey
