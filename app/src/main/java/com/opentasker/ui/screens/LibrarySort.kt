package com.opentasker.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opentasker.app.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** How a library list is ordered. [Default] is the order each list has always had. */
enum class LibrarySortOrder { Default, Name, LastEdited }

/** The lists that can be sorted, each remembering its own order. */
enum class LibraryList(internal val prefKey: String) {
    Profiles("profiles"),
    Tasks("tasks"),
    Variables("variables"),
    Scenes("scenes"),
}

/**
 * [items] in [order]. Name ignores case. Last edited puts the newest edit made in the app first and
 * leaves anything never edited here after it, still in the order it came. Both sorts are stable.
 */
internal fun <T> sortLibrary(
    items: List<T>,
    order: LibrarySortOrder,
    name: (T) -> String,
    lastEditedAtMs: (T) -> Long?,
): List<T> = when (order) {
    LibrarySortOrder.Default -> items
    LibrarySortOrder.Name -> items.sortedBy { name(it).lowercase() }
    LibrarySortOrder.LastEdited -> items.sortedByDescending { lastEditedAtMs(it) ?: Long.MIN_VALUE }
}

private val Context.librarySortDataStore by preferencesDataStore(name = "library_sort_prefs")

/** Each list's chosen order, kept on disk so it survives the app being closed. */
object LibrarySortPreference {
    private fun key(list: LibraryList) = stringPreferencesKey("sort_${list.prefKey}")

    fun orders(context: Context): Flow<Map<LibraryList, LibrarySortOrder>> =
        context.librarySortDataStore.data
            .map { prefs ->
                LibraryList.entries.associateWith { list ->
                    LibrarySortOrder.entries.firstOrNull { it.name == prefs[key(list)] } ?: LibrarySortOrder.Default
                }
            }
            .catch { emit(emptyMap()) }

    suspend fun set(context: Context, list: LibraryList, order: LibrarySortOrder) {
        context.librarySortDataStore.edit { prefs -> prefs[key(list)] = order.name }
    }
}

// A file that can't be read starts empty, as if no time was stamped yet, so clearing it after a
// restore and stamping new times keep working.
internal val Context.libraryEditTimesDataStore by preferencesDataStore(
    name = "library_edit_times",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * When an item was last created, saved, undone or redone in the app, keyed like
 * [EditHistoryAvailabilityState.key]. Edit history already dates an edit to a profile, task or
 * scene, but creating one, saving a variable and undo or redo add no history row of their own.
 */
object LibraryEditTimes {
    // Deleted items leave their time behind, so only the newest are kept.
    private const val MAX_TIMES = 2_000

    fun times(context: Context): Flow<Map<String, Long>> =
        context.libraryEditTimesDataStore.data
            .map { prefs -> prefs.asMap().entries.mapNotNull { (key, value) -> (value as? Long)?.let { key.name to it } }.toMap() }
            .catch { emit(emptyMap()) }

    suspend fun touch(context: Context, entityType: String, entityId: Long, atMs: Long = System.currentTimeMillis()) {
        context.libraryEditTimesDataStore.edit { prefs ->
            prefs[longPreferencesKey(EditHistoryAvailabilityState.key(entityType, entityId))] = atMs
            val stamps = prefs.asMap().entries.mapNotNull { (key, value) -> (value as? Long)?.let { key.name to it } }
            if (stamps.size > MAX_TIMES) {
                stamps.sortedBy { it.second }.take(stamps.size - MAX_TIMES).forEach { prefs.remove(longPreferencesKey(it.first)) }
            }
        }
    }

    /** A restored database brings back items under ids these times may belong to something else. */
    suspend fun clear(context: Context) {
        context.libraryEditTimesDataStore.edit { it.clear() }
    }

    internal suspend fun forget(context: Context, entityType: String, entityId: Long) {
        context.libraryEditTimesDataStore.edit { it.remove(longPreferencesKey(EditHistoryAvailabilityState.key(entityType, entityId))) }
    }
}

/** The newer of each item's last history row and its last stamp from [LibraryEditTimes]. */
internal fun newestEdits(history: Map<String, Long>, stamps: Map<String, Long>): Map<String, Long> =
    (history.keys + stamps.keys).associateWith { key -> maxOf(history[key] ?: Long.MIN_VALUE, stamps[key] ?: Long.MIN_VALUE) }

/** A list's current order and what to call when the user picks another. */
@Immutable
class LibrarySortControl(
    val order: LibrarySortOrder = LibrarySortOrder.Default,
    val onOrderChange: (LibrarySortOrder) -> Unit = {},
)

/**
 * The sort button beside a list's search field. [defaultIsName] is for a list whose own order is
 * already by name, so it offers Name and Last edited instead of a Name twice.
 */
@Composable
internal fun LibrarySortButton(control: LibrarySortControl, defaultIsName: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = if (defaultIsName) {
        listOf(LibrarySortOrder.Default to R.string.library_sort_name, LibrarySortOrder.LastEdited to R.string.library_sort_last_edited)
    } else {
        listOf(
            LibrarySortOrder.Default to R.string.library_sort_default,
            LibrarySortOrder.Name to R.string.library_sort_name,
            LibrarySortOrder.LastEdited to R.string.library_sort_last_edited,
        )
    }
    val current = options.firstOrNull { it.first == control.order } ?: options.first()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.library_sort_button, stringResource(current.second)),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (order, labelRes) ->
                val isCurrent = order == current.first
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        expanded = false
                        if (!isCurrent) control.onOrderChange(order)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                    trailingIcon = if (isCurrent) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
