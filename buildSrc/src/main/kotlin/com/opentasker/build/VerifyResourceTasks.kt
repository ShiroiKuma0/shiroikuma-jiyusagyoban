package com.opentasker.build

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyLocaleResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDirectory: DirectoryProperty

    @get:Input
    abstract val completionThreshold: Property<Double>

    @TaskAction
    fun verify() {
        val resources = resourcesDirectory.get().asFile
        val threshold = completionThreshold.get()
        val defaultValues = localeStringValues(localeXmlFiles(resources.resolve("values")))
        check(defaultValues.isNotEmpty()) { "Default Android string resources are missing." }

        val localeDirectories = resources.listFiles()
            ?.filter { it.isDirectory && isLocaleValuesDirectory(it.name) }
            .orEmpty()
            .sortedBy { it.name }
        val failures = localeDirectories.mapNotNull { directory ->
            val localeFiles = localeXmlFiles(directory)
            if (localeFiles.isEmpty()) {
                return@mapNotNull "${directory.name} contains no XML resources."
            }
            val localeValues = localeStringValues(localeFiles)
            val unknownNames = localeValues.keys - defaultValues.keys
            if (unknownNames.isNotEmpty()) {
                return@mapNotNull "${directory.name} defines unknown string(s): ${unknownNames.sorted().joinToString()}"
            }
            val translated = defaultValues.count { (name, english) ->
                localeValues.containsKey(name) && localeValues.getValue(name) != english
            }
            val completion = translated.toDouble() / defaultValues.size
            if (completion < threshold) {
                "${directory.name} is ${"%.0f%%".format(completion * 100.0)} complete; " +
                    "the release threshold is ${"%.0f%%".format(threshold * 100.0)} " +
                    "($translated/${defaultValues.size} translated strings)."
            } else {
                null
            }
        }
        check(failures.isEmpty()) {
            "Locale resource completeness gate failed:\n${failures.joinToString("\n")}"
        }
        val shipped = localeDirectories.joinToString { it.name.removePrefix("values-") }
        val examined = "${localeDirectories.size} locale director" + if (localeDirectories.size == 1) "y" else "ies"
        println(
            if (shipped.isBlank()) {
                "Locale resource gate passed: English is the only shipped locale; examined $examined."
            } else {
                "Locale resource gate passed: shipped locales $shipped meet the " +
                    "${"%.0f%%".format(threshold * 100.0)} completion threshold; examined $examined."
            },
        )
    }

    private fun localeXmlFiles(directory: File): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }

    private fun isLocaleValuesDirectory(name: String): Boolean {
        val qualifier = name.removePrefix("values-").takeIf { it != name } ?: return false
        return LOCALE_QUALIFIER.matches(qualifier)
    }

    private fun localeStringValues(files: List<File>): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return files.flatMap { file ->
            val document = factory.newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string")
            (0 until strings.length).map { index ->
                val node = strings.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent.trim()
            }
        }.toMap()
    }

    private companion object {
        val LOCALE_QUALIFIER = Regex("""^(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)$""")
    }
}

/**
 * Schema files that differ from what git has committed (modified or untracked). Returns empty when
 * git is unavailable, which keeps a source-only checkout building; the release wrapper still runs
 * its own check.
 */
internal fun gitDirtySchemaFiles(schemaDir: File): List<String> = runCatching {
    val process = ProcessBuilder("git", "status", "--porcelain", "--", schemaDir.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
        return@runCatching emptyList()
    }
    output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .mapNotNull { it.substringAfterLast(' ').substringAfterLast('/').takeIf { name -> name.endsWith(".json") } }
        .toList()
}.getOrDefault(emptyList())

abstract class VerifyRoomSchemaTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val databaseFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val schemaDir = schemaDirectory.get().asFile
        val database = databaseFile.get().asFile
        check(schemaDir.isDirectory) { "Room schema directory missing: $schemaDir" }
        val currentVersion = Regex("(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(database.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("Could not derive Room schema version from ${database.path}.")
        val missing = (1..currentVersion).filter { !File(schemaDir, "$it.json").isFile }
        check(missing.isEmpty()) {
            "Room schema files missing for version(s): ${missing.joinToString()}. Run a build to regenerate, then commit."
        }

        // Existence is not drift detection. Change an entity without bumping the version and KSP
        // silently rewrites the current schema JSON in place: every file still exists, the
        // migration test validates against the regenerated file and passes, and the gate that
        // exists to catch this reports success - while every upgrading user crashes at open with
        // Room's identity-hash mismatch. Only the release wrapper script noticed, via git.
        val drifted = gitDirtySchemaFiles(schemaDir)
        check(drifted.isEmpty()) {
            buildString {
                append("Room schema drift: ")
                append(drifted.sorted().joinToString())
                append(" differ from the committed copy. KSP regenerates the schema for the ")
                append("current version, so an entity change without a bump of ")
                append("OPEN_TASKER_DATABASE_SCHEMA_VERSION rewrites it in place and breaks every ")
                append("upgrading install. Bump the version and add a migration, or commit the ")
                append("regenerated schema if the change is intentional.")
            }
        }
        println("Room schema drift gate passed: versions 1..$currentVersion present and unmodified.")
    }
}
