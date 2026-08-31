package com.opentasker.core.actions

import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Language of a generated reference sheet (scene.gestures / key.bindings).
//
// The sheets print two kinds of text: 白い熊's own names — scenes, tasks, profiles — which are what
// they are in whatever language they were written, and the words the action itself supplies around
// them ("Swipe ↑", "Long press", "Any key"). Only the second kind can be translated, and this is what
// chooses which way it comes out.
//
// It is a per-CALL choice, not a device setting, because the point is to be able to read the sheet in
// English on a phone running Japanese — the same sheet, on demand, in either language.
// ---------------------------------------------------------------------------------------------

internal enum class SheetLang { JA, EN }

/** `ja` / `en`; anything else (including blank) follows whatever language the device is set to. */
internal fun sheetLangOf(raw: String?): SheetLang = when (raw?.trim()?.lowercase()) {
    "en", "eng", "english", "英語" -> SheetLang.EN
    "ja", "jp", "jpn", "japanese", "日本語" -> SheetLang.JA
    else -> if (Locale.getDefault().language == "ja") SheetLang.JA else SheetLang.EN
}

/**
 * Read a caller-supplied string written as `日本語|English` and take the half for this language.
 *
 * Japanese first, matching how every label and note in this workspace is written. A string with no
 * bar is language-neutral and used as-is, so an argument only needs the bar when it actually differs.
 */
internal fun SheetLang.pick(raw: String?): String {
    val text = raw?.trim().orEmpty()
    val bar = text.indexOf('|')
    if (bar < 0) return text
    val half = if (this == SheetLang.JA) text.substring(0, bar) else text.substring(bar + 1)
    return half.trim()
}

/** Pick from a literal pair, for the words the action supplies itself. */
internal fun SheetLang.of(ja: String, en: String): String = if (this == SheetLang.JA) ja else en

/**
 * Close a sheet with a rule and an italic footer line.
 *
 * The footer belongs to the action rather than to the dialog that shows it, so that ONE `lang`
 * argument settles the whole document. That costs it the ability to read `%<store>_count`, which does
 * not exist yet when this action's own arguments are expanded — hence `{count}`, substituted here.
 */
internal fun appendSheetFooter(out: StringBuilder, raw: String?, lang: SheetLang, count: Int) {
    val footer = lang.pick(raw).takeIf { it.isNotEmpty() } ?: return
    out.append("\n\n---\n\n*").append(footer.replace("{count}", count.toString())).append('*')
}
