package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.sysadmindoc.ruledeck.core.contexts.ContextEvent
import com.sysadmindoc.ruledeck.core.contexts.ContextEventObservation
import com.sysadmindoc.ruledeck.core.contexts.ContextInspectionSnapshot
import com.sysadmindoc.ruledeck.core.contexts.ContextSourceSnapshot
import com.sysadmindoc.ruledeck.core.contexts.ProfileInspection
import com.sysadmindoc.ruledeck.core.contexts.inspectProfiles
import com.sysadmindoc.ruledeck.core.model.ContextBooleanOperator
import com.sysadmindoc.ruledeck.core.model.ContextExpressionNode
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
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
            RuleDeckTheme { ContextSourceCard(source(setupReady = false, metadata = mapOf("foreground" to ""), matched = false), nowMs) }
        }

        composeTestRule.onAllNodesWithText("Needs setup").assertCountEquals(1)
        // No freshness pill beside "Needs setup", however old the reading is.
        listOf("No reading yet", "Fresh", "Stale", "Error").forEach { composeTestRule.onAllNodesWithText(it).assertCountEquals(0) }
        // The icon is decorative, so TalkBack reads the status once.
        composeTestRule.onAllNodes(hasText("Needs setup") or hasContentDescription("Needs setup")).assertCountEquals(1)
        composeTestRule.onNodeWithText("Matched: No").assertIsDisplayed()
        // A blank value has nothing to say, so its line is left out.
        composeTestRule.onAllNodes(hasText("Foreground", substring = true)).assertCountEquals(0)
        assertNoKeyValueText()
    }

    @Test
    fun aRunningSourceLabelsItsReadingAndValues() {
        composeTestRule.setContent {
            RuleDeckTheme {
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
    fun aWaitingSourceSaysSoOnce() {
        val waiting = ContextSourceSnapshot(key = "application", label = "Application", registered = true)
        composeTestRule.setContent { RuleDeckTheme { ContextSourceCard(waiting, nowMs) } }

        composeTestRule.onAllNodesWithText("Waiting").assertCountEquals(1)
        // A waiting source has no reading by definition, so a freshness pill only repeated that.
        composeTestRule.onAllNodesWithText("No reading yet").assertCountEquals(0)
    }

    @Test
    fun aProfilesDetailsReadAsWords() {
        val profile = Profile(
            id = 9,
            name = "Mail",
            enabled = true,
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.mail")),
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.chat")),
            ),
            contextExpression = ContextExpressionNode.group(
                ContextBooleanOperator.AND,
                listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
            ),
        )
        // Built the way the screen builds it, so the details show what the producers write.
        val inspection = inspectProfiles(listOf(profile), emptyList(), nowMs).single()
        composeTestRule.setContent {
            RuleDeckTheme { ProfileInspectorCard(inspection, nowMs, emptyList(), onSimulate = {}) }
        }

        composeTestRule.onNodeWithText("Show details").performClick()

        composeTestRule.onNodeWithText("Package: com.example.mail").assertIsDisplayed()
        composeTestRule.onNode(hasText("All of (Context 1", substring = true)).assertExists()
        assertNoKeyValueText()
    }

    @Test
    fun theSummaryCountsSourcesNeedingAttentionInWords() {
        val needsSetup = source(setupReady = false, metadata = emptyMap(), matched = false)
        composeTestRule.setContent {
            RuleDeckTheme {
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
            RuleDeckTheme {
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
            RuleDeckTheme { ProfileInspectorCard(profile("Wi-Fi is not connected", suppressionReason = null), nowMs, emptyList(), onSimulate = {}) }
        }

        composeTestRule.onNodeWithText("Not matching").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Blocked").assertCountEquals(0)
    }

    @Test
    fun aSuppressedProfileStillReadsBlocked() {
        composeTestRule.setContent {
            RuleDeckTheme { ProfileInspectorCard(profile("Paused", suppressionReason = "Paused until tomorrow"), nowMs, emptyList(), onSimulate = {}) }
        }

        composeTestRule.onNodeWithText("Blocked").assertIsDisplayed()
        // The icon once told TalkBack "Not matching" beside this pill, then repeated "Blocked". It's
        // decorative now, so the status is read once and can't disagree with the pill.
        composeTestRule.onAllNodes(hasText("Blocked") or hasContentDescription("Blocked")).assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Not matching").assertCountEquals(0)
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
