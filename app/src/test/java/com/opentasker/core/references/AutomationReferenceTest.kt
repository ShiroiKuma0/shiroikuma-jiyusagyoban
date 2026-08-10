package com.opentasker.core.references

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReferenceTest {

    private val target = Task(id = 10, name = "Morning")
    private val other = Task(id = 20, name = "Evening")

    private fun caller(vararg actions: ActionSpec) = Task(id = 30, name = "Caller", actions = actions.toList())

    private fun scene(vararg elements: SceneElement) =
        Scene(id = 40, name = "Panel", widthDp = 200, heightDp = 200, elements = elements.toList())

    private fun element(tap: Long? = null, longPress: Long? = null) = SceneElement(
        type = SceneElementType.BUTTON,
        xDp = 0,
        yDp = 0,
        widthDp = 10,
        heightDp = 10,
        tapTaskId = tap,
        longPressTaskId = longPress,
    )

    @Test
    fun indexFindsEveryReferenceShapeIncludingTheOnesDeletionUsedToMiss() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = target.id, exitTaskId = target.id)
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "10")),
            ActionSpec(type = "task.run", args = mapOf("name" to "Morning")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task_id" to "10")),
            ActionSpec(type = "notify.show", args = mapOf("button2_label" to "Go", "button2_task" to "Morning")),
        )
        val scene = scene(element(tap = target.id), element(longPress = target.id))

        val references = AutomationReferenceIndex.referencesTo(
            task = target,
            profiles = listOf(profile),
            tasks = listOf(caller),
            scenes = listOf(scene),
        )

        val sites = references.map { it.site::class.simpleName }
        assertEquals(
            listOf(
                "ProfileEnterTask",
                "ProfileExitTask",
                "SubTaskRun",
                "SubTaskRun",
                "NotificationButton",
                "NotificationButton",
                "SceneTap",
                "SceneLongPress",
            ),
            sites,
        )
        assertTrue(references.any { it.ref is TaskRef.ByName })
        assertTrue(references.any { it.ref is TaskRef.ById })
        assertEquals(1, references.count { it.isRequired })
    }

    @Test
    fun aTaskDoesNotBlockItsOwnDeletionThroughASelfReference() {
        val selfCalling = target.copy(actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "10"))))
        val references = AutomationReferenceIndex.referencesTo(
            task = target,
            tasks = listOf(selfCalling),
        )
        assertTrue(references.isEmpty())
    }

    @Test
    fun blockReportsEveryDependentAndChangesNothing() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = target.id)
        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Block,
            profiles = listOf(profile),
        )
        assertFalse(rewrite.canCommit)
        assertTrue(rewrite.isEmpty)
        assertEquals(1, rewrite.blocked.size)
    }

    @Test
    fun reassignRetargetsEverySiteIncludingNameReferences() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = target.id, exitTaskId = target.id)
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "Morning")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task" to "Morning")),
        )
        val scene = scene(element(tap = target.id, longPress = other.id))

        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Reassign(other),
            profiles = listOf(profile),
            tasks = listOf(caller),
            scenes = listOf(scene),
        )

        assertTrue(rewrite.canCommit)
        assertEquals(20L, rewrite.profiles.single().enterTaskId)
        assertEquals(20L, rewrite.profiles.single().exitTaskId)

        val rewrittenActions = rewrite.tasks.single().actions
        assertEquals("20", rewrittenActions[0].args["task"])
        // The legacy name key is replaced by the stable id key, not left beside it.
        assertEquals("20", rewrittenActions[1].args["button1_task_id"])
        assertNull(rewrittenActions[1].args["button1_task"])
        assertEquals("Go", rewrittenActions[1].args["button1_label"])

        val rewrittenElement = rewrite.scenes.single().elements.single()
        assertEquals(20L, rewrittenElement.tapTaskId)
        assertEquals(20L, rewrittenElement.longPressTaskId)

        // Nothing anywhere still points at the deleted task.
        assertTrue(
            AutomationReferenceIndex.referencesTo(
                task = target,
                profiles = rewrite.profiles,
                tasks = rewrite.tasks,
                scenes = rewrite.scenes,
            ).isEmpty(),
        )
    }

    @Test
    fun clearDropsOptionalReferencesAndTheButtonThatWouldHaveNothingToRun() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = other.id, exitTaskId = target.id)
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "10", "input" to "keep")),
            ActionSpec(type = "notify.show", args = mapOf("title" to "Hi", "button1_label" to "Go", "button1_task_id" to "10")),
        )
        val scene = scene(element(tap = target.id))

        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Clear,
            profiles = listOf(profile),
            tasks = listOf(caller),
            scenes = listOf(scene),
        )

        assertTrue(rewrite.canCommit)
        assertNull(rewrite.profiles.single().exitTaskId)
        assertEquals(other.id, rewrite.profiles.single().enterTaskId)

        val actions = rewrite.tasks.single().actions
        assertNull(actions[0].args["task"])
        assertEquals("keep", actions[0].args["input"])
        assertNull(actions[1].args["button1_task_id"])
        assertNull(actions[1].args["button1_label"])
        assertEquals("Hi", actions[1].args["title"])

        assertNull(rewrite.scenes.single().elements.single().tapTaskId)
    }

    @Test
    fun clearRefusesWhenAProfileWouldBeLeftWithoutAnEnterTask() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = target.id)
        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Clear,
            profiles = listOf(profile),
        )
        assertFalse(rewrite.canCommit)
        assertTrue(rewrite.isEmpty)
        assertTrue(rewrite.blocked.single().isRequired)
    }

    @Test
    fun reassigningOntoTheTaskBeingDeletedIsRefused() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = target.id)
        val rewrite = AutomationReferenceRewriter.retarget(
            target = target,
            resolution = ReferenceResolution.Reassign(target),
            profiles = listOf(profile),
        )
        assertFalse(rewrite.canCommit)
    }

    @Test
    fun remapIdsRewritesSubTaskAndNotificationBindingsInsideImportedActions() {
        val imported = Task(
            id = 101,
            name = "Imported",
            actions = listOf(
                ActionSpec(type = "task.run", args = mapOf("task" to "7")),
                ActionSpec(type = "notify.show", args = mapOf("button1_task_id" to "8", "button2_task_id" to "9")),
            ),
        )
        val rewrite = AutomationReferenceRewriter.remapIds(
            idMap = mapOf(7L to 700L, 8L to 800L),
            tasks = listOf(imported),
        )
        val actions = rewrite.tasks.single().actions
        assertEquals("700", actions[0].args["task"])
        assertEquals("800", actions[1].args["button1_task_id"])
        // Ids the bundle did not carry are left alone rather than retargeted at a stranger's task.
        assertEquals("9", actions[1].args["button2_task_id"])
    }

    @Test
    fun remapIdsLeavesUnrelatedObjectsUntouched() {
        val profile = Profile(id = 1, name = "Home", enterTaskId = 7)
        val unrelated = Task(id = 2, name = "Plain", actions = listOf(ActionSpec(type = "notify.show")))
        val rewrite = AutomationReferenceRewriter.remapIds(
            idMap = mapOf(7L to 700L),
            profiles = listOf(profile),
            tasks = listOf(unrelated),
        )
        assertEquals(700L, rewrite.profiles.single().enterTaskId)
        assertTrue(rewrite.tasks.isEmpty())
    }

    @Test
    fun renamePinsNameReferencesToTheStableIdBeforeTheNameChanges() {
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("name" to "morning")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task" to "Morning")),
            ActionSpec(type = "task.run", args = mapOf("task" to "20")),
        )

        val rewrite = AutomationReferenceRewriter.stabilizeNameReferences(
            target = target,
            tasks = listOf(caller),
        )

        val actions = rewrite.tasks.single().actions
        assertEquals("10", actions[0].args["name"])
        assertEquals("10", actions[1].args["button1_task_id"])
        assertNull(actions[1].args["button1_task"])
        // A reference to a different task is untouched.
        assertEquals("20", actions[2].args["task"])

        // The renamed task is no longer reachable by its old name anywhere.
        val renamed = target.copy(name = "Sunrise")
        assertTrue(
            AutomationReferenceIndex
                .referencesTo(task = target.copy(id = -1), tasks = rewrite.tasks)
                .isEmpty(),
        )
        assertEquals(
            2,
            AutomationReferenceIndex.referencesTo(task = renamed, tasks = rewrite.tasks).size,
        )
    }

    @Test
    fun stabilizeIsANoOpWhenNothingReferencesTheTaskByName() {
        val caller = caller(ActionSpec(type = "task.run", args = mapOf("task" to "10")))
        assertTrue(
            AutomationReferenceRewriter.stabilizeNameReferences(target = target, tasks = listOf(caller)).isEmpty,
        )
    }

    @Test
    fun descriptionsNameTheOwningObjectAndLocation() {
        val references = AutomationReferenceIndex.referencesTo(
            task = target,
            profiles = listOf(Profile(id = 1, name = "Home", enterTaskId = target.id)),
            tasks = listOf(caller(ActionSpec(type = "task.run", args = mapOf("task" to "10")))),
            scenes = listOf(scene(element(tap = target.id))),
        )
        assertEquals(
            listOf(
                "Profile \"Home\" enter task",
                "Task \"Caller\" step 1 (run sub-task)",
                "Scene \"Panel\" element 1 (tap)",
            ),
            references.map { it.describe() },
        )
    }

    @Test
    fun variableIndexAndRenameCoverLegacyTemplatesConditionsScenesAndProfiles() {
        val variable = Variable("ApiToken", "secret", isGlobal = true, projectId = 7)
        val profile = Profile(
            id = 1,
            name = "Home",
            enterTaskId = 10,
            projectId = 7,
            contexts = listOf(
                ContextSpec(ContextType.STATE, config = mapOf("value" to "{{ global.ApiToken }}")),
            ),
        )
        val task = Task(
            id = 10,
            name = "Call API",
            projectId = 7,
            actions = listOf(
                ActionSpec(
                    type = "http.request",
                    args = mapOf("url" to "https://example.test/%ApiToken/{{ global.ApiToken }}?next={{ ApiToken | lower }}"),
                    condition = "%ApiToken == {{ global.ApiToken }}",
                ),
            ),
        )
        val scene = Scene(
            id = 20,
            name = "Panel",
            widthDp = 200,
            heightDp = 200,
            projectId = 7,
            elements = listOf(element().copy(config = mapOf("text" to "Token %ApiToken / {{ ApiToken }}"))),
        )

        val references = AutomationReferenceIndex.referencesTo(
            variable,
            profiles = listOf(profile),
            tasks = listOf(task),
            scenes = listOf(scene),
        )
        assertEquals(8, references.size)
        assertEquals(3, references.count { it.syntax == VariableReferenceSyntax.LEGACY })
        assertEquals(5, references.count { it.syntax == VariableReferenceSyntax.TEMPLATE })

        val blocked = AutomationReferenceRewriter.guardVariableDeletion(
            target = variable,
            profiles = listOf(profile),
            tasks = listOf(task),
            scenes = listOf(scene),
        )
        assertFalse(blocked.canCommit)
        assertTrue(blocked.blocked.any { it.describe().contains("Call API") })
        assertTrue(blocked.blocked.any { it.describe().contains("Panel") })

        val rewrite = AutomationReferenceRewriter.renameVariable(
            target = variable,
            replacementName = "RotatedToken",
            profiles = listOf(profile),
            tasks = listOf(task),
            scenes = listOf(scene),
        )
        val rewrittenAction = rewrite.tasks.single().actions.single()
        assertEquals(
            "https://example.test/%RotatedToken/{{ global.RotatedToken }}?next={{ RotatedToken | lower }}",
            rewrittenAction.args["url"],
        )
        assertEquals("%RotatedToken == {{ global.RotatedToken }}", rewrittenAction.condition)
        assertEquals(
            "Token %RotatedToken / {{ RotatedToken }}",
            rewrite.scenes.single().elements.single().config["text"],
        )
        assertEquals(
            "{{ global.RotatedToken }}",
            rewrite.profiles.single().contexts.single().config["value"],
        )
        assertTrue(
            AutomationReferenceIndex.referencesTo(
                Variable("RotatedToken", "secret", isGlobal = true, projectId = 7),
                profiles = rewrite.profiles,
                tasks = rewrite.tasks,
                scenes = rewrite.scenes,
            ).isNotEmpty(),
        )
        assertTrue(
            AutomationReferenceIndex.referencesTo(
                variable,
                profiles = rewrite.profiles,
                tasks = rewrite.tasks,
                scenes = rewrite.scenes,
            ).isEmpty(),
        )
    }

    @Test
    fun variableReferencesKeepLocalAndGlobalNamespacesSeparate() {
        val local = Variable("local_name", "value", isGlobal = false, projectId = 7)
        val task = caller(
            ActionSpec(
                type = "text.show",
                args = mapOf(
                    "text" to "%local_name / {{ local_name }} / {{ task.local_name }} / {{ global.local_name }} / {{ event.local_name }}",
                ),
            ),
        ).copy(projectId = 7)

        val references = AutomationReferenceIndex.referencesTo(local, tasks = listOf(task))
        assertEquals(3, references.size)

        val rewrite = AutomationReferenceRewriter.renameVariable(
            target = local,
            replacementName = "renamed_local",
            tasks = listOf(task),
        )
        assertEquals(
            "%renamed_local / {{ renamed_local }} / {{ task.renamed_local }} / {{ global.local_name }} / {{ event.local_name }}",
            rewrite.tasks.single().actions.single().args["text"],
        )
    }
}
