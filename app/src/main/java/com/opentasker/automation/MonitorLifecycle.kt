package com.opentasker.automation

/**
 * Small, thread-safe lifecycle gate shared by callback-backed monitors.
 *
 * Registration failures leave the gate open so a later start can retry. Teardown marks the
 * monitor stopped before invoking platform cleanup, making repeated stop calls harmless even
 * when the platform reports that a callback was already removed.
 */
internal class MonitorLifecycle {
    private var active = false

    @Synchronized
    fun start(register: () -> Boolean): Boolean {
        if (active) return true
        val registered = runCatching { register() }.getOrDefault(false)
        active = registered
        return registered
    }

    fun stop(unregister: () -> Unit) {
        val shouldUnregister = synchronized(this) {
            if (!active) false else {
                active = false
                true
            }
        }
        if (shouldUnregister) runCatching(unregister)
    }

    @Synchronized
    fun isActive(): Boolean = active
}
