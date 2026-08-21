package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import com.opentasker.ProductionSources
import org.junit.Test

class ImportedProfileRiskSourceTest {


    @Test
    fun importEnableAndRuntimePathsShareFailClosedReviewBoundaries() {
        val bundle = ProductionSources.path("com/opentasker/core/transfer/OpenTaskerBundle.kt").readText()
        val profileDao = ProductionSources.path("com/opentasker/core/storage/ProfileDao.kt").readText()
        val viewModel = ProductionSources.path("com/opentasker/ui/screens/ActiveAutomationViewModel.kt").readText()
        val activeUi = ProductionSources.path("com/opentasker/ui/screens/ActiveAutomationUi.kt").readText()
        val editor = ProductionSources.path("com/opentasker/ui/screens/EditorDialogs.kt").readText()
        val external = ProductionSources.path("com/opentasker/core/external/AutomationTargetReceiver.kt").readText()
        val runner = ProductionSources.path("com/opentasker/core/engine/TaskRunner.kt").readText()

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
        val dialog = ProductionSources.path("com/opentasker/ui/screens/ImportedProfileRiskDialog.kt").readText()
        val importReview = ProductionSources.path("com/opentasker/ui/screens/ImportReviewDialogs.kt").readText()

        assertTrue(dialog.contains("Checkbox(checked = acknowledged"))
        assertTrue(dialog.contains("enabled = review.canAcknowledge && acknowledged"))
        assertTrue(dialog.contains("ImportedProfileEnablePolicy.review"))
        assertTrue(importReview.contains("plan.powerRequests"))
        assertTrue(importReview.contains("R.string.import_power_chain"))
    }
}
