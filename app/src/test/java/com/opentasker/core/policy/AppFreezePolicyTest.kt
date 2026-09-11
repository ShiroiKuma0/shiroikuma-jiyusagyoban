package com.opentasker.core.policy

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

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
     * the action reported success. Every gate, then a re-read — never an exit code.
     *
     * The order is load-bearing at the front. **Unhide first**: while the hidden gate is set the
     * platform answers `NameNotFoundException` for the package, and every later call argues with a
     * lookup that says it is not installed. 応用管理's +29 fixed exactly this bug on their side.
     */
    @Test
    fun `thawing clears every gate and verifies rather than trusting an exit code`() {
        val thaw = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun thaw(",
            "/**\n     * Why [pkg] is still held",
        )
        // In order: the gate that makes the package unreadable, then the two suspensions, then the
        // enabled state.
        val steps = listOf(
            "DevicePolicyBridge.setHidden(context, pkg, false)",    // the hidden gate, first of all
            "pm unsuspend",                                         // the shell's suspension slot
            "DevicePolicyBridge.setSuspended(context, pkg, false)", // the owner's slot
            "pm enable",                                            // the enabled-state slot
        )
        var at = -1
        steps.forEach { step ->
            val next = thaw.indexOf(step)
            assertTrue("thaw must clear the gate written by $step", next >= 0)
            assertTrue("the gates must be cleared in order; $step came early", next > at)
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
    fun `the frozen read stays privilege-free and covers suspension and hiding`() {
        val read = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun read(",
            "/** One gate of a freeze",
        )
        assertTrue("suspension is a public flag; read it", "FLAG_SUSPENDED" in read)
        assertTrue("the enabled state is the other half", "getApplicationEnabledSetting" in read)
        assertTrue(
            "reading state must never need the shell",
            "ShizukuShell" !in read,
        )
        // Hidden has no public flag, so it is read by difference: invisible under plain flags,
        // present under MATCH_FROZEN. Asking the delegate is the corroboration, never the only way
        // — a phone whose delegation is gone is exactly the one that has to answer this.
        assertTrue("a hidden app must be found at all", "MATCH_FROZEN" in read)
        assertTrue("hidden is the difference between the two lookups", "visible == null" in read)
        // The inverse of the bug, and the more dangerous direction: MATCH_UNINSTALLED_PACKAGES also
        // answers for a package that is only remembered (a system app uninstalled for user 0 —
        // 102 of them on this phone), and calling those hidden would report a ghost as frozen.
        assertTrue(
            "a remembered package must not read as hidden; FLAG_INSTALLED is what separates them",
            "FLAG_INSTALLED" in read,
        )
        assertTrue("and it must answer absent, not frozen", "ABSENT.copy(remembered = true)" in read)
    }

    /**
     * Frozen is a claim about an app that is here. Every gate boolean can survive on a package the
     * platform merely remembers, so the installed check has to sit inside `frozen` itself rather than
     * beside each of its readers — one caller forgetting it is a thaw against nothing.
     */
    @Test
    fun `nothing absent can read as frozen`() {
        val state = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "data class State(",
            "fun read(",
        )
        assertTrue(
            "frozen must require the app to be present",
            "get() = installed && (disabled || suspended || hidden)" in state,
        )
    }

    /**
     * 応用管理's "Total freeze" sets four gates; a freeze here that set fewer would leave every app
     * that passes through a launcher task quietly weaker than it was found, because [AppFreeze.thaw]
     * clears all four on the way in.
     *
     * Hiding goes last for the same reason it is undone first: after it, the package is not there to
     * be written to.
     */
    @Test
    fun `freezing applies the same four gates as 応用管理, hiding last`() {
        val freeze = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun freeze(",
            "/**\n     * Clear every gate",
        )
        val gates = listOf(
            "am force-stop",                                   // what is running now
            "setSuspended(context, pkg, true)",                // the owner's suspension
            "pm disable-user",                                 // the enabled state
            "setHidden(context, pkg, true)",                   // hidden, and nothing after it
        )
        var at = -1
        gates.forEach { gate ->
            val next = freeze.indexOf(gate)
            assertTrue("freeze must apply the gate written by $gate", next >= 0)
            assertTrue("the gates must be applied in order; $gate came early", next > at)
            at = next
        }
        assertTrue(
            "the verdict must come from a fresh read, not from what the writes returned",
            "read(context, pkg).frozen" in freeze,
        )
    }

    /**
     * A failed defrost has two causes needing opposite fixes — a policy gate we are not a delegate
     * for, and an enabled-state gate with no Shizuku to lift it. "A lock is still held" pointed at
     * neither, so the message has to name the delegation by the name 白い熊 will look for in 雫.
     */
    @Test
    fun `a stuck app says which power is missing`() {
        val stuck = ProductionSources.block(
            "com/opentasker/core/policy/AppFreeze.kt",
            "fun stuckReason(",
            "/** `sh -c <command>`",
        )
        assertTrue("name the scope", "DELEGATION_PACKAGE_ACCESS" in stuck)
        assertTrue("name the app that grants it", "雫" in stuck)
        assertTrue("the other cause is a missing shell", "Shizuku" in stuck)
    }

    /**
     * The gate that would have caught 2026-09-10 before the phone did: `MATCH_DISABLED_COMPONENTS`
     * alone cannot see a hidden package, so every lookup that may run against a frozen app has to
     * ask for uninstalled ones too — which is what [AppFreeze.MATCH_FROZEN] is.
     *
     * AppFreeze itself is exempt: the difference between the two lookups is precisely how it detects
     * hiding without a privilege.
     */
    @Test
    fun `no lookup asks for disabled components without also asking for hidden ones`() {
        val offenders = ProductionSources.allKotlinFiles()
            .filterNot { it.toString().endsWith("core/policy/AppFreeze.kt") }
            .flatMap { file ->
                val lines = file.readText().split("\n")
                lines.withIndex()
                    .filter { (_, line) -> "MATCH_DISABLED_COMPONENTS" in line }
                    .filterNot { (index, _) ->
                        // The two flags are often ORed across a wrapped expression.
                        (index - 1..index + 1).any { neighbour ->
                            lines.getOrNull(neighbour)?.contains("MATCH_UNINSTALLED_PACKAGES") == true
                        }
                    }
                    .map { (index, line) -> "${ProductionSources.repoRoot.relativize(file)}:${index + 1}: ${line.trim()}" }
            }
        assertEquals(
            "these lookups report a device-policy-hidden app as not installed; use AppFreeze.MATCH_FROZEN",
            emptyList<String>(),
            offenders,
        )
    }
}
