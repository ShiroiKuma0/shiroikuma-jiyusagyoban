package com.opentasker.ui.screens

import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.model.ContextType
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream's picker-search contract, restated for the fork's metadata.
 *
 * Upstream filters a `LocalizedActionMetadata` triple built by resolving three `@StringRes` ids per
 * action, so its version of this test constructs `ActionMetadata(id, nameRes, descriptionRes,
 * categoryRes)` from integers. The fork keeps name/description/category as inline strings on
 * [ActionMetadata] itself, so the filter runs over the metadata directly and the fixtures are plain
 * strings. The behaviour asserted is the same: match on display name, description or stable id;
 * empty query returns everything; no match returns nothing.
 */
class PickerSearchTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app")).first { it.resolve("src").toFile().exists() }

    @Test
    fun actionSearchMatchesNameDescriptionAndIdAndHandlesNoMatch() {
        val items = listOf(
            ActionMetadata("notify.show", "Show notification", "Display a message to the user", "UI"),
            ActionMetadata("file.read", "Read file", "Load text into a variable", "Files"),
        )

        assertEquals(listOf("notify.show"), filterActionPickerItems(items, "notification").map { it.id })
        assertEquals(listOf("file.read"), filterActionPickerItems(items, "FILE.READ").map { it.id })
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

    /**
     * Both catalogues must be built inside `remember`, not rebuilt on every recomposition — typing in
     * the search field recomposes the dialog on every keystroke. Upstream keys the `remember` on the
     * configuration because its labels come from resources and therefore change with the locale; the
     * fork's labels are inline strings, so there is no locale to key on and an unkeyed `remember` is
     * the correct form.
     */
    @Test
    fun pickerCatalogsAreRememberedAcrossRecomposition() {
        val actionSource = moduleRoot.resolve("src/main/java/com/opentasker/ui/screens/ActionEditorDialogs.kt").readText()
        val contextSource = moduleRoot.resolve("src/main/java/com/opentasker/ui/screens/ContextEditorDialogs.kt").readText()

        assertTrue(actionSource.contains("val allActions = remember {"))
        assertTrue(actionSource.contains("val filteredActions = remember(allActions, query)"))
        assertTrue(contextSource.contains("val localizedTypes = remember {"))
        assertTrue(contextSource.contains("val filteredTypes = remember(localizedTypes, query)"))
    }
}
