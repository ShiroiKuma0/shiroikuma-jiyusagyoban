package com.opentasker.core.transfer

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle compatibility contract.
 *
 * The release gate publishes a bundle schema version and a supported range, and
 * `docs/OPEN_JSON_BUNDLE.md` describes what importing an older document does. Those are claims about
 * the codec, so they are checked against the codec here rather than against each other.
 *
 * The two checked-in fixtures carry the contract: `schema1-golden.json` is a v1 document, and
 * `schema2-golden.json` is exactly what the codec produces from it. A change to migration semantics
 * or to the canonical encoding therefore has to update a fixture deliberately instead of passing
 * unnoticed.
 */
class OpenTaskerBundleCompatibilityTest {
    @Test
    fun schema1FixtureMigratesToTheCheckedInSchema2Document() {
        val migrated = OpenTaskerBundleCodec.decode(fixture(SCHEMA_1_FIXTURE))

        assertEquals(
            "the v1 migration no longer produces the documented v2 document",
            fixture(SCHEMA_2_FIXTURE).trim(),
            OpenTaskerBundleCodec.encode(migrated).trim(),
        )
    }

    @Test
    fun schema1MigrationFollowsTheDocumentedSemantics() {
        val migrated = OpenTaskerBundleCodec.decode(fixture(SCHEMA_1_FIXTURE))

        assertEquals(OPEN_TASKER_BUNDLE_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(
            "a v1 bundle carries no projects and must land on the default project",
            listOf("Default"),
            migrated.projects.map { it.name },
        )
        assertTrue(
            "a v1 export could not carry a power manifest, so it must be recomputed",
            migrated.metadata.powerRequests.isNotEmpty(),
        )
        assertTrue(migrated.metadata.warnings.any { it.startsWith("Migrated bundle schema 1 to 2") })
        assertEquals(
            "warnings must be de-duplicated",
            migrated.metadata.warnings.distinct(),
            migrated.metadata.warnings,
        )
    }

    @Test
    fun schema2FixtureRoundTripsByteForByte() {
        val document = fixture(SCHEMA_2_FIXTURE).trim()

        val once = OpenTaskerBundleCodec.decode(document)
        val encoded = OpenTaskerBundleCodec.encode(once).trim()

        assertEquals("v2 encoding must be canonical and stable", document, encoded)
        assertEquals("decoding is deterministic", once, OpenTaskerBundleCodec.decode(encoded))
    }

    /**
     * The version probe has to run before the domain serializers, otherwise a future document could
     * reach code that was never written for it. A document whose domain fields are structurally
     * wrong must still fail on the version, proving the ordering.
     */
    @Test
    fun futureSchemaIsRejectedBeforeAnyDomainFieldIsRead() {
        val future = """
            {"schemaVersion":3,"appVersion":"future","exportedAtEpochMs":0,
             "tasks":"not-an-array","profiles":42}
        """.trimIndent()

        val error = runCatching { OpenTaskerBundleCodec.decode(future) }.exceptionOrNull()

        assertTrue("expected a bounded rejection, got $error", error is IllegalArgumentException)
        assertTrue(
            "the failure must name the unsupported version, not a domain field: ${error?.message}",
            error?.message.orEmpty().contains("Unsupported schema version 3"),
        )
    }

    @Test
    fun everyVersionOutsideTheSupportedRangeIsRejected() {
        listOf(0, -1, 3, 999).forEach { version ->
            val document = """{"schemaVersion":$version,"appVersion":"x","exportedAtEpochMs":0}"""
            val error = runCatching { OpenTaskerBundleCodec.decode(document) }.exceptionOrNull()
            assertTrue("schema $version must be rejected", error is IllegalArgumentException)
        }
        listOf(1, 2).forEach { version ->
            val document = """{"schemaVersion":$version,"appVersion":"x","exportedAtEpochMs":0}"""
            assertEquals(
                "schema $version must import as the current version",
                OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
                OpenTaskerBundleCodec.decode(document).schemaVersion,
            )
        }
    }

    /** A document the importer does not fully understand is refused, not partially imported. */
    @Test
    fun unknownKeysAreRejectedRatherThanSilentlyDropped() {
        val withUnknownTopLevelKey =
            """{"schemaVersion":2,"appVersion":"x","exportedAtEpochMs":0,"hostile":true}"""
        val withUnknownNestedKey =
            """{"schemaVersion":2,"appVersion":"x","exportedAtEpochMs":0,"metadata":{"hostile":true}}"""

        listOf(withUnknownTopLevelKey, withUnknownNestedKey).forEach { document ->
            assertTrue(
                "unknown keys must fail the decode: $document",
                runCatching { OpenTaskerBundleCodec.decode(document) }.isFailure,
            )
        }
    }

    @Test
    fun oversizedDocumentsFailTheBudgetBeforeDecoding() {
        val document = fixture(SCHEMA_2_FIXTURE)

        val error = runCatching {
            OpenTaskerBundleCodec.decode(document, ImportResourceBudget.Default.copy(maxJsonChars = 32))
        }.exceptionOrNull()

        assertTrue("expected a bounded budget failure, got $error", error is IllegalArgumentException)
    }

    /**
     * `docs/OPEN_JSON_BUNDLE.md` is the published contract. If the codec's accepted range moves and
     * the document does not, importers written against the document are wrong.
     */
    @Test
    fun theDocumentedSupportedRangeMatchesTheCodec() {
        val doc = Files.readString(repositoryRoot().resolve("docs/OPEN_JSON_BUNDLE.md"))
        val declared = Regex("""Supported for import: `(\d+)\.\.(\d+)`""").find(doc)
            ?: error("docs/OPEN_JSON_BUNDLE.md no longer declares a supported import range")
        val first = declared.groupValues[1].toInt()
        val last = declared.groupValues[2].toInt()

        assertTrue("the documented range must include the current version", OPEN_TASKER_BUNDLE_SCHEMA_VERSION in first..last)
        assertTrue(
            "docs/OPEN_JSON_BUNDLE.md must state the current version as `$OPEN_TASKER_BUNDLE_SCHEMA_VERSION`",
            doc.contains("Current value: `$OPEN_TASKER_BUNDLE_SCHEMA_VERSION`"),
        )
        // Every version the document promises must actually import, and the first one outside it
        // must not - that is what makes this a check on the codec rather than on the prose.
        (first..last).forEach { version ->
            val document = """{"schemaVersion":$version,"appVersion":"x","exportedAtEpochMs":0}"""
            assertTrue(
                "the document promises schema $version imports, but the codec rejects it",
                runCatching { OpenTaskerBundleCodec.decode(document) }.isSuccess,
            )
        }
        val beyond = """{"schemaVersion":${last + 1},"appVersion":"x","exportedAtEpochMs":0}"""
        assertTrue(
            "the codec accepts schema ${last + 1}, which the document does not promise",
            runCatching { OpenTaskerBundleCodec.decode(beyond) }.isFailure,
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("bundles/$name")) { "Missing fixture $name" }
            .readText()

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.parent
        }
        return checkNotNull(candidate) { "Could not locate the repository root" }
    }

    private companion object {
        const val SCHEMA_1_FIXTURE = "schema1-golden.json"
        const val SCHEMA_2_FIXTURE = "schema2-golden.json"
    }
}
