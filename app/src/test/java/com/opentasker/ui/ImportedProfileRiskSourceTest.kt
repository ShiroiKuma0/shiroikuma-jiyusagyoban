package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedProfileRiskSourceTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker"),
        Path.of("app/src/main/java/com/opentasker"),
    ).first(Files::exists)

    @Test
    fun importEnableAndRuntimePathsShareFailClosedReviewBoundaries() {
        val bundle = sourceRoot.resolve("core/transfer/OpenTaskerBundle.kt").readText()
        val profileDao = sourceRoot.resolve("core/storage/ProfileDao.kt").readText()
        val viewModel = sourceRoot.resolve("ui/screens/ActiveAutomationViewModel.kt").readText()
        val activeUi = sourceRoot.resolve("ui/screens/ActiveAutomationUi.kt").readText()
        val editor = sourceRoot.resolve("ui/screens/EditorDialogs.kt").readText()
        val external = sourceRoot.resolve("core/external/AutomationTargetReceiver.kt").readText()
        val runner = sourceRoot.resolve("core/engine/TaskRunner.kt").readText()

        assertTrue(bundle.contains("requiresRiskAcknowledgement = true"))
        assertTrue(profileDao.contains("enabled = 1 AND requiresRiskAcknowledgement = 0"))
        assertTrue(viewModel.contains("acknowledgeAndEnableImportedProfile"))
        assertTrue(viewModel.contains("ImportedProfileEnablePolicy.review"))
        assertTrue(activeUi.contains("ImportedProfileRiskDialog"))
        assertTrue(editor.contains("enabled = !importedReviewRequired"))
        assertTrue(external.contains("profile.requiresRiskAcknowledgement"))
        assertTrue(runner.contains("unknown unclassified actions"))
    }

    @Test
    fun reviewUiRequiresExplicitAcknowledgementAndShowsComputedPowers() {
        val dialog = sourceRoot.resolve("ui/screens/ImportedProfileRiskDialog.kt").readText()
        val importReview = sourceRoot.resolve("ui/screens/ImportReviewDialogs.kt").readText()

        assertTrue(dialog.contains("Checkbox(checked = acknowledged"))
        assertTrue(dialog.contains("enabled = review.canAcknowledge && acknowledged"))
        assertTrue(dialog.contains("ImportedProfileEnablePolicy.review"))
        assertTrue(importReview.contains("plan.powerRequests"))
        assertTrue(importReview.contains("R.string.import_power_chain"))
    }
}
