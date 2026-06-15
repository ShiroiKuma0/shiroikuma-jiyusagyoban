package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.opentasker.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                writer.append(sw.toString())
            }

            Log.e(TAG, "Crash log written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
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
        val dir = File(context.filesDir, CRASH_DIR)
        return dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
            ?.readText()
    }
}
