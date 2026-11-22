package com.opentasker.build

/**
 * How release truth counts actions and context families from source. The documentation check and
 * `verifyReleaseTruth` both count through here, so they can't drift apart.
 * Two places count with their own copies of these patterns: `tools/generate-release-truth.ps1`,
 * which writes `tools/release-truth.json`, and `ReleaseTruthContractTest`, which checks that file
 * against the source. A drifted copy in the script writes a wrong count that those checks then catch.
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
