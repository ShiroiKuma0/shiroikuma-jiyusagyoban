package com.opentasker.core.capabilities

import com.opentasker.core.actions.ActionCatalog
import com.opentasker.core.model.ContextType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The published capability counts, checked against the compiled artifact.
 *
 * `app/build.gradle.kts`, `VerifyReleaseTruthTask`, `generate-release-truth.ps1` and
 * `ReleaseTruthContractTest` all derive these numbers with the *same* regexes over the *same*
 * source files. A last enum constant written without a trailing comma, or a `define(` whose id sits
 * on the next line, is silently uncounted - and because every counter shares the derivation, they
 * would all agree on the wrong number and no gate could notice. This is the one counter that asks
 * the runtime instead of the text.
 */
class DeclaredCapabilityCountTest {
    @Test
    fun theTruthFileMatchesWhatTheRuntimeActuallyDeclares() {
        val truth = repoFile("tools/release-truth.json").readText()

        assertEquals(
            "registeredActions in release-truth.json must match ActionCatalog",
            ActionCatalog.all.size,
            truth.intField("registeredActions"),
        )
        assertEquals(
            "contextFamilies in release-truth.json must match ContextType",
            ContextType.entries.size,
            truth.intField("contextFamilies"),
        )
    }

    @Test
    fun everyDeclaredActionHasADistinctId() {
        val ids = ActionCatalog.all.map { it.id }

        assertEquals("action ids must be unique", ids.size, ids.toSet().size)
    }

    private fun String.intField(name: String): Int =
        Regex("\"$name\"\\s*:\\s*(\\d+)").find(this)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("release-truth.json has no numeric field named $name")

    private fun repoFile(relative: String): Path =
        listOf(Path.of(relative), Path.of("..").resolve(relative))
            .first(Files::exists)
}
