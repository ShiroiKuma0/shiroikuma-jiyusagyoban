package com.opentasker.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppPickerContractTest {
    @Test
    fun everyPackageEditorUsesTheSharedScopedPicker() {
        val sourceRoot = sequenceOf(
            File("src/main/java/com/opentasker"),
            File("app/src/main/java/com/opentasker"),
        ).first(File::exists)
        val actionMetadata = sourceRoot.resolve("core/actions/ActionMetadata.kt").readText()
        val contextEditors = sourceRoot.resolve("ui/screens/ContextEditorDialogs.kt").readText()
        val actionEditors = sourceRoot.resolve("ui/screens/ActionEditorDialogs.kt").readText()
        val picker = sourceRoot.resolve("ui/screens/InstalledAppPicker.kt").readText()
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first(File::exists).readText()

        actionMetadata.lineSequence()
            .filter { it.contains("ActionField(\"package\"") }
            .forEach { line -> assertTrue(line, line.contains("FieldType.APP")) }
        contextEditors.lineSequence()
            .filter { it.contains("ActionField(\"package\"") }
            .forEach { line -> assertTrue(line, line.contains("FieldType.APP")) }
        assertTrue(actionEditors.contains("FieldType.APP -> InstalledAppFieldInput"))
        assertTrue(picker.contains("InstalledAppRepository(appContext).loadVisibleApps()"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertFalse(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
    }
}
