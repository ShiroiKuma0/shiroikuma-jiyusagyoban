package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.SceneElement

/**
 * One resource contract for every untrusted automation import.
 *
 * Raw input limits bound the source retained by the UI. A streaming preflight rejects excessive
 * token counts and nesting before kotlinx.serialization allocates anything. The decoded-model
 * checks are deliberately repeated so callers that construct a bundle without the codecs cannot
 * bypass the Room write boundary.
 */
internal data class ImportResourceBudget(
    val maxJsonChars: Int = 16 * 1024 * 1024,
    val maxEntities: Long = 5_000,
    val maxProjects: Long = 100,
    val maxBlueprints: Long = 128,
    val maxInvariants: Long = 64,
    val maxBlueprintInputs: Long = 5_000,
    val maxActions: Long = 20_000,
    val maxContexts: Long = 10_000,
    val maxSceneElements: Long = 10_000,
    val maxJsonTokens: Long = 250_000,
    val maxNestingDepth: Int = 64,
    val maxAggregateStringBytes: Long = 8L * 1024 * 1024,
) {
    companion object {
        val Default = ImportResourceBudget()

        /**
         * For the source text of a MacroDroid backup, never for what comes out of it. A `.mdr` is a
         * dump of the whole app, so a modest macro collection already passes the default token
         * limit, and the importer skips most of what it holds. The 16 MiB file cap still bounds the
         * read, and the converted bundle still has to fit [Default].
         */
        val MacroDroidSource = Default.copy(
            maxJsonTokens = 3_000_000,
            maxNestingDepth = 256,
            maxAggregateStringBytes = 16L * 1024 * 1024,
        )
    }
}

internal class ImportBudgetExceededException(
    val budgetName: String,
    val observed: Long,
    val limit: Long,
) : IllegalArgumentException("Import budget exceeded: $budgetName is $observed; limit is $limit.")

internal object ImportResourceGuard {
    /**
     * [allowComments] matches the parser that reads [rawJson] next. One without comments reads `//`
     * as a bare word, so skipping it as a comment here would hide the rest of the line.
     */
    fun requireJsonPreflight(
        rawJson: String,
        budget: ImportResourceBudget = ImportResourceBudget.Default,
        allowComments: Boolean = true,
    ) {
        requireWithin("JSON characters", rawJson.length.toLong(), budget.maxJsonChars.toLong())
        JsonBudgetScanner(rawJson, budget, allowComments).scan()
    }

    fun requireSourceCounts(
        entities: Long,
        actions: Long,
        contexts: Long,
        budget: ImportResourceBudget = ImportResourceBudget.Default,
    ) {
        requireWithin("entities", entities, budget.maxEntities)
        requireWithin("actions", actions, budget.maxActions)
        requireWithin("contexts", contexts, budget.maxContexts)
    }

    /**
     * Removes a benign DOCTYPE declaration from XML before parsing. Android's Expat-backed parsers
     * don't recognise the Apache disallow-doctype-decl feature, so a plain doctype prolog has to be
     * handled here in text. Declarations that define entities or reference external DTDs are
     * rejected outright (that is the XXE surface), as is any input still holding a doctype after
     * the strip.
     */
    fun sanitizeXml(rawXml: String): String {
        val match = DOCTYPE_PATTERN.find(rawXml) ?: return rawXml
        val start = match.range.first
        var index = start
        var inInternalSubset = false
        var end = -1
        while (index < rawXml.length) {
            when (rawXml[index]) {
                '[' -> inInternalSubset = true
                ']' -> inInternalSubset = false
                '>' -> if (!inInternalSubset) {
                    end = index
                    break
                }
            }
            index++
        }
        require(end >= 0) { "XML DOCTYPE declaration is malformed" }
        val declaration = rawXml.substring(start, end + 1)
        require(!DOCTYPE_UNSAFE_PATTERN.containsMatchIn(declaration)) {
            "XML with DOCTYPE entity or external DTD references is not supported"
        }
        val stripped = rawXml.removeRange(start, end + 1)
        require(!DOCTYPE_PATTERN.containsMatchIn(stripped)) {
            "XML with multiple DOCTYPE declarations is not supported"
        }
        return stripped
    }

