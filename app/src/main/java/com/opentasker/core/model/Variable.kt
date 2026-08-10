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
) {
    /**
     * Upstream carries scope as a stored column. The fork derives it from the NAME instead — the
     * rule stated above, and the one VariableStore already applies at every read and write — so a
     * column could only ever drift from it. Every persisted Variable is global by construction, so
     * this reads true in practice; it exists so upstream's reference index, rename rewriter and
     * semantic diff can ask the question at all.
     */
    val isGlobal: Boolean get() = VariableNamePolicy.isGlobal(name)
}
