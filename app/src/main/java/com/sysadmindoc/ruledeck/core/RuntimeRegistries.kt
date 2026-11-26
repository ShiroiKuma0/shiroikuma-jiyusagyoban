package com.sysadmindoc.ruledeck.core

import com.sysadmindoc.ruledeck.core.contexts.ApplicationContextSourceImpl
import com.sysadmindoc.ruledeck.core.contexts.ContextSourceRegistry
import com.sysadmindoc.ruledeck.core.contexts.EventContextSourceImpl
import com.sysadmindoc.ruledeck.core.contexts.LocalePluginConditionContextSource
import com.sysadmindoc.ruledeck.core.contexts.LocationContextSourceImpl
import com.sysadmindoc.ruledeck.core.contexts.StateContextSourceImpl
import com.sysadmindoc.ruledeck.core.contexts.TimeContextSourceImpl
import com.sysadmindoc.ruledeck.core.engine.ActionRegistry

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
