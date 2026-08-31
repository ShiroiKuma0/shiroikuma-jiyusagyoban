package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Guards the one way a regex can pass every JVM test and still kill the app on the phone.
 *
 * Android's regex engine is ICU, which reads `{` and `}` as interval-quantifier syntax and REJECTS a
 * stray brace: `Pattern.compile` throws `PatternSyntaxException`. Desktop Java is lenient and accepts
 * the same pattern happily — so a literal like `"\\{\\{\\s*array\\.(\\w+)\\s*}}"` compiles here, runs
 * green through the whole suite, and then takes the process down at class-init on a device.
 *
 * That is exactly what shipped in `+031`/`+032`: the app died before its first frame on
 * `Syntax error in regexp pattern near index 42`, and nothing on a desktop could reproduce it. This
 * test reads the main sources rather than executing them, because the JVM these tests run on is the
 * very engine that cannot see the problem.
 */
class RegexIcuCompatibilityTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun everyRegexLiteralEscapesItsBracesForIcu() {
        val literal = Regex("""Regex\(\s*"((?:[^"\\]|\\.)*)"""")
        val quantifier = Regex("""\{\d*(,\d*)?}""")

        val offenders = Files.walk(repoRoot.resolve("app/src/main/java")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .flatMap { file ->
                    val text = file.readText()
                    literal.findAll(text).mapNotNull { match ->
                        // Remove the escaped braces and the legitimate {m,n} quantifiers; whatever
                        // brace survives is the bare one ICU refuses.
                        val escapesRemoved = match.groupValues[1]
                            .replace("\\{", "")
                            .replace("\\}", "")
                        val remainder = quantifier.replace(escapesRemoved, "")
                        if (remainder.contains('{') || remainder.contains('}')) {
                            val line = text.take(match.range.first).count { it == '\n' } + 1
                            "${repoRoot.relativize(file)}:$line — ${match.groupValues[1]}"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        }

        assertEquals(
            "Regex literals with a brace ICU would reject (escape it as \\\\{ or \\\\}):\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