    fun bundleViolation(
        bundle: OpenTaskerBundle,
        budget: ImportResourceBudget = ImportResourceBudget.Default,
    ): ImportBudgetExceededException? {
        violation("projects", bundle.projects.size.toLong(), budget.maxProjects)?.let { return it }
        violation("blueprints", bundle.blueprints.size.toLong(), budget.maxBlueprints)?.let { return it }
        violation("automation invariants", bundle.invariants.size.toLong(), budget.maxInvariants)?.let { return it }
        val blueprintInputCount = bundle.blueprints.sumOf { it.inputs.size.toLong() }
        violation("blueprint inputs", blueprintInputCount, budget.maxBlueprintInputs)?.let { return it }
        val entityCount = bundle.tasks.size.toLong() +
            bundle.profiles.size +
            bundle.variables.size +
            bundle.scenes.size +
            bundle.blueprints.size +
            bundle.invariants.size
        violation("entities", entityCount, budget.maxEntities)?.let { return it }

        val actionCount = bundle.tasks.sumOf { task -> task.actions.size.toLong() } +
            bundle.blueprints.sumOf { blueprint -> blueprint.actions.size.toLong() }
        violation("actions", actionCount, budget.maxActions)?.let { return it }

        val contextCount = bundle.profiles.sumOf { profile -> profile.contexts.size.toLong() } +
            bundle.blueprints.sumOf { blueprint -> blueprint.contexts.size.toLong() }
        violation("contexts", contextCount, budget.maxContexts)?.let { return it }

        val sceneElementCount = bundle.scenes.sumOf { scene -> scene.elements.size.toLong() }
        violation("scene elements", sceneElementCount, budget.maxSceneElements)?.let { return it }

        val stringBytes = bundle.aggregateStringBytes()
        return violation("aggregate string bytes", stringBytes, budget.maxAggregateStringBytes)
    }

    fun requireBundle(
        bundle: OpenTaskerBundle,
        budget: ImportResourceBudget = ImportResourceBudget.Default,
    ) {
        bundleViolation(bundle, budget)?.let { throw it }
    }

    private fun OpenTaskerBundle.aggregateStringBytes(): Long {
        var bytes = appVersion.utf8ByteLength()
        projects.forEach { project -> bytes += project.name.utf8ByteLength() }
        bytes += metadata.name.utf8ByteLength()
        bytes += metadata.description.utf8ByteLength()
        metadata.warnings.forEach { bytes += it.utf8ByteLength() }
        metadata.capabilityRequirements.forEach { requirement ->
            bytes += requirement.actionId.utf8ByteLength()
            bytes += requirement.reason.utf8ByteLength()
        }
        metadata.powerRequests.forEach { request ->
            bytes += request.taskName.utf8ByteLength()
            request.profileNames.forEach { bytes += it.utf8ByteLength() }
            request.actionIds.forEach { bytes += it.utf8ByteLength() }
            request.unknownActionIds.forEach { bytes += it.utf8ByteLength() }
            request.dataToExternalChains.forEach { chain ->
                bytes += chain.sourceActionId.utf8ByteLength()
                bytes += chain.sinkActionId.utf8ByteLength()
            }
        }
        tasks.forEach { task ->
            bytes += task.name.utf8ByteLength()
            task.actions.forEach { action -> bytes += action.aggregateStringBytes() }
        }
        profiles.forEach { profile ->
            bytes += profile.name.utf8ByteLength()
            bytes += profile.group?.utf8ByteLength() ?: 0L
            profile.contexts.forEach { context -> bytes += context.aggregateStringBytes() }
        }
        variables.forEach { variable ->
            bytes += variable.name.utf8ByteLength()
            bytes += variable.value.utf8ByteLength()
        }
        scenes.forEach { scene ->
            bytes += scene.name.utf8ByteLength()
            scene.elements.forEach { element -> bytes += element.aggregateStringBytes() }
        }
        blueprints.forEach { blueprint ->
            bytes += blueprint.id.utf8ByteLength()
            bytes += blueprint.title.utf8ByteLength()
            bytes += blueprint.summary.utf8ByteLength()
            bytes += blueprint.category.utf8ByteLength()
            bytes += blueprint.safetyNote.utf8ByteLength()
            blueprint.inputs.forEach { input ->
                bytes += input.key.utf8ByteLength()
                bytes += input.label.utf8ByteLength()
                bytes += input.defaultValue.utf8ByteLength()
                bytes += input.hint?.utf8ByteLength() ?: 0L
                bytes += input.section.utf8ByteLength()
            }
            blueprint.contexts.forEach { context -> bytes += context.aggregateStringBytes() }
            blueprint.actions.forEach { action ->
                bytes += action.type.utf8ByteLength()
                bytes += action.label.utf8ByteLength()
                action.args.forEach { (key, value) ->
                    bytes += key.utf8ByteLength()
                    bytes += value.utf8ByteLength()
                }
            }
        }
        invariants.forEach { invariant ->
            bytes += invariant.name.utf8ByteLength()
            bytes += invariant.guard.key.utf8ByteLength()
            bytes += invariant.guard.value.utf8ByteLength()
            bytes += invariant.forbiddenWriteKey.utf8ByteLength()
        }
        return bytes
    }

