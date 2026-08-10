package com.opentasker.core.engine

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.core.deviceProtectedDataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The deliberately small piece of state that is available during Android's direct-boot phase.
 *
 * This store must never grow into a profile or secret store: it contains only the user's opt-in
 * flag and one collapsed time pulse waiting for the credential-protected engine after unlock.
 */
object DirectBootTriggerStore {
    private const val DATASTORE_FILE = "direct_boot_triggers.preferences_pb"
    private val ENABLED = booleanPreferencesKey("enabled")
    private val PENDING_TIME_TICK_AT = longPreferencesKey("pending_time_tick_at")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun observe(context: Context): Flow<Boolean> =
        dataStore(context).data
            .map { preferences -> preferences[ENABLED] ?: false }
            .catch { emit(false) }

    suspend fun isEnabled(context: Context): Boolean =
        runCatching { dataStore(context).data.first()[ENABLED] ?: false }
            .getOrDefault(false)

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        dataStore(context).edit { preferences ->
            preferences[ENABLED] = enabled
            if (!enabled) preferences.remove(PENDING_TIME_TICK_AT)
        }
    }

    suspend fun markTimeTickPending(context: Context, observedAtMillis: Long) {
        if (observedAtMillis <= 0L) return
        dataStore(context).edit { preferences ->
            // One collapsed pulse is enough to wake the normal engine after unlock. This keeps
            // device-protected storage bounded even if the device remains locked for days.
            if (preferences[ENABLED] == true && preferences[PENDING_TIME_TICK_AT] == null) {
                preferences[PENDING_TIME_TICK_AT] = observedAtMillis
            }
        }
    }

    suspend fun consumePendingTimeTick(context: Context): Boolean {
        var pending = false
        dataStore(context).edit { preferences ->
            if (preferences[PENDING_TIME_TICK_AT] != null) {
                pending = true
                preferences.remove(PENDING_TIME_TICK_AT)
            }
        }
        return pending
    }

    fun isUserUnlocked(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            true
        } else {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }

    private fun dataStore(context: Context): DataStore<Preferences> {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = {
                    context.applicationContext.deviceProtectedDataStoreFile(DATASTORE_FILE)
                },
            ).also { instance = it }
        }
    }
}
