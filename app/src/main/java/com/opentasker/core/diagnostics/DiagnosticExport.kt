package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build
import com.opentasker.app.BuildConfig
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

    private fun redactSensitive(text: String): String {
        return text
            .replace(Regex("""(?i)(password|secret|token|key|auth)\s*[:=]\s*\S+"""), "$1=[REDACTED]")
            .replace(Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b"""), "[REDACTED-CARD]")
    }
}
