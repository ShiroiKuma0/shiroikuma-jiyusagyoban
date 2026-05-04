package com.opentasker.core.contexts

import kotlinx.serialization.Serializable

/** Event fired when a context transitions its match state. */
@Serializable
data class ContextEvent(
    val type: String,
    val matched: Boolean,
    val metadata: Map<String, String> = emptyMap(),
)
