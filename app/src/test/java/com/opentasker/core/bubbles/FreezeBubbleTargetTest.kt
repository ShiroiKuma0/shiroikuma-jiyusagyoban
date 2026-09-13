package com.opentasker.core.bubbles

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which packages a freeze bubble is for, and which it re-freezes.
 *
 * The two are different questions and conflating them was a real bug: a bubble re-froze the app it
 * was labelled with and left the companion the same task had thawed still running — silently, since
 * a bubble vanishing looks exactly like a bubble that did its job.
 *
 * The fixtures are 白い熊's own launcher tasks, read out of the workspace mirror.
 */
class FreezeBubbleTargetTest {

    private fun unfreeze(pkg: String) = ActionSpec(type = "app.unfreeze", args = mapOf("package" to pkg))
    private fun launch(pkg: String) = ActionSpec(type = "app.launch", args = mapOf("package" to pkg))
    private val asIs: (String) -> String = { it }

    /** `RaiPay -- [1707][7107]`: thaws the bank, then the wallet, then opens the wallet. */
    private val raiPay = listOf(
        unfreeze("cz.rb.app.smartphonebanking"),
        unfreeze("cz.raiffeisen.mobilepay"),
        launch("cz.raiffeisen.mobilepay"),
    )

    /** `ČSOB Smart -- [1707][7107]`: thaws the key app, then Smart, then opens Smart. */
    private val csob = listOf(
        unfreeze("cz.csob.smartklic"),
        unfreeze("cz.csob.smart"),
        launch("cz.csob.smart"),
    )

    /** The 57 ordinary launchers: thaw one, open the same one. */
    private val ordinary = listOf(unfreeze("com.anthropic.claude"), launch("com.anthropic.claude"))

    @Test
    fun `a banking pair yields both packages, launch target first`() {
        assertEquals(
            listOf("cz.raiffeisen.mobilepay", "cz.rb.app.smartphonebanking"),
            FreezeBubbleTarget.packagesOf(raiPay, asIs),
        )
        assertEquals(
            listOf("cz.csob.smart", "cz.csob.smartklic"),
            FreezeBubbleTarget.packagesOf(csob, asIs),
        )
    }

    /**
     * The bubble's identity is still the app it opened — its icon, its label, its dedupe key.
     *
     * This is what stops the change from moving the bubble onto the companion app: RaiPay's bubble
     * must stay RaiPay's, and only what it FREEZES grows.
     */
    @Test
    fun `the bubble is still identified by the app it launches`() {
        assertEquals("cz.raiffeisen.mobilepay", FreezeBubbleTarget.packageOf(raiPay, asIs))
        assertEquals("cz.csob.smart", FreezeBubbleTarget.packageOf(csob, asIs))
    }

    /** An ordinary launcher thaws and opens one app, and must not report it twice. */
    @Test
    fun `a package thawed and launched appears once`() {
        assertEquals(listOf("com.anthropic.claude"), FreezeBubbleTarget.packagesOf(ordinary, asIs))
    }

    /**
     * A package still carrying an unexpanded `%var` is dropped, not passed on.
     *
     * A freeze aimed at the literal text `%App` is worse than no freeze: it fails somewhere far from
     * here, and the bubble has already been retired by then.
     */
    @Test
    fun `an unresolved variable is dropped`() {
        val actions = listOf(unfreeze("%Companion"), unfreeze("com.example.real"), launch("%App"))
        assertEquals(listOf("com.example.real"), FreezeBubbleTarget.packagesOf(actions, asIs))
    }

    /** …but an expanded one is kept — the expander is the caller's, and it is trusted. */
    @Test
    fun `a variable that expands is kept`() {
        val actions = listOf(unfreeze("%Companion"), launch("com.example.app"))
        val expand: (String) -> String = { if (it == "%Companion") "com.example.helper" else it }
        assertEquals(
            listOf("com.example.app", "com.example.helper"),
            FreezeBubbleTarget.packagesOf(actions, expand),
        )
    }

    @Test
    fun `a task that names no app yields nothing`() {
        val actions = listOf(ActionSpec(type = "flash", args = mapOf("text" to "hello")))
        assertTrue(FreezeBubbleTarget.packagesOf(actions, asIs).isEmpty())
        assertNull(FreezeBubbleTarget.packageOf(actions, asIs))
    }

    /**
     * A task that only thaws — no launch — still yields its package.
     *
     * `packageOf` has always accepted this shape (`app.unfreeze` was its second choice), and the
     * ordering rule must not quietly drop it now that launch comes first.
     */
    @Test
    fun `a thaw-only task still names its app`() {
        val actions = listOf(unfreeze("com.example.only"))
        assertEquals(listOf("com.example.only"), FreezeBubbleTarget.packagesOf(actions, asIs))
        assertEquals("com.example.only", FreezeBubbleTarget.packageOf(actions, asIs))
    }

    /**
     * [FreezeBubbleTarget.packageOf] is the head of [FreezeBubbleTarget.packagesOf], always.
     *
     * They are one rule with two readings, and the whole point of deriving the second from the first
     * is that a later edit cannot make them disagree.
     */
    @Test
    fun `packageOf is the head of packagesOf`() {
        for (actions in listOf(raiPay, csob, ordinary, emptyList())) {
            assertEquals(
                FreezeBubbleTarget.packagesOf(actions, asIs).firstOrNull(),
                FreezeBubbleTarget.packageOf(actions, asIs),
            )
        }
    }
}
