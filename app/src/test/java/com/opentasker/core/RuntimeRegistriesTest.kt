package com.opentasker.core

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistriesTest {
    // Actions handled directly by the TaskRunner (not via ActionRegistry).
    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID) + FlowControl.ALL

    @Test
    fun everyUiMetadataActionHasRuntimeImplementation() {
        registerActionMetadata()
        registerCoreRuntime()

        val missing = ActionMetadataRegistry.all()
            .map { it.id }
            .filter { it !in engineHandledActions && ActionRegistry.get(it) == null }

        assertTrue("Missing runtime actions: $missing", missing.isEmpty())
    }

    @Test
    fun coreContextSourcesIncludeLiveLocationSource() {
        registerCoreRuntime()

        val registered = ContextSourceRegistry.all().map { it.type }.toSet()

        assertTrue("Location context source must be registered: $registered", "location" in registered)
    }
}
