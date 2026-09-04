package com.opentasker.core.engine

import com.opentasker.ProductionSources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionSemanticsContractTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)

    @Test
    fun everyTopLevelAndNestedRunPassesThroughCollisionAdmission() {
        val engineRoot = sourceRoot.resolve("com/opentasker/core/engine")
        val helper = engineRoot.resolve("TaskExecutionHelper.kt").readText()
        val runner = engineRoot.resolve("TaskRunner.kt").readText()
        assertTrue(helper.contains("TaskCollisionCoordinator.Default"))
        assertTrue(helper.contains("collisionCoordinator.execute(task)"))
        assertTrue(runner.contains("collisionCoordinator?.execute(target)"))

        // Scanned across every production root: the engine primitives moved into core:engine, and
        // a walk limited to app/ would no longer see a direct TaskRunner there.
        val directRunnerConstruction = ProductionSources.allKotlinFiles()
            .filter { it.fileName.toString() !in setOf("TaskRunner.kt", "TaskExecutionHelper.kt") }
            .filter { it.readText().contains("TaskRunner(") }
            .map { ProductionSources.repoRoot.relativize(it).toString() }
        assertEquals("Production run paths must use executeAndLogTask", emptyList<String>(), directRunnerConstruction)
    }

    /**
     * Every exit from a run has to commit the globals it set.
     *
     * Only the report path did, so a cancelled or crashed run discarded them, and a profile in
     * RESTART mode cancels the in-flight run on every re-trigger. This is a source gate because
     * `executeAndLogTask` needs a Room database, which this suite has no JVM harness for; the
     * commit mechanics themselves are covered by DurableGlobalsTest.
     */
    @Test
    fun everyRunExitCommitsChangedGlobals() {
        val helperPath = "com/opentasker/core/engine/TaskExecutionHelper.kt"
        val cancelled = ProductionSources.block(
            helperPath,
            "catch (cancellation: CancellationException) {",
            "throw cancellation",
        )
        val failed = ProductionSources.block(
            helperPath,
            "catch (error: Exception) {",
            "throw error",
        )

        assertTrue(
            "a cancelled run must still persist the globals it set",
            cancelled.contains("commitGlobals()"),
        )
        assertTrue(
            "a run that ended in an exception must still persist the globals it set",
            failed.contains("commitGlobals()"),
        )
        // The commit has to survive the cancellation that is already in flight.
        assertTrue(
            "the cancelled commit must run inside the NonCancellable block",
            cancelled.indexOf("withContext(NonCancellable)") < cancelled.indexOf("commitGlobals()"),
        )
    }

    @Test
    fun actionReorderUsesOneTransactionAndSnapshotsThePreviousOrder() {
        // Scanned across the screens package: the contract is that the reorder path is one
        // transaction, not that it stays in a particular filename.
        val screensRoot = sourceRoot.resolve("com/opentasker/ui/screens")
        val owner = Files.list(screensRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .filter { it.readText().contains("fun moveTaskAction(") }
                .toList()
        }
        assertEquals("Expected exactly one declaration of moveTaskAction", 1, owner.size)
        val source = owner.single().readText()
        // Stop at the next member declaration whatever modifiers it carries; keying on a bare
        // `fun ` let the slice silently swallow the rest of the class.
        val body = source.substringAfter("fun moveTaskAction(")
        val method = Regex("""\n    (?:private |internal |public )?(?:suspend )?fun """)
            .find(body)
            ?.let { body.substring(0, it.range.first) }
            ?: body

        assertTrue(method.contains("db.withTransaction"))
        assertTrue(method.contains("previousJson = StorageJson.encodeToString(decoded.value)"))
        assertTrue(method.contains("nextJson = StorageJson.encodeToString(updated)"))
        assertTrue(method.contains("db.taskDao().update(updated.toEntity())"))
    }
}
