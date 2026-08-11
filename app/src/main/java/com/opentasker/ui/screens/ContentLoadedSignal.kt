package com.opentasker.ui.screens

import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Emits `true` once Room has delivered its first snapshot of the primary tables.
 *
 * Every list flow seeds an empty list, which a screen cannot tell apart from "the user has nothing
 * stored" — so a cold start with existing data rendered the first-run empty state for its first
 * frames. Screens gate their empty states on this instead of on `isEmpty()` alone.
 *
 * One emission from each primary table is enough; the underlying flows stay hot afterwards, so this
 * never flips back to `false`.
 */
internal fun contentLoadedSignal(db: AppDatabase, scope: CoroutineScope): StateFlow<Boolean> {
    val loaded = MutableStateFlow(false)
    scope.launch {
        combine(
            db.profileDao().getAllAsFlow().take(1),
            db.taskDao().getAllAsFlow().take(1),
        ) { _, _ -> true }.collect { loaded.value = true }
    }
    return loaded.asStateFlow()
}
