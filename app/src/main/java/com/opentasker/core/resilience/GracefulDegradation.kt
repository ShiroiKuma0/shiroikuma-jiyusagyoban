package com.opentasker.core.resilience

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextSource
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.logging.AppLogger
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Graceful degradation handlers for missing components.
 * Prevents crashes when actions or context sources are not registered.
 */
object GracefulDegradation {
    private const val TAG = "GracefulDegradation"
    
    /**
     * Get an action from registry, or return a no-op stub if missing.
     */
    fun getActionOrStub(actionId: String): Action {
        val action = ActionRegistry.get(actionId)
        if (action == null) {
            AppLogger.warn(TAG, "Action '$actionId' not found in registry, using no-op stub")
            return NoOpAction(actionId)
        }
        return action
    }
    
    /**
     * Get a context source from registry, or return a no-op stub if missing.
     */
    fun getContextSourceOrStub(contextType: String): ContextSource {
        val source = ContextSourceRegistry.get(contextType)
        if (source == null) {
            AppLogger.warn(TAG, "Context source '$contextType' not found in registry, using no-op stub")
            return NoOpContextSource(contextType)
        }
        return source
    }
    
    /**
     * No-op action that logs and succeeds without doing anything.
     */
    private class NoOpAction(private val actionId: String) : Action {
        override val id: String = actionId
        override val category = ActionCategory.SYSTEM
        
        override suspend fun run(
            ctx: ActionContext,
            args: Map<String, String>
        ): ActionResult {
            AppLogger.warn(TAG, "No-op action executed: $actionId (action not available)")
            return ActionResult.Skip
        }
    }
    
    /**
     * No-op context source that never matches.
     */
    private class NoOpContextSource(private val contextType: String) : ContextSource {
        override val type: String = contextType
        
        override fun events(app: Context): Flow<ContextEvent> {
            AppLogger.warn(TAG, "No-op context source: $contextType (context source not available)")
            return flowOf(ContextEvent(contextType, false, emptyMap()))
        }
    }
}
