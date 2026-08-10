package com.opentasker.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseTruthTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val truthFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actionCatalogFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contextSpecFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalogFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val flowControlFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskRunnerFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val databaseFile: RegularFileProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val truth = parseTruth(truthFile.get().asFile.readText())
        val expectedKeys = setOf(
            "schemaVersion",
            "requiredArtifactCommit",
            "versionName",
            "versionCode",
            "minSdk",
            "compileSdk",
            "targetSdk",
            "buildTools",
            "kotlin",
            "gradle",
            "agp",
            "ksp",
            "room",
            "composeBom",
            "work",
            "registeredActions",
            "engineHandledActions",
            "contextFamilies",
            "bundleSchemaVersion",
            "roomSchemaVersion",
        )
        check(truth.keys == expectedKeys) {
            "Release truth keys differ from the verified contract. " +
                "Missing=${expectedKeys - truth.keys}; unexpected=${truth.keys - expectedKeys}."
        }

        val moduleBuild = moduleBuildFile.get().asFile.readText()
        val versions = versionCatalogFile.get().asFile.readText()
        val wrapper = wrapperFile.get().asFile.readText()
        val actionCatalog = actionCatalogFile.get().asFile.readText()
        val contextSpec = contextSpecFile.get().asFile.readText()
        val bundle = bundleFile.get().asFile.readText()
        val flowControl = flowControlFile.get().asFile.readText()
        val taskRunner = taskRunnerFile.get().asFile.readText()
        val database = databaseFile.get().asFile.readText()

        val flowBody = sourceValue(
            flowControl,
            Regex("(?s)\\bval\\s+ALL\\s*=\\s*setOf\\(([^)]*)\\)"),
            "FlowControl.ALL",
        )
        val flowControlIds = Regex("\\b[A-Z][A-Z0-9_]*\\b")
            .findAll(flowBody)
            .map { it.value }
            .toSet()
        check(flowControlIds.isNotEmpty()) { "FlowControl.ALL must contain engine-handled actions." }
        check(Regex("(?m)^\\s*const val SUB_TASK_ACTION_ID\\s*=").containsMatchIn(taskRunner)) {
            "TaskRunner must declare SUB_TASK_ACTION_ID as an engine-handled action."
        }

        val expected = mapOf(
            "schemaVersion" to "1",
            "versionName" to sourceValue(moduleBuild, Regex("val\\s+appVersionName\\s*=\\s*\"([^\"]+)\""), "version name"),
            "versionCode" to sourceValue(moduleBuild, Regex("val\\s+appVersionCode\\s*=\\s*(\\d+)"), "version code"),
            "minSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*minSdk\\s*=\\s*(\\d+)"), "minimum SDK"),
            "compileSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*compileSdk\\s*=\\s*(\\d+)"), "compile SDK"),
            "targetSdk" to sourceValue(moduleBuild, Regex("(?m)^\\s*targetSdk\\s*=\\s*(\\d+)"), "target SDK"),
            "buildTools" to sourceValue(moduleBuild, Regex("(?m)^\\s*buildToolsVersion\\s*=\\s*\"([^\"]+)\""), "build tools"),
            "kotlin" to catalogValue(versions, "kotlin"),
            "gradle" to sourceValue(wrapper, Regex("gradle-([0-9.]+)-"), "Gradle version"),
            "agp" to catalogValue(versions, "agp"),
            "ksp" to catalogValue(versions, "ksp"),
            "room" to catalogValue(versions, "room"),
            "composeBom" to catalogValue(versions, "composeBom"),
            "work" to catalogValue(versions, "work"),
            "registeredActions" to Regex("(?m)^\\s*define\\(\\\"")
                .findAll(actionCatalog)
                .count()
                .toString(),
            "engineHandledActions" to (flowControlIds.size + 1).toString(),
            "contextFamilies" to sourceValue(contextSpec, Regex("(?s)enum class ContextType\\s*\\{(.*?)\\}"), "context type enum")
                .let { body -> Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(body).count().toString() },
            "bundleSchemaVersion" to sourceValue(
                bundle,
                Regex("(?m)^\\s*const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                "bundle schema version",
            ),
            "roomSchemaVersion" to sourceValue(
                database,
                Regex("(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)"),
                "Room schema version",
            ),
        )
        expected.forEach { (key, value) ->
            check(truth.getValue(key) == value) {
                "Release truth '$key' expected '$value' but found '${truth.getValue(key)}'."
            }
        }

        val artifactCommit = truth.getValue("requiredArtifactCommit")
        check(Regex("[0-9a-f]{40}").matches(artifactCommit)) {
            "Release truth requiredArtifactCommit must be a full lowercase SHA-1."
        }
        val artifactGradle = git("show", "$artifactCommit:app/build.gradle.kts")
        check(sourceValue(artifactGradle, Regex("val\\s+appVersionName\\s*=\\s*\"([^\"]+)\""), "artifact version") == truth.getValue("versionName")) {
            "Required artifact commit has a different version."
        }
        check(sourceValue(artifactGradle, Regex("val\\s+appVersionCode\\s*=\\s*(\\d+)"), "artifact version code") == truth.getValue("versionCode")) {
            "Required artifact commit has a different version code."
        }

        val readme = readmeFile.get().asFile.readText()
        check("version-${truth.getValue("versionName")}-blue.svg" in readme) {
            "README version badge is stale."
        }
        check("### Actions (${truth.getValue("registeredActions")} registered + ${truth.getValue("engineHandledActions")} engine-handled)" in readme) {
            "README action count is stale."
        }
        check("**${truth.getValue("registeredActions")} built-in actions**" in readme) {
            "README built-in action count is stale."
        }
        check("- **${truth.getValue("contextFamilies")} context families**" in readme) {
            "README context-family count is stale."
        }

        val metadata = metadataFile.get().asFile.readText()
        check(metadataValue(metadata, "versionName") == truth.getValue("versionName")) {
            "F-Droid versionName is stale."
        }
        check(metadataValue(metadata, "versionCode") == truth.getValue("versionCode")) {
            "F-Droid versionCode is stale."
        }
        check(metadataValue(metadata, "commit") == artifactCommit) {
            "F-Droid commit does not match release truth."
        }
        println("Release truth passed for v${truth.getValue("versionName")} (${truth.getValue("versionCode")}); artifact $artifactCommit")
    }

    private fun parseTruth(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val pattern = Regex("\\\"([A-Za-z][A-Za-z0-9]*)\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|(\\d+))")
        pattern.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            check(values.put(key, match.groupValues[2].ifBlank { match.groupValues[3] }) == null) {
                "Release truth contains duplicate key '$key'."
            }
        }
        check(values.isNotEmpty()) { "Release truth manifest is empty or invalid." }
        return values
    }

    private fun sourceValue(text: String, pattern: Regex, name: String): String =
        pattern.find(text)?.groupValues?.get(1)
            ?: error("Could not derive $name from shipped source.")

    private fun catalogValue(text: String, key: String): String =
        sourceValue(text, Regex("(?m)^$key\\s*=\\s*\"([^\"]+)\""), "$key version")

    private fun metadataValue(text: String, key: String): String =
        Regex("(?m)^\\s*(?:-\\s*)?$key:\\s*(.+?)\\s*$")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trim('"', '\'')
            ?: error("F-Droid metadata is missing '$key'.")

    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
