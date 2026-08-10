package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationLintTest {
    @Test
    fun persistentSettingWithoutExitReportsMissingReversal() {
        val task = Task(
            id = 10,
            name = "Dim display",
            actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))),
        )
        val profile = profile(
            id = 1,
            name = "Night",
            enterTaskId = task.id,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        )

        val finding = AutomationLint.analyze(profile, listOf(task)).findings.single()

        assertEquals(AutomationLintCode.MISSING_REVERSAL, finding.code)
        assertEquals(AutomationLintSeverity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("brightness"))
        assertTrue(finding.suggestedFix.contains("exit task"))
    }

    @Test
    fun exitTaskSuppressesMissingReversal() {
        val enter = Task(
            id = 10,
            name = "Dim display",
            actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))),
        )
        val exit = Task(
            id = 11,
            name = "Restore display",
            actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "100"))),
        )
        val profile = profile(
            id = 1,
            name = "Night",
            enterTaskId = enter.id,
            exitTaskId = exit.id,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        )

        assertFalse(
            AutomationLint.analyze(profile, listOf(enter, exit)).findings.any {
                it.code == AutomationLintCode.MISSING_REVERSAL
            },
        )
    }

    @Test
    fun unguardedStateTriggerReportsRepeatedTriggeringAndGuardsSuppressIt() {
        val task = Task(id = 10, name = "Notify", actions = listOf(ActionSpec(type = "notify.show")))
        val state = ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true"))
        val unguarded = profile(id = 1, name = "Charging", enterTaskId = task.id, contexts = listOf(state))

        assertTrue(
            AutomationLint.analyze(unguarded, listOf(task)).findings.any {
                it.code == AutomationLintCode.REPEATED_TRIGGERING
            },
        )

        listOf(
            unguarded.copy(cooldownSec = 30),
            unguarded.copy(contexts = listOf(state.copy(config = state.config + ("dwellMillis" to "1000")))),
            unguarded.copy(enterTaskId = 11),
        ).forEachIndexed { index, guarded ->
            val guardedTask = if (index == 2) {
                task.copy(id = 11, actions = listOf(ActionSpec(type = "notify.show", condition = "%charging = true")))
            } else {
                task
            }
            assertFalse(
                "guard $index should suppress repeated-triggering lint",
                AutomationLint.analyze(guarded, listOf(guardedTask)).findings.any {
                    it.code == AutomationLintCode.REPEATED_TRIGGERING
                },
            )
        }
    }

    @Test
    fun equalPriorityOverlappingWritersBlockAndDifferentPriorityWarns() {
        val firstTask = settingTask(10, "Dim display", priority = 5)
        val secondTask = settingTask(11, "Bright display", priority = 5)
        val first = profile(id = 1, name = "Reader", enterTaskId = firstTask.id, app = "com.example.reader")
        val second = profile(id = 2, name = "Reader focus", enterTaskId = secondTask.id, app = "com.example.reader")

        val blocking = AutomationLint.analyze(listOf(first, second), listOf(firstTask, secondTask))
            .findings.single { it.code == AutomationLintCode.PRIORITY_CONFLICT }
        assertEquals(AutomationLintSeverity.BLOCKING, blocking.severity)
        assertEquals(listOf(1L, 2L), blocking.profileIds)
        assertTrue(blocking.suggestedFix.contains("priority"))

        val warning = AutomationLint.analyze(
            listOf(first, second.copy(id = 2, name = "Reader focus", enterTaskId = 12)),
            listOf(firstTask, secondTask.copy(id = 12, priority = 7)),
        ).findings.single { it.code == AutomationLintCode.PRIORITY_CONFLICT }
        assertEquals(AutomationLintSeverity.WARNING, warning.severity)
    }

    @Test
    fun disjointApplicationContextsDoNotReportPriorityConflict() {
        val firstTask = settingTask(10, "Dim reader", priority = 5)
        val secondTask = settingTask(11, "Dim browser", priority = 5)
        val first = profile(id = 1, name = "Reader", enterTaskId = firstTask.id, app = "com.example.reader")
        val second = profile(id = 2, name = "Browser", enterTaskId = secondTask.id, app = "com.example.browser")

        assertFalse(
            AutomationLint.analyze(listOf(first, second), listOf(firstTask, secondTask)).findings.any {
                it.code == AutomationLintCode.PRIORITY_CONFLICT
            },
        )
    }

    @Test
    fun directTaskRunCycleReportsInterProfileLoop() {
        val firstTask = Task(
            id = 10,
            name = "Start A",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("id" to "11"))),
        )
        val secondTask = Task(
            id = 11,
            name = "Start B",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("id" to "10"))),
        )
        val first = profile(
            id = 1,
            name = "Profile A",
            enterTaskId = firstTask.id,
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "a"))),
        )
        val second = profile(
            id = 2,
            name = "Profile B",
            enterTaskId = secondTask.id,
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "b"))),
        )

        val finding = AutomationLint.analyze(listOf(first, second), listOf(firstTask, secondTask)).findings.single()

        assertEquals(AutomationLintCode.INTER_PROFILE_LOOP, finding.code)
        assertEquals(AutomationLintSeverity.WARNING, finding.severity)
        assertEquals(listOf(1L, 2L), finding.profileIds)
    }

    private fun settingTask(id: Long, name: String, priority: Int): Task = Task(
        id = id,
        name = name,
        priority = priority,
        actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))),
    )

    private fun profile(
        id: Long,
        name: String,
        enterTaskId: Long,
        exitTaskId: Long? = null,
        contexts: List<ContextSpec> = emptyList(),
        app: String? = null,
    ): Profile = Profile(
        id = id,
        name = name,
        enabled = true,
        contexts = contexts.ifEmpty {
            listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to requireNotNull(app))))
        },
        enterTaskId = enterTaskId,
        exitTaskId = exitTaskId,
    )
}
