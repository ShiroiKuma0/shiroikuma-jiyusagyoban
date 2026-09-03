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

    /**
     * The region of a production source between two markers, or a failure naming the missing one.
     *
     * `substringAfter` returns the whole receiver when its delimiter is absent, so a gate written
     * as `read(x).substringAfter(guard).contains(token)` still passes after the guard is deleted:
     * the slice widens to the entire file and the token is found somewhere else in it. Such a gate
     * cannot fail on the regression it exists to catch, which is worse than having no gate, because
     * it reads green. Slice with this instead and the missing marker is the failure.
     *
     * [end] is searched from [start] onward, so a marker that also appears earlier in the file
     * cannot silently invert the region.
     */
    fun block(packagePath: String, start: String, end: String): String {
        val source = read(packagePath)
        val open = source.indexOf(start)
        require(open >= 0) { "$packagePath no longer contains the opening marker: $start" }
        val close = source.indexOf(end, open + start.length)
        require(close > open) { "$packagePath has no closing marker after the opening one: $end" }
        return source.substring(open, close)
    }

    /** Every production `.kt` file across all source roots. */
    fun allKotlinFiles(): List<Path> = roots.flatMap { root ->
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }
}
