package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretVariableUiSourceTest {
    private val sourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker"),
        Path.of("app/src/main/java/com/opentasker"),
    ).first(Files::exists)

    @Test
    fun variableVaultUsesExplicitSecretStateAndDeliberateReveal() {
        val source = sourceRoot.resolve("ui/screens/VariablesScreen.kt").readText()

        listOf(
            "variable.isSecret",
            "PasswordVisualTransformation()",
            "var value by remember(stateKey)",
            "var nonSecretDraft by rememberSaveable(stateKey)",
            "nonSecretDraft = if (it) null else value",
            "R.string.variables_reveal_secret",
            "R.string.variables_hide_secret",
            "!variable.secretAvailable",
            "R.string.variables_secret_reentry_helper",
            "Switch(",
        ).forEach { marker ->
            assertTrue("Variable vault is missing secret UI contract: $marker", source.contains(marker))
        }
        assertFalse("Secret state must not be inferred from variable names", source.contains("SENSITIVE_NAMES"))
    }

    @Test
    fun storageExpansionAndExportsKeepSecretBoundaries() {
        val storage = sourceRoot.resolve("core/storage/VariableSecretStorage.kt").readText()
        val runner = sourceRoot.resolve("core/engine/TaskRunner.kt").readText()
        val bundle = sourceRoot.resolve("core/transfer/OpenTaskerBundle.kt").readText()
        val taskerExport = sourceRoot.resolve("core/transfer/TaskerXmlExport.kt").readText()

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
}
