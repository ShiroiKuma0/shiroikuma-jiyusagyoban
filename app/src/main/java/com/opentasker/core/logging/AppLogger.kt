package com.opentasker.core.logging

import android.util.Log
import java.util.ArrayDeque

data class AppLogEntry(
    val timestampMillis: Long,
    val level: AppLogger.Level,
    val tag: String,
    val message: String,
    /** Monotonic per-process id; timestamps alone are not unique for burst logging. */
    val sequence: Long = 0,
)

/** Android logging plus a bounded process-local ring for in-app diagnostics. */
object AppLogger {
    private const val DEFAULT_TAG = "OpenTasker"
    internal const val MAX_BUFFERED_ENTRIES = 300

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    @Volatile private var minimumLevel = Level.DEBUG
    private val ring = ArrayDeque<AppLogEntry>(MAX_BUFFERED_ENTRIES)
    private var nextSequence = 0L

    fun setMinimumLevel(level: Level) {
        minimumLevel = level
    }

    fun debug(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) =
        emit(Level.DEBUG, tag, message, throwable)

    fun info(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) =
        emit(Level.INFO, tag, message, throwable)

    fun warn(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) =
        emit(Level.WARN, tag, message, throwable)

    fun error(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) =
        emit(Level.ERROR, tag, message, throwable)

    fun snapshot(): List<AppLogEntry> = synchronized(ring) { ring.toList() }

    private fun emit(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level < minimumLevel) return
        val bufferedMessage = buildString {
            append(message)
            throwable?.let { error ->
                append(" (")
                append(error::class.java.simpleName)
                error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                append(')')
            }
        }.take(MAX_BUFFERED_MESSAGE_CHARS)
        synchronized(ring) {
            while (ring.size >= MAX_BUFFERED_ENTRIES) ring.removeFirst()
            ring.addLast(
                AppLogEntry(
                    System.currentTimeMillis(),
                    level,
                    tag.take(MAX_TAG_CHARS),
                    bufferedMessage,
                    sequence = nextSequence++,
                ),
            )
        }
        // android.util.Log is unavailable in host JVM tests; the diagnostic ring remains testable.
        runCatching {
            when (level) {
                Level.DEBUG -> if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
                Level.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
                Level.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
                Level.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            }
        }
    }

    fun logExecution(tag: String, operation: String, durationMs: Long, success: Boolean = true) {
        val status = if (success) "OK" else "FAILED"
        info(tag, "$operation completed in ${durationMs}ms [$status]")
    }

    fun logStructured(tag: String, level: Level, message: String, data: Map<String, Any> = emptyMap()) {
        val dataText = if (data.isEmpty()) "" else {
            " | " + data.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        emit(level, tag, message + dataText, null)
    }

    internal fun clearForTest() {
        synchronized(ring) { ring.clear() }
        minimumLevel = Level.DEBUG
    }

    private const val MAX_BUFFERED_MESSAGE_CHARS = 2_000
    private const val MAX_TAG_CHARS = 64
}
