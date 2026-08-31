package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID

/**
 * Expand `%vars` in [raw] against every persisted global (super-globals + all project-globals, merged)
 * with no task context — for rendering widgets and scenes *outside* a task run. Returns [raw] unchanged
 * on any error. Names are merged, so a `%MixedCase` project-global resolves without knowing its project
 * (a collision across projects would be ambiguous, which doesn't arise for the clock's `%DT_*`).
 */
fun expandAgainstGlobals(raw: String): String {
    val scope = InMemoryGlobalScope()
    PersistentGlobalScope.snapshotAll().forEach { (k, v) -> scope.set(SUPER_GLOBAL_PROJECT_ID, k, v) }
    return runCatching { VariableStore(scope, SUPER_GLOBAL_PROJECT_ID).expand(raw) }.getOrDefault(raw)
}
