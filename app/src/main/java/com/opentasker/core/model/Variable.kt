package com.opentasker.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Variables are `%name` slots, expanded at action runtime. Scope follows the name's casing:
 *   - `%ALLCAPS`   → super-global, app-wide, persistent ([projectId] == 0).
 *   - `%MixedCase` → project-global, persistent, owned by one project ([projectId] > 0).
 *   - `%lowercase` → task-local, ephemeral (never persisted, so never a [Variable]).
 *
 * Only persisted (global) variables are represented here; [projectId] 0 means super-global.
 * [isSecret] rows store authenticated Keystore ciphertext in [value] (upstream secret variables).
 */
@Serializable
data class Variable(
    val name: String,
    val value: String,
    val projectId: Long = 0,
    val isSecret: Boolean = false,
    @Transient val secretAvailable: Boolean = true,
)
