package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout pass, run over the real geometry of the sample page.
 *
 * Every rectangle, ink height and stroke weight below was measured off
 * `Screenshot_20260809_095943_com.nytimes.android.jpg` — a 2048x41744 New York Times essay — rather
 * than invented, so this fails if the thresholds stop describing an actual page. What it cannot cover
 * is the recognition itself, which needs the ONNX weights and a device; everything from the boxes
 * onward is here.
 */
class ArticleLayoutTest {

    /** y0, y1, x0, x1, ink height, stroke weight, shear, text. */
    private data class Measured(
        val top: Int, val bottom: Int, val left: Int, val right: Int,
        val inkHeight: Int, val stroke: Float, val shear: Float, val text: String,
    )

    private val page1 = listOf(
        // 白い熊's kanji clock, cut into pieces by the detector and placed as MEASURED: the leftmost
        // glyphs were never detected at all, so no piece reaches a margin — x 490..1842 on a 2048 px
        // page. What every piece does do is run into the top edge of the image.
        Measured(0, 179, 490, 1388, 85, 0.160f, 0f, "午前九時五十九分"),
        Measured(15, 126, 1349, 1614, 60, 0.160f, 0f, "日曜日"),
        Measured(9, 92, 1686, 1842, 50, 0.160f, 0f, "充電"),
        // …and the navigation bar at the far end, which runs into the bottom edge the same way.
        Measured(6020, 6031, 1777, 1789, 11, 0.150f, 0f, "0"),
        Measured(6022, 6059, 1491, 1549, 37, 0.150f, 0f, "888"),
        Measured(6058, 6073, 1776, 1790, 15, 0.150f, 0f, "0"),
        Measured(2505, 2549, 49, 1697, 44, 0.130f, 0f,
            "An anti-integration demonstration at a Montgomery, Ala., high school in 1963."),
        Measured(2668, 2702, 297, 433, 34, 0.203f, 0f, "ESSAY"),
        Measured(2791, 2984, 296, 1623, 191, 0.156f, 0f, "The War on History Is"),
        Measured(3003, 3196, 296, 1537, 191, 0.156f, 0f, "a War on Democracy"),
        Measured(3243, 3304, 294, 1737, 47, 0.155f, 0f, "A scholar of totalitarianism argues that new laws"),
        Measured(3325, 3386, 298, 1631, 47, 0.155f, 0f, "restricting the discussion of race in American"),
        Measured(3408, 3467, 295, 1484, 45, 0.155f, 0f, "schools have dire precedents in Europe."),
        Measured(3545, 3588, 297, 698, 35, 0.189f, 0f, "By Timothy Snyder"),
        Measured(3621, 3650, 294, 548, 29, 0.166f, 0f, "June 29, 2021"),
        Measured(3820, 3852, 296, 690, 24, 0.275f, 0f, "Listen to This Article"),
        Measured(3877, 3915, 294, 770, 32, 0.138f, 0f, "Audio Recording by Audm"),
        Measured(3990, 4031, 294, 527, 41, 0.150f, 0f, "31:29"),
        Measured(4181, 4228, 302, 1727, 37, 0.153f, 0.27f, "To hear more audio stories from publications like The New York"),
        Measured(4252, 4290, 302, 1337, 37, 0.141f, 0.27f, "Times, download Audm for iPhone or Android."),
    ) + bodyParagraph()

    /** The article's first paragraph: fifteen lines, the last of them plainly short. */
    private fun bodyParagraph() = listOf(
        Triple(4450, 4509, 1583), Triple(4545, 4604, 1724), Triple(4640, 4687, 1723),
        Triple(4735, 4794, 1591), Triple(4830, 4877, 1713), Triple(4925, 4984, 1617),
        Triple(5021, 5080, 1643), Triple(5116, 5175, 1703), Triple(5211, 5270, 1368),
        Triple(5306, 5365, 1751), Triple(5401, 5460, 1577), Triple(5496, 5555, 1684),
        Triple(5591, 5646, 1658), Triple(5686, 5745, 1690), Triple(5781, 5840, 833),
    ).mapIndexed { index, (top, bottom, right) ->
        Measured(top, bottom, 295, right, 47, 0.150f, 0f, "body line $index")
    }

    /** The hero photograph, full-bleed, directly under the status bar. */
    private val photograph = 122 to 2465

