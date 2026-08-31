package com.opentasker.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every action the bridge HANDLES must also be DECLARED.
 *
 * An implemented action missing from the manifest is not a compile error, not a warning, and not
 * even a visible failure at runtime: the broadcast is simply never delivered and `am broadcast`
 * answers `result=0` with no data, which looks exactly like an app that chose to ignore you.
 * `RUN_TASK` — the action the entire dev cycle is driven by — sat in that state until it was found
 * by accident on 2026-08-31, while trying to drive something else entirely.
 *
 * Both sides are read from source rather than from a list kept here, so this cannot pass by being
 * updated in sympathy with the bug.
 */
class WorkspaceTransferManifestTest {

    @Test
    fun everyHandledActionIsDeclaredInTheManifest() {
        val source = sourceFile("app/src/main/java/com/opentasker/core/transfer/WorkspaceTransferReceiver.kt")
        val manifest = sourceFile("app/src/main/AndroidManifest.xml").readText()

        // The constants, then the branches that actually dispatch on them: an action with a
        // constant but no branch is dead, and demanding it be declared would be noise.
        val constants = CONSTANT.findAll(source.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val handled = BRANCH.findAll(source.readText())
            .mapNotNull { constants[it.groupValues[1]] }
            .toSortedSet()

        assertTrue("no handled actions found — the parse is wrong, not the manifest", handled.isNotEmpty())

        val declared = handled.filter { manifest.contains("android:name=\"$it\"") }.toSortedSet()
        assertEquals(
            "these actions are handled by WorkspaceTransferReceiver but NOT declared in the " +
                "manifest, so a broadcast to them is silently dropped",
            handled, declared,
        )
    }

    private fun sourceFile(path: String): File {
        // The unit tests run with the module directory as the working directory, but that has moved
        // before; walk up until the repository root is underfoot rather than assuming a depth.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("could not find $path from ${File("").absolutePath}")
    }

    private companion object {
        val CONSTANT = Regex("""const val (ACTION_[A-Z_]+) = "([^"]+)"""")
        val BRANCH = Regex("""^\s+(ACTION_[A-Z_]+) ->""", RegexOption.MULTILINE)
    }
}
