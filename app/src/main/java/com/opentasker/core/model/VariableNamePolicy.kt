package com.opentasker.core.model

/** Canonical parsing and scope classification for Tasker-compatible variable names. */
object VariableNamePolicy {
    const val MAX_LENGTH = 64

    private val validName = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")

    /** Trims user input, removes an optional `%` sigil, and validates the canonical name. */
    fun normalize(rawName: String): String? {
        val trimmed = rawName.trim()
        val name = if (trimmed.startsWith('%')) trimmed.drop(1) else trimmed
        return name.takeIf(validName::matches)
    }

    /** Tasker globals contain at least one uppercase letter; all-lowercase names are local. */
    fun isGlobal(rawName: String): Boolean =
        normalize(rawName)?.any(Char::isUpperCase) == true

    /** Returns a valid global name, promoting an all-lowercase name without losing its spelling. */
    fun promoteToGlobal(rawName: String): String? = normalize(rawName)?.let { name ->
        if (name.any(Char::isUpperCase)) name else name.replaceFirstChar(Char::uppercaseChar)
    }

    /** Normalizes a name for its declared storage scope, or rejects a scope/name mismatch. */
    fun normalizeForScope(rawName: String, isGlobal: Boolean): String? =
        if (isGlobal) {
            promoteToGlobal(rawName)
        } else {
            normalize(rawName)?.takeUnless(::isGlobal)
        }
}
