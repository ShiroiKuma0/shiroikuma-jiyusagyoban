package com.opentasker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order a roster grid draws in — 白い熊, 2026-09-13.
 *
 * Installed and ticked, installed and unticked, then the apps that are not on this phone in the same
 * two groups. The fixtures are 凍結融解's real situation: a workspace that outlived a phone, where 36
 * of 56 entries name apps that were never installed here.
 */
class RosterOrderTest {

    private fun app(label: String, pkg: String = label) = PickableApp(label, pkg)

    /** Resolved apps carry real labels; absent ones are labelled by their package id. */
    private val installed = listOf(
        app("WhatsApp", "com.whatsapp"),
        app("Claude", "com.anthropic.claude"),
        app("Raiffeisen", "cz.rb.app.smartphonebanking"),
    )
    private val absent = listOf(
        app("org.fdroid.fdroid"),
        app("com.twofasapp"),
        app("yqtrack.app"),
    )

    @Test
    fun `installed come before absent, ticked before unticked`() {
        val order = rosterOrder(
            installed, absent,
            preselected = setOf("com.whatsapp", "com.twofasapp"),
        ).map { it.pkg }
        assertEquals(
            listOf(
                "com.whatsapp",                  // installed + ticked
                "com.anthropic.claude",          // installed, unticked, alphabetical by label
                "cz.rb.app.smartphonebanking",
                "com.twofasapp",                 // absent + ticked
                "org.fdroid.fdroid",             // absent, unticked, alphabetical
                "yqtrack.app",
            ),
            order,
        )
    }

    /**
     * The 56-entry case that prompted it: a handful installed among many that are not.
     *
     * Before this, the absent ones were prepended and filled the first screen with package ids while
     * every app 白い熊 could act on sat below the fold.
     */
    @Test
    fun `a mostly-absent roster still leads with what is installed`() {
        val many = (1..36).map { app("z.absent.$it") }
        val order = rosterOrder(installed, many, preselected = emptySet())
        assertEquals(
            "the first three tiles must be the installed apps",
            listOf("Claude", "Raiffeisen", "WhatsApp"),
            order.take(3).map { it.label },
        )
        assertTrue("and the rest are the absent ones", order.drop(3).all { it.label.startsWith("z.absent") })
    }

    /**
     * Ordering keys off the INITIAL selection, so ticking a tile does not re-sort under the finger.
     *
     * Asserted by passing a selection that disagrees with nothing in the inputs: the function has no
     * access to live state at all, which is what makes the property hold.
     */
    @Test
    fun `order depends only on the selection it is given`() {
        val a = rosterOrder(installed, absent, preselected = setOf("com.whatsapp"))
        val b = rosterOrder(installed, absent, preselected = setOf("com.whatsapp"))
        assertEquals(a, b)
    }

    /** A package appearing in both lists is resolved once — the installed reading wins. */
    @Test
    fun `a duplicate is kept once, as installed`() {
        val order = rosterOrder(
            installed,
            absent + app("com.whatsapp"),
            preselected = emptySet(),
        )
        assertEquals(1, order.count { it.pkg == "com.whatsapp" })
        assertEquals("WhatsApp", order.first { it.pkg == "com.whatsapp" }.label)
        assertTrue("and stays in the installed block", order.indexOfFirst { it.pkg == "com.whatsapp" } < 3)
    }

    @Test
    fun `empty inputs are handled`() {
        assertTrue(rosterOrder(emptyList(), emptyList(), emptySet()).isEmpty())
        assertEquals(3, rosterOrder(emptyList(), absent, emptySet()).size)
        assertEquals(3, rosterOrder(installed, emptyList(), emptySet()).size)
    }
}
