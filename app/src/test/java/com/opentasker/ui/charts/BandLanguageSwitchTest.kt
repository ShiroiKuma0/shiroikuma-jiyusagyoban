package com.opentasker.ui.charts

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 日本語／英語 pill's rewrite of `健康の設定 -- [727][01]`.
 *
 * The task is 白い熊's, and the pill edits it in place — so what matters most here is not that the
 * value changes but that NOTHING ELSE does. The labels in that task are the bilingual documentation
 * of every band setting, and they are the thing a careless `copy` would flatten.
 */
class BandLanguageSwitchTest {

    private val variable = BandLanguageSwitch.VARIABLE

    /** A miniature of the real settings task: documented labels, several variables, a closing flash. */
    private fun settingsTask(language: String = "en-US", name: String = BandLanguageSwitch.SETTINGS_TASK) = Task(
        id = 7,
        name = name,
        actions = listOf(
            ActionSpec(
                type = "var.set",
                label = "バンドのBluetoothアドレス。\n\nThe band's Bluetooth address.",
                args = mapOf("name" to "Band_Address", "value" to "D5:A7:06:DC:A1:3A"),
            ),
            ActionSpec(
                type = "var.set",
                label = "「健康」の画面表示の言語。\n\nDisplay language for the 健康 window.",
                args = mapOf("name" to variable, "value" to language),
            ),
            ActionSpec(
                type = "flash",
                label = "設定を読み込んだことを知らせる。",
                args = mapOf("text" to "健康の設定 ✓ 表示 %Band_Language"),
            ),
        ),
    )

    @Test
    fun `the language value is replaced`() {
        val out = BandLanguageSwitch.retarget(settingsTask("en-US"), variable, "ja-JP")
        assertEquals("ja-JP", out.actions[1].args["value"])
    }

    @Test
    fun `the documented labels survive the rewrite untouched`() {
        // The labels are 白い熊's documentation, not the app's strings; switching display language
        // must not paraphrase, translate or drop a single one of them.
        val before = settingsTask("en-US")
        val after = BandLanguageSwitch.retarget(before, variable, "ja-JP")
        assertEquals(before.actions.map { it.label }, after.actions.map { it.label })
        assertEquals(before.actions.map { it.type }, after.actions.map { it.type })
        assertEquals(before.name, after.name)
        assertEquals(before.id, after.id)
    }

    @Test
    fun `every other variable keeps its value`() {
        val after = BandLanguageSwitch.retarget(settingsTask(), variable, "ja-JP")
        assertEquals("D5:A7:06:DC:A1:3A", after.actions[0].args["value"])
        assertEquals("健康の設定 ✓ 表示 %Band_Language", after.actions[2].args["text"])
    }

    @Test
    fun `the variable's name argument is not disturbed`() {
        // A rewrite that replaced the whole args map would take `name` with it and quietly turn the
        // action into a var.set of nothing.
        val after = BandLanguageSwitch.retarget(settingsTask(), variable, "ja-JP")
        assertEquals(variable, after.actions[1].args["name"])
    }

    @Test
    fun `a task that does not set the variable comes back unchanged`() {
        val other = Task(name = "同期 -- [727]", actions = listOf(ActionSpec(type = "band.sync")))
        assertEquals(other, BandLanguageSwitch.retarget(other, variable, "ja-JP"))
    }

    @Test
    fun `switching twice returns to where it started`() {
        assertEquals(BandLanguage.JA, BandLanguageSwitch.other(BandLanguage.EN))
        assertEquals(BandLanguage.EN, BandLanguageSwitch.other(BandLanguage.JA))
    }

    @Test
    fun `the written tag is one the window can parse back`() {
        // retarget writes BandLanguage.tag; parse is what BandChartsActivity reads it with. If these
        // two ever disagree the pill would appear to do nothing at all.
        BandLanguage.entries.forEach { lang ->
            val out = BandLanguageSwitch.retarget(settingsTask(), variable, lang.tag)
            assertEquals(lang, BandLanguage.parse(out.actions[1].args["value"]))
        }
    }

    @Test
    fun `the settings task is recognised by what it sets`() {
        assertTrue(BandLanguageSwitch.definesVariable(settingsTask(), variable))
        assertTrue(
            "renaming the task must not hide it",
            BandLanguageSwitch.definesVariable(settingsTask(name = "健康の設定"), variable),
        )
    }

    @Test
    fun `a lone candidate is chosen whatever it is called`() {
        val renamed = settingsTask(name = "設定")
        assertSame(renamed, BandLanguageSwitch.choose(listOf(renamed)))
    }

    @Test
    fun `two candidates are broken by the canonical name`() {
        val decoy = settingsTask(name = "健康の設定 -- 古い")
        val real = settingsTask()
        assertEquals(real, BandLanguageSwitch.choose(listOf(decoy, real)))
    }

    @Test
    fun `a genuinely ambiguous workspace is refused rather than guessed at`() {
        val a = settingsTask(name = "設定 A")
        val b = settingsTask(name = "設定 B")
        assertNull(BandLanguageSwitch.choose(listOf(a, b)))
        assertNull(BandLanguageSwitch.choose(emptyList()))
    }
}
