package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.opentasker.ProductionSources
import org.junit.Test

class SecretVariableUiSourceTest {


    /**
     * Masking a field is only half of hiding it.
     *
     * Without `KeyboardType.Password` the IME treats the input as ordinary text: autocorrect and
     * the personal dictionary stay on, so the keyboard can learn a secret variable's value or a
     * backup passphrase and offer it as a suggestion in another app later. The masking makes it
     * look handled, which is what makes the omission easy to miss.
     */
    @Test
    fun everyMaskedFieldAlsoAsksForThePasswordKeyboard() {
        val surfaces = listOf(
            "com/opentasker/ui/screens/VariablesScreen.kt",
            "com/opentasker/ui/screens/PermissionOnboardingScreen.kt",
        )

        surfaces.forEach { path ->
            val source = ProductionSources.read(path)
            val masked = Regex("PasswordVisualTransformation\\(\\)").findAll(source).count()
            val passwordKeyboards = Regex("KeyboardType\\.Password").findAll(source).count()

            assertTrue(
                "$path masks $masked field(s) but asks for the password keyboard $passwordKeyboards time(s)",
                passwordKeyboards >= masked,
            )
        }
    }

    @Test
    fun variableVaultUsesExplicitSecretStateAndDeliberateReveal() {
        val source = ProductionSources.path("com/opentasker/ui/screens/VariablesScreen.kt").readText()

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
}
