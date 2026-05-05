package com.opentasker.core.flow

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFlowGraphTest {
    @Test
    fun buildCreatesProfileContextTaskAndActionChain() {
        val enterTask = Task(
            id = 10,
            name = "Arrive home",
            priority = 7,
            actions = listOf(
                ActionSpec(type = "notify.show", label = "Welcome", args = mapOf("title" to "Home")),
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "5000")),
            ),
        )
        val profile = Profile(
            id = 1,
            name = "Home arrival",
            enabled = true,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("wifiSsid" to "Home"))),
            enterTaskId = enterTask.id,
            automationMode = AutomationMode.RESTART,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))

        assertEquals("Home arrival", graph.title)
        assertEquals(1, graph.contextNodes.size)
        assertEquals(AutomationFlowTarget.Profile(1), graph.nodes.first { it.kind == AutomationFlowNodeKind.PROFILE }.target)
        assertEquals(AutomationFlowTarget.Context(1, 0), graph.contextNodes.single().target)
        assertEquals("Arrive home", graph.enterTaskNode?.title)
        assertEquals(AutomationFlowTarget.Task(10), graph.enterTaskNode?.target)
        assertEquals(listOf("Welcome", "Step 2: flow.wait"), graph.actionNodesFor("enter-task:10").map { it.title })
        assertEquals(
            listOf(
                AutomationFlowTarget.Action(10, 0),
                AutomationFlowTarget.Action(10, 1),
            ),
            graph.actionNodesFor("enter-task:10").map { it.target },
        )
        assertTrue(graph.edges.any { it.fromId == "profile:1:context:0" && it.toId == "profile:1" })
        assertTrue(graph.edges.any { it.fromId == "enter-task:10" && it.toId == "enter-task:10:action:0" })
        assertTrue(graph.warnings.isEmpty())
    }

    @Test
    fun buildReportsMissingTasksAndEmptyContexts() {
        val profile = Profile(
            id = 2,
            name = "Broken profile",
            contexts = emptyList(),
            enterTaskId = 404,
            exitTaskId = 405,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, emptyList())

        assertEquals(2, graph.nodes.count { it.kind == AutomationFlowNodeKind.MISSING })
        assertEquals(
            listOf(AutomationFlowTarget.Profile(2), AutomationFlowTarget.Profile(2)),
            graph.nodes.filter { it.kind == AutomationFlowNodeKind.MISSING }.map { it.target },
        )
        assertTrue(graph.warnings.contains("Profile has no contexts."))
        assertTrue(graph.warnings.contains("Enter task 404 is missing."))
        assertTrue(graph.warnings.contains("Exit task 405 is missing."))
    }

    @Test
    fun buildKeepsExitTaskLaneSeparateFromEnterTaskLane() {
        val enterTask = Task(id = 10, name = "Enable mode", actions = listOf(ActionSpec(type = "dnd.set")))
        val exitTask = Task(id = 11, name = "Restore mode", actions = listOf(ActionSpec(type = "dnd.set")))
        val profile = Profile(
            id = 3,
            name = "Meeting",
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "calendar"))),
            enterTaskId = enterTask.id,
            exitTaskId = exitTask.id,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask, exitTask))

        assertEquals("Enable mode", graph.enterTaskNode?.title)
        assertEquals("Restore mode", graph.exitTaskNode?.title)
        assertEquals(listOf("Step 1: dnd.set"), graph.actionNodesFor("enter-task:10").map { it.title })
        assertEquals(listOf("Step 1: dnd.set"), graph.actionNodesFor("exit-task:11").map { it.title })
    }

    @Test
    fun buildMarksConditionalActionsAndIncomingEdges() {
        val enterTask = Task(
            id = 20,
            name = "Battery guard",
            actions = listOf(
                ActionSpec(type = "notify.show", label = "Battery warning", condition = "%battery < 20"),
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "500")),
            ),
        )
        val profile = Profile(
            id = 4,
            name = "Low battery",
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "battery"))),
            enterTaskId = enterTask.id,
        )

        val graph = AutomationFlowGraphBuilder.build(profile, listOf(enterTask))
        val actionNodes = graph.actionNodesFor("enter-task:20")

        assertEquals("%battery < 20", actionNodes.first().condition)
        assertEquals("if %battery < 20", graph.incomingEdgeLabel("enter-task:20:action:0"))
        assertEquals("then", graph.incomingEdgeLabel("enter-task:20:action:1"))
    }
}
