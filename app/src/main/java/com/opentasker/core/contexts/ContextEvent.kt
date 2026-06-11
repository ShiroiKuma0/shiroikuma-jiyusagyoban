package com.opentasker.core.contexts

import kotlinx.serialization.Serializable

/** Context observation emitted by level sources or one-shot event sources. */
@Serializable
data class ContextEvent(
    val type: String,
    val matched: Boolean,
    val metadata: Map<String, String> = emptyMap(),
)
