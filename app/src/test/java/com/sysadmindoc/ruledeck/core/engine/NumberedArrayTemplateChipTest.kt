package com.sysadmindoc.ruledeck.core.engine

import com.sysadmindoc.ruledeck.core.expressions.TemplateExpressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `{{ array.x }}` chip Variable Split, Array Push and Replace Text offer reads their numbered
 * elements (`%x1`, `%x2`, ...) in any field, the way For Each reads it, not just a list Split Text kept.
 */
class NumberedArrayTemplateChipTest {

    private fun expand(store: VariableStore, text: String) = TemplateExpressionEngine().expand(text, store.toTemplateScope())

    @Test
    fun theChipReadsNumberedElementsInIndexOrder() {
        val store = VariableStore().apply {
            set("found2", "b")
            set("found1", "a")
            set("found4", "d")
        }
        assertEquals("a,b,d|b|3", expand(store, "{{ array.found }}|{{ array.found[1] }}|{{ array.found[#] }}").value)
    }

    @Test
    fun aGlobalArrayReadsToo() {
        val store = VariableStore().apply {
            set("Found1", "x")
            set("Found2", "y")
        }
        assertEquals("x,y", expand(store, "{{ array.Found }}").value)
    }

    @Test
    fun aSecretElementMarksTheWholeRead() {
        val store = VariableStore().apply {
            set("pins1", "1111")
            set("pins2", "2222", sensitive = true)
        }
        val result = expand(store, "{{ array.pins }}")
        assertEquals("1111,2222", result.value)
        assertTrue(result.traces.single().isSecretDerived)
        assertFalse(expand(VariableStore().apply { set("ok1", "1") }, "{{ array.ok }}").traces.single().isSecretDerived)
    }

    @Test
    fun aKeptListStillWinsAndNothingIsStillMissing() {
        val store = VariableStore().apply {
            setArray("parts", listOf("kept"))
            set("parts1", "numbered")
        }
        assertEquals("kept", expand(store, "{{ array.parts }}").value)
        assertEquals("", expand(VariableStore(), "{{ array.none }}").value)
    }
}