    private fun scan(): PageScan {
        val height = 6100
        val rows = RowProfile(height)
        page1.forEach { line ->
            for (y in line.top until line.bottom) {
                rows.ink[y] = 0.035f
                rows.left[y] = line.left
                rows.right[y] = line.right
            }
        }
        // A photograph is a solid run of inked rows with no leading anywhere in it.
        for (y in photograph.first until photograph.second) {
            rows.ink[y] = 0.9f
            rows.left[y] = 0
            rows.right[y] = 2047
        }
        return PageScan(
            index = 0, path = "/sdcard/tmp/page1.jpg",
            width = 2048, height = height, background = 18,
            rows = rows,
            boxes = page1.map { line ->
                ScannedBox(
                    text = line.text,
                    confidence = 0.97f,
                    // Boxes arrive DILATED by the unclip step, which is exactly why the pass may not
                    // measure type size from them. The inflation is reproduced here so that it would.
                    quad = unclipped(line),
                    probe = LineStyle.Probe(line.stroke, line.shear, line.inkHeight),
                )
            },
        )
    }

    /** PaddleOCR's dilation: every side pushed out by `area * 1.5 / perimeter`. */
    private fun unclipped(line: Measured): List<OcrPoint> {
        val width = (line.right - line.left).toFloat()
        val height = (line.bottom - line.top).toFloat()
        val out = width * height * 1.5f / (2f * (width + height))
        val left = line.left - out
        val right = line.right + out
        val top = line.top - out
        val bottom = line.bottom + out
        return listOf(
            OcrPoint(left, top), OcrPoint(right, top),
            OcrPoint(right, bottom), OcrPoint(left, bottom),
        )
    }

    private fun build(): List<ArticleNode> = ArticleLayout.build(
        scans = listOf(scan()), cropTop = 0, cropBottom = 0,
        figureJpeg = { _, _, _, _, _ -> null },
    )

    @Test
    fun `the status bar never reaches the article`() {
        val everything = build().filterIsInstance<ArticleText>().joinToString(" ") { it.plain }
        // The piece that actually leaked on the sample: a middle box of the bar, in no margin.
        assertFalse("the middle of the clock survived", everything.contains("午前九時五十九分"))
        assertFalse("the clock's date survived", everything.contains("日曜日"))
        assertFalse("the charge indicator survived", everything.contains("充電"))
        assertFalse("the navigation bar survived", everything.contains("888"))
    }

    @Test
    fun `a photograph with writing in it is still a photograph`() {
        // The sample's Indianapolis picket signs: a 1056 px band, densely inked, with nearly half its
        // rows claimed by the detector. Judged on text coverage alone it came out as headings.
        val height = 3000
        val rows = RowProfile(height)
        for (y in 500 until 1556) {
            rows.ink[y] = 0.70f
            rows.left[y] = 294
            rows.right[y] = 1755
        }
        val signs = listOf(700 to 260, 1000 to 240, 1300 to 200).map { (top, tall) ->
            Measured(top, top + tall, 400, 1500, tall, 0.150f, 0f, "sign")
        }
        val body = (0 until 6).map { index ->
            val top = 1700 + index * 95
            Measured(top, top + 47, 295, 1700, 47, 0.150f, 0f, "body $index")
        }
        (signs + body).forEach { line ->
            for (y in line.top until line.bottom) {
                if (rows.ink[y] == 0f) { rows.ink[y] = 0.035f; rows.left[y] = line.left; rows.right[y] = line.right }
            }
        }
        val scan = PageScan(
            index = 0, path = "/sdcard/tmp/p.jpg", width = 2048, height = height, background = 18,
            rows = rows,
            boxes = (signs + body).map {
                ScannedBox(it.text, 0.9f, unclipped(it), LineStyle.Probe(it.stroke, it.shear, it.inkHeight))
            },
        )
        val nodes = ArticleLayout.build(listOf(scan), 0, 0) { _, _, _, _, _ -> null }
        assertEquals("the picture was read as text", 1, nodes.filterIsInstance<ArticleFigure>().size)
        val text = nodes.filterIsInstance<ArticleText>().joinToString(" ") { it.plain }
        assertFalse("sign text escaped the picture: $text", text.contains("sign"))
    }

    @Test
    fun `a curly quote does not swallow the space before it`() {
        val runs = listOf("are called", "\u201Cmemory laws\u201D so").mapIndexed { index, text ->
            ArticleRun(text, 0, unclipped(Measured(index * 95, index * 95 + 47, 295, 1700, 47, 0.15f, 0f, text)),
                bold = false, italic = false, confidence = 0.9f)
        }
        assertEquals(
            "are called \u201Cmemory laws\u201D so",
            ArticleText(ArticleKind.PARAGRAPH, 1f, 0, runs).plain,
        )
    }

