package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.opentasker.core.diagnostics.CrashLogRecord
import com.opentasker.core.diagnostics.EngineHealthStatus
import com.opentasker.core.logging.AppLogEntry
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.theme.OpenTaskerTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiagnosticsScreenTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun healthCrashesLogsAndTheShareAndCopyActionsAreReachable() {
        val shared = AtomicBoolean(false)
        val copied = AtomicBoolean(false)
        val state = DiagnosticsUiState(
            health = EngineHealthStatus(
                serviceRunning = true,
                lastHeartbeatAtMillis = 1_789_000_000_000L,
                activeForegroundServiceTypes = "special use",
                standbyBucket = "Active",
                standbyThrottled = false,
                advancedProtectionEnabled = false,
                exactAlarmStatus = "Exact allowed",
                lastMatcherError = null,
                lastMatcherErrorAtMillis = 0L,
                lastWorkerStopReason = "Not stopped",
            ),
            crashLogs = listOf(CrashLogRecord("crash-test.txt", 1_789_000_000_000L, "redacted crash")),
            appLogs = listOf(AppLogEntry(1_789_000_000_000L, AppLogger.Level.INFO, "Test", "engine ready")),
        )
        composeTestRule.setContent {
            OpenTaskerTheme {
                DiagnosticsScreen(
                    state = state,
                    contentPadding = PaddingValues(0.dp),
                    onRefresh = {},
                    onShare = { shared.set(true) },
                    onCopy = { copied.set(true) },
                )
            }
        }
        composeTestRule.performAccessibilityChecks()

        composeTestRule.onNodeWithText("Engine healthy").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Share redacted report").performClick()
        assertTrue(shared.get())
        composeTestRule.onNodeWithContentDescription("Copy redacted report").performClick()
        assertTrue(copied.get())
        composeTestRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("crash-test.txt", substring = true))
        composeTestRule.onNodeWithText("crash-test.txt", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("engine ready"))
        composeTestRule.onNodeWithText("engine ready").assertIsDisplayed()
    }
}
