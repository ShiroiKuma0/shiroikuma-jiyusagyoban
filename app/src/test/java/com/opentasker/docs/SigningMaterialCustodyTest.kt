package com.opentasker.docs

import com.opentasker.ProductionSources
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release signing material lives outside this repository and must stay there.
 *
 * Two releases have already had to break signing identity: v0.2.79 lost a machine-global debug
 * keystore that had been regenerated underneath it, and v0.2.93 had to retire a key whose bytes
 * were public because they were committed. Both forced every installed copy to be uninstalled
 * first. The gitignore rules are the intent; this is the check that the intent held, because a
 * `git add -f` walks straight past a gitignore.
 */
class SigningMaterialCustodyTest {
    private val repoRoot = ProductionSources.repoRoot

    private val forbiddenSuffixes = listOf(".jks", ".keystore", ".p12", ".pfx", ".bks")
    private val forbiddenNames = listOf("signing.properties", "keystore.properties")

    @Test
    fun `no signing material is tracked in the repository`() {
        val offenders = trackedFiles().filter { path ->
            val name = path.substringAfterLast('/')
            forbiddenSuffixes.any(name::endsWith) || name in forbiddenNames
        }

        assertTrue(
            "Signing material must never be tracked. Remove it and rotate the key it belongs to: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `gitignore still refuses signing material`() {
        val patterns = repoRoot.resolve(".gitignore").readText()
            .lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter(String::isNotEmpty)
            .toSet()

        (forbiddenSuffixes.map { suffix -> "*$suffix" } + forbiddenNames)
            .filterNot { pattern -> pattern in patterns }
            .let { missing ->
                assertTrue(".gitignore no longer excludes: $missing", missing.isEmpty())
            }
    }

    /**
     * Reads the index rather than the working tree: a gitignored file is invisible to a directory
     * walk, and the failure mode worth catching is the one that survives a commit.
     */
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
}
