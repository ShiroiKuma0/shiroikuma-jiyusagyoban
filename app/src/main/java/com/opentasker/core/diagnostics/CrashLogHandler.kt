package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build
import com.opentasker.app.BuildConfig
import com.opentasker.core.logging.AppLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashLogRecord(
    val fileName: String,
    val modifiedAtMillis: Long,
    val redactedContent: String,
)

object CrashLogHandler {
    private const val TAG = "CrashLogHandler"
    private const val MAX_CRASH_FILES = 5
    private const val CRASH_DIR = "crash_logs"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(context, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val dir = File(context.filesDir, CRASH_DIR)
            dir.mkdirs()
            pruneOldLogs(dir)

            val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            val file = File(dir, "crash-${dateFormat.format(Date())}.txt")

            file.bufferedWriter().use { writer ->
                writer.appendLine("=== OpenTasker Crash Log ===")
                writer.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}")
                writer.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.appendLine("Thread: ${thread.name}")
                writer.appendLine()
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                writer.append(stackTrace.take(MAX_CRASH_LOG_CHARS))
                if (stackTrace.length > MAX_CRASH_LOG_CHARS) writer.appendLine("\n[stack trace truncated]")
            }

            AppLogger.error(TAG, "Crash log written to ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to write crash log", e)
        }
    }

    private fun pruneOldLogs(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size >= MAX_CRASH_FILES) {
            files.drop(MAX_CRASH_FILES - 1).forEach { it.delete() }
        }
    }

    fun getLatestCrashLog(context: Context): String? {
        return listCrashLogs(context).firstOrNull()?.redactedContent
    }

    fun listCrashLogs(context: Context): List<CrashLogRecord> =
        listCrashLogFiles(File(context.filesDir, CRASH_DIR))

    internal fun listCrashLogFiles(directory: File): List<CrashLogRecord> =
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.name.endsWith(".txt")
        }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .take(MAX_CRASH_FILES)
            .mapNotNull { file ->
                runCatching {
                    CrashLogRecord(
                        fileName = file.name,
                        modifiedAtMillis = file.lastModified(),
                        redactedContent = DiagnosticExport.redactSensitive(readBounded(file)),
                    )
                }.getOrNull()
            }

    private fun readBounded(file: File): String = file.reader().use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(8_192)
        while (result.length < MAX_CRASH_LOG_CHARS) {
            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_CRASH_LOG_CHARS - result.length))
            if (count <= 0) break
            result.append(buffer, 0, count)
        }
        if (reader.read() != -1) result.append("\n[crash log truncated]")
        result.toString()
    }

    private const val MAX_CRASH_LOG_CHARS = 256 * 1024
}
