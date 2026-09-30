package com.opentasker.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentasker.core.storage.RestoreRollback
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** After a restore, Setup offers the database it replaced (A-323). */
class BackupSetupCardTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun aKeptRollbackIsOfferedAndOpensTheReview() {
        var reviews = 0
        composeTestRule.setContent {
            OpenTaskerTheme {
                BackupSetupCard(
                    state = BackupSetupState(
                        busy = false,
                        latestBackupName = "opentasker_backup_2026-09-25_08-00-00.db",
                        lastRestoreRollback = RestoreRollback(
                            File("opentasker_pre_restore_2026-09-26_08-00-00.db"),
                            restoredAtMs = 1_790_000_000_000L,
                        ),
                    ),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                    onCancelPendingRestore = {},
                    onReviewRestoreRollback = { reviews++ },
                    onSnapshotPolicyChanged = {},
                    onSnapshotDestinationSelected = { _, _, _ -> },
                    initiallyExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Roll back").assertIsDisplayed().performClick()
        assertEquals(1, reviews)
    }

    @Test
    fun noRollbackMeansNoOffer() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                BackupSetupCard(
                    state = BackupSetupState(busy = false, latestBackupName = "opentasker_backup_2026-09-26_08-00-00.db"),
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

        composeTestRule.onNodeWithText("Roll back").assertDoesNotExist()
    }
}
