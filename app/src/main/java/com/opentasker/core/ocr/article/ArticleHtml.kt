package com.opentasker.core.ocr.article

import kotlin.math.roundToInt

/**
 * The article as one self-contained HTML file.
 *
 * Two audiences at once, and they pull in opposite directions. It has to READ — a page 白い熊 can open
 * and just look at — and it has to be reopenable by step 3's editor, which needs to know which
 * screenshot every word came from and where on it. The compromise is that the provenance rides in
 * `data-` attributes on spans that carry no visible weight of their own: strip them and the page is
 * unchanged, keep them and every word can be put back on the pixels it was read from.
 *
 * Self-contained on purpose. The photographs are inlined as data URIs, so the file can be moved,
 * mailed or opened from anywhere without a sidecar folder to lose.
 */
object ArticleHtml {

    /** Above this size ratio a block is display type and gets a viewport cap. See [sizeStyle]. */
    private const val DISPLAY_RATIO = 2.0f

    fun render(document: ArticleDocument, builtAt: String): String = buildString(64 * 1024) {
        append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<meta name=\"generator\" content=\"白い熊 自由作業盤 文字認識\">\n")
        append("<meta name=\"ocr-built\" content=\"").append(escape(builtAt)).append("\">\n")
        // The editor reopens exactly these, in this order; data-p on every run indexes into the list.
        append("<meta name=\"ocr-pages\" content=\"")
            .append(escape(document.sources.joinToString("|"))).append("\">\n")
        append("<title>").append(escape(document.title)).append("</title>\n")
        append("<style>\n").append(CSS).append("</style>\n</head>\n<body>\n<article>\n")

        document.nodes.forEach { node ->
            when (node) {
                is ArticleText -> appendText(node)
                is ArticleFigure -> appendFigure(node)
            }
        }

