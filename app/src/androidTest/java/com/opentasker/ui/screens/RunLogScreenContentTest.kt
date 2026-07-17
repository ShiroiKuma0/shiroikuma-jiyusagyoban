package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Rule
import org.junit.Test

class RunLogScreenContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyRunLogShowsEmptyState() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                RunLogScreenContent(
                    logs = emptyList(),
                    tasks = emptyList(),
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.onNodeWithText("No run log entries", substring = true).assertIsDisplayed()
    }

    @Test
    fun runLogWithEntriesShowsTaskName() {
        val entries = listOf(
            RunLogEntry(
                id = 1,
                taskId = 10,
                taskName = "Morning Routine",
                timestamp = System.currentTimeMillis(),
                durationMs = 1200,
                success = true,
                message = "All actions completed",
            ),
        )
        val tasks = listOf(Task(id = 10, name = "Morning Routine", priority = 5, actions = emptyList()))

        composeTestRule.setContent {
            OpenTaskerTheme {
                RunLogScreenContent(
                    logs = entries,
                    tasks = tasks,
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        // The task name appears in both the per-task summary ("Latest: …") and the entry card,
        // so assert the first displayed node rather than requiring a single match.
        composeTestRule.onAllNodesWithText("Morning Routine", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun runLogShowsFailedEntryStatus() {
        val entries = listOf(
            RunLogEntry(
                id = 2,
                taskId = 20,
                taskName = "Backup Task",
                timestamp = System.currentTimeMillis(),
                durationMs = 500,
                success = false,
                message = "Permission denied",
            ),
        )

        composeTestRule.setContent {
            OpenTaskerTheme {
                RunLogScreenContent(
                    logs = entries,
                    tasks = emptyList(),
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.onAllNodesWithText("Backup Task", substring = true).onFirst().assertIsDisplayed()
        // "Failed" shows on both the status pill and the per-task health summary.
        composeTestRule.onAllNodesWithText("Failed", substring = true).onFirst().assertIsDisplayed()
    }
}
