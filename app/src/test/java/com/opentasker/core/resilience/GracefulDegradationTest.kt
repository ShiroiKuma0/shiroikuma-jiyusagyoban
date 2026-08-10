package com.opentasker.core.resilience

import android.content.Context
import android.content.ContextWrapper
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextSource
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GracefulDegradationTest {
    @Test
    fun missingActionFailsHonestlyWhenLookedUpAndExecuted() = runBlocking {
        val action = GracefulDegradation.getActionOrStub("test.missing.action")

        assertEquals("test.missing.action", action.id)
        assertEquals(ActionCategory.SYSTEM, action.category)
        assertEquals(ActionResult.Failure("Action 'test.missing.action' is not registered"), action.run(
            ActionContext(ContextWrapper(null), VariableStore()),
            emptyMap(),
        ))
    }

    @Test
    fun registeredActionPassesThroughTheRegistry() {
        val action = object : Action {
            override val id = "test.graceful.action"
            override val category = ActionCategory.SYSTEM
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult = ActionResult.Success
        }
        ActionRegistry.register(action)

        assertSame(action, GracefulDegradation.getActionOrStub(action.id))
    }

    @Test
    fun missingContextSourceEmitsOneNonMatchingEvent() = runBlocking {
        val event = GracefulDegradation
            .getContextSourceOrStub("test.missing.context")
            .events(ContextWrapper(null))
            .first()

        assertEquals(ContextEvent("test.missing.context", matched = false), event)
    }

    @Test
    fun registeredContextSourcePassesThroughTheRegistry() = runBlocking {
        val source = object : ContextSource {
            override val type = "test.graceful.context"
            override fun events(app: Context) = flowOf(ContextEvent(type, matched = true))
        }
        ContextSourceRegistry.register(source)

        assertSame(source, GracefulDegradation.getContextSourceOrStub(source.type))
        assertTrue(source.events(ContextWrapper(null)).first().matched)
    }
}