        append("</article>\n<footer>")
        append(escape("文字認識 · "))
        append(document.blocks).append(" blocks · ").append(document.figures).append(" figures · ")
        append(document.characters).append(" characters · ").append(escape(builtAt))
        document.sources.forEach { append("<br>").append(escape(it)) }
        append("</footer>\n</body>\n</html>\n")
    }

    private fun StringBuilder.appendText(node: ArticleText) {
        val tag = node.kind.tag
        append('<').append(tag)
        if (node.kind == ArticleKind.SMALL) append(" class=\"sm\"")
        append(" data-k=\"").append(node.kind.name.lowercase()).append('"')
        // The measurement, kept exactly, next to the rendered size which may have been capped.
        append(" data-size=\"").append(twoPlaces(node.sizeRatio)).append('"')
        append(" style=\"").append(sizeStyle(node.sizeRatio)).append("\">")
        appendRuns(node)
        append("</").append(tag).append(">\n")
    }

    private fun StringBuilder.appendRuns(node: ArticleText) {
        var previous: Char? = null
        node.runs.forEach { run ->
            val before = previous
            if (before != null && needsSpace(before, run.text.firstOrNull())) append(' ')
            append("<span data-p=\"").append(run.page).append('"')
            append(" data-q=\"").append(quad(run)).append('"')
            append(" data-c=\"").append(twoPlaces(run.confidence)).append('"')
            val classes = buildString {
                if (run.bold) append('b')
                if (run.italic) { if (isNotEmpty()) append(' '); append('i') }
            }
            if (classes.isNotEmpty()) append(" class=\"").append(classes).append('"')
            append('>').append(escape(run.text)).append("</span>")
            previous = run.text.lastOrNull() ?: previous
        }
    }

    private fun StringBuilder.appendFigure(node: ArticleFigure) {
        append("<figure data-p=\"").append(node.page).append('"')
        append(" data-r=\"").append(node.left).append(',').append(node.top).append(',')
            .append(node.width).append(',').append(node.height).append("\">")
        val image = node.image
        if (image != null) {
            append("<img alt=\"\" src=\"data:image/jpeg;base64,").append(image).append("\">")
        } else {
            append("<div class=\"nofig\">")
            append(node.width).append("×").append(node.height)
            append(escape(" — 図はページ ")).append(node.page + 1).append(escape(" の y "))
            append(node.top).append('–').append(node.bottom)
            append("</div>")
        }
        node.caption?.let { caption ->
            append("<figcaption data-k=\"caption\" data-size=\"")
                .append(twoPlaces(caption.sizeRatio)).append("\">")
            appendRuns(caption)
            append("</figcaption>")
        }
        append("</figure>\n")
    }

    /**
     * The block's measured size, rendered.
     *
     * Body text is 1em by definition, so every other block states its own multiple of it and the
     * relative sizes on the page are the relative sizes in the file. Display type gets a `min()` with
     * a viewport unit on top: the sample headline measures 4.11x body, which is faithful on a screen
     * with room for it and about five characters to the line on a phone.
     */
    private fun sizeStyle(ratio: Float): String {
        val size = "${twoPlaces(ratio)}em"
        return if (ratio > DISPLAY_RATIO) "font-size:min($size,11vw)" else "font-size:$size"
    }

    private fun quad(run: ArticleRun): String =
        run.quad.joinToString(",") { "${it.x.roundToInt()},${it.y.roundToInt()}" }

    private fun twoPlaces(value: Float): String {
        val scaled = (value * 100f).roundToInt()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private fun escape(text: String): String = buildString(text.length + 16) {
        text.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(character)
            }
        }
    }

    /**
     * `<stamp>-<title>.html`, with the datetime leading so a directory listing sorts by when it was
     * read and a second run on the same article never lands on the first.
     */
    fun fileName(title: String, stamp: String): String = "$stamp-${safeTitle(title)}.html"

    private fun safeTitle(title: String): String {
        val cleaned = title
            .map { if (it.isISOControl() || it in FORBIDDEN) ' ' else it }
            .joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            .trim()
            .take(MAX_TITLE)
            .trim()
        return cleaned.ifEmpty { "記事" }
    }

    private const val MAX_TITLE = 60
    private val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' ')

    private val CSS = """
        :root {
          --bg: #fdfcf8; --fg: #17160f; --muted: #605c4e; --rule: #dcd7c6; --accent: #8a6b12;
        }
        @media (prefers-color-scheme: dark) {
          :root { --bg: #111110; --fg: #eceadf; --muted: #9d998a; --rule: #32312a; --accent: #e6bd3a; }
        }
        * { box-sizing: border-box; }
        body {
          margin: 0; background: var(--bg); color: var(--fg);
          font-family: "Iowan Old Style", Charter, Georgia, "Noto Serif", "Noto Serif JP", serif;
          font-size: clamp(16px, 1.1vw + 13px, 20px); line-height: 1.62;
          -webkit-text-size-adjust: 100%;
        }
        article { max-width: 40rem; margin: 0 auto; padding: 2.2rem 1.1rem 3rem; }
        p { margin: 0 0 1.05em; }
        h1, h2, h3 { line-height: 1.08; margin: 1.1em 0 .45em; font-weight: 700; overflow-wrap: break-word; }
        h1 { letter-spacing: -0.015em; }
        .sm { color: var(--muted); line-height: 1.45; margin-bottom: .6em; }
        .b { font-weight: 700; }
        .i { font-style: italic; }
        figure { margin: 1.8em 0; }
        figure img { display: block; width: 100%; height: auto; }
        figcaption { margin-top: .55em; color: var(--muted); line-height: 1.4; font-size: .82em; }
        .nofig {
          padding: 2.5em 1em; text-align: center; color: var(--muted);
          border: 1px dashed var(--rule); font-size: .8em;
        }
        footer {
          max-width: 40rem; margin: 0 auto; padding: 1.2rem 1.1rem 4rem;
          border-top: 1px solid var(--rule); color: var(--muted);
          font-size: .72em; line-height: 1.6; word-break: break-all;
          font-family: ui-monospace, "DejaVu Sans Mono", monospace;
        }
    """.trimIndent()
}
