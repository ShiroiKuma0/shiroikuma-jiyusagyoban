package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.diagnostics.EngineHealthStatus
import com.opentasker.core.diagnostics.HealthSignalState
import com.opentasker.core.diagnostics.assessment
import com.opentasker.core.diagnostics.healthy
import com.opentasker.core.engine.EngineExitCorrelation
import com.opentasker.core.engine.EngineExitCorrelationState
import com.opentasker.core.diagnostics.EngineHealthReader
import com.opentasker.core.logging.AppLogEntry
import com.opentasker.ui.theme.DesignSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val health = state.health
    val healthy = health?.healthy == true

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
    ) {
        item {
            DiagnosticSummaryCard(
                healthy = healthy,
                reason = health?.assessment?.reason,
                onRefresh = onRefresh,
                onShare = onShare,
            )
        }
        item {
            SectionTitle(stringResource(R.string.diagnostics_engine_health))
            EngineHealthCard(health, formatter)
        }
        item { SectionTitle(stringResource(R.string.diagnostics_crash_logs, state.crashLogs.size)) }
        if (state.crashLogs.isEmpty()) {
            item { EmptyDiagnosticCard(stringResource(R.string.diagnostics_no_crashes)) }
        } else {
            items(state.crashLogs, key = { it.fileName }) { crash ->
                DiagnosticRecordCard(
                    title = stringResource(
                        R.string.diagnostics_crash_record,
                        crash.fileName,
                        formatter.format(Date(crash.modifiedAtMillis)),
                    ),
                    body = crash.redactedContent.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                    accent = MaterialTheme.colorScheme.error,
                )
            }
        }
        item { SectionTitle(stringResource(R.string.diagnostics_app_log, state.appLogs.size)) }
        if (state.appLogs.isEmpty()) {
            item { EmptyDiagnosticCard(stringResource(R.string.diagnostics_no_app_logs)) }
        } else {
            // Keyed on the monotonic sequence: two identical log lines in the same
            // millisecond previously produced duplicate LazyColumn keys and crashed
            // this screen mid-diagnosis.
            items(state.appLogs.asReversed(), key = { it.sequence }) { entry ->
                AppLogCard(entry, formatter)
            }
        }
    }
}

