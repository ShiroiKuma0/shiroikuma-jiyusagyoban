package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import com.sysadmindoc.ruledeck.core.storage.RunLogRetentionPolicy
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import org.junit.Rule
import org.junit.Test

class RunLogRetentionPreviewDialogTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    /**
     * A shorter policy also drops held runs past its smaller held cap. The preview used to count
     * ordinary rows only, so it named none of them, or didn't open at all when only held rows went.
     */
    @Test
    fun heldRunsTheChangeWouldDropAreCountedAndNamed() {
        composeTestRule.setContent {
            RuleDeckTheme {
                RunLogRetentionPreviewDialog(
                    preview = RunLogRetentionPreview(
                        policy = RunLogRetentionPolicy(maxEntries = 250, maxAgeDays = 7),
                        storedCount = 302,
                        prunableCount = 0,
                        oldestTimestamp = null,
                        heldPrunableCount = 275,
                    ),
                    onDismiss = {},
                    onExportJson = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithText("permanently remove 275 run log entries", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("That includes 275 held runs you could still replay.", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPreviewWithNoHeldRunsSaysNothingAboutThem() {
        composeTestRule.setContent {
            RuleDeckTheme {
                RunLogRetentionPreviewDialog(
                    preview = RunLogRetentionPreview(
                        policy = RunLogRetentionPolicy(),
                        storedCount = 10,
                        prunableCount = 2,
                        oldestTimestamp = null,
                    ),
                    onDismiss = {},
                    onExportJson = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithText("permanently remove 2 run log entries", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("held runs", substring = true).assertCountEquals(0)
    }
}
