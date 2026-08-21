package com.opentasker.core.actions

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Static guard for the PendingIntent surface: every constructed PendingIntent must be immutable,
 * with the single documented exception of the Termux plugin callback (which the plugin protocol
 * requires to be mutable so the receiver can fill in result extras, and the PackageInstaller
 * archive status callback). Also verifies the notification button request-code allocator hands
 * out collision-free codes.
 */
class PendingIntentSourceGuardTest {

    /** Files permitted to build a mutable PendingIntent, with the reason each is allowed. */
    private val mutableAllowlist = setOf("TermuxCommandBroker.kt", "PackageArchiveActions.kt")

    // Every production source root: pointing this at app/ alone stopped covering the files the
    // core modules own, and a guard that scans less still reports green.
    private fun kotlinFiles(): List<Path> = ProductionSources.allKotlinFiles()

    @Test
    fun everyPendingIntentIsImmutableExceptDocumentedMutableCallbacks() {
        val constructors = Regex("""PendingIntent\.(getBroadcast|getActivity|getService)\s*\(""")
        val offenders = kotlinFiles()
            .filter { it.readText().contains(constructors) }
            .filter { file ->
                file.fileName.toString() !in mutableAllowlist &&
                    !file.readText().contains("FLAG_IMMUTABLE")
            }
            .map { ProductionSources.repoRoot.relativize(it).toString() }

        assertTrue("PendingIntent without FLAG_IMMUTABLE in $offenders", offenders.isEmpty())
    }

    @Test
    fun mutablePendingIntentsAreConfinedToTheAllowlist() {
        val offenders = kotlinFiles()
            .filter { it.readText().contains("FLAG_MUTABLE") }
            .filter { it.fileName.toString() !in mutableAllowlist }
            .map { ProductionSources.repoRoot.relativize(it).toString() }

        assertTrue("Unexpected mutable PendingIntent in $offenders", offenders.isEmpty())
    }

    @Test
    fun requestCodeAllocatorNeverRepeatsAcrossAdjacentNotifications() {
        // Two notifications with two buttons each: all four request codes must be distinct so a
        // newer notification cannot overwrite an older button's PendingIntent.
        val codes = List(4) { PendingIntentRequestCodes.next() }
        assertEquals("Allocated request codes collided: $codes", codes.size, codes.toSet().size)
        assertTrue("Request codes must be non-negative", codes.all { it >= 0 })
    }
}
