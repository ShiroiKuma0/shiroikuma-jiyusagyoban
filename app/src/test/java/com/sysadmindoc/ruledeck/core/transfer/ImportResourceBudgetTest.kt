package com.sysadmindoc.ruledeck.core.transfer

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.SceneElement
import com.sysadmindoc.ruledeck.core.model.SceneElementType
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportResourceBudgetTest {
    @Test
    fun jsonPreflightAcceptsExactTokenDepthAndStringLimitsThenRejectsOneOver() {
        val exactJson = """{"a":"é"}"""
        ImportResourceGuard.requireJsonPreflight(
            exactJson,
            budget().copy(maxJsonTokens = 5, maxNestingDepth = 1, maxAggregateStringBytes = 3),
        )

        assertBudget("JSON tokens") {
            ImportResourceGuard.requireJsonPreflight(exactJson, budget().copy(maxJsonTokens = 4))
        }
        assertBudget("aggregate string bytes") {
            ImportResourceGuard.requireJsonPreflight(
                exactJson,
                budget().copy(maxAggregateStringBytes = 2),
            )
        }
        assertBudget("nesting depth") {
            ImportResourceGuard.requireJsonPreflight(
                """{"a":[]}""",
                budget().copy(maxNestingDepth = 1),
            )
        }
    }

    @Test
    fun jsonPreflightDoesNotCountCommentsOrStructuralCharactersInsideStrings() {
        val handEdited = """
            // [ ignored comment token ]
            {"description":"[[{{,,::}}]]",}
        """.trimIndent()

        ImportResourceGuard.requireJsonPreflight(
            handEdited,
            budget().copy(maxNestingDepth = 1, maxJsonTokens = 6),
        )

        // The inverse: the same document is refused one under, so the six tokens and the depth of
        // one were really counted, not let through by a check that counts nothing.
        assertBudget("JSON tokens") {
            ImportResourceGuard.requireJsonPreflight(handEdited, budget().copy(maxNestingDepth = 1, maxJsonTokens = 5))
        }
        assertBudget("nesting depth") {
            ImportResourceGuard.requireJsonPreflight(handEdited, budget().copy(maxNestingDepth = 0, maxJsonTokens = 6))
        }
    }

    @Test
    fun decodedJsonAcceptsExactEntityLimitAndRejectsOneOver() {
        val exact = RuleDeckBundle(
            appVersion = "test",
            exportedAtEpochMs = 0,
            tasks = listOf(Task(id = 1, name = "One")),
        )
        val over = exact.copy(tasks = exact.tasks + Task(id = 2, name = "Two"))
        val oneEntity = budget().copy(maxEntities = 1)

        assertEquals(exact, RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(exact), oneEntity))
        assertBudget("entities") {
            RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(over), oneEntity)
        }
        val plan = RuleDeckBundleCodec.validate(over, oneEntity)
        assertTrue(!plan.canImport)
        assertTrue(plan.warnings.single().contains("entities"))
    }

    @Test
    fun decodedBundleChecksEveryStructuralCollectionAtItsWriteBoundary() {
        val bundle = RuleDeckBundle(
            appVersion = "",
            exportedAtEpochMs = 0,
            metadata = BundleMetadata(name = "", description = ""),
            projects = emptyList(),
            tasks = listOf(Task(id = 1, name = "", actions = listOf(ActionSpec(type = "")))),
            profiles = listOf(
                Profile(
                    id = 1,
                    name = "",
                    contexts = listOf(ContextSpec(ContextType.EVENT)),
                    enterTaskId = 1,
                )
            ),
            variables = listOf(Variable(name = "", value = "", isGlobal = true)),
            scenes = listOf(
                Scene(
                    id = 1,
                    name = "",
                    widthDp = 1,
                    heightDp = 1,
                    elements = listOf(
                        SceneElement(
                            id = 1,
                            type = SceneElementType.TEXT,
                            xDp = 0,
                            yDp = 0,
                            widthDp = 1,
                            heightDp = 1,
                        )
                    ),
                )
            ),
        )
        val exact = budget().copy(maxEntities = 4, maxActions = 1, maxContexts = 1, maxSceneElements = 1)

        assertNull(ImportResourceGuard.bundleViolation(bundle, exact))
        assertEquals("entities", ImportResourceGuard.bundleViolation(bundle, exact.copy(maxEntities = 3))?.budgetName)
        assertEquals("actions", ImportResourceGuard.bundleViolation(bundle, exact.copy(maxActions = 0))?.budgetName)
        assertEquals("contexts", ImportResourceGuard.bundleViolation(bundle, exact.copy(maxContexts = 0))?.budgetName)
        assertEquals(
            "scene elements",
            ImportResourceGuard.bundleViolation(bundle, exact.copy(maxSceneElements = 0))?.budgetName,
        )
    }

    @Test
    fun decodedBundleCountsUtf8StringBytesWithoutAllocatingEncodedCopies() {
        val bundle = RuleDeckBundle(
            appVersion = "",
            exportedAtEpochMs = 0,
            projects = emptyList(),
            metadata = BundleMetadata(name = "", description = ""),
            variables = listOf(Variable(name = "a", value = "é", isGlobal = true)),
        )

        assertNull(ImportResourceGuard.bundleViolation(bundle, budget().copy(maxAggregateStringBytes = 3)))
        assertEquals(
            "aggregate string bytes",
            ImportResourceGuard.bundleViolation(bundle, budget().copy(maxAggregateStringBytes = 2))?.budgetName,
        )
    }

    @Test
    fun sanitizeStripsBenignDoctypeAndRejectsUnsafeOnes() {
        assertEquals("<root/>", ImportResourceGuard.sanitizeXml("<!DOCTYPE root>\n<root/>").trim())
        assertEquals("<root/>", ImportResourceGuard.sanitizeXml("<root/>"))

        val unterminated = runCatching {
            ImportResourceGuard.sanitizeXml("<!DOCTYPE root [<!ELEMENT root EMPTY>")
        }.exceptionOrNull()
        assertEquals(true, unterminated is IllegalArgumentException)

        val entity = runCatching {
            ImportResourceGuard.sanitizeXml("<!DOCTYPE root [<!ENTITY x \"y\">]><root/>")
        }.exceptionOrNull()
        assertEquals(true, entity is IllegalArgumentException)

        val doubled = runCatching {
            ImportResourceGuard.sanitizeXml("<!DOCTYPE root><!DOCTYPE root><root/>")
        }.exceptionOrNull()
        assertEquals(true, doubled is IllegalArgumentException)
    }

    @Test
    fun repositoryValidationStaysBeforeTheRoomTransaction() {
        val source = sequenceOf(
            File("src/main/java/com/sysadmindoc/ruledeck/core/transfer/RuleDeckBundle.kt"),
            File("app/src/main/java/com/sysadmindoc/ruledeck/core/transfer/RuleDeckBundle.kt"),
        ).first { it.exists() }.readText()
        val validation = source.indexOf("val plan = planImport(bundle)")
        val transaction = source.indexOf("db.withTransaction", startIndex = validation)

        assertTrue("Bundle validation must exist", validation >= 0)
        assertTrue("Room writes must start only after validation", transaction > validation)
    }

    private fun assertBudget(name: String, block: () -> Unit) {
        val error = assertThrows(ImportBudgetExceededException::class.java, block)
        assertEquals(name, error.budgetName)
        assertTrue(error.message.orEmpty().contains("limit is"))
    }

    private fun budget(): ImportResourceBudget = ImportResourceBudget(
        maxJsonChars = 10_000,
        maxEntities = 100,
        maxActions = 100,
        maxContexts = 100,
        maxSceneElements = 100,
        maxJsonTokens = 1_000,
        maxNestingDepth = 20,
        maxAggregateStringBytes = 10_000,
    )
}
