package com.sysadmindoc.ruledeck.i18n

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// The fork's screens, and its whole action catalogue, carry their English in the code — the Kotlin
// literal is the text. Upstream moved to string resources; this fork did not follow, on purpose (see
// ActionMetadata), and is rebased onto every upstream commit, so thousands of literals cannot be
// turned into resource ids without every sync fighting it.
//
// So the fork's own text is translated the gettext way (白い熊, 2026-11-23: full Japanese): the
// English literal is the key, and a table per language maps it to its translation. English shows
// the literal untouched; a string with no entry shows its English. The tables are plain text files
// on the class path, `i18n/<language>/<area>.txt`, listed in `i18n/<language>/index.txt` — no
// Context is needed, so a label can be translated wherever it is shown, and JVM tests keep working.
//
// Upstream's string RESOURCES are a separate matter and are translated the Android way, in
// `res/values-ja/`.

/**
 * [text] in the language of the device: its translation where the table has one, else [text] itself.
 * With [args] the result is formatted (`%1$s`, `%2$d`, …) — write the placeholders in [text] and
 * never build a sentence by gluing translated pieces, because Japanese orders them differently.
 *
 * Translate where a text is SHOWN, not where it is defined: a label kept in an enum, a constant or
 * a database row stays English there, and the composable or the notification that shows it calls
 * `tr(label)`. That way a language switched while the app runs is followed, and nothing that is
 * stored or compared ever holds a translation.
 */
fun tr(text: String, vararg args: Any?): String = Translations.translate(null, text, args)

/** As [tr], for a word that needs different translations in different places ([context] tells them apart). */
fun trc(context: String, text: String, vararg args: Any?): String = Translations.translate(context, text, args)

internal object Translations {
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    /** For tests: translate as if the device spoke this language. Null follows the device again. */
    @Volatile
    var forcedLanguage: String? = null

    fun translate(context: String?, text: String, args: Array<out Any?>): String {
        val language = forcedLanguage ?: Locale.getDefault().language
        val found = if (language == "en" || text.isEmpty()) null else {
            val table = table(language)
            (if (context != null) table["$context|$text"] else null) ?: table[text]
        }
        if (args.isEmpty()) return found ?: text
        if (found != null) {
            // A translation with a broken placeholder must never take the screen down with it.
            runCatching { return String.format(Locale.getDefault(), found, *args) }
        }
        return String.format(Locale.getDefault(), text, *args)
    }

    /** Whether [language] has an entry for [text] — what the coverage test asks. */
    fun has(language: String, text: String): Boolean = text in table(language)

    fun table(language: String): Map<String, String> = tables.getOrPut(language) { load(language) }

    private fun load(language: String): Map<String, String> {
        val loader = Translations::class.java.classLoader ?: return emptyMap()
        val index = loader.getResourceAsStream("i18n/$language/index.txt") ?: return emptyMap()
        val table = HashMap<String, String>()
        index.bufferedReader(Charsets.UTF_8).useLines { names ->
            names.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { name ->
                loader.getResourceAsStream("i18n/$language/$name")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                    parse(lines) { key, value -> table.putIfAbsent(key, value) }
                }
            }
        }
        return table
    }

    /**
     * The table format: an entry is a line `= English` followed by a line `: translation`. In both,
     * `\n` is a line break and `\\` a backslash. A context is written `=[context] English`. Lines
     * starting with `#` and blank lines are skipped.
     */
    internal fun parse(lines: Sequence<String>, entry: (String, String) -> Unit) {
        var key: String? = null
        for (line in lines) {
            when {
                line.startsWith("= ") -> key = unescape(line.substring(2))
                line.startsWith("=[") && "] " in line -> {
                    val close = line.indexOf("] ")
                    key = line.substring(2, close) + "|" + unescape(line.substring(close + 2))
                }
                line.startsWith(": ") && key != null -> {
                    entry(key, unescape(line.substring(2)))
                    key = null
                }
            }
        }
    }

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
