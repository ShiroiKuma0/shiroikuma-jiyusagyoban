package com.sysadmindoc.ruledeck.core.engine

import com.sysadmindoc.ruledeck.core.capabilities.AutomationLint
import com.sysadmindoc.ruledeck.core.capabilities.AutomationLintCode
import com.sysadmindoc.ruledeck.core.capabilities.AutomationLintSeverity
import com.sysadmindoc.ruledeck.core.flow.AutomationFlowGraphBuilder
import com.sysadmindoc.ruledeck.core.flow.AutomationFlowNodeKind
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.model.enterTaskIdOrNull
import com.sysadmindoc.ruledeck.core.references.AutomationReferenceIndex
import com.sysadmindoc.ruledeck.core.references.TaskReferenceSite
import com.sysadmindoc.ruledeck.core.transfer.RuleDeckBundle
import com.sysadmindoc.ruledeck.core.transfer.RuleDeckBundleCodec
import com.sysadmindoc.ruledeck.core.validation.InputValidation
import com.sysadmindoc.ruledeck.ui.screens.profileEditorCanSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile with an exit task and no enter task: nothing runs when it
 * goes on, its exit task runs when it goes off. Every place that used to read enter task 0 as a
 * missing task 0 has to take it as "none" instead.
 */
class ExitOnlyProfileTest {
    private val workHours = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "08:00", "end" to "17:30")))
    private val lightsOff = Task(id = 7, name = "Lights off", actions = listOf(ActionSpec(type = "flash", args = mapOf("text" to "off"))))
    private val exitOnly = Profile(id = 3, name = "Leaving home", contexts = workHours, enterTaskId = 0, exitTaskId = lightsOff.id)

    @Test
    fun validationAcceptsAnExitTaskInPlaceOfAnEnterTask() {
        assertTrue(InputValidation.validateProfile(exitOnly).none { it.field == "enterTaskId" || it.field == "exitTaskId" })

        val neither = InputValidation.validateProfile(exitOnly.copy(exitTaskId = null))
        assertTrue(neither.toString(), neither.any { it.field == "enterTaskId" })

        val badExit = InputValidation.validateProfile(exitOnly.copy(exitTaskId = 0))
        assertTrue(badExit.toString(), badExit.any { it.field == "exitTaskId" })
        assertTrue(badExit.toString(), badExit.any { it.field == "enterTaskId" })
    }

    @Test
    fun theEditorSavesNoEnterTaskOnlyWithAnExitTaskThatExists() {
        fun canSave(exitTaskId: Long?, exitExists: Boolean) = profileEditorCanSave(
            name = "Leaving home",
            enterTaskId = 0,
            selectedTaskExists = false,
            selectedExitTaskExists = exitExists,
            cooldown = "0",
            parsedCooldown = 0,
            exitTaskId = exitTaskId,
        )

        assertTrue(canSave(exitTaskId = 7, exitExists = true))
        assertFalse(canSave(exitTaskId = null, exitExists = true))
        assertFalse(canSave(exitTaskId = 7, exitExists = false))
        // An enter task that's gone still blocks Save, exit task or not.
        assertFalse(
            profileEditorCanSave(
                name = "Leaving home",
                enterTaskId = 5,
                selectedTaskExists = false,
                selectedExitTaskExists = true,
                cooldown = "0",
                parsedCooldown = 0,
                exitTaskId = 7,
            ),
        )
    }

    @Test
    fun aBundleRoundTripKeepsTheProfileExitOnly() {
        val bundle = RuleDeckBundle(appVersion = "test", exportedAtEpochMs = 0L, tasks = listOf(lightsOff), profiles = listOf(exitOnly))

        val back = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle)).profiles.single()

        assertNull(back.enterTaskIdOrNull)
        assertEquals(7L, back.exitTaskId)
    }

    @Test
    fun lintOnlyBlocksWhenTheProfileHasNoTaskItCanRun() {
        fun unreachable(profile: Profile, tasks: List<Task>) =
            AutomationLint.analyze(profile, tasks).findings.filter { it.code == AutomationLintCode.UNREACHABLE_RULE }

        assertTrue(unreachable(exitOnly, listOf(lightsOff)).toString(), unreachable(exitOnly, listOf(lightsOff)).isEmpty())
        val exitGone = unreachable(exitOnly, emptyList()).single()
        assertTrue(exitGone.detail, "exit task 7" in exitGone.detail)
        val neither = unreachable(exitOnly.copy(exitTaskId = null), listOf(lightsOff)).single()
        assertFalse(neither.detail, "enter task 0" in neither.detail)
    }

    @Test
    fun theFlowGraphDrawsNoMissingEnterTask() {
        val graph = AutomationFlowGraphBuilder.build(exitOnly, listOf(lightsOff))

        assertTrue(graph.warnings.toString(), graph.warnings.none { it.startsWith("Enter task") })
        assertTrue(graph.nodes.none { it.kind == AutomationFlowNodeKind.MISSING || it.kind == AutomationFlowNodeKind.ENTER_TASK })
        assertTrue(graph.nodes.any { it.kind == AutomationFlowNodeKind.EXIT_TASK })
    }

    @Test
    fun theExitTaskIsTheReferenceThatCannotBeCleared() {
        val references = AutomationReferenceIndex.build(listOf(exitOnly), listOf(lightsOff))

        assertTrue(references.none { it.site is TaskReferenceSite.ProfileEnterTask })
        assertTrue(references.single { it.site is TaskReferenceSite.ProfileExitTask }.isRequired)
        // With an enter task the exit task stays optional.
        val both = AutomationReferenceIndex.build(listOf(exitOnly.copy(enterTaskId = 8)), listOf(lightsOff))
        assertFalse(both.single { it.site is TaskReferenceSite.ProfileExitTask }.isRequired)
    }

    @Test
    fun aBundleKeepsAnExitOnlyProfileAndSkipsOneWithNoTaskLeft() {
        fun lossy(vararg profiles: Profile) = RuleDeckBundleCodec.validate(
            RuleDeckBundle(appVersion = "test", exportedAtEpochMs = 0L, tasks = listOf(lightsOff), profiles = profiles.toList()),
        ).lossyWarnings

        assertTrue(lossy(exitOnly).toString(), lossy(exitOnly).isEmpty())
        val exitGone = lossy(exitOnly.copy(exitTaskId = 99))
        assertEquals(exitGone.toString(), 1, exitGone.size)
        assertTrue(exitGone.single(), "will be skipped" in exitGone.single())
    }

    @Test
    fun reloadPlanningKeepsAnExitOnlyProfileOnLikeAnyOther() {
        val edited = exitOnly.copy(contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:30"))))
        val matched = mutableMapOf(exitOnly.id to exitOnly)
        val dispatched = mutableSetOf(exitOnly.id)

        val plan = planMatcherReload(mapOf(exitOnly.id to exitOnly), listOf(edited), matched, dispatched)

        assertEquals(setOf(exitOnly.id), plan.stillOn)
        assertEquals(setOf(exitOnly.id), dispatched)
    }

    @Test
    fun theFlowSummarySaysNoEnterTaskInsteadOfMissing() {
        val summary = AutomationFlowGraphBuilder.build(exitOnly, listOf(lightsOff)).accessibilitySummary()

        assertTrue(summary, "no enter task" in summary)
        assertFalse(summary, "Missing enter task" in summary)
        // An enter task id that points nowhere is still reported missing.
        val gone = AutomationFlowGraphBuilder.build(exitOnly.copy(enterTaskId = 5), listOf(lightsOff)).accessibilitySummary()
        assertTrue(gone, "Missing enter task" in gone)
    }

    @Test
    fun anEventContextWithNoEnterTaskIsBlocked() {
        val onEvent = exitOnly.copy(contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "manual"))))

        val finding = AutomationLint.analyze(onEvent, listOf(lightsOff)).findings.single { it.code == AutomationLintCode.UNREACHABLE_RULE }
        assertEquals(AutomationLintSeverity.BLOCKING, finding.severity)
        assertTrue(finding.detail, "event" in finding.detail)
        // An event profile with an enter task is fine.
        val withEnter = AutomationLint.analyze(onEvent.copy(enterTaskId = lightsOff.id), listOf(lightsOff)).findings
        assertTrue(withEnter.toString(), withEnter.none { it.code == AutomationLintCode.UNREACHABLE_RULE })
    }

    @Test
    fun aCooldownDoesNotGuardAnExitOnlyStateProfileButAGracePeriodDoes() {
        val onState = exitOnly.copy(contexts = listOf(ContextSpec(ContextType.STATE, mapOf("state" to "charging"))), cooldownSec = 30)
        fun repeated(profile: Profile, exitTask: Task = lightsOff) =
            AutomationLint.analyze(profile, listOf(exitTask)).findings.filter { it.code == AutomationLintCode.REPEATED_TRIGGERING }

        val unguarded = repeated(onState).single()
        assertTrue(unguarded.suggestedFix, "exit task" in unguarded.suggestedFix)
        assertTrue(repeated(onState.copy(gracePeriodSec = 30)).isEmpty())
        val guardedExit = lightsOff.copy(actions = lightsOff.actions.map { it.copy(condition = "%CHARGING == false") })
        assertTrue(repeated(onState, guardedExit).isEmpty())
        // With an enter task a cooldown still counts.
        assertTrue(repeated(onState.copy(enterTaskId = lightsOff.id)).isEmpty())
    }

    @Test
    fun onlyZeroMeansNoEnterTaskAndANegativeIdIsBroken() {
        val negative = exitOnly.copy(enterTaskId = -1)

        val errors = InputValidation.validateProfile(negative)
        assertTrue(errors.toString(), errors.any { it.field == "enterTaskId" && "negative" in it.message })
        val lossy = RuleDeckBundleCodec.validate(
            RuleDeckBundle(appVersion = "test", exportedAtEpochMs = 0L, tasks = listOf(lightsOff), profiles = listOf(negative)),
        ).lossyWarnings
        assertTrue(lossy.toString(), lossy.single().let { "-1" in it && "skipped" in it })
        // A row that got one anyway (a restored or hand-edited database) stays a broken enter task,
        // not an exit-only profile that would start running.
        assertEquals(-1L, negative.enterTaskIdOrNull)
        val unreachable = AutomationLint.analyze(negative, listOf(lightsOff)).findings.single { it.code == AutomationLintCode.UNREACHABLE_RULE }
        assertTrue(unreachable.detail, "-1" in unreachable.detail && "missing" in unreachable.detail)
    }
}
