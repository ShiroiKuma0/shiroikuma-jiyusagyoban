package com.opentasker.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDiffContractTest {
    @Test
    fun semanticDiffUsesDecodedModelsAcrossUndoImportAndFlowReview() {
        val diff = repoFile("src/main/java/com/opentasker/core/diff/AutomationSemanticDiff.kt").readText()
        // Scanned across the screens package: publishing the review and building the document are
        // allowed to live in separate files.
        val viewModel = screensSources()
        val importReview = repoFile("src/main/java/com/opentasker/ui/screens/ImportReviewDialogs.kt").readText()
        val diffDialogs = repoFile("src/main/java/com/opentasker/ui/screens/SemanticDiffDialogs.kt").readText()
        val flow = repoFile("src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt").readText()

        assertTrue("The diff engine must compare typed automation entities", diff.contains("fun compareTask") && diff.contains("fun compareProfile"))
        assertTrue("Undo/redo must publish a semantic diff document", viewModel.contains("SemanticDiffReviewState") && viewModel.contains("AutomationSemanticDiff.compare"))
        assertTrue("Bundle review must render the semantic diff", importReview.contains("SemanticDiffSummary(plan.semanticDiff)"))
        assertTrue("Bundle review must render structured semantic entries", importReview.contains("SemanticDiffDetails(plan.semanticDiff)"))
        assertTrue("Diff dialog must render structured semantic entries", diffDialogs.contains("SemanticDiffDetails(document)"))
        assertTrue("Flow review must accept changed node keys", flow.contains("changedNodeKeys") && flow.contains("node.changed"))
    }

    private fun repoFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun screensSources(): String {
        val root = listOf(
            File("src/main/java/com/opentasker/ui/screens"),
            File("app/src/main/java/com/opentasker/ui/screens"),
        ).first { it.isDirectory }
        return root.listFiles { file -> file.name.endsWith(".kt") }
            .orEmpty()
            .joinToString(separator = System.lineSeparator()) { it.readText() }
    }
}
