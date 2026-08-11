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
 * A grant is minted when the user selects a profile, context, or variable in
 * [LocaleConditionEditActivity] and is bound to that exact condition binding, so a query about
 * anything the user never exposed is refused. Secret variables are refused separately and are
 * never selectable here.
 */
class LocaleConditionGrantStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Create and persist a grant for [bindingKey]; returns the opaque token for the bundle. */
    fun issue(bindingKey: String): String {
        val token = LocaleGrantStore.newToken()
        prefs.edit().putString(KEY_PREFIX + token, bindingKey).apply()
        return token
    }

    /** True only when [token] is currently stored and bound to [bindingKey]. */
    fun isValid(token: String?, bindingKey: String): Boolean {
        val stored = if (token.isNullOrBlank()) null else prefs.getString(KEY_PREFIX + token, null)
        return isConditionGrantValid(stored, token, bindingKey)
    }

    fun revoke(token: String) {
        prefs.edit().remove(KEY_PREFIX + token).apply()
    }

    /** Revoke every grant bound to [bindingKey] (e.g. when its target is deleted). */
    fun revokeAllForBinding(bindingKey: String) {
        val toRemove = prefs.all
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value == bindingKey }
            .keys
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }

    /** Revoke every grant bound to a variable (kept as a named compatibility helper). */
    fun revokeAllForVariable(variableKey: String) = revokeAllForBinding(variableKey)

    /** All currently issued grants, for inspection/revocation UIs. */
    fun grants(): List<LocaleConditionGrant> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                LocaleConditionGrant(key.removePrefix(KEY_PREFIX), value)
            } else {
                null
            }
        }.sortedBy { it.bindingKey }

    companion object {
        private const val PREFS = "locale_condition_grants"
        private const val KEY_PREFIX = "grant:"

        /** Stable identity of the profile condition a grant authorizes reading. */
        fun profileKey(profileId: Long): String = "profile:${requirePositiveId(profileId)}"

        /** Stable identity of the context condition a grant authorizes reading. */
        fun contextKey(profileId: Long, contextIndex: Int): String {
            requirePositiveId(profileId)
            require(contextIndex >= 0) { "Context index must not be negative." }
            return "context:$profileId:$contextIndex"
        }

        /** Stable identity of the variable a grant authorizes reading. */
        fun variableKey(projectId: Long, variableName: String): String = "$projectId:$variableName"

        /** Stable binding used by the receiver before it touches the database. */
        fun bindingKey(spec: LocaleConditionSpec): String = when (spec.kind) {
            LocaleConditionKind.PROFILE_ACTIVE -> profileKey(requireNotNull(spec.profileId))
            LocaleConditionKind.CONTEXT_SATISFIED -> contextKey(
                requireNotNull(spec.profileId),
                requireNotNull(spec.contextIndex),
            )
            LocaleConditionKind.VARIABLE_COMPARE -> variableKey(
                spec.variableProjectId,
                requireNotNull(spec.variableName),
            )
        }

        private fun requirePositiveId(value: Long): Long = value.also {
            require(it > 0) { "Identifier must be positive." }
        }
    }
}

/** A revocable read grant: a high-entropy token bound to one condition binding. */
data class LocaleConditionGrant(val token: String, val bindingKey: String)

/**
 * Pure grant-validation decision. A query is authorized only when a non-blank token was supplied
 * and the stored binding names the same condition. Missing, forged, revoked, and
 * bound-to-another-condition tokens all fail.
 */
internal fun isConditionGrantValid(
    storedBindingKey: String?,
    requestedToken: String?,
    requestedBindingKey: String,
): Boolean = !requestedToken.isNullOrBlank() &&
    requestedBindingKey.isNotBlank() &&
    storedBindingKey == requestedBindingKey