    private fun ActionSpec.aggregateStringBytes(): Long {
        var bytes = type.utf8ByteLength()
        bytes += label?.utf8ByteLength() ?: 0L
        bytes += condition?.utf8ByteLength() ?: 0L
        args.forEach { (key, value) ->
            bytes += key.utf8ByteLength()
            bytes += value.utf8ByteLength()
        }
        return bytes
    }

    private fun ContextSpec.aggregateStringBytes(): Long {
        var bytes = orGroup?.utf8ByteLength() ?: 0L
        config.forEach { (key, value) ->
            bytes += key.utf8ByteLength()
            bytes += value.utf8ByteLength()
        }
        return bytes
    }

    private fun com.opentasker.core.templates.TemplateContext.aggregateStringBytes(): Long {
        var bytes = type.name.utf8ByteLength()
        config.forEach { (key, value) ->
            bytes += key.utf8ByteLength()
            bytes += value.utf8ByteLength()
        }
        return bytes
    }

    private fun SceneElement.aggregateStringBytes(): Long {
        var bytes = 0L
        config.forEach { (key, value) ->
            bytes += key.utf8ByteLength()
            bytes += value.utf8ByteLength()
        }
        return bytes
    }
}

private class JsonBudgetScanner(
    private val source: String,
    private val budget: ImportResourceBudget,
    private val allowComments: Boolean,
) {
    private var index = 0
    private var depth = 0
    private var tokenCount = 0L
    private var stringBytes = 0L
    private var closed = false

    fun scan() {
        while (index < source.length) {
            when (val char = source[index]) {
                ' ', '\t', '\r', '\n' -> index++
                '"' -> {
                    requireSeparatorAfterClose(char)
                    countToken()
                    scanString()
                }
                '{', '[' -> {
                    requireSeparatorAfterClose(char)
                    countToken()
                    depth++
                    requireWithin("nesting depth", depth.toLong(), budget.maxNestingDepth.toLong())
                    index++
                }
                '}', ']' -> {
                    requireSeparatorAfterClose(char)
                    countToken()
                    depth--
                    closed = true
                    index++
                }
                ':', ',' -> {
                    requireSeparatorAfterClose(char)
                    countToken()
                    index++
                }
                '/' -> if (!(allowComments && scanComment())) {
                    requireSeparatorAfterClose(char)
                    scanBareToken()
                }
                else -> {
                    requireSeparatorAfterClose(char)
                    if (char.isWhitespace()) index++ else scanBareToken()
                }
            }
        }
    }

    /**
     * kotlinx keeps reading values after a `]` or `}` as more of the container it just closed, so
     * `[0][0]` nests while the depth count stays flat. Only `,`, `]` or `}` may follow a close; a
     * Unicode space counts as a value there, since kotlinx reads it as the start of one.
     */
    private fun requireSeparatorAfterClose(char: Char) {
        if (closed && char != ',' && char != ']' && char != '}') {
            throw IllegalArgumentException("Import JSON has a value right after a closing bracket.")
        }
        closed = false
    }

    private fun scanString() {
        index++
        var pendingHighSurrogate: Int? = null
        while (index < source.length) {
            val char = source[index++]
            if (char == '"') break
            val codeUnit = if (char == '\\' && index < source.length) {
                when (val escaped = source[index++]) {
                    '"', '\\', '/' -> escaped.code
                    'b' -> '\b'.code
                    'f' -> 12
                    'n' -> '\n'.code
                    'r' -> '\r'.code
                    't' -> '\t'.code
                    'u' -> readUnicodeEscape()
                    else -> escaped.code
                }
            } else {
                char.code
            }

            if (pendingHighSurrogate != null) {
                if (codeUnit in LOW_SURROGATE_RANGE) {
                    stringBytes += 4
                    pendingHighSurrogate = null
                    requireStringBytes()
                    continue
                }
                stringBytes += 3
                pendingHighSurrogate = null
            }
            when {
                codeUnit in HIGH_SURROGATE_RANGE -> pendingHighSurrogate = codeUnit
                codeUnit <= 0x7f -> stringBytes++
                codeUnit <= 0x7ff -> stringBytes += 2
                else -> stringBytes += 3
            }
            requireStringBytes()
        }
        if (pendingHighSurrogate != null) {
            stringBytes += 3
            requireStringBytes()
        }
    }

    private fun readUnicodeEscape(): Int {
        if (index + 4 > source.length) return 0xfffd
        var value = 0
        repeat(4) { offset ->
            val digit = source[index + offset].digitToIntOrNull(16) ?: return 0xfffd
            value = value * 16 + digit
        }
        index += 4
        return value
    }

    private fun scanComment(): Boolean {
        if (index + 1 >= source.length) return false
        return when (source[index + 1]) {
            '/' -> {
                index += 2
                while (index < source.length && source[index] != '\n') index++
                true
            }
            '*' -> {
                index += 2
                while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                index = (index + 2).coerceAtMost(source.length)
                true
            }
            else -> false
        }
    }

    private fun scanBareToken() {
        countToken()
        do {
            index++
        } while (index < source.length && !source[index].isJsonTokenBoundary())
    }

    private fun countToken() {
        tokenCount++
        requireWithin("JSON tokens", tokenCount, budget.maxJsonTokens)
    }

    private fun requireStringBytes() {
        requireWithin("aggregate string bytes", stringBytes, budget.maxAggregateStringBytes)
    }
}

private fun Char.isJsonTokenBoundary(): Boolean =
    isWhitespace() || this == '"' || this == '{' || this == '}' || this == '[' || this == ']' ||
        this == ':' || this == ',' || this == '/'

private fun String.utf8ByteLength(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.code <= 0x7f -> bytes++
            char.code <= 0x7ff -> bytes += 2
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index++
            }
            else -> bytes += 3
        }
        index++
    }
    return bytes
}

private fun violation(name: String, observed: Long, limit: Long): ImportBudgetExceededException? =
    if (observed > limit) ImportBudgetExceededException(name, observed, limit) else null

private fun requireWithin(name: String, observed: Long, limit: Long) {
    violation(name, observed, limit)?.let { throw it }
}

private val HIGH_SURROGATE_RANGE = 0xd800..0xdbff
private val LOW_SURROGATE_RANGE = 0xdc00..0xdfff
private val DOCTYPE_PATTERN = Regex("""<!\s*DOCTYPE\b""", RegexOption.IGNORE_CASE)
private val DOCTYPE_UNSAFE_PATTERN = Regex("""<!\s*ENTITY\b|\bSYSTEM\b|\bPUBLIC\b""", RegexOption.IGNORE_CASE)
