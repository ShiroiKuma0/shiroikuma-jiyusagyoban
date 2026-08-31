package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrQuad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One recognised box on a page, with the styling measured off its own pixels. */
data class ScannedBox(
    val text: String,
    val confidence: Float,
    /** Page coordinates, not slice coordinates — [ArticleReader] offsets these as it goes. */
    val quad: OcrQuad,
    val probe: LineStyle.Probe,
) {
    val left: Int = quad.minOf { it.x }.px()
    val right: Int = quad.maxOf { it.x }.px()
    val top: Int = quad.minOf { it.y }.px()
    val bottom: Int = quad.maxOf { it.y }.px()
    val height: Int get() = bottom - top
    val centreY: Int get() = (top + bottom) / 2

    /**
     * How tall the TYPE is, as opposed to how tall its box is.
     *
     * Never [height]. A detected box has been through the unclip dilation, which grows it by roughly
     * `area * ratio / perimeter` — a quantity that depends on the box's own proportions, so a short
     * caption and a full-width body line are inflated by different fractions and their heights stop
     * being comparable. The ink extent measured off the pixels has no such problem, and it is what
     * every size threshold here was calibrated against.
     */
    val textHeight: Int get() = if (probe.inkHeight > 0) probe.inkHeight else height

    /**
     * The type's own top and bottom, recovered by shrinking the box back around its ink.
     *
     * Every vertical distance in the layout pass uses these and not [top]/[bottom]. The dilation is
     * symmetric, so the centre survives it and the ink can be re-centred exactly; the box cannot be
     * used directly because it grows by ~0.75x the line height at each edge, which is more than the
     * leading between two lines. Measured against boxes, consecutive body lines OVERLAP by 30 px
     * where they actually sit 36 px apart — so a paragraph-gap test reading boxes is not merely
     * imprecise, it has the wrong sign.
     */
    val inkTop: Int get() = centreY - textHeight / 2
    val inkBottom: Int get() = inkTop + textHeight
}

/** Everything one screenshot yielded, before any of it is interpreted. */
class PageScan(
    val index: Int,
    val path: String,
    val width: Int,
    val height: Int,
    val background: Int,
    val rows: RowProfile,
    val boxes: List<ScannedBox>,
)

/**
 * Turns recognised boxes and row statistics into an article.
 *
 * The whole pass rests on one measurement, taken from the sample pages: a photograph is an unbroken
 * run of inked rows, and printed text is not, because there is leading between every line. Bands of
 * rows with no blank row in them are therefore picture candidates — and the one thing that also
 * satisfies that description, a large headline, is separated for free by asking how much of the band
 * the text detector already claimed. On the sample pages the headline bands come back ~100 % claimed
 * and every photograph ~0 %.
 */
object ArticleLayout {

/**
     * How close to the screenshot's own top or bottom edge a line must reach to be the phone's
     * furniture rather than the article.
     *
     * This is the whole chrome test, and it is the third one tried. Judging a box by whether it sits
     * in a band at the end of the page keeps the status bar (the clock's centre is in the band, but so
     * is the first line of a continuation page). Judging it by whether it reaches into a margin the
     * article column never uses keeps it too: measured on the sample, the leftmost glyphs of the
     * kanji clock were never detected at all, so the surviving boxes span x 490–1842 and touch no
     * margin — the clock came through as the article's TITLE and named the file.
     *
     * What is actually true of chrome is that it runs into the edge of the image. On both sample
     * pages every status-bar box starts within 50 px of y=0 and every navigation-bar box ends within
     * 50 px of the bottom, while the nearest real content — a continuation page opening mid-sentence
     * — begins at y=147. Everything above the lowest edge-touching box goes with it, which is what
     * catches the middle of a bar the detector cut into five pieces.
     */
    private const val EDGE_REACH = 0.05f

    /** However far the furniture reaches, it may never claim more of the page than this. */
    private const val CHROME_CLAMP = 0.10f

    /** Clearance left below the status bar before a picture band may start. */
    private const val CHROME_CLEARANCE = 0.012f

    /** A picture band must be at least this many body lines tall — below it is a rule or an icon. */
    private const val PICTURE_MIN_LINES = 2.5f

    /** …and this inked, and this wide. */
    private const val PICTURE_MIN_INK = 0.05f
    private const val PICTURE_MIN_WIDTH = 0.20f

    /** Above this share of claimed rows a band is big type, not a picture. */
    private const val PICTURE_MAX_TEXT = 0.25f

