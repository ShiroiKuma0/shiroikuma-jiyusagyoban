package com.opentasker.core.references

import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy

/** Syntax used by a variable reference in an automation field. */
enum class VariableReferenceSyntax {
    LEGACY,
    TEMPLATE,
}

/** Scope implied by a template namespace, or inferred from the variable spelling. */
enum class VariableReferenceScope {
    INFERRED,
    GLOBAL,
    LOCAL,
    EXTERNAL,
}

/** A variable token with offsets into the source field so a rewrite can preserve all other text. */
data class ScannedVariableReference(
    val name: String,
    val syntax: VariableReferenceSyntax,
    val scope: VariableReferenceScope,
    val start: Int,
    val endExclusive: Int,
)

/**
 * Shared scanner for the two expression syntaxes accepted by the engine.
 *
 * The scanner intentionally only rewrites the reference base. Template paths, legacy operators,
 * function pipelines, whitespace, and surrounding prose remain byte-for-byte unchanged.
 */
object VariableReferenceScanner {
    fun scan(value: String): List<ScannedVariableReference> {
        if (value.isEmpty()) return emptyList()

        val references = mutableListOf<ScannedVariableReference>()
        var cursor = 0
        while (cursor < value.length) {
            val templateStart = value.indexOf("{{", startIndex = cursor)
            val legacyStart = value.indexOf('%', startIndex = cursor)
            if (templateStart == -1 && legacyStart == -1) break

            val nextTemplate = templateStart != -1 && (legacyStart == -1 || templateStart < legacyStart)
            if (nextTemplate) {
                val close = value.indexOf("}}", startIndex = templateStart + 2)
                if (close == -1) {
                    // The template evaluator treats an unclosed token as literal text. Continue
                    // after its opener so a legacy token outside a valid template is still found.
                    cursor = templateStart + 2
                    continue
                }
                scanTemplate(value, templateStart, close)?.let(references::add)
                cursor = close + 2
            } else {
                val reference = scanLegacy(value, legacyStart)
                if (reference == null) {
                    cursor = legacyStart + 1
                } else {
                    references += reference
                    cursor = reference.endExclusive
                }
            }
        }
        return references
    }

    /** Rewrites only references to [target], preserving the field's original formatting. */
    fun rewrite(value: String, target: Variable, replacementName: String): String {
        val oldName = VariableNamePolicy.normalizeForScope(target.name, target.isGlobal) ?: return value
        val newName = VariableNamePolicy.normalizeForScope(replacementName, target.isGlobal) ?: return value
        if (oldName == newName) return value

        val matches = scan(value).filter { reference ->
            reference.matches(oldName, target.isGlobal)
        }
        if (matches.isEmpty()) return value

        var rewritten = value
        matches.asReversed().forEach { reference ->
            rewritten = rewritten.replaceRange(reference.start, reference.endExclusive, newName)
        }
        return rewritten
    }

    private fun scanLegacy(value: String, percentIndex: Int): ScannedVariableReference? {
        val nameStart = percentIndex + 1
        if (nameStart >= value.length || !value[nameStart].isLetter()) return null

        var nameEnd = nameStart + 1
        while (nameEnd < value.length && isReferenceChar(value[nameEnd])) nameEnd++
        val name = value.substring(nameStart, nameEnd)
        if (VariableNamePolicy.normalize(name) == null) return null
        return ScannedVariableReference(
            name = name,
            syntax = VariableReferenceSyntax.LEGACY,
            scope = VariableReferenceScope.INFERRED,
            start = nameStart,
            endExclusive = nameEnd,
        )
    }

    private fun scanTemplate(
        value: String,
        templateStart: Int,
        closeIndex: Int,
    ): ScannedVariableReference? {
        val expressionStart = templateStart + 2
        val expression = value.substring(expressionStart, closeIndex)
        val pipelineEnd = expression.indexOf('|').takeIf { it >= 0 } ?: expression.length
        val referenceText = expression.substring(0, pipelineEnd)

        var relative = 0
        while (relative < referenceText.length && referenceText[relative].isWhitespace()) relative++
        if (relative >= referenceText.length) return null

        if (referenceText[relative] == '%') relative++
        val firstStart = relative
        while (relative < referenceText.length && isReferenceChar(referenceText[relative])) relative++
        if (relative == firstStart) return null

        val first = referenceText.substring(firstStart, relative)
        var scope = VariableReferenceScope.INFERRED
        var nameStart = firstStart
        var nameEnd = relative

        if (relative < referenceText.length && referenceText[relative] == '.') {
            val namespace = when (first) {
                "global" -> VariableReferenceScope.GLOBAL
                "task" -> VariableReferenceScope.LOCAL
                "event", "array" -> VariableReferenceScope.EXTERNAL
                else -> null
            }
            if (namespace != null) {
                scope = namespace
                nameStart = relative + 1
                nameEnd = nameStart
                while (nameEnd < referenceText.length && isReferenceChar(referenceText[nameEnd])) nameEnd++
                if (nameEnd == nameStart) return null
            }
        }

        val name = referenceText.substring(nameStart, nameEnd)
        if (name.equals("true", ignoreCase = false) || name.equals("false", ignoreCase = false)) return null
        if (VariableNamePolicy.normalize(name) == null) return null

        return ScannedVariableReference(
            name = name,
            syntax = VariableReferenceSyntax.TEMPLATE,
            scope = scope,
            start = expressionStart + nameStart,
            endExclusive = expressionStart + nameEnd,
        )
    }

    private fun ScannedVariableReference.matches(oldName: String, isGlobal: Boolean): Boolean {
        if (name != oldName || scope == VariableReferenceScope.EXTERNAL) return false
        return when (scope) {
            VariableReferenceScope.GLOBAL -> isGlobal
            VariableReferenceScope.LOCAL -> !isGlobal
            VariableReferenceScope.INFERRED -> VariableNamePolicy.isGlobal(name) == isGlobal
            VariableReferenceScope.EXTERNAL -> false
        }
    }

    private fun isReferenceChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-'
}
