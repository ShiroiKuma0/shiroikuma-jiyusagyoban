package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.PersistVariableAction
import com.opentasker.core.actions.SetVariableAction
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretVariableProvenanceTest {
    @Test
    fun structuredHttpHeadersAreAlwaysRedactedFromTraceSummaries() = runBlocking {
        val actionId = "test.http.headers"
        ActionRegistry.register(
            object : Action {
                override val id = actionId
                override val category = ActionCategory.NET
                override suspend fun run(ctx: ActionContext, args: Map<String, String>) = ActionResult.Success
            },
        )
        val variables = VariableStore().apply { seedGlobals(mapOf("TOKEN" to "literal-secret")) }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Header redaction",
                actions = listOf(
                    ActionSpec(
                        type = actionId,
                        args = mapOf("headers" to "Authorization: Bearer {{ global.TOKEN }}"),
                    ),
                ),
            ),
        )

        val runLog = report.traces.toRunLogMessage()
        assertTrue(runLog.contains("headers=<redacted>"))
        assertFalse(runLog.contains("literal-secret"))
    }

    @Test
    fun legacyExpansionRedactsNonsensitiveArgumentAndTaintsDerivedOutput() = runBlocking {
        val actionId = "test.secret.legacy"
        ActionRegistry.register(capturingAction(actionId))
        val variables = VariableStore().apply {
            seedGlobals(mapOf("API_TOKEN" to "secret-token"), secretNames = setOf("API_TOKEN"))
        }
        val logs = mutableListOf<String>()
        val report = TaskRunner(
            ActionContext(ContextWrapper(null), variables, logger = logs::add),
        ).run(
            Task(
                name = "Secret legacy expansion",
                actions = listOf(ActionSpec(type = actionId, args = mapOf("message" to "Bearer %API_TOKEN(upper)"))),
            ),
        )

        val runLog = report.traces.toRunLogMessage()
        assertTrue(report.success)
        assertTrue(variables.isSensitive("DERIVED"))
        assertEquals(listOf(SECRET_DERIVED_ACTION_LOG), logs)
        assertEquals("<redacted>", report.traces.single().argumentExpansions.single().expandedValue)
        assertTrue(runLog.contains("message=<redacted>"))
        assertFalse(runLog.contains("SECRET-TOKEN"))
    }

    @Test
    fun templateFunctionsKeepSecretProvenanceInTrace() = runBlocking {
        val actionId = "test.secret.template"
        ActionRegistry.register(capturingAction(actionId))
        val variables = VariableStore().apply {
            seedGlobals(mapOf("API_TOKEN" to "secret-token"), secretNames = setOf("API_TOKEN"))
        }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Secret template expansion",
                actions = listOf(
                    ActionSpec(
                        type = actionId,
                        args = mapOf("message" to "{{ global.API_TOKEN | upper }}"),
                    ),
                ),
            ),
        )

        val runLog = report.traces.toRunLogMessage()
        assertTrue(runLog.contains("Template:\tmessage\tglobal\t<redacted>\t<redacted>\t"))
        assertFalse(runLog.contains("SECRET-TOKEN"))
    }

    @Test
    fun secretDerivedFailuresRedactSecretValueButKeepErrorClass() = runBlocking {
        val actionId = "test.secret.failure"
        ActionRegistry.register(
            object : Action {
                override val id = actionId
                override val category = ActionCategory.VARIABLE

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    error("request failed for ${args.getValue("message")}")
                }
            },
        )
        val variables = VariableStore().apply {
            seedGlobals(mapOf("API_TOKEN" to "secret-token"), secretNames = setOf("API_TOKEN"))
        }

        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Secret failure",
                actions = listOf(ActionSpec(type = actionId, args = mapOf("message" to "%API_TOKEN"))),
            ),
        )

        val failure = report.results.single() as ActionResult.Failure
        // The real error class survives so the failure is debuggable...
        assertTrue(failure.message.contains("request failed for"))
        assertTrue(failure.message.contains("<redacted>"))
        // ...but the secret value itself never leaks into the message, cause, or run log.
        assertFalse(failure.message.contains("secret-token"))
        assertNull(failure.cause)
        assertFalse(report.traces.toRunLogMessage().contains("secret-token"))
    }

    @Test
    fun variableActionsNeverLogDeclaredOrPropagatedSecretValues() = runBlocking {
        val logs = mutableListOf<String>()
        val variables = VariableStore().apply {
            seedGlobals(mapOf("API_TOKEN" to "old-token"), secretNames = setOf("API_TOKEN"))
            pushScope()
            set("localSecret", "local-token", sensitive = true)
        }
        val context = ActionContext(ContextWrapper(null), variables, logger = logs::add)

        assertTrue(SetVariableAction().run(context, mapOf("name" to "API_TOKEN", "value" to "new-token")) is ActionResult.Success)
        assertTrue(PersistVariableAction().run(context, mapOf("name" to "localSecret", "global_name" to "COPIED")) is ActionResult.Success)

        assertTrue(variables.isSensitive("API_TOKEN"))
        assertTrue(variables.isSensitive("COPIED"))
        assertTrue(logs.all { it.contains("<redacted>") })
        assertTrue(logs.none { it.contains("new-token") || it.contains("local-token") })
    }

    private fun capturingAction(actionId: String): Action = object : Action {
        override val id = actionId
        override val category = ActionCategory.VARIABLE

        override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
            val message = args.getValue("message")
            ctx.variables.set("DERIVED", message)
            ctx.logger("Captured $message")
            return ActionResult.Success
        }
    }
}
