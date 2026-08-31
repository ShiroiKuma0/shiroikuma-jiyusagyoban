package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round trip the editor stands on: render an article, read it back, and get the same article.
 *
 * This is the one piece of the whole pipeline that MUST be exact. Everything else can be approximate
 * and corrected by hand, but if reopening a file quietly loses a quad or a style then every edit made
 * afterwards is written back wrong.
 */
class ArticleHtmlParserTest {

    private fun quad(left: Int, top: Int, right: Int, bottom: Int) = listOf(
        OcrPoint(left.toFloat(), top.toFloat()), OcrPoint(right.toFloat(), top.toFloat()),
        OcrPoint(right.toFloat(), bottom.toFloat()), OcrPoint(left.toFloat(), bottom.toFloat()),
    )

    private fun run(text: String, page: Int = 0, bold: Boolean = false, italic: Boolean = false,
                    confidence: Float = 0.97f, top: Int = 100) =
        ArticleRun(text, page, quad(295, top, 1700, top + 47), bold, italic, confidence)

    private val document = ArticleDocument(
        title = "The War on History Is a War on Democracy",
        sources = listOf(
            "/sdcard/Pictures/Screenshots/Screenshot_20260809_095943_com.nytimes.android.jpg",
            "/sdcard/Pictures/Screenshots/Screenshot_20260809_100054_com.nytimes.android.jpg",
        ),
        nodes = listOf(
            ArticleFigure(
                page = 0, left = 0, top = 142, right = 2048, bottom = 2465,
                image = "/9j/4AAQSkZJRgABAQ==",
                caption = ArticleText(
                    ArticleKind.CAPTION, 0.75f, 2505,
                    listOf(run("An anti-integration demonstration in 1963.", top = 2505)),
                ),
            ),
            ArticleText(ArticleKind.SMALL, 0.72f, 2668, listOf(run("ESSAY", bold = true, top = 2668))),
            ArticleText(
                ArticleKind.TITLE, 4.06f, 2791,
                listOf(run("The War on History Is", top = 2791), run("a War on Democracy", top = 3003)),
            ),
            ArticleText(
                ArticleKind.PARAGRAPH, 1.0f, 4181,
                listOf(
                    run("To hear more audio stories from \"publications\" & <others>", italic = true, top = 4181),
                    run("download Audm for iPhone or Android.", page = 1, italic = true, top = 4252),
                ),
            ),
        ),
    )

    private fun roundTrip(): ArticleDocument =
        ArticleHtmlParser.parse(ArticleHtml.render(document, "2026-08-09 15:08:42")).document

    @Test
    fun `the title and the source screenshots survive`() {
        val back = roundTrip()
        assertEquals(document.title, back.title)
        assertEquals(document.sources, back.sources)
    }

    @Test
    fun `every node comes back, in order and of the same kind`() {
        val back = roundTrip()
        assertEquals(document.nodes.size, back.nodes.size)
        document.nodes.zip(back.nodes).forEach { (before, after) ->
            when (before) {
                is ArticleText -> {
                    after as ArticleText
                    assertEquals(before.kind, after.kind)
                    assertEquals(before.sizeRatio, after.sizeRatio, 0.01f)
                }
                is ArticleFigure -> {
                    after as ArticleFigure
                    assertEquals(before.page, after.page)
                    assertEquals(before.left, after.left)
                    assertEquals(before.top, after.top)
                    assertEquals(before.right, after.right)
                    assertEquals(before.bottom, after.bottom)
                }
            }
        }
    }

