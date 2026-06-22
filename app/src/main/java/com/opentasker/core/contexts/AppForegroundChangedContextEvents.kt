package com.opentasker.core.contexts

import com.opentasker.core.engine.variables.PersistentGlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fires "app_foreground" whenever the foreground app changes (a wildcard trigger — any app). The new
 * package is exposed as the super-global %APP_PACKAGE and as the event's "package" metadata, so an EVENT
 * context can optionally narrow with config package=com.foo,com.bar (CSV) — or omit it to fire on every
 * app switch (e.g. a per-app dispatcher that reads %APP_PACKAGE and acts).
 */
object AppForegroundChangedContextEvents {
    private val changes = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ContextEvent> = changes.asSharedFlow()

    @Volatile
    private var lastPublished: String? = null

    fun publish(packageName: String) {
        val pkg = packageName.trim()
        // Skip blanks and consecutive repeats — both the accessibility service and the UsageStats poll
        // feed this, so dedup here keeps a single switch from firing the trigger twice.
        if (pkg.isBlank() || pkg == lastPublished) return
        lastPublished = pkg
        PersistentGlobalScope.set(0L, "APP_PACKAGE", pkg)
        changes.tryEmit(
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf("event" to "app_foreground", "package" to pkg),
            ),
        )
    }
}
