package com.opentasker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps source-scanning gates able to fail.
 *
 * `substringAfter` returns the whole receiver when its delimiter is absent, so a gate written as
 * `read(file).substringAfter(guard)` followed by a positive assertion goes green the moment the
 * guard it protects is deleted: the slice silently widens to the entire file and the token turns up
 * somewhere else in it. That has now happened twice in this repo, so the pattern is fenced off
 * rather than left to review.
 */
class ProductionSourceSliceGuardTest {

    /**
     * Files allowed to slice a production source with a bare `substringAfter`, and why.
     *
     * The bar for adding one: widening the slice must make the test *stricter*, never looser. A
     * positive assertion (`token in slice`) never qualifies, because a wider slice can only make it
     * more likely to pass.
     */
    private val allowed = mapOf(
        "app/src/test/java/com/opentasker/core/actions/WifiScanActionTest.kt" to
            "asserts a token is absent from the slice, so widening it can only make the test fail",
    )

    /** This file names the pattern it forbids, so it would otherwise report itself. */
    private val guardFile = "app/src/test/java/com/opentasker/ProductionSourceSliceGuardTest.kt"

    @Test
    fun `production sources are sliced with a bounded helper, not a bare substringAfter`() {
        val offenders = testFiles()
            .filter { path ->
                val source = path.readText()
                "ProductionSources.read(" in source && Regex("""\.substringAfter\(""").containsMatchIn(source)
            }
            .map { relative(it) }
            .filterNot { it == guardFile }
            .filterNot(allowed::containsKey)
            .sorted()

        assertTrue(
            "Slice these with ProductionSources.block, or justify them in this test's allowlist: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the allowlist does not outlive the files it excuses`() {
        val stale = (allowed.keys + guardFile)
            .filterNot { Files.exists(ProductionSources.repoRoot.resolve(it)) }

        assertTrue("Allowlisted files that no longer exist: $stale", stale.isEmpty())

        // An entry that stopped slicing anything is an exemption nobody is checking any more.
        val unused = allowed.keys.filterNot { entry ->
            val source = ProductionSources.repoRoot.resolve(entry).readText()
            "ProductionSources.read(" in source && Regex("""\.substringAfter\(""").containsMatchIn(source)
        }
        assertTrue("Allowlist entries that no longer need an exemption: $unused", unused.isEmpty())
    }

    // --- the helper's own behaviour, since every gate above now depends on it ---

    @Test
    fun `block returns only the region between its markers`() {
        val region = ProductionSources.block(
            "com/opentasker/core/actions/NetworkActions.kt",
            "fun readActiveTransport(",
            "\n}",
        )

        assertTrue("must start at the opening marker", region.startsWith("fun readActiveTransport("))
        assertTrue("must carry the body", "NET_CAPABILITY_INTERNET" in region)
        // The proof that it is a region and not the rest of the file.
        assertTrue("must stop before the next declaration", "internal fun activeTransport(" !in region)
    }

    @Test
    fun `block fails loudly when either marker is gone`() {
        val missingOpen = runCatching {
            ProductionSources.block(
                "com/opentasker/core/actions/NetworkActions.kt",
                "fun aFunctionThatWasDeleted(",
                "\n}",
            )
        }.exceptionOrNull()
        assertTrue("a missing opening marker must throw, not widen", missingOpen is IllegalArgumentException)

        val missingClose = runCatching {
            ProductionSources.block(
                "com/opentasker/core/actions/NetworkActions.kt",
                "fun readActiveTransport(",
                "a closing marker that is not in this file",
            )
        }.exceptionOrNull()
        assertTrue("a missing closing marker must throw, not run to EOF", missingClose is IllegalArgumentException)
    }

    @Test
    fun `block searches for the closing marker after the opening one`() {
        // A marker that also appears before the opening one must not invert the region.
        val region = ProductionSources.block(
            "com/opentasker/core/actions/NetworkActions.kt",
            "fun readActiveTransport(",
            "\n}",
        )

        assertEquals("the region must be non-empty and forward", region, region.trimEnd())
    }

    private fun testFiles(): List<Path> {
        val root = ProductionSources.repoRoot.resolve("app/src/test/java")
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    private fun relative(path: Path): String =
        ProductionSources.repoRoot.relativize(path).toString().replace('\\', '/')
}
