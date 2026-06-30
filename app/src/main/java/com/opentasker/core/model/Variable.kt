package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * Variables are `%name` slots, expanded at action runtime. Scope follows the name's casing:
 *   - `%ALLCAPS`   → super-global, app-wide, persistent ([projectId] == 0).
 *   - `%MixedCase` → project-global, persistent, owned by one project ([projectId] > 0).
 *   - `%lowercase` → task-local, ephemeral (never persisted, so never a [Variable]).
 *
 * Only persisted (global) variables are represented here; [projectId] 0 means super-global.
 */
@Serializable
data class Variable(
    val name: String,
    val value: String,
    val projectId: Long = 0,
)
