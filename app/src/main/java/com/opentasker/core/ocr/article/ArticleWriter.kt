package com.opentasker.core.ocr.article

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Putting a finished article on disk — one definition, because there are two callers.
 *
 * `ocr.article` writes headlessly from a task; 「記事変換」 writes from its own window. They must agree
 * on the filename, or the same article read twice lands in two differently-named files and the
 * datetime stamp stops being the thing that says which is current.
 */
object ArticleWriter {

    /** Where an article goes when nothing says otherwise. */
    const val DEFAULT_DIRECTORY = "/sdcard/tmp"

    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Writes [document] into [directory] as `<yyyy-MM-dd_HH-mm-ss>-<headline>.html`.
     *
     * The stamp leads so a directory listing sorts by when the article was read, and so a second run
     * over the same screenshots never lands on the first one's file.
     */
    fun write(document: ArticleDocument, directory: File, now: Date = Date()): File =
        writeTo(document, File(directory, suggestedName(document, now)), now)

    /**
     * Writes [document] to exactly [file] — the name 白い熊 typed, in the folder 白い熊 chose.
     *
     * Separate from [write] because the editor lets both be changed before saving, and a name that
     * has been edited must not be quietly replaced by a freshly generated one.
     */
    fun writeTo(document: ArticleDocument, file: File, now: Date = Date()): File {
        val directory = file.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw Failed("could not create \"${directory.absolutePath}\"")
        }
        runCatching { file.writeText(ArticleHtml.render(document, BUILT_AT.format(now))) }
            .onFailure { throw Failed("could not write \"${file.absolutePath}\": ${it.message}", it) }
        return file
    }

    /** What the editor puts in its filename box before 白い熊 touches it. */
    fun suggestedName(document: ArticleDocument, now: Date = Date()): String =
        ArticleHtml.fileName(document.title, STAMP.format(now))

    /** A typed name made safe to write: no path separators, always `.html`. */
    fun sanitiseName(typed: String): String {
        val cleaned = typed.trim()
            .map { if (it.isISOControl() || it in FORBIDDEN) '_' else it }
            .joinToString("")
            .trim()
            .ifEmpty { "記事" }
        return if (cleaned.endsWith(".html", ignoreCase = true)) cleaned else "$cleaned.html"
    }

    private val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val BUILT_AT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
