package com.opentasker.core.actions

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What 「起動作業を作る」 does when two installed apps answer to the same name.
 *
 * Not a hypothetical: `com.rovio.angrybirdsgo`, `com.rovio.angrybirdstransformers`,
 * `com.rovio.angrybirdsstarwarsii.ads` and `com.rovio.angrybirdsstarwarshd.premium.iap` are all four
 * installed on 白い熊's phone and all four report the label `Angry Birds` (checked with `aapt2 dump
 * badging` on the APKs themselves, 2026-09-20). One of them had a task, so the generator's
 * name-based duplicate check swallowed every other one and reported success having created nothing.
 */
class LauncherTaskNamingTest {

    private val suffix = " -- [1707][7107]"

    @Test
    fun `an unused label keeps the plain name`() {
        assertEquals(
            "Angry Birds -- [1707][7107]",
            uniqueTaskName("Angry Birds", suffix, "com.rovio.angrybirdsgo", emptySet()),
        )
    }

    /** The real case: the name exists, so the package qualifies it — and the suffix stays the TAIL. */
    @Test
    fun `a taken label is qualified with the package, before the suffix`() {
        val name = uniqueTaskName(
            "Angry Birds",
            suffix,
            "com.rovio.angrybirdsgo",
            setOf("Angry Birds -- [1707][7107]"),
        )
        assertEquals("Angry Birds (com.rovio.angrybirdsgo) -- [1707][7107]", name)
        assertTrue("the group's suffix has to stay at the end", name.endsWith(suffix))
    }

    /**
     * Four apps, one label, one pass — every name distinct and none of them the one already there.
     *
     * A UNIQUE (projectId, name) index sits behind this: a repeat is not a cosmetic duplicate, it is
     * an insert that throws.
     */
    @Test
    fun `four apps sharing one label all get a name of their own`() {
        val taken = mutableSetOf("Angry Birds -- [1707][7107]")
        val made = listOf(
            "com.rovio.angrybirdsgo",
            "com.rovio.angrybirdstransformers",
            "com.rovio.angrybirdsstarwarsii.ads",
        ).map { pkg ->
            uniqueTaskName("Angry Birds", suffix, pkg, taken).also { taken += it }
        }
        assertEquals("every one distinct", made.size, made.distinct().size)
        assertFalse("and none of them the existing task", "Angry Birds -- [1707][7107]" in made)
        assertEquals(4, taken.size)
    }

    /** Even a name collision ON the qualified form resolves rather than returning a duplicate. */
    @Test
    fun `a qualified name that is also taken keeps counting`() {
        val taken = setOf(
            "Angry Birds -- [1707][7107]",
            "Angry Birds (com.rovio.angrybirdsgo) -- [1707][7107]",
        )
        val name = uniqueTaskName("Angry Birds", suffix, "com.rovio.angrybirdsgo", taken)
        assertFalse("a returned name is never one that is taken", name in taken)
        assertTrue(name.endsWith(suffix))
    }

    /**
     * The duplicate test is by PACKAGE, and the grid says which apps already have a task.
     *
     * Both are the same decision — an app is its package — and both were the bug: the check keyed off
     * the label, and the picker was opened with no pre-ticks at all, so there was nothing on screen
     * to say the app 白い熊 was picking was already covered (白い熊, 2026-09-20).
     */
    @Test
    fun `the generator identifies an app by its package, and pre-ticks the covered ones`() {
        val src = ProductionSources.read("com/opentasker/core/actions/MakeLauncherTasksAction.kt")
        assertTrue("the skip is by package", "if (pkg in covered) { already++; continue }" in src)
        assertFalse("never again by the generated name", "if (taskName in existingNamesInGroup)" in src)
        assertTrue(
            "the grid opens with the covered apps ticked",
            "putExtra(DialogActivity.EXTRA_PRESELECTED, ticked.joinToString(\"\\n\"))" in src,
        )
        assertTrue(
            "and only where there is a tile to tick — an unresolvable package would be a bare id " +
                "stand-in at the top of the generator's grid",
            "pm.getApplicationInfo(pkg, AppFreeze.MATCH_FROZEN)" in src,
        )
        assertTrue(
            "and what counts as covered is what the tasks LAUNCH",
            "FreezeBubbleTarget.packageOf(actions)" in src,
        )
    }

    /** "Created 0" has two meanings and the run log has to separate them. */
    @Test
    fun `the run log says how many were already there`() {
        val src = ProductionSources.read("com/opentasker/core/actions/MakeLauncherTasksAction.kt")
        assertTrue("(\$skipped already had one)" in src)
    }
}