    /**
     * …unless the band is this densely inked, in which case it may be up to [PICTURE_DENSE_MAX_TEXT]
     * claimed and still be a photograph.
     *
     * A photograph with writing IN it — the sample's Indianapolis picket signs — has its rows claimed
     * by the detector like any other text, and on that photograph nearly half of them are. Ink density
     * is what still tells the two apart: measured, every photograph on these pages runs 0.49–0.97 while
     * both headline bands sit at 0.24–0.26, so nothing in the middle has to be guessed at.
     */
    private const val PICTURE_DENSE_INK = 0.35f
    private const val PICTURE_DENSE_MAX_TEXT = 0.70f

    /** Size classes, as a multiple of the page's body text. */
    private const val TITLE_RATIO = 2.5f
    private const val HEADING_RATIO = 1.7f
    private const val SUBHEADING_RATIO = 1.3f
    private const val SMALL_RATIO = 0.85f

    /** Consecutive lines differing in measured size by more than this are not the same block. */
    private const val SIZE_CHANGE = 1.35f

    /** A gap this much past the page's usual leading ends the paragraph. */
    private const val PARAGRAPH_GAP = 1.6f

    /** Stroke weight this far above the page's body text reads as bold. */
    private const val BOLD_MARGIN = 1.22f
    private const val BOLD_MIN_SAMPLE = 3

    /**
     * A line filling less than this much of the column has ended its paragraph.
     *
     * Ragged-right text varies far more than intuition suggests. Measured across one paragraph of the
     * sample: 0.75, 0.90, 0.98, 0.93, 0.99, 0.93, 0.94, 0.99, 0.90, 1.00, 0.89, 0.97, 0.95, 0.98 — and
     * then 0.38 for the line that actually ends it. Anything above about 0.7 catches ordinary lines.
     */
    private const val COLUMN_FILL = 0.55f

    /** Lean past this is italic. Measured: italic 0.27, everything upright 0.00. */
    private const val ITALIC_SHEAR = 0.125f

    /** How many lines either side of a page join are compared when looking for the repeat. */
    private const val OVERLAP_WINDOW = 24

    /** Normalised text shorter than this is too weak to prove a page overlap. */
    private const val OVERLAP_MIN_CHARS = 8

    fun build(
        scans: List<PageScan>,
        cropTop: Int,
        cropBottom: Int,
        figureJpeg: (page: Int, left: Int, top: Int, right: Int, bottom: Int) -> String?,
    ): List<ArticleNode> {
        if (scans.isEmpty()) return emptyList()

        // --- what is article, and what is the phone's own furniture -------------------------------
        val kept = HashMap<Int, List<ScannedBox>>()
        val chromeEdges = HashMap<Int, Pair<Int, Int>>()
        scans.forEach { scan ->
            val edge = (scan.width * EDGE_REACH).toInt()
            val clamp = (scan.width * CHROME_CLAMP).toInt()
            val lowLimit = cropTop
            val highLimit = scan.height - cropBottom
            val inRange = scan.boxes.filter { it.bottom > lowLimit && it.top < highLimit }

            val chromeBottom = inRange.filter { it.inkTop <= lowLimit + edge }
                .maxOfOrNull { it.inkBottom }?.coerceAtMost(lowLimit + clamp)
            val chromeTop = inRange.filter { it.inkBottom >= highLimit - edge }
                .minOfOrNull { it.inkTop }?.coerceAtLeast(highLimit - clamp)

            kept[scan.index] = inRange.filter { box ->
                (chromeBottom == null || box.centreY > chromeBottom) &&
                    (chromeTop == null || box.centreY < chromeTop)
            }
            val clearance = (scan.width * CHROME_CLEARANCE).toInt()
            chromeEdges[scan.index] =
                (chromeBottom?.plus(clearance)?.coerceAtLeast(lowLimit) ?: lowLimit) to
                    (chromeTop?.minus(clearance)?.coerceAtMost(highLimit) ?: highLimit)
        }

        val allBoxes = scans.flatMap { kept.getValue(it.index) }
        if (allBoxes.isEmpty()) return emptyList()
        val bodyHeight = median(allBoxes.map { it.textHeight.toFloat() }.filter { it >= 8f })
            .takeIf { it > 0f } ?: return emptyList()

        val bold = boldness(allBoxes)

        // --- pictures, and the boxes they swallow -------------------------------------------------
        val bands = HashMap<Int, List<IntArray>>()   // [top, bottom, left, right] per page
        scans.forEach { scan ->
            val (scanTop, scanBottom) = chromeEdges.getValue(scan.index)
            bands[scan.index] = pictures(scan, kept.getValue(scan.index), bodyHeight, scanTop, scanBottom)
        }

        val items = ArrayList<Item>()
        scans.forEach { scan ->
            val pageBands = bands.getValue(scan.index)
            val outside = kept.getValue(scan.index).filterNot { box ->
                pageBands.any { box.centreY >= it[0] && box.centreY < it[1] }
            }
            lines(scan.index, outside).forEach { items += Item.Text(it) }
            pageBands.forEach { items += Item.Picture(scan.index, it[0], it[1], it[2], it[3]) }
        }
        items.sortWith(compareBy({ it.page }, { it.top }))

        val joined = dropPageOverlap(items)
        val blocks = paragraphs(joined, scans, bodyHeight, bold)
        return attachCaptions(blocks, bodyHeight, figureJpeg)
    }

