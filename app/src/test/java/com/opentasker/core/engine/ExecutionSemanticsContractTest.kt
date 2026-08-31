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
        // The nested call moved into runNestedUnderItsOwnJob so the coordinator registers the
        // sub-task's own Job rather than the caller's. The invariant is unchanged: a sub-task is
        // still admitted by the coordinator, and the null case still runs it directly.
        assertTrue(runner.contains("coordinator.execute(target)"))
        assertTrue(runner.contains("runNestedUnderItsOwnJob(coordinator, target, child)"))

        // Scanned across every production root: the engine primitives moved into core:engine, and
        // a walk limited to app/ would no longer see a direct TaskRunner there.
        val directRunnerConstruction = ProductionSources.allKotlinFiles()
            .filter { it.fileName.toString() !in setOf("TaskRunner.kt", "TaskExecutionHelper.kt") }
            .filter { it.readText().contains("TaskRunner(") }
            .map { ProductionSources.repoRoot.relativize(it).toString() }
        assertEquals("Production run paths must use executeAndLogTask", emptyList<String>(), directRunnerConstruction)
    }

// RETIRED: upstream's `everyRunExitCommitsChangedGlobals` source gate, and its transactional
// `moveTaskAction` snapshot contract. Neither describes the fork: globals here are durable at set
// time through the DB-backed PersistentGlobalScope, so TaskExecutionHelper has no commitGlobals()
// to call on any exit path, and actions are reordered through the fork's multi-select / clone /
// cut / paste editor rather than upstream's move-up/down card controls.
}