@Composable
private fun DiagnosticSummaryCard(
    healthy: Boolean,
    reason: String?,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    val statusColor = if (healthy) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val statusLabel = stringResource(
        if (healthy) R.string.diagnostics_status_healthy else R.string.diagnostics_status_attention,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            },
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.40f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = statusLabel,
                    tint = statusColor,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.diagnostics_summary_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                    )
                    reason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh))
                }
            }
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.diagnostics_share))
                Text(stringResource(R.string.diagnostics_share), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun EngineHealthCard(health: EngineHealthStatus?, formatter: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HealthRow(
                stringResource(R.string.diagnostics_service),
                health?.let {
                    stringResource(
                        if (it.serviceRunning) R.string.diagnostics_service_running else R.string.diagnostics_service_stopped,
                    )
                } ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_last_heartbeat),
                health?.lastHeartbeatAtMillis?.takeIf { it > 0L }?.let { formatter.format(Date(it)) }
                    ?: stringResource(R.string.diagnostics_never),
            )
            HealthRow(
                stringResource(R.string.diagnostics_process_exit),
                health?.let { processExitSummary(it.processExitCorrelation, formatter) }
                    ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_fgs_types),
                health?.activeForegroundServiceTypes ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_standby_bucket),
                health?.standbyBucket ?: stringResource(R.string.diagnostics_loading),
            )
            if (health?.standbyThrottled == true) {
                Text(
                    stringResource(R.string.diagnostics_standby_throttled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HealthRow(
                stringResource(R.string.diagnostics_exact_alarm),
                health?.exactAlarmStatus ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_advanced_protection),
                health?.let {
                    stringResource(
                        if (it.advancedProtectionEnabled) R.string.diagnostics_advanced_protection_on
                        else R.string.diagnostics_advanced_protection_off,
                    )
                } ?: stringResource(R.string.diagnostics_loading),
            )
            if (health?.advancedProtectionEnabled == true) {
                Text(
                    stringResource(R.string.diagnostics_advanced_protection_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HealthRow(
                stringResource(R.string.diagnostics_matcher_error),
                health?.lastMatcherError ?: stringResource(R.string.diagnostics_none),
            )
            HealthRow(
                stringResource(R.string.diagnostics_worker_stop),
                health?.lastWorkerStopReason ?: stringResource(R.string.diagnostics_none),
            )
            HealthRow(
                stringResource(R.string.diagnostics_pending_jobs),
                health?.pendingScheduledJobReasons ?: stringResource(R.string.diagnostics_pending_jobs_none),
            )
            HealthRow(
                stringResource(R.string.diagnostics_active_executions),
                health?.activeExecutionCount?.toString() ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_pending_executions),
                health?.pendingExecutionCount?.toString() ?: stringResource(R.string.diagnostics_loading),
            )
            if (health?.signals?.isNotEmpty() == true) {
                Text(
                    stringResource(R.string.diagnostics_health_evidence),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                health.signals.forEach { signal ->
                    val color = when (signal.state) {
                        HealthSignalState.Ready -> MaterialTheme.colorScheme.tertiary
                        HealthSignalState.Loading -> MaterialTheme.colorScheme.secondary
                        HealthSignalState.Stale,
                        HealthSignalState.Error,
                        -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        stringResource(
                            R.string.diagnostics_health_signal,
                            signal.label,
                            stringResource(signal.state.resourceId),
                            signal.reason,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun processExitSummary(correlation: EngineExitCorrelation, formatter: SimpleDateFormat): String = when (correlation.state) {
    EngineExitCorrelationState.UNAVAILABLE -> stringResource(R.string.diagnostics_process_exit_unavailable)
    EngineExitCorrelationState.NO_GAP -> stringResource(R.string.diagnostics_process_exit_no_gap)
    EngineExitCorrelationState.NO_MATCH -> stringResource(
        R.string.diagnostics_process_exit_no_match,
        correlation.gapMillis?.let(EngineHealthReader::ageLabel) ?: stringResource(R.string.diagnostics_none),
    )
    EngineExitCorrelationState.MATCHED -> stringResource(
        R.string.diagnostics_process_exit_matched,
        correlation.reason ?: stringResource(R.string.diagnostics_none),
        correlation.description ?: stringResource(R.string.diagnostics_none),
        correlation.timestampMillis?.let { formatter.format(Date(it)) } ?: stringResource(R.string.diagnostics_never),
        correlation.gapMillis?.let(EngineHealthReader::ageLabel) ?: stringResource(R.string.diagnostics_none),
    )
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EmptyDiagnosticCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticRecordCard(title: String, body: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = CRASH_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppLogCard(entry: AppLogEntry, formatter: SimpleDateFormat) {
    val accent = when (entry.level) {
        com.opentasker.core.logging.AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error
        com.opentasker.core.logging.AppLogger.Level.WARN -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    DiagnosticRecordCard(
        title = stringResource(
            R.string.diagnostics_log_record,
            formatter.format(Date(entry.timestampMillis)),
            entry.level.name,
            entry.tag,
        ),
        body = entry.message,
        accent = accent,
    )
}

private const val CRASH_PREVIEW_LINES = 6

private val HealthSignalState.resourceId: Int
    get() = when (this) {
        HealthSignalState.Ready -> R.string.diagnostics_health_state_ready
        HealthSignalState.Loading -> R.string.diagnostics_health_state_loading
        HealthSignalState.Stale -> R.string.diagnostics_health_state_stale
        HealthSignalState.Error -> R.string.diagnostics_health_state_error
    }
