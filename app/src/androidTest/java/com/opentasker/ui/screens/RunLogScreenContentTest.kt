package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class RunLogScreenContentTest {

    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

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
        composeTestRule.performAccessibilityChecks()
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
        composeTestRule.performAccessibilityChecks()
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
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onAllNodesWithText("Backup Task", substring = true).onFirst().assertIsDisplayed()
        // "Failed" shows on both the status pill and the per-task health summary.
        composeTestRule.onAllNodesWithText("Failed", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun pagedRunLogExposesLoadMoreAndBothExports() {
        var loadMoreClicks = 0
        var jsonClicks = 0
        var csvClicks = 0
        val entries = listOf(
            RunLogEntry(
                id = 1,
                taskId = 10,
                taskName = "Paged Task",
                durationMs = 10,
                success = true,
            ),
        )
        composeTestRule.setContent {
            OpenTaskerTheme {
                RunLogScreenContent(
                    logs = entries,
                    tasks = emptyList(),
                    totalCount = 101,
                    hasMore = true,
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    onLoadMore = { loadMoreClicks++ },
                    onExportJson = { jsonClicks++ },
                    onExportCsv = { csvClicks++ },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithTag(RUN_LOG_ACTIONS_TAG).performScrollToNode(hasText("JSON"))
        composeTestRule.onNodeWithText("JSON").performClick()
        composeTestRule.onNodeWithTag(RUN_LOG_ACTIONS_TAG).performScrollToNode(hasText("CSV"))
        composeTestRule.onNodeWithText("CSV").performClick()
        composeTestRule.onNodeWithTag(RUN_LOG_LIST_TAG).performScrollToNode(hasText("Load more"))
        composeTestRule.onNodeWithText("Load more").performClick()

        assertEquals(1, jsonClicks)
        assertEquals(1, csvClicks)
        assertEquals(1, loadMoreClicks)
    }

    @Test
    fun actionTracesCanExpandBeyondTheOldFourRowLimit() {
        val traces = (1..6).joinToString("\n") { index ->
            "$index. success: Action $index [log] 1ms - Completed"
        }
        val entry = RunLogEntry(
            id = 8,
            taskId = 10,
            taskName = "Trace Task",
            durationMs = 6,
            success = true,
            message = "Source: manual\n$traces",
        )
        composeTestRule.setContent {
            OpenTaskerTheme {
                RunLogScreenContent(
                    logs = listOf(entry),
                    tasks = emptyList(),
                    retentionPolicy = RunLogRetentionPolicy(),
                    onRetentionPolicyChange = {},
                    onShareDiagnostic = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithTag(RUN_LOG_LIST_TAG).performScrollToNode(hasText("Show all 6 actions"))
        composeTestRule.onNodeWithText("Show all 6 actions").performClick()
        composeTestRule.onNodeWithTag(RUN_LOG_LIST_TAG).performScrollToNode(hasText("6. Action 6", substring = true))
        composeTestRule.onNodeWithText("6. Action 6", substring = true).assertIsDisplayed()
    }
}
