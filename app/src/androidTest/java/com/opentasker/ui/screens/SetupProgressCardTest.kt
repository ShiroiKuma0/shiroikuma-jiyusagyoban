package com.opentasker.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Rule
import org.junit.Test

class SetupProgressCardTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun setupDoesNotClaimReadyBeforeItHasReadAnyAccess() {
        // A-327: before the first permission read the empty row list counted as "0 of 0 ready",
        // a Ready pill and 0% for a moment on every open.
        composeTestRule.setContent {
            OpenTaskerTheme {
                SetupProgressCard(loaded = false, grantedCount = 0, requiredCount = 0)
            }
        }

        composeTestRule.onNodeWithText("Checking what's set up…").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Ready").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("0 of 0 ready").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("0%").assertCountEquals(0)
    }

    @Test
    fun onceReadTheCardCountsWhatItShows() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                SetupProgressCard(loaded = true, grantedCount = 2, requiredCount = 7)
            }
        }

        composeTestRule.onNodeWithText("2 of 7 ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("28%").assertIsDisplayed()
    }
}
