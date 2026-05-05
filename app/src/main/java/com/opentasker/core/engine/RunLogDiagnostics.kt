package com.opentasker.core.engine

import com.opentasker.core.model.RunLogEntry

enum class RunLogOutcome(val label: String) {
    Succeeded("Succeeded"),
    Failed("Failed"),
    Skipped("Skipped"),
}

data class RunLogDiagnostics(
    val source: String? = null,
    val decision: String? = null,
    val reason: String? = null,
    val traces: List<RunLogActionDiagnostic> = emptyList(),
    val detailLines: List<String> = emptyList(),
) {
    val isSkipped: Boolean
        get() = decision.equals(SKIPPED_DECISION, ignoreCase = true)
}

data class RunLogActionDiagnostic(
    val index: Int,
    val status: ActionTraceStatus,
    val label: String,
    val actionType: String,
    val durationMs: Long,
    val message: String,
    val argumentSummary: String? = null,
    val templateWarningCount: Int = 0,
    val templateExpressions: List<RunLogTemplateDiagnostic> = emptyList(),
)

data class RunLogTemplateDiagnostic(
    val argName: String,
    val source: String,
    val expression: String,
    val value: String,
    val warning: String? = null,
)

fun RunLogEntry.outcome(): RunLogOutcome {
    val diagnostics = message.toRunLogDiagnostics()
    return when {
        diagnostics.isSkipped -> RunLogOutcome.Skipped
        success -> RunLogOutcome.Succeeded
        else -> RunLogOutcome.Failed
    }
}

fun runLogMessage(
    source: String,
    metadata: List<String> = emptyList(),
    traces: List<ActionExecutionTrace> = emptyList(),
): String = buildList {
    add("Source: ${source.trim()}")
    metadata
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
    addAll(traces.toRunLogMessage().lines())
}.joinToString("\n")

fun skippedRunLogMessage(
    source: String,
    reason: String,
    metadata: List<String> = emptyList(),
): String = buildList {
    add("Source: ${source.trim()}")
    add("Decision: $SKIPPED_DECISION")
    add("Reason: ${reason.trim()}")
    metadata
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(::add)
}.joinToString("\n")

fun String.toRunLogDiagnostics(): RunLogDiagnostics {
    if (isBlank()) return RunLogDiagnostics()

    var source: String? = null
    var decision: String? = null
    var reason: String? = null
    val traces = mutableListOf<RunLogActionDiagnostic>()
    val details = mutableListOf<String>()

    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            when {
                line == LEGACY_EXTERNAL_SOURCE -> source = LEGACY_EXTERNAL_SOURCE
                line.startsWith(SOURCE_PREFIX, ignoreCase = true) -> source = line.valueAfterPrefix(SOURCE_PREFIX)
                line.startsWith(DECISION_PREFIX, ignoreCase = true) -> decision = line.valueAfterPrefix(DECISION_PREFIX)
                line.startsWith(REASON_PREFIX, ignoreCase = true) -> reason = line.valueAfterPrefix(REASON_PREFIX)
                line.startsWith(TEMPLATE_TRACE_PREFIX, ignoreCase = true) -> {
                    val template = parseTemplateTraceLine(line)
                    if (template != null && traces.isNotEmpty()) {
                        val previous = traces.removeAt(traces.lastIndex)
                        traces += previous.copy(templateExpressions = previous.templateExpressions + template)
                    } else {
                        details.add(line)
                    }
                }
                else -> parseTraceLine(line)?.let(traces::add) ?: details.add(line)
            }
        }

    return RunLogDiagnostics(
        source = source?.takeIf { it.isNotBlank() },
        decision = decision?.takeIf { it.isNotBlank() },
        reason = reason?.takeIf { it.isNotBlank() },
        traces = traces,
        detailLines = details,
    )
}

private fun String.valueAfterPrefix(prefix: String): String =
    substring(prefix.length).trim()

private fun parseTraceLine(line: String): RunLogActionDiagnostic? {
    val match = tracePattern.matchEntire(line) ?: return null
    val status = runCatching { ActionTraceStatus.valueOf(match.groupValues[2].uppercase()) }.getOrNull()
        ?: return null
    val parsedMessage = parseTraceMessage(match.groupValues[6])
    return RunLogActionDiagnostic(
        index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null,
        status = status,
        label = match.groupValues[3],
        actionType = match.groupValues[4],
        durationMs = match.groupValues[5].toLongOrNull() ?: return null,
        message = parsedMessage.message,
        argumentSummary = parsedMessage.argumentSummary,
        templateWarningCount = parsedMessage.templateWarningCount,
    )
}

private fun parseTraceMessage(message: String): ParsedTraceMessage {
    val detailStart = message.lastIndexOf(" (")
    if (detailStart == -1 || !message.endsWith(")")) {
        return ParsedTraceMessage(message)
    }

    val details = message.substring(detailStart + 2, message.length - 1)
    val segments = details.split(";").map { it.trim() }.filter { it.isNotBlank() }
    if (segments.none { it.startsWith(ARGUMENTS_DETAIL_PREFIX) || it.startsWith(TEMPLATE_WARNINGS_DETAIL_PREFIX) }) {
        return ParsedTraceMessage(message)
    }

    val argumentSummary = segments
        .firstOrNull { it.startsWith(ARGUMENTS_DETAIL_PREFIX) }
        ?.substring(ARGUMENTS_DETAIL_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val warningCount = segments
        .firstOrNull { it.startsWith(TEMPLATE_WARNINGS_DETAIL_PREFIX) }
        ?.substring(TEMPLATE_WARNINGS_DETAIL_PREFIX.length)
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    return ParsedTraceMessage(
        message = message.substring(0, detailStart),
        argumentSummary = argumentSummary,
        templateWarningCount = warningCount,
    )
}

private fun parseTemplateTraceLine(line: String): RunLogTemplateDiagnostic? {
    val parts = line.split('\t', limit = TEMPLATE_TRACE_SPLIT_LIMIT)
    if (parts.size < TEMPLATE_TRACE_MIN_FIELD_COUNT || !parts.first().equals(TEMPLATE_TRACE_PREFIX, ignoreCase = true)) {
        return null
    }
    return RunLogTemplateDiagnostic(
        argName = parts[1].trim().takeIf { it.isNotBlank() } ?: return null,
        source = parts[2].trim().takeIf { it.isNotBlank() } ?: return null,
        expression = parts[3].trim(),
        value = parts[4].trim(),
        warning = parts.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() },
    )
}

private data class ParsedTraceMessage(
    val message: String,
    val argumentSummary: String? = null,
    val templateWarningCount: Int = 0,
)

private val tracePattern = Regex("""^(\d+)\. ([a-z]+): (.*?) \[([^]]+)] (\d+)ms - (.*)$""")
private const val SOURCE_PREFIX = "Source:"
private const val DECISION_PREFIX = "Decision:"
private const val REASON_PREFIX = "Reason:"
private const val ARGUMENTS_DETAIL_PREFIX = "args:"
private const val TEMPLATE_WARNINGS_DETAIL_PREFIX = "template warnings:"
private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val TEMPLATE_TRACE_MIN_FIELD_COUNT = 5
private const val TEMPLATE_TRACE_SPLIT_LIMIT = 6
private const val SKIPPED_DECISION = "Skipped"
private const val LEGACY_EXTERNAL_SOURCE = "External intent"
