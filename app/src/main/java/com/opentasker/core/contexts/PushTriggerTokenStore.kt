package com.opentasker.core.contexts

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/** Stores the per-install secret used by the exported distributor bridge. */
class PushTriggerTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun token(): String = synchronized(preferences) {
        preferences.getString(KEY_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: generateAndStore()
    }

    fun rotate(): String = synchronized(preferences) { generateAndStore() }

    private fun generateAndStore(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        const val PREFERENCES = "push_trigger"
        const val KEY_TOKEN = "token"
        const val TOKEN_BYTES = 32
    }
}
