package com.sysadmindoc.ruledeck.core.storage

import android.content.Context

/**
 * The app keeps one wrapped SQLCipher key for every database, in `database_security`. A test that
 * clears it to act out a reinstall leaves the app's own database unreadable on a phone with real
 * data, so it takes this snapshot first and puts it back when it's done.
 */
internal class KeptDatabaseKey(private val context: Context) {
    private val saved: Map<String, Any?> = preferences().all.toMap().onEach { (key, value) ->
        // Checked before the test clears anything, so a value this can't write back stops the test
        // while the key is still in place.
        check(value is String || value is Boolean || value is Int || value is Long || value is Float) {
            "database_security holds a $key this test can't put back"
        }
    }

    private fun preferences() = context.getSharedPreferences("database_security", Context.MODE_PRIVATE)

    /**
     * Holds [DatabaseKeyStore]'s lock, the one its `getOrCreate` takes, so the app in the same
     * process can't read the cleared prefs, mint a key and commit it over this one.
     */
    fun putBack() = synchronized(DatabaseKeyStore) {
        val editor = preferences().edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        check(editor.commit()) { "Could not put the app's database key back" }
    }
}
