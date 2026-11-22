package com.opentasker.build

/**
 * How release truth counts actions and context families from source. The documentation check and
 * `verifyReleaseTruth` both count through here, so they can't drift apart.
 * `tools/generate-release-truth.ps1` and `ReleaseTruthContractTest` count with their own copies of
 * these patterns, and each result is checked against `tools/release-truth.json`.
 */
object ReleaseTruthSources {
    private val actionDefinition = Regex("(?m)^\\s*define\\(\\\"")
    private val engineActionDefinition = Regex("(?m)^\\s*defineEngine\\(\\\"")
    private val contextTypeEnum = Regex("(?s)enum class ContextType\\s*\\{(.*?)\\}")
    private val contextTypeEntry = Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)")

    /** `define(...)` entries in `ActionCatalog.kt`. */
    fun registeredActionCount(actionCatalog: String): Int = actionDefinition.findAll(actionCatalog).count()

    /** `defineEngine(...)` entries in `ActionCatalog.kt`. */
    fun engineHandledActionCount(actionCatalog: String): Int = engineActionDefinition.findAll(actionCatalog).count()

    /** Entries of `enum class ContextType` in `ContextSpec.kt`. */
    fun contextFamilyCount(contextSpec: String): Int {
        val body = contextTypeEnum.find(contextSpec)?.groupValues?.get(1)
            ?: error("Could not derive the context type enum from ContextSpec.kt.")
        return contextTypeEntry.findAll(body).count()
    }
}
