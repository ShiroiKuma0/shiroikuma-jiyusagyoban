package com.opentasker.core.logging

import android.util.Log

/**
 * Structured logging framework with levels: DEBUG, INFO, WARN, ERROR.
 * Uses android.util.Log under the hood with consistent tag-based filtering.
 */
object AppLogger {
    private const val DEFAULT_TAG = "OpenTasker"
    
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    // Can be configured to filter logs
    private var minimumLevel = Level.DEBUG
    
    fun setMinimumLevel(level: Level) {
        minimumLevel = level
    }
    
    fun debug(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (minimumLevel <= Level.DEBUG) {
            if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
        }
    }
    
    fun info(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (minimumLevel <= Level.INFO) {
            if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
        }
    }
    
    fun warn(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (minimumLevel <= Level.WARN) {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }
    
    fun error(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (minimumLevel <= Level.ERROR) {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }
    
    /**
     * Log execution timing for performance monitoring.
     */
    fun logExecution(tag: String, operation: String, durationMs: Long, success: Boolean = true) {
        val status = if (success) "OK" else "FAILED"
        info(tag, "$operation completed in ${durationMs}ms [$status]")
    }
    
    /**
     * Log with structured data for easier analysis.
     */
    fun logStructured(tag: String, level: Level, message: String, data: Map<String, Any> = emptyMap()) {
        val dataStr = if (data.isNotEmpty()) {
            " | " + data.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else ""
        
        val fullMessage = message + dataStr
        when (level) {
            Level.DEBUG -> debug(tag, fullMessage)
            Level.INFO -> info(tag, fullMessage)
            Level.WARN -> warn(tag, fullMessage)
            Level.ERROR -> error(tag, fullMessage)
        }
    }
}
