package com.opentasker.core.engine

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

        val directRunnerConstruction = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() !in setOf("TaskRunner.kt", "TaskExecutionHelper.kt") }
                .filter { it.readText().contains("TaskRunner(") }
                .map { sourceRoot.relativize(it).toString() }
                .toList()
        }
        assertEquals("Production run paths must use executeAndLogTask", emptyList<String>(), directRunnerConstruction)
    }

    // RETIRED: upstream's transactional `moveTaskAction` snapshot contract. The fork reorders actions
    // through its own multi-select / clone / cut / paste editor path, not upstream's move-up/down card
    // controls, so this source-text assertion no longer describes our ViewModel.
}