    @Test
    fun `every run keeps its text, page, quad, styling and confidence`() {
        val before = document.nodes.filterIsInstance<ArticleText>().flatMap { it.runs }
        val after = roundTrip().nodes.filterIsInstance<ArticleText>().flatMap { it.runs }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.text, b.text)
            assertEquals(a.page, b.page)
            assertEquals(a.bold, b.bold)
            assertEquals(a.italic, b.italic)
            assertEquals(a.confidence, b.confidence, 0.01f)
            assertEquals(a.quad.size, b.quad.size)
            a.quad.zip(b.quad).forEach { (p, q) ->
                assertEquals(p.x, q.x, 0.51f)
                assertEquals(p.y, q.y, 0.51f)
            }
        }
    }

    @Test
    fun `escaped text comes back exactly, quotes and angle brackets included`() {
        val text = roundTrip().nodes.filterIsInstance<ArticleText>()
            .flatMap { it.runs }.map { it.text }
        assertTrue(text.any { it == "To hear more audio stories from \"publications\" & <others>" })
    }

    @Test
    fun `a figure keeps its image blob byte for byte and its caption`() {
        val figure = roundTrip().nodes.filterIsInstance<ArticleFigure>().single()
        assertEquals("/9j/4AAQSkZJRgABAQ==", figure.image)
        assertNotNull(figure.caption)
        assertEquals(ArticleKind.CAPTION, figure.caption?.kind)
        assertTrue(figure.caption!!.plain.startsWith("An anti-integration"))
    }

    @Test
    fun `rendering what was parsed reproduces the file`() {
        val once = ArticleHtml.render(document, "2026-08-09 15:08:42")
        val twice = ArticleHtml.render(ArticleHtmlParser.parse(once).document, "2026-08-09 15:08:42")
        assertEquals(once, twice)
    }

    @Test
    fun `a hand-edited element is stepped over rather than fatal`() {
        val html = ArticleHtml.render(document, "2026-08-09 15:08:42")
            .replace(Regex("<p data-k=\"paragraph\"[^>]*>.*?</p>", RegexOption.DOT_MATCHES_ALL),
                "<p>someone retyped this by hand</p>")
        val parsed = ArticleHtmlParser.parse(html)
        assertEquals(1, parsed.skipped)
        assertEquals(document.nodes.size - 1, parsed.document.nodes.size)
    }

    @Test(expected = ArticleHtmlParser.NotAnArticle::class)
    fun `something that is not one of ours is refused`() {
        ArticleHtmlParser.parse("<html><body><p>hello</p></body></html>")
    }
}

/** The filename the editor prefills, and what happens to whatever 白い熊 types over it. */
class ArticleWriterNameTest {

    @Test
    fun `the suggestion leads with the stamp and carries the headline`() {
        val document = ArticleDocument("The War on History Is a War on Democracy", emptyList(), emptyList())
        val suggested = ArticleWriter.suggestedName(document, java.util.Date(0))
        assertTrue(suggested, suggested.endsWith("-The War on History Is a War on Democracy.html"))
        assertTrue(suggested, Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-").containsMatchIn(suggested))
    }

    @Test
    fun `a typed name gets html put back on it`() {
        assertEquals("memory laws.html", ArticleWriter.sanitiseName("memory laws"))
        assertEquals("memory laws.html", ArticleWriter.sanitiseName("  memory laws.html  "))
        assertEquals("Notes.HTML", ArticleWriter.sanitiseName("Notes.HTML"))
    }

    @Test
    fun `a typed name cannot climb out of the folder it was given`() {
        // The property that matters is that no separator survives, so the name can only ever be a
        // leaf inside the chosen folder. What the dots collapse to is cosmetic.
        listOf("../../etc/passwd", "a:b", "x\\y", "a|b?c*d").forEach { typed ->
            val safe = ArticleWriter.sanitiseName(typed)
            assertTrue(safe, safe.none { it in "/\\:*?\"<>|" })
            assertTrue(safe, safe.endsWith(".html"))
        }
        assertEquals("a_b.html", ArticleWriter.sanitiseName("a:b"))
    }

    @Test
    fun `an emptied name still writes something`() {
        assertEquals("記事.html", ArticleWriter.sanitiseName("   "))
    }
}
