package com.opentasker.core.references

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDuplicatorTest {
    @Test
    fun generatedCopyNamesUseTheCallerSuppliedStringProvider() {
        val localized = object : AutomationDuplicateStrings {
            override fun untitled() = "localized-untitled"
            override fun copySuffix(copyNumber: Int) = "-$copyNumber"
        }

        assertEquals(
            "localized-untitled-1",
            AutomationDuplicator.copyName(" ", emptyList(), localized),
        )
    }

    @Test
    fun copyNamesAreDistinctCaseInsensitivelyAndStayWithinTheInputLimit() {
        assertEquals(
            "Morning (copy 3)",
            AutomationDuplicator.copyName("Morning", listOf("Morning", "Morning (copy)", "morning (COPY 2)")),
        )

        val longName = "x".repeat(250)
        val copy = AutomationDuplicator.copyName(longName, emptyList())
        assertTrue(copy.endsWith(" (copy)"))
        assertTrue(copy.length <= 200)
    }

    @Test
    fun taskCopiesRegenerateActionIdsAndRebindSelfReferences() {
        val source = Task(
            id = 10,
            name = "Morning",
            actions = listOf(
                ActionSpec(id = 4, type = "task.run", args = mapOf("task" to "10")),
                ActionSpec(id = 5, type = "notify.show", args = mapOf("button1_label" to "Run", "button1_task" to "Morning")),
                ActionSpec(id = 6, type = "task.run", args = mapOf("task" to "99")),
            ),
        )

        val staged = AutomationDuplicator.taskPayload(source, "Morning (copy)")
        val duplicate = AutomationReferenceRewriter.remapDuplicateSelfReferences(source, staged.copy(id = 44))

        assertEquals(44L, duplicate.id)
        assertEquals(listOf(7L, 8L, 9L), duplicate.actions.map { it.id })
        assertEquals("44", duplicate.actions[0].args["task"])
        assertEquals("44", duplicate.actions[1].args["button1_task_id"])
        assertEquals(null, duplicate.actions[1].args["button1_task"])
        assertEquals("99", duplicate.actions[2].args["task"])
        assertNotSame(source.actions, duplicate.actions)
        assertNotSame(source.actions[0].args, duplicate.actions[0].args)
    }

    @Test
    fun profileCopiesAreDisabledResetAndDeepCopied() {
        val source = Profile(
            id = 10,
            name = "At home",
            enabled = true,
            requiresRiskAcknowledgement = true,
            lifetimeConsumed = true,
            contexts = listOf(
                ContextSpec(ContextType.STATE, config = mutableMapOf("state" to "charging")),
                ContextSpec(ContextType.TIME, config = mutableMapOf("from" to "08:00")),
            ),
            contextExpression = ContextExpressionNode.group(
                ContextBooleanOperator.AND,
                listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
            ),
            enterTaskId = 20,
        )

        val duplicate = AutomationDuplicator.profilePayload(source, "At home (copy)").copy(id = 44)

        assertFalse(duplicate.enabled)
        assertFalse(duplicate.lifetimeConsumed)
        assertEquals(44L, duplicate.id)
        assertNotSame(source.contexts, duplicate.contexts)
        assertNotSame(source.contexts[0].config, duplicate.contexts[0].config)
        assertNotSame(source.contextExpression, duplicate.contextExpression)
        assertNotSame(source.contextExpression?.children, duplicate.contextExpression?.children)
    }

    @Test
    fun profileCopiesKeepTheRiskReviewRequirement() {
        // The copy has the original's actions and therefore its powers. Clearing the flag let a
        // user duplicate a gated imported profile and enable the copy without the risk dialog,
        // which is the only surface that discloses what the automation is allowed to do.
        val gated = Profile(id = 10, name = "Imported", requiresRiskAcknowledgement = true, enterTaskId = 20)
        val ordinary = gated.copy(id = 11, name = "Mine", requiresRiskAcknowledgement = false)

        assertTrue(AutomationDuplicator.profilePayload(gated, "Imported (copy)").requiresRiskAcknowledgement)
        assertFalse(AutomationDuplicator.profilePayload(ordinary, "Mine (copy)").requiresRiskAcknowledgement)
    }

    @Test
    fun sceneCopiesRegenerateElementIdsWhilePreservingTaskBindings() {
        val source = Scene(
            id = 10,
            name = "Controls",
            widthDp = 320,
            heightDp = 240,
            elements = listOf(
                SceneElement(20, SceneElementType.BUTTON, 0, 0, 100, 48, mutableMapOf("label" to "Go"), tapTaskId = 7),
                SceneElement(21, SceneElementType.TEXT, 0, 60, 100, 40, mutableMapOf("text" to "Status"), longPressTaskId = 8),
            ),
        )

        val duplicate = AutomationDuplicator.scenePayload(source, "Controls (copy)").copy(id = 44)

        assertEquals(44L, duplicate.id)
        assertEquals(listOf(22L, 23L), duplicate.elements.map { it.id })
        assertEquals(7L, duplicate.elements[0].tapTaskId)
        assertEquals(8L, duplicate.elements[1].longPressTaskId)
        assertNotEquals(source.elements.map { it.id }, duplicate.elements.map { it.id })
        assertNotSame(source.elements, duplicate.elements)
        assertNotSame(source.elements[0].config, duplicate.elements[0].config)
    }
}
