package com.opentasker.core.model

import kotlinx.serialization.Serializable

/** A user-declared state that enabled profiles must not change while it is true. */
@Serializable
data class AutomationInvariant(
    val id: Long = 0L,
    val name: String = "",
    val guard: InvariantStatePredicate = InvariantStatePredicate(),
    val forbiddenWriteKey: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class InvariantStatePredicate(
    val key: String = "",
    val operator: InvariantOperator = InvariantOperator.EQUALS,
    val value: String = "",
)

@Serializable
enum class InvariantOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    ;

    val symbol: String
        get() = when (this) {
            EQUALS -> "="
            NOT_EQUALS -> "!="
            GREATER_THAN -> ">"
            GREATER_OR_EQUAL -> ">="
            LESS_THAN -> "<"
            LESS_OR_EQUAL -> "<="
        }

    companion object {
        fun fromSymbol(symbol: String): InvariantOperator = when (symbol.trim()) {
            "!=" -> NOT_EQUALS
            ">" -> GREATER_THAN
            ">=" -> GREATER_OR_EQUAL
            "<" -> LESS_THAN
            "<=" -> LESS_OR_EQUAL
            else -> EQUALS
        }
    }
}

/** Pure normalization and validation shared by the editor, import boundary, and preferences. */
object AutomationInvariantPolicy {
    const val MAX_INVARIANTS = 64
    const val MAX_NAME_LENGTH = 64
    const val MAX_KEY_LENGTH = 64
    const val MAX_VALUE_LENGTH = 96

    private val KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")

    fun normalize(invariants: List<AutomationInvariant>): List<AutomationInvariant> {
        val usedIds = mutableSetOf<Long>()
        var nextId = 1L
        return invariants.asSequence()
            .map(::normalized)
            .filter { validate(it) == null }
            .take(MAX_INVARIANTS)
            .map { invariant ->
                val retainedId = invariant.id.takeIf { it > 0L && usedIds.add(it) }
                val assignedId = retainedId ?: run {
                    while (nextId in usedIds) nextId++
                    usedIds += nextId
                    nextId++
                    nextId - 1L
                }
                invariant.copy(id = assignedId)
            }
            .toList()
    }

    /** Returns a stable, user-facing validation key, or null when the invariant is valid. */
    fun validate(invariant: AutomationInvariant): String? = when {
        invariant.name.isBlank() -> "name"
        invariant.name.length > MAX_NAME_LENGTH -> "name_length"
        !validKey(invariant.guard.key) -> "guard_key"
        invariant.guard.value.isBlank() -> "guard_value"
        invariant.guard.value.length > MAX_VALUE_LENGTH -> "guard_value_length"
        !validKey(invariant.forbiddenWriteKey) -> "write_key"
        else -> null
    }

    fun validKey(value: String): Boolean = value.length <= MAX_KEY_LENGTH && KEY_PATTERN.matches(value)

    private fun normalized(invariant: AutomationInvariant): AutomationInvariant = invariant.copy(
        name = bounded(invariant.name, MAX_NAME_LENGTH),
        guard = invariant.guard.copy(
            key = bounded(invariant.guard.key, MAX_KEY_LENGTH),
            value = bounded(invariant.guard.value, MAX_VALUE_LENGTH),
        ),
        forbiddenWriteKey = bounded(invariant.forbiddenWriteKey, MAX_KEY_LENGTH),
    )

    private fun bounded(value: String, maxLength: Int): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(maxLength)
}
