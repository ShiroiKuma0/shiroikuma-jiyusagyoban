package com.opentasker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSharePreviewTest {
    @Test
    fun defaultSlugIsStableAndManifestSafe() {
        assertEquals("morning-routine", defaultProfileShareSlug("Morning routine"))
        assertEquals("opentasker-share", defaultProfileShareSlug("--"))
    }

    @Test
    fun defaultSlugIsBoundedForLongWorkspaceNames() {
        val slug = defaultProfileShareSlug("a".repeat(120))

        assertEquals(64, slug.length)
        assertEquals(slug, slug.lowercase())
    }
}
