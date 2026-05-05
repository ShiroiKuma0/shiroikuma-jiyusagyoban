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
        )

        val filtered = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Failed),
        )

        assertEquals(listOf("Commute"), filtered.map { it.taskName })
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
            log(taskName = "Morning", success = true, message = "WiFi connected"),
            log(taskName = "Commute", success = false, message = "WiFi permission missing"),
            log(taskName = "Bedtime", success = false, message = "DND denied"),
        )

        val filtered = filterRunLogs(
            logs,
            RunLogFilterState(status = RunLogStatusFilter.Failed, query = "wifi"),
        )

        assertEquals(listOf("Commute"), filtered.map { it.taskName })
    }

    private fun log(
        taskName: String,
        success: Boolean = true,
        message: String = "",
    ) = RunLogEntry(
        taskId = taskName.hashCode().toLong(),
        taskName = taskName,
        durationMs = 10,
        success = success,
        message = message,
    )
}
