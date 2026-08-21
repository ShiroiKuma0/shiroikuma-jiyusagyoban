package com.opentasker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Resolves a production source file by its package path, wherever it lives.
 *
 * Source-scanning gates used to hardcode `app/src/main/java`. As the core modules take physical
 * ownership of their files those paths stop resolving, and a gate that cannot find its file is a
 * gate that no longer runs. Look the file up across every production source root instead.
 */
object ProductionSources {
    val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .map(Path::toAbsolutePath)
        .map(Path::normalize)
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val roots: List<Path> = listOf(
        "app/src/main/java",
        "core/model/src/main/kotlin",
        "core/common/src/main/kotlin",
        "core/storage/src/main/kotlin",
        "core/engine/src/main/kotlin",
        "feature/automation/src/main/kotlin",
    ).map(repoRoot::resolve).filter(Files::isDirectory)

    /** [packagePath] is relative to the source root, e.g. `com/opentasker/core/storage/AppDatabase.kt`. */
    fun path(packagePath: String): Path = roots.map { it.resolve(packagePath) }.firstOrNull(Files::exists)
        ?: error("No production source root contains $packagePath (looked in $roots)")

    fun read(packagePath: String): String = path(packagePath).readText()

    /** Every production `.kt` file across all source roots. */
    fun allKotlinFiles(): List<Path> = roots.flatMap { root ->
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }
}
