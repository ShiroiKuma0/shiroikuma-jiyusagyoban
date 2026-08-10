package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build
import com.opentasker.app.BuildConfig
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExport {

    suspend fun buildReport(context: Context, db: AppDatabase): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)

        sb.appendLine("=== OpenTasker Diagnostic Report ===")
        sb.appendLine("Generated: ${dateFormat.format(Date(now))}")
        sb.appendLine()

        sb.appendLine("--- App ---")
        sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Distribution: ${BuildConfig.DISTRIBUTION}")
        sb.appendLine("Debug: ${BuildConfig.DEBUG}")
        sb.appendLine()

        sb.appendLine("--- Device ---")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        sb.appendLine("--- Engine Health ---")
        try {
            val health = EngineHealthReader.read(context, now)
            sb.appendLine("Overall state: ${health.assessment.state}")
            sb.appendLine("Overall reason: ${redactSensitive(health.assessment.reason)}")
            sb.appendLine("Service running: ${health.serviceRunning}")
            sb.appendLine("Last heartbeat: ${formatTimestamp(health.lastHeartbeatAtMillis, dateFormat)}")
            sb.appendLine("Foreground service types: ${health.activeForegroundServiceTypes}")
            sb.appendLine("Standby bucket: ${health.standbyBucket}")
            sb.appendLine("Exact alarm: ${health.exactAlarmStatus}")
            sb.appendLine("Process exit correlation: ${health.processExitCorrelation.state}")
            sb.appendLine("Process exit reason: ${redactSensitive(health.processExitCorrelation.reason ?: "none")}")
            sb.appendLine("Process exit description: ${redactSensitive(health.processExitCorrelation.description ?: "none")}")
            sb.appendLine("Process exit timestamp: ${formatTimestamp(health.processExitCorrelation.timestampMillis ?: 0L, dateFormat)}")
            sb.appendLine("Heartbeat gap: ${health.processExitCorrelation.gapMillis?.let(EngineHealthReader::ageLabel) ?: "none"}")
            sb.appendLine("Last matcher error: ${health.lastMatcherError ?: "none"}")
            sb.appendLine("Last worker stop reason: ${health.lastWorkerStopReason ?: "none"}")
            sb.appendLine("Active executions: ${health.activeExecutionCount}")
            sb.appendLine("Pending external executions: ${health.pendingExecutionCount}")
            health.signals.forEach { signal ->
                sb.appendLine("Health signal ${signal.key}: ${signal.state} — ${redactSensitive(signal.reason)}")
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read engine health: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("--- In-App Log (recent 100) ---")
        AppLogger.snapshot().takeLast(100).forEach { entry ->
            val time = dateFormat.format(Date(entry.timestampMillis))
            sb.appendLine("  [$time] ${entry.level} ${entry.tag}: ${redactSensitive(entry.message)}")
        }
        sb.appendLine()

        sb.appendLine("--- Crash Logs ---")
        val crashes = CrashLogHandler.listCrashLogs(context)
        if (crashes.isEmpty()) {
            sb.appendLine("  none")
        } else {
            crashes.forEach { crash ->
                sb.appendLine("  === ${crash.fileName} (${dateFormat.format(Date(crash.modifiedAtMillis))}) ===")
                sb.appendLine(crash.redactedContent.take(MAX_EXPORTED_CRASH_CHARS))
                if (crash.redactedContent.length > MAX_EXPORTED_CRASH_CHARS) sb.appendLine("[export excerpt truncated]")
            }
        }
        sb.appendLine()

        sb.appendLine("--- Run Log (recent 50) ---")
        try {
            val logs = db.runLogDao().getRecent().take(50)
            val logCount = db.runLogDao().count()
            sb.appendLine("Total entries: $logCount")
            for (entry in logs) {
                val time = dateFormat.format(Date(entry.timestamp))
                val status = if (entry.success) "OK" else "FAIL"
                val source = entry.sourceLabel ?: entry.source ?: ""
                sb.appendLine("  [$time] $status ${entry.taskName} (${entry.durationMs}ms) $source")
                val firstLine = entry.message.lineSequence().firstOrNull()?.take(200) ?: ""
                if (firstLine.isNotBlank()) {
                    sb.appendLine("    ${redactSensitive(firstLine)}")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read run log: ${e.message})")
        }
        sb.appendLine()

        sb.appendLine("--- Permissions ---")
        val pm = context.packageManager
        try {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: emptyArray()
            val granted = info.requestedPermissionsFlags ?: intArrayOf()
            for (i in requested.indices) {
                val perm = requested[i].substringAfterLast('.')
                val isGranted = (granted.getOrElse(i) { 0 } and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                sb.appendLine("  $perm: ${if (isGranted) "granted" else "denied"}")
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read permissions: ${e.message})")
        }
        sb.appendLine()

        sb.appendLine("=== End Report ===")
        return sb.toString()
    }

    internal fun redactSensitive(text: String): String {
        return text
            .replace(Regex("""(?i)\bauthorization\s*:\s*[^\r\n]+"""), "Authorization: [REDACTED]")
            .replace(Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+"""), "Bearer [REDACTED]")
            .replace(Regex("""(?i)(password|secret|token|key|auth)\s*[:=]\s*\S+"""), "$1=[REDACTED]")
            .replace(Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b"""), "[REDACTED-CARD]")
    }

    private fun formatTimestamp(timestampMillis: Long, dateFormat: SimpleDateFormat): String =
        if (timestampMillis <= 0L) "never" else dateFormat.format(Date(timestampMillis))

    private const val MAX_EXPORTED_CRASH_CHARS = 32 * 1024
}