    @Test
    fun `japanese lines join with no space`() {
        val runs = listOf("記事を", "読み取る").mapIndexed { index, text ->
            ArticleRun(text, 0, unclipped(Measured(index * 95, index * 95 + 47, 295, 700, 47, 0.15f, 0f, text)),
                bold = false, italic = false, confidence = 0.9f)
        }
        assertEquals("記事を読み取る", ArticleText(ArticleKind.PARAGRAPH, 1f, 0, runs).plain)
    }

    @Test
    fun `the photograph becomes a figure and takes the caption under it`() {
        val figures = build().filterIsInstance<ArticleFigure>()
        assertEquals(1, figures.size)
        val figure = figures.single()
        // The photograph starts at 122, immediately under the status bar; the band may not begin
        // until the clock's ink is safely behind it, which costs about 20 px off the top by design.
        assertTrue("figure top ${figure.top}", figure.top in 122..220)
        assertEquals(2465, figure.bottom)
        assertEquals(2048, figure.width)
        assertTrue(
            "the caption did not attach: ${figure.caption?.plain}",
            figure.caption?.plain?.startsWith("An anti-integration") == true,
        )
        assertEquals(ArticleKind.CAPTION, figure.caption?.kind)
    }

    @Test
    fun `the headline is one title of both its lines`() {
        val title = build().filterIsInstance<ArticleText>()
            .single { it.kind == ArticleKind.TITLE }
        assertEquals("The War on History Is a War on Democracy", title.plain)
        // 191 px of type against 47 px of body.
        assertTrue("measured ${title.sizeRatio}", title.sizeRatio in 3.9f..4.2f)
    }

    @Test
    fun `the deck is one paragraph and the byline is not part of it`() {
        val texts = build().filterIsInstance<ArticleText>()
        val deck = texts.single { it.plain.startsWith("A scholar of totalitarianism") }
        assertEquals(3, deck.runs.size)
        assertTrue(deck.plain.endsWith("dire precedents in Europe."))
        assertTrue(texts.any { it.plain == "By Timothy Snyder" })
    }

    @Test
    fun `fifteen body lines are one paragraph, ended by the short line`() {
        val texts = build().filterIsInstance<ArticleText>()
        val body = texts.single { it.plain.startsWith("body line 0") }
        assertEquals(ArticleKind.PARAGRAPH, body.kind)
        assertEquals("a ragged-right line was mistaken for a paragraph end", 15, body.runs.size)
        assertTrue("measured ${body.sizeRatio}", body.sizeRatio in 0.95f..1.05f)
    }

    @Test
    fun `bold is the three bold lines and nothing else`() {
        val bold = build().flatMap { node ->
            when (node) {
                is ArticleText -> node.runs
                is ArticleFigure -> node.caption?.runs.orEmpty()
            }
        }.filter { it.bold }.map { it.text }
        assertEquals(setOf("ESSAY", "By Timothy Snyder", "Listen to This Article"), bold.toSet())
    }

    @Test
    fun `italic is the two lines of the Audm note`() {
        val italic = build().filterIsInstance<ArticleText>()
            .flatMap { it.runs }.filter { it.italic }
        assertEquals(2, italic.size)
        assertTrue(italic.all { it.text.contains("Audm") || it.text.contains("audio stories") })
    }

    @Test
    fun `the title survives into the filename`() {
        val document = ArticleDocument("The War on History Is a War on Democracy", emptyList(), build())
        assertEquals(
            "2026-08-09_10-14-33-The War on History Is a War on Democracy.html",
            ArticleHtml.fileName(document.title, "2026-08-09_10-14-33"),
        )
    }

    @Test
    fun `the rendered html keeps the provenance of every run`() {
        val nodes = build()
        val html = ArticleHtml.render(
            ArticleDocument("The War on History Is a War on Democracy", listOf("/sdcard/tmp/page1.jpg"), nodes),
            "2026-08-09 10:14:33",
        )
        assertTrue(html.contains("<meta name=\"ocr-pages\" content=\"/sdcard/tmp/page1.jpg\">"))
        assertTrue(html.contains("<h1"))
        assertTrue("bold lost", html.contains("class=\"b\""))
        assertTrue("italic lost", html.contains("class=\"i\""))
        assertTrue("figure lost", html.contains("<figure"))
        assertTrue("caption lost", html.contains("<figcaption"))
        // Every run has to say which page and which quad it came from, or step 3 cannot reopen it.
        val runs = Regex("<span data-p=\"\\d+\" data-q=\"[-\\d,]+\"").findAll(html).count()
        assertEquals(nodes.sumOf { node ->
            when (node) {
                is ArticleText -> node.runs.size
                is ArticleFigure -> node.caption?.runs?.size ?: 0
            }
        }, runs)
    }
}

