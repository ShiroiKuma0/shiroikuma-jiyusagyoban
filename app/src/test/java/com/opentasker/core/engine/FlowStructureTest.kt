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
}
