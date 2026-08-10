package com.opentasker.core.ocr.article

import com.opentasker.core.ocr.OcrQuad

/**
 * What a block of text is doing on the page.
 *
 * Deliberately shallow. The request was "headings, italics, bold, paragraphs, relative text sizes" —
 * so this carries the structure a reader would name out loud, and the finer gradations ride on
 * [ArticleText.sizeRatio], which is a measurement rather than a guess.
 */
enum class ArticleKind(val tag: String) {
    TITLE("h1"),
    HEADING("h2"),
    SUBHEADING("h3"),
    PARAGRAPH("p"),
    CAPTION("figcaption"),
    /** Print smaller than the body: a kicker, a byline, a dateline, a credit. */
    SMALL("p"),
    ;

    val isHeading: Boolean get() = this == TITLE || this == HEADING || this == SUBHEADING
}

/**
 * One recognised box: its text, its styling, and exactly where it came from.
 *
 * [page] and [quad] are the whole point of keeping runs separate rather than flattening a paragraph
 * to a string. They are written into the HTML, so step 3's editor can put a box back on the original
 * screenshot and move the caret to the word under a fingertip — the same gesture 文字認識 already has.
 */
data class ArticleRun(
    val text: String,
    val page: Int,
    val quad: OcrQuad,
    val bold: Boolean,
    val italic: Boolean,
    val confidence: Float,
)

sealed interface ArticleNode

data class ArticleText(
    val kind: ArticleKind,
    /** This block's ink height against the page's body text. 1.0 is body; the sample headline is 4.1. */
    val sizeRatio: Float,
    /**
     * Top of this block's type on its page.
     *
     * Recorded rather than derived from [runs], because a run carries the DILATED quad and the top of
     * that is up to three quarters of a line height above where the ink starts. What this is for —
     * deciding whether a line of small print sits close enough under a photograph to be its caption —
     * is a question about the ink.
     */
    val inkTop: Int,
    val runs: List<ArticleRun>,
) : ArticleNode {

    /** The block as one string, joined the way its script joins. */
    val plain: String
        get() = buildString {
            runs.forEach { run ->
                if (isNotEmpty() && needsSpace(last(), run.text.firstOrNull())) append(' ')
                append(run.text)
            }
        }
}

data class ArticleFigure(
    val page: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /**
     * The cropped photograph as base64 JPEG, without the `data:` prefix — null when figures were
     * switched off or the region would not decode.
     *
     * Encoded rather than raw bytes so that reading an article back in and writing it out again
     * hands the very same blob through, with no decode-and-re-encode generation loss, and so the
     * layout pass needs no Android imports.
     */
    val image: String?,
    val caption: ArticleText?,
) : ArticleNode {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

}

data class ArticleDocument(
    val title: String,
    /** The screenshots this was read from, in order — what step 3's editor reopens. */
    val sources: List<String>,
    val nodes: List<ArticleNode>,
) {
    val figures: Int get() = nodes.count { it is ArticleFigure }
    val blocks: Int get() = nodes.count { it is ArticleText }
    val characters: Int
        get() = nodes.sumOf { node ->
            when (node) {
                is ArticleText -> node.plain.length
                is ArticleFigure -> node.caption?.plain?.length ?: 0
            }
        }
}

/**
 * Whether two adjacent recognised boxes want a space between them.
 *
 * Each box is a LINE of the original column, not a sentence, so joining them into flowing text has to
 * put back the space the line break stood for — but only where the script uses one. Japanese sets
 * without inter-word spaces, and inserting them would corrupt the text rather than reflow it.
 */
internal fun needsSpace(before: Char, after: Char?): Boolean {
    if (after == null) return false
    if (before.isWhitespace() || after.isWhitespace()) return false
    return !before.isWideScript() && !after.isWideScript()
}

/**
 * CJK, kana and Hangul — everything set without word spaces.
 *
 * Enumerated rather than "anything above U+1100", which was the first attempt and swept in General
 * Punctuation with everything else: a curly quote counted as wide, so the space before it was
 * dropped and the sample came out reading `called“memory laws`. Halfwidth katakana stays out on
 * purpose — it is written with spaces like Latin.
 */
private fun Char.isWideScript(): Boolean =
    this in '\u3000'..'\u303F' ||     // CJK punctuation
        this in '\u3040'..'\u30FF' || // hiragana + katakana
        this in '\u3400'..'\u4DBF' || // CJK extension A
        this in '\u4E00'..'\u9FFF' || // CJK unified
        this in '\uAC00'..'\uD7AF' || // hangul syllables
        this in '\u1100'..'\u11FF' || // hangul jamo
        this in '\uFF01'..'\uFF60'    // fullwidth forms
