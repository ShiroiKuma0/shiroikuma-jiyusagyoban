package com.opentasker.core.policy

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freeze rules that are decisions rather than mechanics, pinned so a later edit has to argue
 * with them. The mechanics themselves need a device — every call goes through PackageManager or
 * DevicePolicyManager — so what is checkable here is the policy, and it is the half that bites.
 */
class AppFreezePolicyTest {

    /**
     * 白い熊, 2026-09-05: these three must never be frozen by anything in this app.
     *
     * Not a style rule. Freezing 雫 strands every policy lock on the phone (only the owner or a
     * delegate can lift one), freezing 応用管理 removes the interactive way back, and freezing this
     * app stops the engine that would run the thaw. Any of the three is a state with no route out
     * except a factory reset.
     */
    @Test
    fun `the three packages that must never be frozen are named and explained`() {
        assertEquals(
            setOf("shiroikuma.shizuku", "shiroikuma.oyokanri", "shiroikuma.jiyusagyoban"),
            AppFreeze.PROTECTED,
        )
        AppFreeze.PROTECTED.forEach { pkg ->
            val reason = AppFreeze.protectedReason(pkg)
            assertNotNull("$pkg must carry a reason a log line can print", reason)
            assertTrue("$pkg's reason must say something", reason!!.isNotBlank())
        }
        assertNull("an ordinary app must stay freezable", AppFreeze.protectedReason("com.anthropic.claude"))
    }

    /**
     * A bubble is a re-freeze button, so the guard has to sit on the store every bubble passes
     * through — not on the two call sites, which is where a third one would miss it.
     */
    @Test
    fun `no bubble can be queued for a protected package`() {
        val store = ProductionSources.read("com/opentasker/core/bubbles/FreezeBubbleStore.kt")
        val enqueue = ProductionSources.block(
            "com/opentasker/core/bubbles/FreezeBubbleStore.kt",
            "fun enqueue(",
            "val current = _bubbles.value",
        )
        assertTrue(
            "enqueue must refuse a protected package before it stores anything",
            "AppFreeze.protectedReason(pkg) != null" in enqueue,
        )
        assertTrue("the guard belongs to the store, not its callers", "protectedReason" in store)
    }

    /**
     * The incident this whole file exists for: `pm enable` cleared one slot of three, exited 0, and
     * the action reported success. Every slot, then a re-read — never an exit code.
     */
    @Test
    fun `thawing clears every slot and verifies rather than trusting an exit code`() {
        val thaw = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun thaw(",
            "/** `ApplicationInfo.FLAG_SUSPENDED`",
        )
        // In order, because the cheap shell clears come before the one binder call that can refuse.
        val steps = listOf(
            "pm unsuspend",                                         // the shell's suspension slot
            "DevicePolicyBridge.setSuspended(context, pkg, false)", // the owner's slot
            "pm enable",                                            // the enabled-state slot
        )
        var at = -1
        steps.forEach { step ->
            val next = thaw.indexOf(step)
            assertTrue("thaw must clear the slot written by $step", next >= 0)
            assertTrue("the slots must be cleared in order; $step came early", next > at)
            at = next
        }
        assertTrue(
            "thaw must answer from a fresh read, not from what the writes returned",
            "return !read(context, pkg).frozen" in thaw,
        )
        assertEquals(
            "each step is wrapped so a failure cannot stop the next one",
            steps.size,
            Regex("runCatching").findAll(thaw).count(),
        )
    }

    /**
     * `app.frozen` is the pre-flight every thaw-work-refreeze caller branches on, and it must stay
     * privilege-free: a phone with neither Shizuku nor a delegation still deserves a true answer.
     */
    @Test
    fun `the frozen read stays privilege-free and covers suspension`() {
        val read = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun read(",
            "/** What [freeze] did",
        )
        assertTrue("suspension is a public flag; read it", "FLAG_SUSPENDED" in read)
        assertTrue("the enabled state is the other half", "getApplicationEnabledSetting" in read)
        assertTrue(
            "reading state must never need the shell",
            "ShizukuShell" !in read,
        )
    }
}
