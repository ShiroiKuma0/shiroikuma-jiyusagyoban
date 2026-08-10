package com.opentasker.core

import com.opentasker.core.contexts.ApplicationContextSourceImpl
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.EventContextSourceImpl
import com.opentasker.core.contexts.LocalePluginConditionContextSource
import com.opentasker.core.contexts.LocationContextSourceImpl
import com.opentasker.core.contexts.StateContextSourceImpl
import com.opentasker.core.contexts.TimeContextSourceImpl
import com.opentasker.core.engine.ActionRegistry

fun registerCoreRuntime() {
    registerBuiltInActions()
    registerContextSources()
}

private fun registerBuiltInActions() {
    ActionRegistry.clear()
    ActionRegistry.registerAll()
}

private fun registerContextSources() {
    listOf(
        ApplicationContextSourceImpl(),
        TimeContextSourceImpl(),
        StateContextSourceImpl(),
        EventContextSourceImpl(),
        LocationContextSourceImpl(),
        LocalePluginConditionContextSource(),
    ).forEach(ContextSourceRegistry::register)
}