    // -- pictures ----------------------------------------------------------------------------------

    private fun pictures(
        scan: PageScan,
        boxes: List<ScannedBox>,
        bodyHeight: Float,
        scanTop: Int,
        scanBottom: Int,
    ): List<IntArray> {
        val from = scanTop.coerceIn(0, scan.height)
        val to = scanBottom.coerceIn(from, scan.height)
        if (to - from < bodyHeight * PICTURE_MIN_LINES) return emptyList()

        // Rows the text detector already claimed — what tells a photograph from a headline.
        val claimed = BooleanArray(scan.height)
        boxes.forEach { box ->
            for (y in max(0, box.inkTop) until min(scan.height, box.inkBottom)) claimed[y] = true
        }

        val out = ArrayList<IntArray>()
        val minHeight = (bodyHeight * PICTURE_MIN_LINES).toInt()
        var y = from
        while (y < to) {
            if (scan.rows.blank(y)) { y++; continue }
            var end = y
            while (end < to && !scan.rows.blank(end)) end++

            val height = end - y
            if (height >= minHeight) {
                var ink = 0f
                var claimedRows = 0
                var left = Int.MAX_VALUE
                var right = -1
                for (row in y until end) {
                    ink += scan.rows.ink[row]
                    if (claimed[row]) claimedRows++
                    if (scan.rows.left[row] >= 0) left = min(left, scan.rows.left[row])
                    if (scan.rows.right[row] >= 0) right = max(right, scan.rows.right[row])
                }
                val meanInk = ink / height
                val textShare = claimedRows.toFloat() / height
                val wide = right - left >= scan.width * PICTURE_MIN_WIDTH
                val readsAsPicture = textShare < PICTURE_MAX_TEXT ||
                    (meanInk >= PICTURE_DENSE_INK && textShare < PICTURE_DENSE_MAX_TEXT)
                if (meanInk >= PICTURE_MIN_INK && readsAsPicture && wide) {
                    out += intArrayOf(y, end, left, right + 1)
                }
            }
            y = end
        }
        return out
    }

    // -- lines -------------------------------------------------------------------------------------

    private class Line(val page: Int, val boxes: List<ScannedBox>) {
        val top = boxes.minOf { it.inkTop }
        val bottom = boxes.maxOf { it.inkBottom }
        val left = boxes.minOf { it.left }
        val right = boxes.maxOf { it.right }
        val height get() = bottom - top

        /** The tallest type on the line — the best estimate of the size it was set at. */
        val textHeight = boxes.maxOf { it.textHeight }
    }

    private sealed class Item(val page: Int, val top: Int, val bottom: Int) {
        class Text(val line: Line) : Item(line.page, line.top, line.bottom)
        class Picture(page: Int, top: Int, bottom: Int, val left: Int, val right: Int) :
            Item(page, top, bottom)
    }

    /** Groups boxes that share a baseline, the way [com.opentasker.core.ocr.ReadingOrder] does. */
    private fun lines(page: Int, boxes: List<ScannedBox>): List<Line> {
        if (boxes.isEmpty()) return emptyList()
        val grouped = ArrayList<MutableList<ScannedBox>>()
        boxes.sortedBy { it.centreY }.forEach { box ->
            val current = grouped.lastOrNull()
            val reference = current?.last()
            // textHeight, not height: a dilated headline box is 443 px tall against 191 px of
            // type, and half of that reaches past the next line entirely.
            val sameLine = reference != null &&
                abs(box.centreY - reference.centreY) <= max(reference.textHeight, box.textHeight) * 0.5f
            if (sameLine) current += box else grouped += mutableListOf(box)
        }
        return grouped.map { Line(page, it.sortedBy { box -> box.left }) }
    }

