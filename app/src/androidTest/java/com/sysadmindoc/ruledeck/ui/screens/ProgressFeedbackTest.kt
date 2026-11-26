package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.ruledeck.StartupPreparingScreen
import com.sysadmindoc.ruledeck.StartupStage
import com.sysadmindoc.ruledeck.core.engine.PreflightInputs
import com.sysadmindoc.ruledeck.core.engine.PreflightReport
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Long operations show that they're moving and, where it's safe, can be stopped (A-361). */
class ProgressFeedbackTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun aPreflightRerunShowsABarAndCloseBecomesStop() {
        var busy by mutableStateOf(true)
        var stops = 0
        var dismissals = 0
        composeTestRule.setContent {
            RuleDeckTheme {
                PreflightReviewDialog(
                    state = PreflightReviewState(
                        target = PreflightTarget.TaskTarget(Task(id = 1L, name = "Porch lights")),
                        inputs = PreflightInputs(),
                        report = PreflightReport(title = "Porch lights is ready"),
                    ),
                    busy = busy,
                    onDismiss = { dismissals++ },
                    onRerun = {},
                    onStop = { stops++ },
                )
            }
        }

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeTestRule.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
        assertEquals(0, dismissals)

        busy = false
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissals)
    }

    @Test
    fun aRunningBackupShowsABarAndOffersNoStop() {
        composeTestRule.setContent {
            RuleDeckTheme {
                BackupSetupCard(
                    state = BackupSetupState(busy = true),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    onCancelPendingRestore = {},
                    onReviewRestoreRollback = {},
                    onSnapshotPolicyChanged = {},
                    onSnapshotDestinationSelected = { _, _, _ -> },
                    initiallyExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Working…").assertIsDisplayed()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeTestRule.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Test
    fun anImportThatKnowsItsStepsShowsHowFarItIs() {
        composeTestRule.setContent {
            RuleDeckTheme {
                TransferProgressRow(TransferProgress(TransferStage.Decode, stepFraction(FILE_PREVIEW_STEPS, TransferStage.Decode)))
            }
        }

        composeTestRule.onNodeWithText("Reading it").assertIsDisplayed()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()
    }

    @Test
    fun theLaunchScreenSaysWhatItIsWaitingFor() {
        var stage by mutableStateOf(StartupStage.Restoring)
        composeTestRule.setContent {
            RuleDeckTheme { StartupPreparingScreen(stage) }
        }

        composeTestRule.onNodeWithText("Restoring your backup…").assertIsDisplayed()
        stage = StartupStage.Encrypting
        composeTestRule.onNodeWithText("Encrypting your automations. This only happens once.").assertIsDisplayed()
    }
}
