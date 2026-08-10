package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FlowStructureTest {

    private fun a(type: String) = ActionSpec(type = type)

    @Test
    fun pairsIfElseEndif() {
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.IF), a("x"), a(FlowControl.ELSE), a("y"), a(FlowControl.ENDIF)),
        )
        assertNull(s.error)
        assertEquals(2, s.ifToElse[0])
        assertEquals(4, s.ifToEndif[0])
        assertEquals(4, s.elseToEndif[2])
    }

    @Test
    fun pairsForeachEndfor() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.FOREACH), a("x"), a(FlowControl.ENDFOR)))
        assertNull(s.error)
        assertEquals(2, s.foreachToEndfor[0])
        assertEquals(0, s.endforToForeach[2])
    }

    @Test
    fun ifWithoutElseHasNoElseMapping() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.IF), a("x"), a(FlowControl.ENDIF)))
        assertNull(s.error)
        assertNull(s.ifToElse[0])
        assertEquals(2, s.ifToEndif[0])
    }

    @Test
    fun detectsUnclosedIf() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.IF), a("x")))
        assertNotNull(s.error)
    }

    @Test
    fun detectsStrayEndif() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.ENDIF)))
        assertNotNull(s.error)
    }

    @Test
    fun detectsCrossedNesting() {
        // foreach ... if ... endfor ... endif is invalid nesting
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.FOREACH), a(FlowControl.IF), a(FlowControl.ENDFOR), a(FlowControl.ENDIF)),
        )
        assertNotNull(s.error)
    }

    @Test
    fun pairsTryCatchEndtry() {
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.TRY), a("x"), a(FlowControl.CATCH), a("y"), a(FlowControl.ENDTRY)),
        )
        assertNull(s.error)
        assertEquals(2, s.tryToCatch[0])
        assertEquals(4, s.tryToEndtry[0])
        assertEquals(4, s.catchToEndtry[2])
    }

    @Test
    fun rejectsInvalidTryBoundsAndDuplicateCatch() {
        assertNotNull(FlowStructure.analyze(listOf(ActionSpec(type = FlowControl.TRY, args = mapOf("max_attempts" to "6")), a(FlowControl.ENDTRY))).error)
        assertNotNull(FlowStructure.analyze(listOf(a(FlowControl.TRY), a(FlowControl.CATCH), a(FlowControl.CATCH), a(FlowControl.ENDTRY))).error)
    }

    @Test
    fun retryPlanSeparatesBodyActionsFromFlowMarkers() {
        val actions = listOf(
            a(FlowControl.TRY),
            a("safe.read"),
            a(FlowControl.IF),
            a("unsafe.send"),
            a(FlowControl.ENDIF),
            a(FlowControl.CATCH),
            a(FlowControl.ENDTRY),
        )

        val plan = tryRetryPlan(actions, tryIndex = 0) { action ->
            when (action.type) {
                "safe.read" -> ActionRetrySafety.IDEMPOTENT
                "unsafe.send" -> ActionRetrySafety.NEVER
                else -> null
            }
        }

        assertEquals(listOf("safe.read"), plan.retryableActionIds)
        assertEquals(listOf("unsafe.send"), plan.nonRetryableActionIds)
    }
}
