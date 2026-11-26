package com.sysadmindoc.ruledeck.core

import com.sysadmindoc.ruledeck.ProductionSources
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM half of [ProductionRegexIcuCompilationTest], for the one ICU difference this project
 * keeps shipping. Android's ICU engine refuses a `}` that closes no `{n}`, `{n,}` or `{n,m}`
 * quantifier, where desktop Java reads it as a literal. In a companion object that throws from the
 * class initializer, so the whole class is dead on a phone while every JVM test stays green: a
 * TaskRunner pattern once failed every task that way, and a VariableStore one did again. The device
 * test still checks everything else ICU refuses; this catches the brace without a device.
 */
class ProductionRegexBraceTest {
    @Test
    fun noProductionRegexHasAClosingBraceAndroidRefuses() {
        val corpus = ProductionSources.repoRoot.resolve("app/build/generated/regexCorpus/production-regex-patterns.txt")
        assertTrue("the regex corpus is missing; :app:generateRegexCorpus makes it", Files.exists(corpus))
        val patterns = Files.readAllLines(corpus).filter { it.isNotBlank() }.map { it.replace("\\n", "\n") }
        assertTrue("an empty corpus would check nothing", patterns.isNotEmpty())

        val refused = patterns.filter { bareClosingBraces(it).isNotEmpty() }

        assertEquals("escape each of these as \\} so Android's ICU engine compiles them", emptyList<String>(), refused)
    }

    @Test
    fun aQuantifierIsToldFromABareBrace() {
        assertEquals(listOf(7, 8), bareClosingBraces("""\{\{.*?}}"""))
        assertEquals(listOf(1), bareClosingBraces("a}"))
        assertEquals(listOf(3), bareClosingBraces("a{b}"))
        assertEquals(emptyList<Int>(), bareClosingBraces("""\{\{.*?\}\}"""))
        assertEquals(emptyList<Int>(), bareClosingBraces("a{2}b{1,}c{1,3}"))
        assertEquals(emptyList<Int>(), bareClosingBraces("[}]x[^}]y[]}]"))
        assertEquals(emptyList<Int>(), bareClosingBraces("""[a-z&&[^}]]"""))
        assertEquals(emptyList<Int>(), bareClosingBraces("""\Q}}\E"""))
        assertEquals(emptyList<Int>(), bareClosingBraces("""\p{Cntrl}+\P{L}\x{41}\p{Nd}{2}"""))
        assertEquals(listOf(6), bareClosingBraces("""\p{Nd}}"""))
    }

    /** Indexes of each `}` that isn't escaped, quoted, in a class, or the end of a quantifier. */
    private fun bareClosingBraces(pattern: String): List<Int> {
        val bare = mutableListOf<Int>()
        var index = 0
        var classDepth = 0
        var quantifierStart = -1
        while (index < pattern.length) {
            val char = pattern[index]
            if (char == '\\') {
                val escaped = pattern.getOrNull(index + 1)
                index = when {
                    escaped == 'Q' -> pattern.indexOf("\\E", index + 2).let { end -> if (end < 0) pattern.length else end + 2 }
                    // \p{Nd}, \P{L}, \x{1F600}, \N{name}: the braces belong to the escape.
                    escaped in BRACED_ESCAPES && pattern.getOrNull(index + 2) == '{' ->
                        pattern.indexOf('}', index + 3).let { end -> if (end < 0) pattern.length else end + 1 }
                    else -> index + 2
                }
                continue
            }
            when {
                classDepth > 0 -> when (char) {
                    '[' -> classDepth++
                    ']' -> classDepth--
                }
                char == '[' -> {
                    classDepth = 1
                    // A `]` right after `[` or `[^` is a literal member, not the end of the class.
                    if (pattern.getOrNull(index + 1) == '^') index++
                    if (pattern.getOrNull(index + 1) == ']') index++
                }
                char == '{' -> quantifierStart = index
                char == '}' -> {
                    val body = if (quantifierStart >= 0) pattern.substring(quantifierStart + 1, index) else null
                    if (body == null || !QUANTIFIER_BODY.matches(body)) bare += index
                    quantifierStart = -1
                }
            }
            index++
        }
        return bare
    }

    private companion object {
        val QUANTIFIER_BODY = Regex("""\d+(,\d*)?""")
        val BRACED_ESCAPES = setOf('p', 'P', 'x', 'N')
    }
}
