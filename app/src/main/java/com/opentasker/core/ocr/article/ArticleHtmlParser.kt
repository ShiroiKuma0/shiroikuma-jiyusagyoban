package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrPoint

/**
 * Reads an article back out of the HTML [ArticleHtml] wrote.
 *
 * This is the hinge the editor turns on, and it only has to understand ONE dialect — our own. The
 * shape is fixed and narrow: a `<meta name="ocr-pages">` naming the screenshots, then a flat run of
 * `<h1>/<h2>/<h3>/<p>/<figure>` in reading order, each text element a sequence of `<span>`s carrying
 * `data-p`, `data-q` and `data-c`. That is what makes a hand-rolled scanner reasonable here where a
 * general HTML parser would not be.
 *
 * Deliberately forgiving. Anything it does not recognise it steps over rather than failing on, so an
 * article 白い熊 has been at with a text editor still opens — it simply loses the provenance of
 * whatever was rewritten, which the editor reports rather than hides.
 */
object ArticleHtmlParser {

    class NotAnArticle(message: String) : IllegalArgumentException(message)

    /** What came back, including what could not be understood. */
    data class Parsed(
        val document: ArticleDocument,
        /** Elements inside `<article>` that matched nothing known — a hand edit, usually. */
        val skipped: Int,
    )

    fun parse(html: String): Parsed {
        val body = section(html, "<article>", "</article>")
            ?: throw NotAnArticle("no <article> — was this written by 記事変換?")

        val nodes = ArrayList<ArticleNode>()
        var skipped = 0
        var cursor = 0
        while (true) {
            val open = ELEMENT.find(body, cursor) ?: break
            val tag = open.groupValues[1]
            val attributes = open.groupValues[2]
            val close = body.indexOf("</$tag>", open.range.last + 1)
            if (close < 0) { skipped++; break }
            val inner = body.substring(open.range.last + 1, close)

            when (tag) {
                "figure" -> nodes += figure(attributes, inner)
                else -> {
                    val runs = runs(inner)
                    if (runs.isEmpty()) skipped++ else nodes += ArticleText(
                        kind = kindOf(attributes, tag),
                        sizeRatio = attribute(attributes, "data-size")?.toFloatOrNull() ?: 1f,
                        inkTop = runs.minOf { run -> run.quad.minOf { it.y }.toInt() },
                        runs = runs,
                    )
                }
            }
            cursor = close + tag.length + 3
        }

        return Parsed(
            document = ArticleDocument(
                title = unescape(section(html, "<title>", "</title>").orEmpty()).ifBlank { "記事" },
                sources = meta(html, "ocr-pages")?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
                nodes = nodes,
            ),
            skipped = skipped,
        )
    }

    // -- pieces --------------------------------------------------------------------------------------

    private val ELEMENT = Regex("<(h1|h2|h3|p|figure)\\b([^>]*)>")
    private val SPAN = Regex("<span\\b([^>]*)>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val IMG = Regex("<img\\b[^>]*src=\"data:image/jpeg;base64,([^\"]*)\"")
    private val CAPTION = Regex("<figcaption\\b([^>]*)>(.*?)</figcaption>", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("([\\w-]+)=\"([^\"]*)\"")

    private fun figure(attributes: String, inner: String): ArticleFigure {
        val rect = attribute(attributes, "data-r")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        val left = rect?.getOrNull(0) ?: 0
        val top = rect?.getOrNull(1) ?: 0
        val width = rect?.getOrNull(2) ?: 0
        val height = rect?.getOrNull(3) ?: 0
        val caption = CAPTION.find(inner)
        val captionRuns = caption?.let { runs(it.groupValues[2]) }.orEmpty()
        return ArticleFigure(
            page = attribute(attributes, "data-p")?.toIntOrNull() ?: 0,
            left = left, top = top, right = left + width, bottom = top + height,
            image = IMG.find(inner)?.groupValues?.get(1),
            caption = if (captionRuns.isEmpty()) null else ArticleText(
                kind = ArticleKind.CAPTION,
                // Read, not assumed: the caption carries its own measured size like any other block,
                // and inventing one here made a re-saved file differ from the one that was opened.
                sizeRatio = attribute(caption!!.groupValues[1], "data-size")?.toFloatOrNull() ?: 0.8f,
                inkTop = captionRuns.minOf { run -> run.quad.minOf { it.y }.toInt() },
                runs = captionRuns,
            ),
        )
    }

    private fun runs(inner: String): List<ArticleRun> = SPAN.findAll(inner).mapNotNull { match ->
        val attributes = match.groupValues[1]
        val quad = attribute(attributes, "data-q")
            ?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size >= 8 }
            ?: return@mapNotNull null
        val classes = attribute(attributes, "class").orEmpty().split(' ')
        ArticleRun(
            text = unescape(match.groupValues[2].replace(TAGS, "")),
            page = attribute(attributes, "data-p")?.toIntOrNull() ?: 0,
            quad = (0 until 4).map { OcrPoint(quad[it * 2], quad[it * 2 + 1]) },
            bold = "b" in classes,
            italic = "i" in classes,
            confidence = attribute(attributes, "data-c")?.toFloatOrNull() ?: 1f,
        )
    }.toList()

    private val TAGS = Regex("<[^>]+>")

    private fun kindOf(attributes: String, tag: String): ArticleKind {
        attribute(attributes, "data-k")?.let { declared ->
            ArticleKind.entries.firstOrNull { it.name.equals(declared, ignoreCase = true) }
                ?.let { return it }
        }
        // A hand-edited file may have lost data-k; the tag alone still says most of it.
        return when (tag) {
            "h1" -> ArticleKind.TITLE
            "h2" -> ArticleKind.HEADING
            "h3" -> ArticleKind.SUBHEADING
            else -> ArticleKind.PARAGRAPH
        }
    }

    private fun attribute(attributes: String, name: String): String? =
        ATTRIBUTE.findAll(attributes).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)

    private fun meta(html: String, name: String): String? =
        Regex("<meta\\s+name=\"$name\"\\s+content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.let(::unescape)

    private fun section(text: String, open: String, close: String): String? {
        val from = text.indexOf(open)
        if (from < 0) return null
        val to = text.indexOf(close, from + open.length)
        if (to < 0) return null
        return text.substring(from + open.length, to)
    }

    private fun unescape(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