    // -- the repeat where two screenshots meet -----------------------------------------------------

    /**
     * Drops the tail of each page that the next page repeats.
     *
     * Scrolling screenshots overlap: page 2 of the sample opens with "In Russia, it is tempting to
     * imagine…" and re-runs six lines that page 1 already ends with. The EARLIER copy goes, because
     * the later page's version of those lines is the complete one — the last line visible on a page is
     * routinely half-hidden behind the navigation bar.
     */
    private fun dropPageOverlap(items: List<Item>): List<Item> {
        val byPage = items.groupBy { it.page }.toSortedMap()
        if (byPage.size < 2) return items

        val dropped = HashSet<Item>()
        val pages = byPage.keys.toList()
        for (index in 0 until pages.size - 1) {
            val tail = byPage.getValue(pages[index]).filterIsInstance<Item.Text>()
            val head = byPage.getValue(pages[index + 1]).filterIsInstance<Item.Text>()
            val window = minOf(OVERLAP_WINDOW, tail.size, head.size)
            for (count in window downTo 1) {
                val a = tail.takeLast(count)
                val b = head.take(count)
                val strong = a.all { normalise(it.line).length >= OVERLAP_MIN_CHARS }
                if (strong && a.indices.all { similar(normalise(a[it].line), normalise(b[it].line)) }) {
                    dropped += a
                    break
                }
            }
        }
        return items.filterNot { it in dropped }
    }

    private fun normalise(line: Line): String =
        line.boxes.joinToString("") { it.text }.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Two renderings of the same line, allowing for a character the recogniser saw differently.
     *
     * Common prefix plus common suffix rather than an edit distance: the two readings come from the
     * same pixels at slightly different slice offsets, so where they differ they differ in the middle
     * of a word, and this costs one pass instead of a matrix.
     */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val shortest = min(a.length, b.length)
        var prefix = 0
        while (prefix < shortest && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++
        return (prefix + suffix).toFloat() / max(a.length, b.length) >= 0.85f
    }

    // -- paragraphs --------------------------------------------------------------------------------

    private fun kindOf(ratio: Float): ArticleKind = when {
        ratio >= TITLE_RATIO -> ArticleKind.TITLE
        ratio >= HEADING_RATIO -> ArticleKind.HEADING
        ratio >= SUBHEADING_RATIO -> ArticleKind.SUBHEADING
        ratio <= SMALL_RATIO -> ArticleKind.SMALL
        else -> ArticleKind.PARAGRAPH
    }

    private fun paragraphs(
        items: List<Item>,
        scans: List<PageScan>,
        bodyHeight: Float,
        bold: Map<ScannedBox, Boolean>,
    ): List<ArticleNode> {
        val texts = items.filterIsInstance<Item.Text>()
        if (texts.isEmpty()) return items.map { picture(it as Item.Picture) }

        // The page's own leading, so the paragraph test does not depend on a font size guessed in
        // advance. Measured on the samples: within a paragraph 36–61 px, between them 84 and up.
        val gaps = ArrayList<Float>()
        for (index in 1 until texts.size) {
            val previous = texts[index - 1]
            val next = texts[index]
            if (previous.page != next.page) continue
            val gap = (next.top - previous.bottom).toFloat()
            if (gap > 0f && gap < bodyHeight * 4f) gaps += gap
        }
        val gapLimit = max(median(gaps) * PARAGRAPH_GAP, bodyHeight * 0.35f)

        val bodyLines = texts.map { it.line }
            .filter { it.textHeight / bodyHeight in 0.8f..1.3f }
            .ifEmpty { texts.map { it.line } }
        // The column's own edges, so "short" is a fraction of the measure rather than of the screen.
        val columnLeft = percentile(bodyLines.map { it.left.toFloat() }, 0.15f)
        val columnRight = percentile(bodyLines.map { it.right.toFloat() }, 0.85f)
        val shortLimit = columnLeft + (columnRight - columnLeft) * COLUMN_FILL

        val out = ArrayList<ArticleNode>()
        var run = ArrayList<Line>()

        fun flush() {
            if (run.isEmpty()) return
            val ratios = run.map { it.textHeight / bodyHeight }
            val ratio = median(ratios)
            out += ArticleText(
                kind = kindOf(ratio),
                sizeRatio = ratio,
                inkTop = run.minOf { it.top },
                runs = run.flatMap { line ->
                    line.boxes.map { box ->
                        ArticleRun(
                            text = box.text,
                            page = line.page,
                            quad = box.quad,
                            bold = bold[box] == true,
                            italic = box.probe.shear >= ITALIC_SHEAR,
                            confidence = box.confidence,
                        )
                    }
                },
            )
            run = ArrayList()
        }

        items.forEach { item ->
            when (item) {
                is Item.Picture -> { flush(); out += picture(item) }
                is Item.Text -> {
                    val previous = run.lastOrNull()
                    if (previous != null && breaks(previous, item.line, bodyHeight, gapLimit, shortLimit)) {
                        flush()
                    }
                    run += item.line
                }
            }
        }
        flush()
        return out
    }

