package com.opentasker.core.engine

/**
 * Records the most recent `widget.refresh` (the pull-model tick): when it ran and which placed widgets
 * it re-rendered, flagging each as a **pull** widget (template-bound → re-expands globals every tick) or
 * **static** (a fixed layout → never updates). Surfaced in the Monitor so 白い熊 can see at a glance
 * whether the per-minute tick is actually pulling the clock/battery widgets. In-memory; per process.
 */
object WidgetRefreshLog {
    data class Entry(val label: String, val isPull: Boolean)

    @Volatile var lastAt: Long = 0L
        private set
    @Volatile var entries: List<Entry> = emptyList()
        private set

    fun record(es: List<Entry>, now: Long = System.currentTimeMillis()) {
        lastAt = now
        entries = es
    }
}
