package com.opentasker.core.contexts

import kotlinx.serialization.Serializable

/** Context observation emitted by level sources or one-shot event sources. */
@Serializable
data class ContextEvent(
    val type: String,
    val matched: Boolean,
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Per-event snapshot of the super-global names+values this event sets (e.g. `NOTIF_PACKAGE` →
     * pkg). Carried through the matcher to the fired task and injected as task-locals, so a queued
     * task reads THIS event's values instead of the shared (since-overwritten) super-global.
     */
    val vars: Map<String, String> = emptyMap(),
)
