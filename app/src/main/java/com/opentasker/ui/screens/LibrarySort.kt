package com.opentasker.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opentasker.ui.components.DropdownMenuItem
import com.opentasker.ui.components.OutlinedButton
import com.opentasker.ui.components.ThemedDropdownMenu
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

/** [list]'s remembered order, and the writer that stores another. */
@Composable
internal fun rememberLibrarySort(list: LibraryList): LibrarySortControl {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val orders by remember(context) { LibrarySortPreference.orders(context) }.collectAsState(initial = emptyMap())
    val order = orders[list] ?: LibrarySortOrder.Default
    return remember(order, list) {
        LibrarySortControl(order) { picked -> scope.launch { LibrarySortPreference.set(context, list, picked) } }
    }
}

/**
 * The key [lastEdited] maps are read by, the same one edit history and [LibraryEditTimes] write.
 */
internal fun lastEditedKey(entityType: String, entityId: Long): String = EditHistoryAvailabilityState.key(entityType, entityId)

/**
 * The sort pill beside a list's search field, in the fork's look: an outlined pill naming the
 * current order, opening a menu of choice rows.
 *
 * FORK: a sort here is a VIEW. [LibrarySortOrder.Default] is the list as 白い熊 arranged it — groups,
 * drag order, `position` — and it is called [defaultLabel]. Any other order shows a flat list and
 * writes nothing: no `position`, no group, no bundle `sort` changes because of it. [offerName] is
 * false for a list whose own order is already by name.
 */
@Composable
internal fun LibrarySortButton(
    control: LibrarySortControl,
    modifier: Modifier = Modifier,
    defaultLabel: String = "Manual",
    offerName: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val options = buildList {
        add(LibrarySortOrder.Default to defaultLabel)
        if (offerName) add(LibrarySortOrder.Name to "Name")
        add(LibrarySortOrder.LastEdited to "Last edited")
    }
    val current = options.firstOrNull { it.first == control.order } ?: options.first()
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort order", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.second, maxLines = 1)
        }
        ThemedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (order, label) ->
                val chosen = order == current.first
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = role(UiRole.LIST_SELECTED)) },
                    choice = true,
                    modifier = Modifier.semantics { selected = chosen },
                    onClick = {
                        open = false
                        if (!chosen) control.onOrderChange(order)
                    },
                )
            }
        }
    }
}

/** Says, above a sorted list, that the order is a view and how to get the arranged list back. */
@Composable
internal fun LibrarySortNotice(order: LibrarySortOrder, defaultLabel: String = "Manual") {
    Text(
        text = (if (order == LibrarySortOrder.Name) "Sorted by name" else "Sorted by last edited, newest first") +
            " — a view only. Groups and your own order are untouched; “$defaultLabel” shows them again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
