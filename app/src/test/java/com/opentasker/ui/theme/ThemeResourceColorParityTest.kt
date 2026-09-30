package com.opentasker.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget, the splash fill and the notification accent come from colors.xml, which Compose
 * never sees. They stayed on the retired sage palette after the move to Quiet Workshop (A-338).
 * Each one is pinned to a named Theme.kt colour here, so the next palette change cannot leave them
 * behind again, and a new resource colour has to be mapped before it can ship.
 */
class ThemeResourceColorParityTest {
    private val themeColors: Map<String, String> by lazy {
        val theme = repoFile("src/main/java/com/opentasker/ui/theme/Theme.kt").readText()
        Regex("""private val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)""").findAll(theme)
            .associate { it.groupValues[1] to "#" + it.groupValues[2].uppercase() } +
            ("Black" to "#000000")
    }

    @Test
    fun lightResourceColoursAreThemeColours() = assertColours(
        "src/main/res/values/colors.xml",
        mapOf(
            "widget_background" to "LightSurface",
            "widget_border" to "LightOutline",
            "widget_primary" to "LightCyan",
            "widget_text_primary" to "LightText",
            "widget_text_secondary" to "LightSubtext",
            "window_background" to "LightBase",
            "notification_accent" to "BrandCyan",
        ),
    )

    @Test
    fun darkResourceColoursAreThemeColours() = assertColours(
        "src/main/res/values-night/colors.xml",
        mapOf(
            "widget_background" to "WorkshopNavy",
            "widget_border" to "WorkshopOutline",
            "widget_primary" to "BrandCyan",
            "widget_text_primary" to "Text",
            "widget_text_secondary" to "TextSecondary",
            "window_background" to "Black",
        ),
    )

    private fun assertColours(path: String, expected: Map<String, String>) {
        val resources = Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""")
            .findAll(repoFile(path).readText())
            .associate { it.groupValues[1] to "#" + it.groupValues[2].uppercase() }
        assertEquals("$path must define exactly the mapped colours", expected.keys, resources.keys)
        expected.forEach { (resource, themeName) ->
            val themeValue = themeColors[themeName] ?: error("Theme.kt no longer defines $themeName")
            assertEquals("$path $resource should be $themeName", themeValue, resources[resource])
        }
    }

    private fun repoFile(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
}
