package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
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
    }

    @Test
    fun decodedJsonAcceptsExactEntityLimitAndRejectsOneOver() {
        val exact = OpenTaskerBundle(
            appVersion = "test",
            exportedAtEpochMs = 0,
            tasks = listOf(Task(id = 1, name = "One")),
        )
        val over = exact.copy(tasks = exact.tasks + Task(id = 2, name = "Two"))
        val oneEntity = budget().copy(maxEntities = 1)

        assertEquals(exact, OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(exact), oneEntity))
        assertBudget("entities") {
            OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(over), oneEntity)
        }
        val plan = OpenTaskerBundleCodec.validate(over, oneEntity)
        assertTrue(!plan.canImport)
        assertTrue(plan.warnings.single().contains("entities"))
    }

    @Test
    fun decodedBundleChecksEveryStructuralCollectionAtItsWriteBoundary() {
        val bundle = OpenTaskerBundle(
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
        val bundle = OpenTaskerBundle(
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
        assertEquals("<root/>", ImportResourceGuard.sanitizeTaskerXml("<!DOCTYPE root>\n<root/>").trim())
        assertEquals("<root/>", ImportResourceGuard.sanitizeTaskerXml("<root/>"))

        val unterminated = runCatching {
            ImportResourceGuard.sanitizeTaskerXml("<!DOCTYPE root [<!ELEMENT root EMPTY>")
        }.exceptionOrNull()
        assertEquals(true, unterminated is IllegalArgumentException)

        val entity = runCatching {
            ImportResourceGuard.sanitizeTaskerXml("<!DOCTYPE root [<!ENTITY x \"y\">]><root/>")
        }.exceptionOrNull()
        assertEquals(true, entity is IllegalArgumentException)

        val doubled = runCatching {
            ImportResourceGuard.sanitizeTaskerXml("<!DOCTYPE root><!DOCTYPE root><root/>")
        }.exceptionOrNull()
        assertEquals(true, doubled is IllegalArgumentException)
    }

    @Test
    fun xmlPreflightAcceptsExactNodeAndDepthLimitsThenRejectsOneOver() {
        val exact = "<root><child/></root>"
        ImportResourceGuard.requireXmlPreflight(
            exact,
            budget().copy(maxXmlNodes = 2, maxNestingDepth = 2),
        )

        assertBudget("XML nodes") {
            ImportResourceGuard.requireXmlPreflight(exact, budget().copy(maxXmlNodes = 1))
        }
        assertBudget("nesting depth") {
            ImportResourceGuard.requireXmlPreflight(exact, budget().copy(maxNestingDepth = 1))
        }
    }

    @Test
    fun xmlPreflightReportsMalformedInputAsAnExpectedImportFailure() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportResourceGuard.requireXmlPreflight("<TaskerData></TkaserData>", budget())
        }

        assertEquals("Tasker XML is malformed.", error.message)
    }

    @Test
    fun taskerXmlAcceptsExactActionLimitAndRejectsOneOverBeforeDomParsing() {
        fun xml(actionCount: Int): String = buildString {
            append("<TaskerData><Task><id>1</id><nme>Task</nme>")
            repeat(actionCount) { append("<Action><code>30</code></Action>") }
            append("</Task></TaskerData>")
        }
        val oneAction = budget().copy(maxActions = 1)

        assertEquals(
            1,
            TaskerXmlImporter.parse(xml(1), "test", 0, oneAction).mappedActions.size,
        )
        assertBudget("actions") {
            TaskerXmlImporter.parse(xml(2), "test", 0, oneAction)
        }
    }

    @Test
    fun repositoryValidationStaysBeforeTheRoomTransaction() {
        val source = sequenceOf(
            File("src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt"),
            File("app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt"),
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
        maxXmlChars = 10_000,
        maxEntities = 100,
        maxActions = 100,
        maxContexts = 100,
        maxSceneElements = 100,
        maxJsonTokens = 1_000,
        maxXmlNodes = 1_000,
        maxNestingDepth = 20,
        maxAggregateStringBytes = 10_000,
    )
}
