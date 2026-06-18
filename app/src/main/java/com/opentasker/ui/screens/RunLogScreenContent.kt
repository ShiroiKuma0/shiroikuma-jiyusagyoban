package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunLogActionDiagnostic
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.RunLogSource
import com.opentasker.core.engine.outcome
import com.opentasker.core.engine.toRunLogDiagnostics
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.RunLogRetentionOptions
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.displayLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RunLogScreenContent(
    logs: List<RunLogEntry>,
    tasks: List<Task>,
    retentionPolicy: RunLogRetentionPolicy,
    onRetentionPolicyChange: (RunLogRetentionPolicy) -> Unit,
    onShareDiagnostic: () -> Unit,
    contentPadding: PaddingValues,
) {
    var statusFilterOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val statusFilter = RunLogStatusFilter.entries.getOrElse(statusFilterOrdinal) { RunLogStatusFilter.All }
    var taskIdFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val taskOptions = remember(logs, tasks) { runLogTaskOptions(logs, tasks) }
    val filteredLogs = remember(logs, statusFilter, taskIdFilter, query) {
        filterRunLogs(logs, RunLogFilterState(status = statusFilter, taskId = taskIdFilter, query = query))
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (logs.isEmpty()) {
            item {
                InlineNotice(
                    title = "No execution history yet",
                    body = "Run log entries appear here when enabled profiles execute tasks. Current retention: ${retentionPolicy.displayLabel()}.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            item {
                RunLogSummaryCard(logs, onShareDiagnostic)
            }
        }
        item {
            RunLogRetentionCard(
                policy = retentionPolicy,
                onPolicyChange = onRetentionPolicyChange,
            )
        }
        if (logs.isNotEmpty()) {
            item {
                RunLogFilterCard(
                    totalCount = logs.size,
                    visibleCount = filteredLogs.size,
                    statusFilter = statusFilter,
                    onStatusFilterChange = { statusFilterOrdinal = it.ordinal },
                    taskOptions = taskOptions,
                    selectedTaskId = taskIdFilter,
                    onTaskFilterChange = { taskIdFilter = it },
                    query = query,
                    onQueryChange = { query = it },
                )
            }
        }
        if (logs.isNotEmpty() && filteredLogs.isEmpty()) {
            item {
                InlineNotice(
                    title = "No matching runs",
                    body = "Adjust the status filter or search text to review more execution history.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredLogs, key = { it.id }) { entry ->
            RunLogCard(entry)
        }
    }
}

@Composable
private fun RunLogRetentionCard(
    policy: RunLogRetentionPolicy,
    onPolicyChange: (RunLogRetentionPolicy) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Retention", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stored run history is pruned in the background. The Log tab loads the newest 100 entries for fast review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RunLogRetentionOptions.all.forEach { option ->
                    val selected = option.policy == policy
                    OutlinedButton(
                        onClick = { onPolicyChange(option.policy) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                                Text(option.label, style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunLogFilterCard(
    totalCount: Int,
    visibleCount: Int,
    statusFilter: RunLogStatusFilter,
    onStatusFilterChange: (RunLogStatusFilter) -> Unit,
    taskOptions: List<Pair<Long, String>>,
    selectedTaskId: Long?,
    onTaskFilterChange: (Long?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Find runs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$visibleCount of $totalCount shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (statusFilter != RunLogStatusFilter.All || selectedTaskId != null || query.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onStatusFilterChange(RunLogStatusFilter.All)
                            onTaskFilterChange(null)
                            onQueryChange("")
                        },
                    ) {
                        Text("Clear")
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    RunLogFilterChip(
                        label = "Any task",
                        selected = selectedTaskId == null,
                        onClick = { onTaskFilterChange(null) },
                    )
                }
                items(taskOptions, key = { it.first }) { (taskId, taskName) ->
                    RunLogFilterChip(
                        label = taskName,
                        selected = selectedTaskId == taskId,
                        onClick = { onTaskFilterChange(taskId) },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(RunLogStatusFilter.entries.toList(), key = { it.name }) { filter ->
                    RunLogFilterChip(
                        label = filter.label,
                        selected = statusFilter == filter,
                        onClick = { onStatusFilterChange(filter) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search task or message") },
                placeholder = { Text("permission, WiFi, task name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunLogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            } else {
                Color.Transparent
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
            },
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunLogSummaryCard(logs: List<RunLogEntry>, onShareDiagnostic: () -> Unit = {}) {
    val outcomes = remember(logs) { logs.map { it.outcome() } }
    val failures = outcomes.count { it == RunLogOutcome.Failed }
    val skipped = outcomes.count { it == RunLogOutcome.Skipped }
    val latest = logs.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Execution history", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Recent runs with duration and failure details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    when {
                        failures > 0 -> "$failures failed"
                        skipped > 0 -> "$skipped skipped"
                        else -> "Healthy"
                    },
                    when {
                        failures > 0 -> MaterialTheme.colorScheme.error
                        skipped > 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { SummaryMetric("${logs.size}", "Entries", Modifier.width(104.dp)) }
                item { SummaryMetric("${outcomes.count { it == RunLogOutcome.Succeeded }}", "Succeeded", Modifier.width(104.dp)) }
                item { SummaryMetric("$failures", "Failed", Modifier.width(104.dp)) }
                item { SummaryMetric("$skipped", "Skipped", Modifier.width(104.dp)) }
            }
            latest?.let {
                Text(
                    "Latest: ${it.taskName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onShareDiagnostic,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Share diagnostic report")
            }
        }
    }
}

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val diagnostics = remember(entry.message) { entry.message.toRunLogDiagnostics() }
    val hasStructuredDiagnostics = diagnostics.source != null || diagnostics.decision != null || diagnostics.traces.isNotEmpty()
    val outcome = remember(entry.success, entry.message) { entry.outcome() }
    val accent = when (outcome) {
        RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.primary
        RunLogOutcome.Failed -> MaterialTheme.colorScheme.error
        RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary
    }
    val sourceText = entry.source?.let { key ->
        val name = RunLogSource.displayName(key)
        entry.sourceLabel?.let { "$name: $it" } ?: name
    } ?: diagnostics.source
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (outcome) {
                RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                RunLogOutcome.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
            }
        ),
        border = BorderStroke(
            1.dp,
            when (outcome) {
                RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
                RunLogOutcome.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.30f)
                RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.34f)
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    when (outcome) {
                        RunLogOutcome.Succeeded -> Icons.Filled.CheckCircle
                        RunLogOutcome.Failed -> Icons.Filled.Error
                        RunLogOutcome.Skipped -> Icons.Filled.Info
                    },
                    contentDescription = when (outcome) {
                        RunLogOutcome.Succeeded -> "Succeeded"
                        RunLogOutcome.Failed -> "Failed"
                        RunLogOutcome.Skipped -> "Skipped"
                    },
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.taskName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sourceText?.let { source ->
                        Text(
                            "Source: $source",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill(outcome.label, accent) }
                item { StatusPill("${entry.durationMs} ms", accent) }
            }
            Column(Modifier.fillMaxWidth()) {
                if (hasStructuredDiagnostics && diagnostics.detailLines.isNotEmpty()) {
                    Text(
                        diagnostics.detailLines.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                diagnostics.reason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = accent)
                }
                if (diagnostics.traces.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        diagnostics.traces.take(4).forEach { trace ->
                            RunLogTraceRow(trace)
                        }
                        if (diagnostics.traces.size > 4) {
                            Text(
                                "${diagnostics.traces.size - 4} more action(s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (diagnostics.detailLines.isNotEmpty()) {
                    Text(
                        diagnostics.detailLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunLogTraceRow(trace: RunLogActionDiagnostic) {
    val color = when (trace.status) {
        ActionTraceStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ActionTraceStatus.FAILURE -> MaterialTheme.colorScheme.error
        ActionTraceStatus.TIMEOUT -> MaterialTheme.colorScheme.error
        ActionTraceStatus.SKIPPED -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(trace.status.readableName(), color)
        Column(Modifier.weight(1f)) {
            Text(
                "${trace.index + 1}. ${trace.label}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${trace.actionType} - ${trace.durationMs} ms - ${trace.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            trace.argumentSummary?.let { summary ->
                Text(
                    "Expanded: $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trace.templateWarningCount > 0) {
                Spacer(Modifier.height(4.dp))
                StatusPill(
                    "${trace.templateWarningCount} template warning${plural(trace.templateWarningCount)}",
                    MaterialTheme.colorScheme.error,
                )
            }
            if (trace.templateExpressions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    trace.templateExpressions.take(3).forEach { expression ->
                        Text(
                            "${expression.argName}: ${expression.expression} -> ${expression.value} (${expression.source})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        expression.warning?.let { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (trace.templateExpressions.size > 3) {
                        Text(
                            "${trace.templateExpressions.size - 3} more template expression(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun runLogTaskOptions(logs: List<RunLogEntry>, tasks: List<Task>): List<Pair<Long, String>> {
    val taskNames = tasks.associate { it.id to it.name }
    return logs
        .groupBy { it.taskId }
        .map { (taskId, entries) -> taskId to (taskNames[taskId] ?: entries.first().taskName) }
        .sortedWith(compareBy<Pair<Long, String>> { it.second.lowercase() }.thenBy { it.first })
}

private fun ActionTraceStatus.readableName(): String =
    name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun plural(count: Int): String = if (count == 1) "" else "s"
