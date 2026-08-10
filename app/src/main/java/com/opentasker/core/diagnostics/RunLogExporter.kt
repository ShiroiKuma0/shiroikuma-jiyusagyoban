package com.opentasker.core.diagnostics

import com.opentasker.core.engine.outcome
import com.opentasker.core.storage.RunLogDao
import com.opentasker.core.storage.RunLogEntity
import com.opentasker.core.storage.RunLogKey
import com.opentasker.core.storage.RunLogSnapshot
import com.opentasker.core.storage.key
import com.opentasker.core.storage.loadPage
import java.io.OutputStream
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RunLogExportFormat { JSON, CSV }

class RunLogExporter(private val dao: RunLogDao) {
    suspend fun export(
        snapshot: RunLogSnapshot,
        format: RunLogExportFormat,
        output: OutputStream,
    ): Int {
        val writer = output.writer(Charsets.UTF_8).buffered()
        var cursor: RunLogKey? = null
        var exported = 0
        var firstJsonRow = true
        if (format == RunLogExportFormat.JSON) writer.append("[\n") else writer.append(CSV_HEADER).append('\n')
        do {
            val page = dao.loadPage(snapshot, cursor, EXPORT_PAGE_SIZE)
            page.entries.forEach { entity ->
                val row = entity.toExportRow()
                when (format) {
                    RunLogExportFormat.JSON -> {
                        if (!firstJsonRow) writer.append(",\n")
                        writer.append("  ").append(JSON.encodeToString(row))
                        firstJsonRow = false
                    }
                    RunLogExportFormat.CSV -> writer.append(row.toCsv()).append('\n')
                }
                exported++
            }
            cursor = page.entries.lastOrNull()?.key()
        } while (page.hasMore && cursor != null)
        if (format == RunLogExportFormat.JSON) writer.append("\n]\n")
        writer.flush()
        return exported
    }

    private fun RunLogEntity.toExportRow(): RunLogExportRow = RunLogExportRow(
        id = id,
        taskId = taskId,
        taskName = ExportRedactionPolicy.redactText(taskName),
        timestamp = timestamp,
        timestampIso = Instant.ofEpochMilli(timestamp).toString(),
        durationMs = durationMs,
        outcome = toDomain().outcome().name.lowercase(),
        message = ExportRedactionPolicy.redactText(message),
        source = source?.let(ExportRedactionPolicy::redactText),
        sourceLabel = sourceLabel?.let(ExportRedactionPolicy::redactText),
        executionId = executionId,
        replayOf = replayOf,
        held = held,
        heldPolicy = heldPolicy?.let(ExportRedactionPolicy::redactText),
        starred = starred,
    )

    private fun RunLogExportRow.toCsv(): String = listOf(
        id.toString(),
        taskId.toString(),
        taskName,
        timestamp.toString(),
        timestampIso,
        durationMs.toString(),
        outcome,
        message,
        source.orEmpty(),
        sourceLabel.orEmpty(),
        executionId.orEmpty(),
        replayOf.orEmpty(),
        held.toString(),
        heldPolicy.orEmpty(),
        starred.toString(),
    ).joinToString(",", transform = ::csvCell)

    private fun csvCell(value: String): String {
        val spreadsheetSafe = if (value.trimStart().firstOrNull() in CSV_FORMULA_PREFIXES) "'$value" else value
        return "\"${spreadsheetSafe.replace("\"", "\"\"")}\""
    }

    private companion object {
        val JSON = Json { encodeDefaults = true; explicitNulls = true }
        const val EXPORT_PAGE_SIZE = 250
        const val CSV_HEADER = "id,task_id,task_name,timestamp_ms,timestamp_iso,duration_ms,outcome,message,source,source_label,execution_id,replay_of,held,held_policy,starred"
        val CSV_FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    }
}

@Serializable
private data class RunLogExportRow(
    val id: Long,
    val taskId: Long,
    val taskName: String,
    val timestamp: Long,
    val timestampIso: String,
    val durationMs: Long,
    val outcome: String,
    val message: String,
    val source: String?,
    val sourceLabel: String?,
    val executionId: String?,
    val replayOf: String?,
    val held: Boolean,
    val heldPolicy: String?,
    val starred: Boolean,
)
