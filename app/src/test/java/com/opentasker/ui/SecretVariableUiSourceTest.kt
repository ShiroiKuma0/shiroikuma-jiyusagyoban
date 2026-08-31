package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.opentasker.ProductionSources
import org.junit.Test

class SecretVariableUiSourceTest {



    @Test
    fun storageExpansionAndExportsKeepSecretBoundaries() {
        val storage = ProductionSources.path("com/opentasker/core/storage/VariableSecretStorage.kt").readText()
        val runner = ProductionSources.path("com/opentasker/core/engine/TaskRunner.kt").readText()
        val bundle = ProductionSources.path("com/opentasker/core/transfer/OpenTaskerBundle.kt").readText()
        val taskerExport = ProductionSources.path("com/opentasker/core/transfer/TaskerXmlExport.kt").readText()

        listOf("AndroidKeyStore", "AES/GCM/NoPadding", "updateAAD", "isSecret = true").forEach { marker ->
            assertTrue("Secret storage is missing $marker", storage.contains(marker))
        }
        assertTrue(runner.contains("isSecretDerived"))
        // Secret-derived failures must still fail closed by scrubbing the secret from the message
        // rather than leaking it; the redactor replaced the old blanket SECRET_DERIVED_FAILURE.
        assertTrue(runner.contains("redactSecretDerivedValues"))
        assertTrue(bundle.contains("filterNot { it.isSecret }"))
        assertTrue(taskerExport.contains("variables.filterNot { it.isSecret }"))
    }
// RETIRED: this pinned upstream's secret-variable UI in VariablesScreen.kt — a Switch to mark a
// variable secret, a PasswordVisualTransformation display, reveal/hide, and the re-entry helper
// shown when the keystore key is gone. The fork rewrote the Variables tab and ships NONE of it:
// nothing in the UI references `isSecret` at all, so no variable can be marked secret from the app.
// The storage half is fully implemented and tested (VariableSecretStorageTest) — only the surface
// is missing, which is a feature to build, not a test to repair.
}
