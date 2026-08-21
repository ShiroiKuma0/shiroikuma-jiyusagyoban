package com.opentasker.core.storage

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The restore review gate: selecting a database must not stage it.
 *
 * Selection used to replace the pending-restart journal outright, so a user could not inspect the
 * candidate, could not tell it apart from a restore staged earlier, and could not back out.
 */
class RestoreReviewContractTest {

    private val source: String =
        ProductionSources.read("com/opentasker/core/storage/DatabaseBackupManager.kt")

    @Test
    fun inspectionWritesTheCandidateFileNotThePendingJournal() {
        val inspect = source.substring(
            source.indexOf("suspend fun inspectRestore("),
            source.indexOf("suspend fun inspectManagedBackup("),
        )
        assertTrue("inspection must write the separate candidate file", "candidateRestoreFile(" in inspect)
        assertFalse("inspection must not touch the pending journal", "pendingRestoreFile(" in inspect)
        assertTrue("a rejected candidate must not be left behind", ".delete()" in inspect)
    }

    @Test
    fun stagingIsASeparateExplicitStep() {
        val stage = source.substring(
            source.indexOf("suspend fun stageInspectedRestore("),
            source.indexOf("fun discardInspectedRestore("),
        )
        assertTrue("staging promotes the inspected candidate", "candidateRestoreFile(" in stage)
        assertTrue("staging publishes atomically", "replaceFileAtomically(" in stage)
        assertTrue("staging refuses without an inspected candidate", "No inspected restore candidate" in stage)
    }

    @Test
    fun cancellingRemovesOnlyTheValidatedPendingJournal() {
        val cancel = source.substring(
            source.indexOf("fun cancelPendingRestore("),
            source.indexOf("suspend fun pendingRestoreSummary("),
        )
        assertTrue("cancel deletes the pending journal", "pending.delete()" in cancel)
        assertFalse("cancel must not touch the live database", "dbFile" in cancel)
        assertFalse("cancel must not delete backups", "listBackups" in cancel)
        assertFalse("cancel must not remove the pre-restore snapshot", "rollback" in cancel)
    }

    @Test
    fun theCandidateAndPendingFilesAreDistinct() {
        assertTrue("candidate file must exist as its own path", "_restore_candidate.db" in source)
        assertTrue("pending journal keeps its own path", "_restore_pending.db" in source)
    }

    @Test
    fun compatibilityFollowsTheSupportedSchemaRange() {
        val supported = RestoreCandidate("b.db", 1024, OPEN_TASKER_DATABASE_SCHEMA_VERSION)
        assertTrue(supported.compatible)

        assertFalse(RestoreCandidate("b.db", 1024, 0).compatible)
        assertFalse(RestoreCandidate("b.db", 1024, OPEN_TASKER_DATABASE_SCHEMA_VERSION + 1).compatible)
        // An unreadable staged restore can never be applied, so it can never read as compatible.
        assertFalse(RestoreCandidate("b.db", 0, 1, error = "unreadable").compatible)
    }

    @Test
    fun theReviewIsShownBeforeAnythingIsStaged() {
        val viewModel = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).first(Files::exists)
            .resolve("com/opentasker/ui/screens/ActiveAutomationViewModel.kt")
            .readText()

        val import = viewModel.substring(
            viewModel.indexOf("fun importDatabaseBackup("),
            viewModel.indexOf("fun confirmStageRestore("),
        )
        assertTrue("selection inspects", "inspectRestore(" in import)
        assertFalse("selection must not stage", "stageInspectedRestore(" in import)
        assertTrue(
            "the review must say what it would replace",
            "replacesPending = databaseBackupManager.pendingRestoreSummary()" in import,
        )

        val dismiss = viewModel.substring(
            viewModel.indexOf("fun dismissRestoreReview("),
            viewModel.indexOf("fun cancelPendingRestore("),
        )
        assertTrue("declining the review drops the candidate bytes", "discardInspectedRestore(" in dismiss)
    }

    @Test
    fun countsAreReportedForTheEntitiesAUserWouldRecognize() {
        val summarize = source.substring(
            source.indexOf("private fun summarize("),
            source.indexOf("private fun countOrZero("),
        )
        listOf("profiles", "tasks", "scenes", "variables", "run_logs").forEach { table ->
            assertTrue("the summary must count $table", "\"$table\"" in summarize)
        }
    }

    @Test
    fun candidateRendersACompleteSummary() {
        val candidate = RestoreCandidate(
            sourceLabel = "opentasker_backup_2026-07-29.db",
            sizeBytes = 2_097_152,
            schemaVersion = OPEN_TASKER_DATABASE_SCHEMA_VERSION,
            profileCount = 4,
            taskCount = 9,
            sceneCount = 1,
            variableCount = 12,
            runLogCount = 250,
        )
        assertEquals(OPEN_TASKER_DATABASE_SCHEMA_VERSION, candidate.schemaVersion)
        assertTrue(candidate.compatible)
        assertEquals(250, candidate.runLogCount)
    }
}
