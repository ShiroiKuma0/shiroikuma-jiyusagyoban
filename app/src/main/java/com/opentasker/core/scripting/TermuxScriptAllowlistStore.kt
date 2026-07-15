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

internal class TermuxScriptAllowlistStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun entries(): List<ApprovedTermuxScript> = synchronized(this) {
        preferences.all.values
            .mapNotNull { value -> decode(value as? String) }
            .distinctBy(ApprovedTermuxScript::executable)
            .sortedBy(ApprovedTermuxScript::executable)
    }

    fun expectedHash(executable: String): String? {
        val normalized = TermuxScriptPolicy.normalizeExecutable(executable) ?: return null
        return preferences.getString(keyFor(normalized), null)?.let(::decode)?.sha256
    }

    fun approve(executable: String, sha256: String): TermuxAllowlistSaveResult = synchronized(this) {
        val normalizedPath = TermuxScriptPolicy.normalizeExecutable(executable)
            ?: return TermuxAllowlistSaveResult.INVALID_PATH
        val normalizedHash = TermuxScriptPolicy.normalizeHash(sha256)
            ?: return TermuxAllowlistSaveResult.INVALID_HASH
        val key = keyFor(normalizedPath)
        if (!preferences.contains(key) && entries().size >= MAX_APPROVED_SCRIPTS) {
            return TermuxAllowlistSaveResult.FULL
        }
        preferences.edit().putString(key, "$normalizedHash\n$normalizedPath").apply()
        TermuxAllowlistSaveResult.SAVED
    }

    fun revoke(executable: String) {
        TermuxScriptPolicy.normalizeExecutable(executable)?.let { normalized ->
            preferences.edit().remove(keyFor(normalized)).apply()
        }
    }

    private fun decode(value: String?): ApprovedTermuxScript? {
        val parts = value?.split('\n', limit = 2) ?: return null
        if (parts.size != 2) return null
        val hash = TermuxScriptPolicy.normalizeHash(parts[0]) ?: return null
        val executable = TermuxScriptPolicy.normalizeExecutable(parts[1]) ?: return null
        return ApprovedTermuxScript(executable, hash)
    }

    private fun keyFor(executable: String): String =
        "script_${TermuxScriptPolicy.hash(executable.toByteArray())}"

    companion object {
        internal const val MAX_APPROVED_SCRIPTS = 64
        private const val PREFERENCES_NAME = "termux_script_allowlist"
    }
}
