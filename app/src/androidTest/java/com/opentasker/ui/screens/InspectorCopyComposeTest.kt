package com.opentasker.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextInspectionSnapshot
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.ProfileInspection
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Rule
import org.junit.Test

/**
 * The Inspector reads as plain words (A-340): no key=value lines, one status pill on a source that
 * needs setup, and "Blocked" only for a profile something refuses to run.
 */
class InspectorCopyComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val nowMs = 1_700_000_000_000L

    private fun source(setupReady: Boolean, metadata: Map<String, String>, matched: Boolean) = ContextSourceSnapshot(
        key = "application",
        label = "Application",
        registered = true,
        setupReady = setupReady,
        setupDetail = if (setupReady) null else "Allow usage access",
        lastObservation = ContextEventObservation(ContextEvent("application", matched, metadata), observedAtMs = nowMs),
    )

    private fun assertNoKeyValueText() {
        composeTestRule.onAllNodes(hasText("=", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aSourceThatNeedsSetupShowsOnePill() {
        composeTestRule.setContent {
            OpenTaskerTheme { ContextSourceCard(source(setupReady = false, metadata = mapOf("foreground" to ""), matched = false), nowMs) }
        }

        composeTestRule.onAllNodesWithText("Needs setup").assertCountEquals(1)
        // The freshness pill said "Ready" here before, beside "Needs setup".
        composeTestRule.onAllNodesWithText("Ready").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Fresh").assertCountEquals(0)
        composeTestRule.onNodeWithText("Matched: No").assertIsDisplayed()
        // A blank value has nothing to say, so its line is left out.
        composeTestRule.onAllNodes(hasText("Foreground", substring = true)).assertCountEquals(0)
        assertNoKeyValueText()
    }

    @Test
    fun aRunningSourceLabelsItsReadingAndValues() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextSourceCard(
                    source(setupReady = true, metadata = mapOf("foreground" to "com.example.mail", "battery_level" to "80"), matched = true),
                    nowMs,
                )
            }
        }

        composeTestRule.onNodeWithText("Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fresh").assertIsDisplayed()
        composeTestRule.onNodeWithText("Matched: Yes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Foreground: com.example.mail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery level: 80").assertIsDisplayed()
        assertNoKeyValueText()
    }

    @Test
    fun theSummaryCountsSourcesNeedingAttentionInWords() {
        val needsSetup = source(setupReady = false, metadata = emptyMap(), matched = false)
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextInspectorSummaryCard(
                    snapshot = ContextInspectionSnapshot(generatedAtMs = nowMs, sources = listOf(needsSetup, needsSetup.copy(key = "wifi")), profiles = emptyList()),
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 need attention").assertIsDisplayed()
    }

    @Test
    fun oneSourceNeedingAttentionIsSingular() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextInspectorSummaryCard(
                    snapshot = ContextInspectionSnapshot(
                        generatedAtMs = nowMs,
                        sources = listOf(source(setupReady = false, metadata = emptyMap(), matched = false)),
                        profiles = emptyList(),
                    ),
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("1 needs attention").assertIsDisplayed()
    }

    @Test
    fun aProfileWhoseContextsDontHoldIsNotMatchingNotBlocked() {
        composeTestRule.setContent {
            OpenTaskerTheme { ProfileInspectorCard(profile("Wi-Fi is not connected", suppressionReason = null), nowMs, emptyList(), onSimulate = {}) }
        }

        composeTestRule.onNodeWithText("Not matching").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Blocked").assertCountEquals(0)
    }

    @Test
    fun aSuppressedProfileStillReadsBlocked() {
        composeTestRule.setContent {
            OpenTaskerTheme { ProfileInspectorCard(profile("Paused", suppressionReason = "Paused until tomorrow"), nowMs, emptyList(), onSimulate = {}) }
        }

        composeTestRule.onNodeWithText("Blocked").assertIsDisplayed()
    }

    private fun profile(summary: String, suppressionReason: String?) = ProfileInspection(
        profileId = 4,
        profileName = "Office",
        enabled = true,
        matching = false,
        summary = summary,
        contexts = emptyList(),
        suppressionReason = suppressionReason,
    )
}
