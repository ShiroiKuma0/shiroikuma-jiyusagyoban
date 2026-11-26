package com.sysadmindoc.ruledeck.ui.screens

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Runs [block], then puts the app's Last edited times back as they were. The view model dates
 * edits by item id in the app's own store, and a test database's ids name the device's real items.
 */
internal suspend fun <T> keepingLibraryEditTimes(context: Context, block: suspend () -> T): T {
    val saved = context.libraryEditTimesDataStore.data.first()
    try {
        return block()
    } finally {
        context.libraryEditTimesDataStore.edit { prefs ->
            prefs.clear()
            prefs += saved
        }
    }
}
