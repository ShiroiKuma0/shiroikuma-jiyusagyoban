package com.opentasker.core.contexts

/** Matches an observed foreground Activity class against a bounded exact/glob pattern. */
object ApplicationComponentMatcher {
    const val MAX_PATTERN_LENGTH = 256

    fun matches(pattern: String, actual: String): Boolean {
        val normalizedPattern = pattern.trim()
        val normalizedActual = actual.trim()
        if (!isValidPattern(normalizedPattern) || normalizedActual.isBlank()) return false

        var patternIndex = 0
        var actualIndex = 0
        var starIndex = -1
        var starMatchIndex = -1
        while (actualIndex < normalizedActual.length) {
            when {
                patternIndex < normalizedPattern.length &&
                    (normalizedPattern[patternIndex] == '?' || normalizedPattern[patternIndex] == normalizedActual[actualIndex]) -> {
                    patternIndex++
                    actualIndex++
                }

                patternIndex < normalizedPattern.length && normalizedPattern[patternIndex] == '*' -> {
                    starIndex = patternIndex++
                    starMatchIndex = actualIndex
                }

                starIndex >= 0 -> {
                    patternIndex = starIndex + 1
                    actualIndex = ++starMatchIndex
                }

                else -> return false
            }
        }

        while (patternIndex < normalizedPattern.length && normalizedPattern[patternIndex] == '*') {
            patternIndex++
        }
        return patternIndex == normalizedPattern.length
    }

    fun isValidPattern(pattern: String): Boolean {
        val normalized = pattern.trim()
        if (normalized.isBlank() || normalized.length > MAX_PATTERN_LENGTH) return false
        return normalized.none { character ->
            character.isWhitespace() || character.isISOControl() || character in INVALID_CHARS
        }
    }

    private val INVALID_CHARS = setOf('/', '\\', ',', ';', ':')
}
