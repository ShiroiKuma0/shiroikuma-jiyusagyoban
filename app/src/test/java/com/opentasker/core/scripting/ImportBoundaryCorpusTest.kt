package com.opentasker.core.scripting

import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.plugins.locale.LocaleConditionOperator
import com.opentasker.core.plugins.locale.LocaleConditionTarget
import com.opentasker.core.plugins.locale.LocalePluginBundleCodec
import com.opentasker.core.plugins.locale.LocalePluginContract
import com.opentasker.core.transfer.ImportResourceBudget
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A small deterministic corpus for every external-data boundary. It intentionally uses only the
 * JUnit/runtime APIs already in the app: new parser behavior must stay in the normal JVM gate.
 */
class ImportBoundaryCorpusTest {
    @Test
    fun openTaskerJsonCorpusIsBoundedAndCompatibilityAware() {
        val seed = OpenTaskerBundleCodec.encode(seedBundle())
        val cases = listOf(
            "truncated" to seed.dropLast(2),
            "future schema" to seed.replaceFirst(Regex("\"schemaVersion\"\\s*:\\s*2"), "\"schemaVersion\": 999"),
            "unknown key" to seed.trimEnd().dropLast(1) + ",\"hostile\":true}",
            "deep nesting" to "[".repeat(80) + "0" + "]".repeat(80),
            "bare token" to "not-json",
        ) + seededInsertions(seed, count = 16).mapIndexed { index, value -> "seed mutation $index" to value }

        cases.forEach { (label, raw) ->
            val result = runCatching { OpenTaskerBundleCodec.decode(raw) }
            result.exceptionOrNull()?.let { error ->
                assertBoundedFailure(label, error)
            } ?: assertTrue(
                "$label decoded data must remain reviewable",
                OpenTaskerBundleCodec.validate(result.getOrThrow()).warnings.size <= 128,
            )
        }

        // The fork's bundle format carries no ids at all — a NAME is the identity — so the blocking
        // collision is a duplicate name, not a duplicate id.
        val duplicateNames = seedBundle().copy(
            tasks = listOf(
                Task(id = 7, name = "Same", actions = listOf(ActionSpec(type = "log"))),
                Task(id = 8, name = "Same", actions = listOf(ActionSpec(type = "log"))),
            ),
        )
        val plan = OpenTaskerBundleCodec.validate(duplicateNames)
        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("duplicate task names") })

        // The fork's decode() carries its own fixed size cap rather than an injectable budget, so the
        // boundary this exercises is the other hard gate it fails closed on: the name-based schema cut.
        assertBoundedFailure("pre-name-based bundle schema") {
            OpenTaskerBundleCodec.decode("""{"schemaVersion":1,"tasks":[]}""")
        }
    }

    @Test
    fun taskerXmlCorpusRejectsMalformedAndOversizedInputsBeforeImport() {
        val valid = """
            <TaskerData>
                <Task sr="task1"><id>1</id><nme>Notify</nme><Action><code>523</code><Str>Ready</Str></Action></Task>
                <Profile sr="profile1"><id>2</id><nme>Morning</nme><mid0>1</mid0><Time><from>08:00</from><to>09:00</to></Time></Profile>
            </TaskerData>
        """.trimIndent()
        val duplicateIds = valid.replace("</TaskerData>", "<Task><id>1</id><nme>Second</nme></Task></TaskerData>")
        val duplicateReport = TaskerXmlImporter.parse(duplicateIds, "test", 0L)
        assertEquals(2, duplicateReport.bundle.tasks.size)
        assertEquals(2, duplicateReport.bundle.tasks.map { it.id }.distinct().size)

        listOf(
            "truncated" to valid.dropLast(10),
            "broken entity" to valid.replace("Notify", "&broken;"),
            "external entity" to "<!DOCTYPE TaskerData SYSTEM \"file:///etc/passwd\">$valid",
        ).forEach { (label, raw) ->
            assertBoundedFailure(label) { TaskerXmlImporter.parse(raw, "test", 0L) }
        }

        val manyTasks = "<Task><id>1</id><nme>Repeated</nme></Task>".repeat(32)
        assertBoundedFailure("XML entity budget") {
            TaskerXmlImporter.parse(
                "<TaskerData>$manyTasks</TaskerData>",
                "test",
                0L,
                ImportResourceBudget.Default.copy(maxEntities = 4),
            )
        }
    }

    @Test
    fun externalIntentCorpusKeepsVariablesDeterministicAndBounded() {
        val values = linkedMapOf<String, String?>()
        repeat(AutomationTargetContract.MAX_SUPPLIED_VARIABLES + 16) { index ->
            values[AutomationTargetContract.VARIABLE_EXTRA_PREFIX + "Var${index.toString().padStart(2, '0')}"] =
                "value-$index" + "x".repeat(if (index == 0) AutomationTargetContract.MAX_VARIABLE_VALUE_CHARS else 0)
        }
        values[AutomationTargetContract.VARIABLE_EXTRA_PREFIX + "bad-name"] = "must drop"
        values[AutomationTargetContract.VARIABLE_EXTRA_PREFIX + "NullValue"] = null
        values[AutomationTargetContract.VARIABLE_EXTRA_PREFIX + "ArrayValue"] = "ignored by Bundle when not a String"

        val extracted = AutomationTargetContract.extractVariableExtras(values)

        assertEquals(AutomationTargetContract.MAX_SUPPLIED_VARIABLES, extracted.size)
        assertEquals(extracted.keys.toList(), extracted.keys.sorted())
        assertTrue(extracted.values.all { it.length <= AutomationTargetContract.MAX_VARIABLE_VALUE_CHARS })
        assertFalse("bad-name" in extracted)
        assertEquals(
            ("value-0" + "x".repeat(AutomationTargetContract.MAX_VARIABLE_VALUE_CHARS))
                .take(AutomationTargetContract.MAX_VARIABLE_VALUE_CHARS),
            extracted.getValue("Var00"),
        )

        listOf("", "1bad", "bad-name", "name\nwith-control", "x".repeat(65)).forEach { name ->
            assertBoundedFailure("variable name") { AutomationTargetContract.variableExtraName(name) }
        }
    }

    @Test
    fun localeBundleCorpusRejectsUnknownSchemaNestedAndOversizedValues() {
        val invalidConditionBundles = listOf(
            "missing schema" to emptyMap(),
            "future schema" to mapOf(LocaleConditionTarget.BUNDLE_KEY_SCHEMA to "99"),
            "unknown kind" to mapOf(
                LocaleConditionTarget.BUNDLE_KEY_SCHEMA to LocaleConditionTarget.SCHEMA_VERSION,
                LocaleConditionTarget.BUNDLE_KEY_KIND to "future_kind",
            ),
            "invalid profile" to LocaleConditionTarget.profileActive(1, "Work") +
                (LocaleConditionTarget.BUNDLE_KEY_PROFILE_ID to "0"),
            "invalid context" to LocaleConditionTarget.contextSatisfied(1, "Work", 0, "State") +
                (LocaleConditionTarget.BUNDLE_KEY_CONTEXT_INDEX to "1025"),
        )
        invalidConditionBundles.forEach { (label, values) ->
            assertBoundedFailure(label) { LocaleConditionTarget.parse(values) }
        }
        assertBoundedFailure("oversized condition value") {
            LocaleConditionTarget.variableCompare(
                "Mode",
                1,
                LocaleConditionOperator.EQUALS,
                "x".repeat(LocaleConditionTarget.MAX_EXPECTED_VALUE_BYTES + 1),
            )
        }

        listOf(
            "not-json",
            "{\"nested\":{\"unsafe\":true}}",
            "{\"nullValue\":null}",
            "{\"payload\":\"${"x".repeat(LocalePluginContract.MAX_BUNDLE_JSON_BYTES)}\"}",
        ).forEach { raw ->
            assertBoundedFailure("Locale bundle") { LocalePluginBundleCodec.decodeStringBundle(raw) }
        }

        val valid = LocalePluginBundleCodec.decodeStringBundle("{\"enabled\":true,\"count\":3}")
        assertEquals(mapOf("enabled" to "true", "count" to "3"), valid)
    }

    @Test
    fun termuxCorpusFailsClosedWithoutCommandDispatch() {
        val invalid = listOf(
            TermuxScriptInvocation(
                executable = "~/.termux/tasker/../escape.sh",
                argumentText = null,
                workingDirectory = null,
                stdin = null,
                timeoutMs = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
            ),
            TermuxScriptInvocation(
                executable = "~/.termux/tasker/run.sh",
                argumentText = "'unterminated",
                workingDirectory = null,
                stdin = null,
                timeoutMs = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
            ),
            TermuxScriptInvocation(
                executable = "~/.termux/tasker/run.sh",
                argumentText = null,
                workingDirectory = null,
                stdin = "x".repeat(TermuxScriptPolicy.MAX_STDIN_BYTES + 1),
                timeoutMs = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
            ),
            TermuxScriptInvocation(
                executable = "~/.termux/tasker/run.sh",
                argumentText = null,
                workingDirectory = null,
                stdin = null,
                timeoutMs = TermuxScriptPolicy.MAX_TIMEOUT_MS + 1,
            ),
        )
        invalid.forEach { invocation ->
            val prepared = TermuxScriptPolicy.prepare(invocation)
            assertTrue(prepared is TermuxPreparationResult.Invalid)
        }

        var calls = 0
        invalid.forEach { invocation ->
            val result = kotlinx.coroutines.runBlocking {
                TermuxScriptCoordinator(TermuxDispatchLimiter(clock = { 1_000L })).execute(
                    ready = true,
                    invocation = invocation,
                    approvedHashFor = { "a".repeat(64) },
                    commandRunner = {
                        calls++
                        TermuxCommandResult("", "", 0, 0, 0, 0)
                    },
                )
            }
            assertTrue(result is TermuxScriptExecutionResult.Rejected)
            assertEquals(TermuxScriptRejectionReason.INVALID_INPUT, (result as TermuxScriptExecutionResult.Rejected).reason)
        }
        assertEquals(0, calls)

        val hash = "a".repeat(64)
        val parsedHash = TermuxScriptPolicy.parseHashResult(
            TermuxCommandResult("$hash\n", "", 0, hash.length + 1, 0, 0),
        )
        assertEquals(hash, parsedHash)
        val oversizedHash = TermuxScriptPolicy.parseHashResult(
            TermuxCommandResult("$hash\n${"x".repeat(TermuxScriptPolicy.HASH_OUTPUT_LIMIT_BYTES)}", "", 0, hash.length + 1 + TermuxScriptPolicy.HASH_OUTPUT_LIMIT_BYTES, 0, 0),
        )
        assertEquals(null, oversizedHash)
    }

    private fun seedBundle() = OpenTaskerBundle(
        appVersion = "test",
        exportedAtEpochMs = 0L,
        tasks = listOf(
            Task(
                id = 1,
                name = "Corpus task",
                actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "safe"))),
            ),
        ),
    )

    private fun seededInsertions(seed: String, count: Int): List<String> {
        val random = Random(0x4f70656eL)
        return List(count) {
            val position = random.nextInt(seed.length)
            val injected = when (random.nextInt(4)) {
                0 -> '\u0000'
                1 -> '}'
                2 -> '\\'
                else -> ':'
            }
            seed.substring(0, position) + injected + seed.substring(position)
        }
    }

    private fun assertBoundedFailure(label: String, error: Throwable) {
        assertNotNull("$label should produce a diagnostic", error.message)
        assertTrue("$label diagnostic must be bounded", error.message.orEmpty().length <= 2_048)
        assertTrue(
            "$label should fail as a parser/validation exception, got ${error::class.java.name}",
            error is Exception,
        )
    }

    private fun assertBoundedFailure(label: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("$label should fail", error)
        assertBoundedFailure(label, requireNotNull(error))
    }
}
