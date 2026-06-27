package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveAutomationModuleSplitTest {
    private val screensSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui/screens"),
        Path.of("app/src/main/java/com/opentasker/ui/screens"),
    ).first(Files::exists)

    @Test
    fun activeAutomationShellDelegatesRunLogAndImportReviewWorkflows() {
        val shellSource = screensSourceRoot.resolve("ActiveAutomationUi.kt").readText()
        val runLogSource = screensSourceRoot.resolve("RunLogScreenContent.kt").readText()
        val importReviewSource = screensSourceRoot.resolve("ImportReviewDialogs.kt").readText()

        listOf(
            "RunLogScreenContent",
            "RunLogRetentionCard",
            "RunLogFilterCard",
            "RunLogCard",
            "RunLogTraceRow",
            "OpenTaskerBundleReviewDialog",
            "TaskerImportReviewDialog",
            "TaskerImportListSection",
        ).forEach { functionName ->
            assertFalse(
                "ActiveAutomationUi.kt should not own $functionName",
                Regex("""private fun $functionName\b|internal fun $functionName\b""").containsMatchIn(shellSource),
            )
        }

        assertTrue(runLogSource.contains("internal fun RunLogScreenContent"))
        assertTrue(runLogSource.contains("private fun RunLogCard"))
        assertTrue(runLogSource.contains("private fun RunLogTraceRow"))
        assertTrue(importReviewSource.contains("internal fun OpenTaskerBundleReviewDialog"))
        assertTrue(importReviewSource.contains("internal fun TaskerImportReviewDialog"))
        assertTrue(importReviewSource.contains("private fun TaskerImportListSection"))
    }

    @Test
    fun activeAutomationShellExposesSharedUiHelpersInternally() {
        val shellSource = screensSourceRoot.resolve("ActiveAutomationUi.kt").readText()

        listOf(
            "internal fun SummaryMetric",
            "internal fun StatusPill",
            "internal fun InlineNotice",
        ).forEach { helperDeclaration ->
            assertTrue("Missing shared helper: $helperDeclaration", shellSource.contains(helperDeclaration))
        }
    }

    @Test
    fun importReviewDialogsKeepScrollableContentBounded() {
        val importReviewSource = screensSourceRoot.resolve("ImportReviewDialogs.kt").readText()

        assertTrue(
            "Import review dialogs must constrain long warning and action lists on small screens",
            Regex("""heightIn\(max\s*=\s*460\.dp\)""").findAll(importReviewSource).count() >= 2,
        )
    }

    @Test
    fun appShellKeepsPremiumCreateAndOnboardingActionsDiscoverable() {
        val shellSource = screensSourceRoot.resolve("ActiveAutomationUi.kt").readText()
        val listSource = screensSourceRoot.resolve("ActiveAutomationLists.kt").readText()
        val editorSource = screensSourceRoot.resolve("EditorDialogs.kt").readText()

        assertTrue("Create actions should stay labeled, not icon-only", shellSource.contains("ExtendedFloatingActionButton"))
        assertTrue("First-run onboarding should recommend guided templates first", listSource.contains("actionLabel = \"Browse Templates\""))
        assertTrue("Empty-state actions should not stretch awkwardly on large screens", editorSource.contains("widthIn(max = 420.dp)"))
    }

    @Test
    fun activeAutomationShellStaysBelowModuleSplitCeiling() {
        val shell = screensSourceRoot.resolve("ActiveAutomationUi.kt")
        val extractedModules = listOf(
            "ActiveAutomationLists.kt",
            "ActiveAutomationViewModel.kt",
            "ActionEditorDialogs.kt",
            "ContextEditorDialogs.kt",
            "EditorDialogs.kt",
            "ImportReviewDialogs.kt",
            "RunLogScreenContent.kt",
        )

        assertTrue("ActiveAutomationUi.kt should stay under 1,500 lines", Files.readAllLines(shell).size < 1_500)
        extractedModules.forEach { fileName ->
            assertTrue("Missing extracted active-automation module: $fileName", Files.exists(screensSourceRoot.resolve(fileName)))
        }
    }
}
