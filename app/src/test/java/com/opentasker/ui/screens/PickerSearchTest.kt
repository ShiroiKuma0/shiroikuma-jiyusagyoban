package com.opentasker.ui.screens

import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.model.ContextType
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerSearchTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app")).first { it.resolve("src").toFile().exists() }

    @Test
    fun actionSearchMatchesNameDescriptionAndIdAndHandlesNoMatch() {
        val items = listOf(
            LocalizedActionMetadata(
                metadata = ActionMetadata("notify.show", 0, 0, 0),
                name = "Show notification",
                description = "Display a message to the user",
                category = "UI",
            ),
            LocalizedActionMetadata(
                metadata = ActionMetadata("file.read", 0, 0, 0),
                name = "Read file",
                description = "Load text into a variable",
                category = "Files",
            ),
        )

        assertEquals(listOf("notify.show"), filterActionPickerItems(items, "notification").map { it.metadata.id })
        assertEquals(listOf("file.read"), filterActionPickerItems(items, "FILE.READ").map { it.metadata.id })
        assertTrue(filterActionPickerItems(items, "does-not-exist").isEmpty())
        assertEquals(items, filterActionPickerItems(items, "  "))
    }

    @Test
    fun contextSearchMatchesLocalizedCopyAndEnumId() {
        val items = listOf(
            LocalizedContextType(ContextType.APPLICATION, "Application", "When an app is in the foreground"),
            LocalizedContextType(ContextType.LOCATION, "Location", "When the device enters an area"),
        )

        assertEquals(listOf(ContextType.APPLICATION), filterContextPickerItems(items, "foreground").map { it.type })
        assertEquals(listOf(ContextType.LOCATION), filterContextPickerItems(items, "location").map { it.type })
        assertTrue(filterContextPickerItems(items, "missing").isEmpty())
    }

    @Test
    fun pickerCatalogsAreRememberedAcrossRecomposition() {
        val actionSource = moduleRoot.resolve("src/main/java/com/opentasker/ui/screens/ActionEditorDialogs.kt").readText()
        val contextSource = moduleRoot.resolve("src/main/java/com/opentasker/ui/screens/ContextEditorDialogs.kt").readText()

        assertTrue(actionSource.contains("val localizedActions = remember(configuration)"))
        assertTrue(contextSource.contains("val localizedTypes = remember(configuration)"))
    }
}
