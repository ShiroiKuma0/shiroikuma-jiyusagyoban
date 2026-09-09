package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `%%` is a literal percent.
 *
 * 「電池 %HUAWEI_BatteryPct%%」 printed 「電池 19%%」 on the sync watchdog's notification — the one
 * line meant to be read at a glance (白い熊, 2026-09-08). Nothing consumed the pair, so the variable
 * expanded and both signs survived.
 */
class PercentEscapeTest {

    private fun expand(s: String, vars: Map<String, String> = emptyMap()): String {
        val store = VariableStore()
        vars.forEach { (k, v) -> store.set(k, v) }
        return store.expand(s)
    }

    @Test
    fun `a doubled percent collapses to one`() {
        assertEquals("100%", expand("100%%"))
        assertEquals("%", expand("%%"))
    }

    @Test
    fun `the real case — a percent immediately after a variable`() {
        assertEquals("battery 19%", expand("battery %Batt%%", mapOf("Batt" to "19")))
        assertEquals("電池 19% ／ ファーム x", expand("電池 %Batt%% ／ ファーム x", mapOf("Batt" to "19")))
    }

    @Test
    fun `a lone percent and a variable are untouched`() {
        // The escape must not eat a percent that is doing nothing, nor one that begins a variable.
        assertEquals("50% of it", expand("50% of it"))
        assertEquals("19", expand("%Batt", mapOf("Batt" to "19")))
        assertEquals("19%x", expand("%Batt%%x", mapOf("Batt" to "19")))
    }
}
