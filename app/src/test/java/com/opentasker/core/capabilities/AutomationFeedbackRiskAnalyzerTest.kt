package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFeedbackRiskAnalyzerTest {
    @Test
    fun reportsAStaticSelfReferentialTaskRun() {
        val task = Task(id = 1, name = "Loop", actions = listOf(runTask("1")))
        val profile = Profile(id = 5, name = "Looping", enterTaskId = task.id)

        val risks = AutomationFeedbackRiskAnalyzer.analyze(profile, listOf(task))

        assertEquals(1, risks.size)
        assertEquals(listOf("Loop", "Loop"), risks.single().taskPath)
    }

    @Test
    fun reportsAStaticMutualTaskCycleOnce() {
        val first = Task(id = 1, name = "First", actions = listOf(runTask("Second")))
        val second = Task(id = 2, name = "Second", actions = listOf(runTask("1")))
        val profile = Profile(id = 5, name = "Cycle", enterTaskId = first.id)

        val risks = AutomationFeedbackRiskAnalyzer.analyze(profile, listOf(first, second))

        assertEquals(1, risks.size)
        assertTrue(risks.single().taskPath == listOf("First", "Second", "First") ||
            risks.single().taskPath == listOf("Second", "First", "Second"))
    }

    @Test
    fun ignoresDynamicReferencesAndUnreachableCycles() {
        val dynamic = Task(id = 1, name = "Dynamic", actions = listOf(runTask("%target")))
        val unreachable = Task(id = 2, name = "Unreachable", actions = listOf(runTask("2")))
        val profile = Profile(id = 5, name = "Safe", enterTaskId = dynamic.id)

        assertTrue(AutomationFeedbackRiskAnalyzer.analyze(profile, listOf(dynamic, unreachable)).isEmpty())
    }

    private fun runTask(reference: String) = ActionSpec(
        type = "task.run",
        args = mapOf("task" to reference),
    )
}