    private fun breaks(
        previous: Line,
        next: Line,
        bodyHeight: Float,
        gapLimit: Float,
        shortLimit: Float,
    ): Boolean {
        // A size CHANGE, not a change of class. Whether a line happens to contain a descender moves
        // its ink extent by a quarter, which is enough to push body text across a class boundary and
        // would otherwise start a new paragraph on every line that has no "g" in it.
        val ratio = max(previous.textHeight, next.textHeight).toFloat() /
            max(1, min(previous.textHeight, next.textHeight))
        if (ratio > SIZE_CHANGE) return true
        // A line that stopped short of the column edge finished its paragraph. This is the only test
        // available across a page join, where there is no meaningful vertical gap to measure.
        if (previous.right < shortLimit) return true
        if (previous.page != next.page) return false
        if (next.top - previous.bottom > gapLimit) return true
        return abs(next.left - previous.left) > bodyHeight * 0.6f
    }

    private fun picture(item: Item.Picture) = ArticleFigure(
        page = item.page,
        left = item.left,
        top = item.top,
        right = item.right,
        bottom = item.bottom,
        image = null,
        caption = null,
    )

    // -- captions ----------------------------------------------------------------------------------

    /** Small print immediately under a picture belongs to it. */
    private fun attachCaptions(
        nodes: List<ArticleNode>,
        bodyHeight: Float,
        figureJpeg: (Int, Int, Int, Int, Int) -> String?,
    ): List<ArticleNode> {
        val out = ArrayList<ArticleNode>(nodes.size)
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            if (node !is ArticleFigure) { out += node; index++; continue }

            val next = nodes.getOrNull(index + 1) as? ArticleText
            val caption = next?.takeIf {
                it.kind == ArticleKind.SMALL || it.kind == ArticleKind.PARAGRAPH
            }?.takeIf {
                it.sizeRatio <= 1.0f &&
                    it.runs.size <= 8 &&
                    it.inkTop - node.bottom <= bodyHeight * 1.6f
            }
            out += ArticleFigure(
                page = node.page,
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom,
                image = figureJpeg(node.page, node.left, node.top, node.right, node.bottom),
                caption = caption?.copy(kind = ArticleKind.CAPTION),
            )
            index += if (caption != null) 2 else 1
        }
        return out
    }

    // -- bold --------------------------------------------------------------------------------------

    /**
     * Bold, against the page's own body text.
     *
     * There is not much room in the measurement, so the reference has to be the most stable thing
     * available. Body text reads 0.150; the three bold lines on the sample page read 0.189, 0.203 and
     * 0.275, and the heaviest roman line ("June 29, 2021", helped by having no descenders at all)
     * reads 0.166. A 1.22x margin puts the cut at 0.183 — above every roman line and below every bold
     * one, with the narrowest gap being the 0.017 between that dateline and the byline under it.
     *
     * An earlier version compared each line to a cohort of similarly-sized lines, on the theory that
     * ascender content was the confound. The measurement says otherwise: the cohort around the byline
     * is five lines and the dateline drags its median to 0.166, which puts the cut above the byline
     * itself. Fewer, better-founded samples beat more, noisier ones.
     */
    private fun boldness(boxes: List<ScannedBox>): Map<ScannedBox, Boolean> {
        val measurable = boxes.filter { it.probe.stroke > 0f }
        if (measurable.size < BOLD_MIN_SAMPLE) return emptyMap()
        // The median over the whole page IS the body text — body lines outnumber everything else on
        // any page worth calling an article, so this needs no separate estimate of which they are.
        val reference = median(measurable.map { it.probe.stroke })
        if (reference <= 0f) return emptyMap()
        return measurable.associateWith { it.probe.stroke >= reference * BOLD_MARGIN }
    }

    // -- small helpers -------------------------------------------------------------------------------

    private fun median(values: List<Float>): Float = percentile(values, 0.5f)

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val at = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[at]
    }
}
