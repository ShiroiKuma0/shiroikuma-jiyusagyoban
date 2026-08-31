package com.opentasker.ui.screens

import com.opentasker.core.model.RunLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RunLogFiltersTest {
    @Test
    fun filtersLogsByFailureStatus() {
        val logs = listOf(
            log(taskName = "Morning", success = true),
            log(taskName = "Commute", success = false),
            log(taskName = "Bedtime", success = false, message = "Decision: Skipped"),
        )

        val filtered = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Failed),
        )

        assertEquals(listOf("Commute"), filtered.map { it.taskName })
    }

    @Test
    fun filtersSkippedLogsSeparatelyFromFailures() {
        val logs = listOf(
            log(taskName = "Morning", success = false, message = "Decision: Skipped\nReason: Cooldown active."),
            log(taskName = "Commute", success = false, message = "1. failure: Notify [notify.show] 3ms - Permission denied"),
            log(taskName = "Bedtime", success = true),
        )

        val skipped = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Skipped),
        )
        val failed = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Failed),
        )

        assertEquals(listOf("Morning"), skipped.map { it.taskName })
        assertEquals(listOf("Commute"), failed.map { it.taskName })
    }

    @Test
    fun filtersLogsByTaskNameOrMessageQuery() {
        val logs = listOf(
            log(taskName = "Morning", message = "Completed"),
            log(taskName = "Commute", message = "WiFi permission missing"),
            log(taskName = "Bedtime", message = "DND enabled"),
        )

        assertEquals(
            listOf("Commute"),
            filterRunLogs(logs, RunLogFilterState(query = "wifi")).map { it.taskName },
        )
        assertEquals(
            listOf("Bedtime"),
            filterRunLogs(logs, RunLogFilterState(query = "bed")).map { it.taskName },
        )
    }

    @Test
    fun combinesStatusAndQueryFilters() {
        val logs = listOf(
            log(taskId = 1, taskName = "Morning", success = true, message = "WiFi connected"),
            log(taskId = 2, taskName = "Commute", success = false, message = "WiFi permission missing"),
            log(taskId = 3, taskName = "Bedtime", success = false, message = "DND denied"),
        )

        val filtered = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Failed, query = "wifi"),
        )

        assertEquals(listOf("Commute"), filtered.map { it.taskName })
    }

    @Test
    fun filtersLogsByTaskId() {
        val logs = listOf(
            log(taskId = 10, taskName = "Morning", success = true),
            log(taskId = 11, taskName = "Morning", success = false),
            log(taskId = 10, taskName = "Morning", success = false, message = "Second run"),
        )

        val filtered = filterRunLogs(
            logs,
            RunLogFilterState(taskId = 10),
        )

        assertEquals(listOf("Morning", "Morning"), filtered.map { it.taskName })
        assertEquals(listOf(10L, 10L), filtered.map { it.taskId })
    }

    private fun log(
        taskName: String,
        taskId: Long = taskName.hashCode().toLong(),
        success: Boolean = true,
        message: String = "",
    ) = RunLogEntry(
        taskId = taskId,
        taskName = taskName,
        durationMs = 10,
        success = success,
        message = message,
    )
}
