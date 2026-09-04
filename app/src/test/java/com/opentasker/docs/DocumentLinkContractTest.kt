package com.opentasker.docs

import com.opentasker.ProductionSources
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A document a user can be sent to has to exist where the user can reach it.
 *
 * `.gitignore` excludes the whole docs directory and re-includes one file, so most of the guides
 * in a working tree never reach github.com. Nothing stopped the README or an in-app link pointing
 * at one of them, and the link would be dead for everyone but the person who wrote it. This reads
 * the git index rather than the working tree, because that is exactly the difference that matters:
 * an untracked file is present locally and absent for everyone else.
 *
 * Only surfaces a user can follow are scanned. A KDoc comment naming a design document is a note
 * to whoever is reading the code, which is a different thing from a link.
 */
class DocumentLinkContractTest {
    private val repoRoot = ProductionSources.repoRoot

    @Test
    fun `every document a user can follow is published`() {
        val trackedDocuments = trackedFiles().filter { it.startsWith("docs/") }.toSet()
        assertTrue(
            "expected at least one tracked document, so a passing result means something",
            trackedDocuments.isNotEmpty(),
        )

        val unpublished = USER_FACING_SURFACES.flatMap { relative ->
            val text = repoRoot.resolve(relative).readText()
            // In markdown, only a real link is followable. A backticked path in a changelog entry
            // is a record of which file changed, and rewriting years of history to avoid naming a
            // local document would be worse than leaving it. Everywhere else there is no link
            // syntax, so any mention is the pointer.
            val pattern = if (relative.endsWith(".md")) MARKDOWN_LINK else DOCUMENT_REFERENCE
            pattern.findAll(text)
                .map { match -> match.groupValues.last() }
                .distinct()
                .filterNot { it in trackedDocuments }
                .map { "$relative points at $it, which is not tracked" }
                .toList()
        }

        assertEquals(
            "A link a user can follow must point at a document that is committed. Either track " +
                "the document, or stop linking it.",
            emptyList<String>(),
            unpublished,
        )
    }

    private fun trackedFiles(): List<String> {
        val process = ProcessBuilder("git", "ls-files")
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "git ls-files did not finish; this gate cannot pass without reading the index"
        }
        check(process.exitValue() == 0) { "git ls-files failed: $output" }
        val tracked = output.lines().map(String::trim).filter(String::isNotEmpty)
        check(tracked.isNotEmpty()) { "git ls-files reported no tracked files, so nothing was checked" }
        return tracked
    }

    private companion object {
        val USER_FACING_SURFACES = listOf(
            "README.md",
            "CHANGELOG.md",
            "CONTRIBUTING.md",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values/dynamic_surface_strings.xml",
            "app/src/main/res/values/action_catalog_strings.xml",
            "app/src/main/java/com/opentasker/core/support/ProjectLinks.kt",
        )
        val DOCUMENT_REFERENCE = Regex("""(docs/[A-Za-z0-9_/-]+\.md)""")

        /** `[label](docs/NAME.md)`, including the form inside a full github.com blob URL. */
        val MARKDOWN_LINK = Regex("""]\((?:https?://[^)]*?/)?(docs/[A-Za-z0-9_/-]+\.md)\)""")
    }
}
