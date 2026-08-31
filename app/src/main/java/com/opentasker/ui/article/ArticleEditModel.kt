package com.opentasker.ui.article

import android.content.Context
import android.net.Uri
import com.opentasker.core.ocr.article.ArticleFigure
import com.opentasker.core.ocr.article.ArticleNode
import com.opentasker.core.ocr.article.ArticleRun
import com.opentasker.core.ocr.article.ArticleText
import java.io.File

/**
 * Every recognised box, with where its words sit in the block below it.
 *
 * The strip needs a rectangle to draw and to hit-test; the text pane needs a block to scroll to and a
 * character offset to drop the caret on. This is the one place those two coordinate systems are tied
 * together, so a tap on the image and a caret in the text can never disagree about which word is
 * which.
 */
internal fun targetsOf(nodes: List<ArticleNode>): List<StripTarget> = buildList {
    nodes.forEachIndexed { nodeIndex, node ->
        val text = node as? ArticleText ?: return@forEachIndexed
        var offset = 0
        text.runs.forEachIndexed { runIndex, run ->
            // The join the block's own text uses, so the offsets land on the right characters.
            if (runIndex > 0 && joinsWithSpace(text.runs[runIndex - 1], run)) offset++
            add(
                StripTarget(
                    page = run.page,
                    nodeIndex = nodeIndex,
                    runIndex = runIndex,
                    left = run.quad.minOf { it.x },
                    top = run.quad.minOf { it.y },
                    right = run.quad.maxOf { it.x },
                    bottom = run.quad.maxOf { it.y },
                    caretOffset = offset,
                )
            )
            offset += run.text.length
        }
    }
}

private fun joinsWithSpace(before: ArticleRun, after: ArticleRun): Boolean {
    val last = before.text.lastOrNull() ?: return false
    val first = after.text.firstOrNull() ?: return false
    // Mirrors ArticleText.plain: a space goes in only where the script uses one.
    return !last.isWideScript() && !first.isWideScript() && !last.isWhitespace() && !first.isWhitespace()
}

private fun Char.isWideScript(): Boolean =
    this in '　'..'〿' || this in '぀'..'ヿ' ||
        this in '㐀'..'䶿' || this in '一'..'鿿' ||
        this in '가'..'힯' || this in 'ᄀ'..'ᇿ' ||
        this in '！'..'｠'

/**
 * The nodes as they will be written, with 白い熊's corrections folded in.
 *
 * A block left alone keeps its runs exactly — every quad, every bold and italic, every confidence.
 * A block that was corrected collapses to ONE run: the runs were the lines of the original column,
 * and once the words have been retyped there is no honest way to say which of them each character
 * now belongs to. What that run keeps is the block's provenance (the page, the whole block's box) and
 * the styling the majority of it had, so an italic note stays italic.
 */
internal fun applyEdits(nodes: List<ArticleNode>, edits: Map<Int, String>): List<ArticleNode> =
    nodes.mapIndexed { index, node ->
        val edited = edits[index] ?: return@mapIndexed node
        val text = node as? ArticleText ?: return@mapIndexed node
        if (edited == text.plain || text.runs.isEmpty()) return@mapIndexed node
        text.copy(
            runs = listOf(
                ArticleRun(
                    text = edited,
                    page = text.runs.first().page,
                    quad = text.runs.first().quad.let { first ->
                        val left = text.runs.minOf { run -> run.quad.minOf { it.x } }
                        val right = text.runs.maxOf { run -> run.quad.maxOf { it.x } }
                        val top = text.runs.minOf { run -> run.quad.minOf { it.y } }
                        val bottom = text.runs.maxOf { run -> run.quad.maxOf { it.y } }
                        listOf(
                            first[0].copy(x = left, y = top), first[1].copy(x = right, y = top),
                            first[2].copy(x = right, y = bottom), first[3].copy(x = left, y = bottom),
                        )
                    },
                    bold = text.runs.count { it.bold } * 2 > text.runs.size,
                    italic = text.runs.count { it.italic } * 2 > text.runs.size,
                    // Corrected by hand, so no longer the recogniser's doubt to record.
                    confidence = 1f,
                )
            )
        )
    }

/** Takes a picked image into our own cache, because a picker grant does not outlive the window. */
internal fun copyIn(context: Context, uri: Uri): File? = runCatching {
    val directory = File(context.cacheDir, "article").apply { mkdirs() }
    val out = File(directory, "page-${System.currentTimeMillis()}-${uri.hashCode()}.img")
    context.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "no stream for $uri" }
        out.outputStream().use { sink -> input.copyTo(sink) }
    }
    check(out.length() > 0) { "empty image" }
    out
}.getOrNull()

/** A figure's own rectangle, for the row that stands in for it in the text pane. */
internal val ArticleFigure.label: String get() = "図 ${width}×$height"
