package com.opentasker.core.scripting

import android.content.Context

internal data class ApprovedTermuxScript(
    val executable: String,
    val sha256: String,
)

internal enum class TermuxAllowlistSaveResult {
    SAVED,
    INVALID_PATH,
    INVALID_HASH,
    FULL,
}

/**
 * The approval decision, separated from SharedPreferences so it can be tested.
 *
 * This is the boundary that decides whether a script may run at all, so its rules are worth
 * asserting directly rather than only through a store that needs a Context.
 */
internal object TermuxAllowlistPolicy {
    private const val SEPARATOR = '\n'

    fun encode(script: ApprovedTermuxScript): String = script.sha256 + SEPARATOR + script.executable

    /** Null for anything this version did not write, so a corrupt row reads as absent, not trusted. */
    fun decode(value: String?): ApprovedTermuxScript? {
        val parts = value?.split(SEPARATOR, limit = 2) ?: return null
        if (parts.size != 2) return null
        val hash = TermuxScriptPolicy.normalizeHash(parts[0]) ?: return null
        val executable = TermuxScriptPolicy.normalizeExecutable(parts[1]) ?: return null
        return ApprovedTermuxScript(executable, hash)
    }

    fun admit(
        executable: String,
        sha256: String,
        alreadyApproved: Boolean,
        approvedCount: Int,
        maxApproved: Int = TermuxScriptAllowlistStore.MAX_APPROVED_SCRIPTS,
    ): TermuxAllowlistSaveResult {
        TermuxScriptPolicy.normalizeExecutable(executable) ?: return TermuxAllowlistSaveResult.INVALID_PATH
        TermuxScriptPolicy.normalizeHash(sha256) ?: return TermuxAllowlistSaveResult.INVALID_HASH
        if (!alreadyApproved && approvedCount >= maxApproved) return TermuxAllowlistSaveResult.FULL
        return TermuxAllowlistSaveResult.SAVED
    }
}

internal class TermuxScriptAllowlistStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun entries(): List<ApprovedTermuxScript> = synchronized(this) {
        preferences.all.values
            .mapNotNull { value -> TermuxAllowlistPolicy.decode(value as? String) }
            .distinctBy(ApprovedTermuxScript::executable)
            .sortedBy(ApprovedTermuxScript::executable)
    }

    fun expectedHash(executable: String): String? {
        val normalized = TermuxScriptPolicy.normalizeExecutable(executable) ?: return null
        return preferences.getString(keyFor(normalized), null)?.let(TermuxAllowlistPolicy::decode)?.sha256
    }

    fun approve(executable: String, sha256: String): TermuxAllowlistSaveResult = synchronized(this) {
        val normalizedPath = TermuxScriptPolicy.normalizeExecutable(executable)
        val normalizedHash = TermuxScriptPolicy.normalizeHash(sha256)
        val key = normalizedPath?.let(::keyFor)
        val decision = TermuxAllowlistPolicy.admit(
            executable = executable,
            sha256 = sha256,
            alreadyApproved = key != null && preferences.contains(key),
            approvedCount = entries().size,
        )
        if (decision != TermuxAllowlistSaveResult.SAVED) return decision
        val approved = ApprovedTermuxScript(requireNotNull(normalizedPath), requireNotNull(normalizedHash))
        preferences.edit().putString(requireNotNull(key), TermuxAllowlistPolicy.encode(approved)).apply()
        TermuxAllowlistSaveResult.SAVED
    }

    fun revoke(executable: String) {
        TermuxScriptPolicy.normalizeExecutable(executable)?.let { normalized ->
            preferences.edit().remove(keyFor(normalized)).apply()
        }
    }

    private fun keyFor(executable: String): String =
        "script_${TermuxScriptPolicy.hash(executable.toByteArray())}"

    companion object {
        internal const val MAX_APPROVED_SCRIPTS = 64
        private const val PREFERENCES_NAME = "termux_script_allowlist"
    }
}
