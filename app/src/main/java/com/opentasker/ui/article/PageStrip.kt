package com.opentasker.ui.article

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A box on a page that the text below can be scrolled to. */
data class StripTarget(
    val page: Int,
    val nodeIndex: Int,
    val runIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Where this box's words start in its block's text — where the caret goes on a tap. */
    val caretOffset: Int = 0,
) {
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

/** One horizontal band of one page — the unit that gets decoded, drawn and thrown away. */
private data class Tile(val page: Int, val top: Int, val height: Int, val key: String)

/**
 * The source screenshots, fitted to the width and stacked one under the other.
 *
 * Scrolled, never zoomed: fit-to-width is the only scale worth having here, because the point is to
 * read the article against the pixels it came from, and at any other scale the column either does not
 * fit or is too small to read. That also means the only thing to preserve when the keyboard opens is
 * the scroll position, which is why the caller holds the [state].
 *
 * Decoded in bands. A page is 2048x41744 — 342 MB as ARGB — so what is on screen is decoded through
 * [BitmapRegionDecoder] a band at a time and released when it scrolls away, exactly as the reader
 * does its slices. A LazyColumn does the releasing for us.
 */
@Composable
fun PageStrip(
    pages: List<File>,
    targets: List<StripTarget>,
    selected: StripTarget?,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onPick: (StripTarget) -> Unit,
) {
    val decoders = remember(pages) { PageDecoders(pages) }
    DisposableEffect(decoders) { onDispose { decoders.close() } }

    val outline = MaterialTheme.colorScheme.primary
    val marked = MaterialTheme.colorScheme.secondary

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(decoders.tiles, key = { it.key }) { tile ->
                val size = decoders.sizeOf(tile.page) ?: return@items
                // Fit to width. The band's height on screen follows from that one number, and the
                // constraints are the only honest source for it.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val factor = with(LocalDensity.current) { maxWidth.toPx() } / max(1, size.first)
                    val bitmap by produceState<Bitmap?>(null, tile) {
                        value = withContext(Dispatchers.IO) { decoders.decode(tile) }
                    }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(with(LocalDensity.current) { (tile.height * factor).toDp() })
                            .pointerInput(targets, tile, factor) {
                                detectTapGestures { tap ->
                                    val pageX = tap.x / factor
                                    val pageY = tile.top + tap.y / factor
                                    targets.firstOrNull {
                                        it.page == tile.page && it.contains(pageX, pageY)
                                    }?.let(onPick)
                                }
                            },
                    ) {
                        bitmap?.let {
                            drawImage(
                                image = it.asImageBitmap(),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(
                                    this.size.width.roundToInt(),
                                    (tile.height * factor).roundToInt(),
                                ),
                            )
                        }
                        targets.forEach { target ->
                            if (target.page != tile.page) return@forEach
                            if (target.bottom < tile.top || target.top > tile.top + tile.height) return@forEach
                            val isSelected = target === selected
                            drawRect(
                                color = if (isSelected) marked else outline.copy(alpha = 0.35f),
                                topLeft = Offset(target.left * factor, (target.top - tile.top) * factor),
                                size = Size(
                                    (target.right - target.left) * factor,
                                    (target.bottom - target.top) * factor,
                                ),
                                style = Stroke(width = if (isSelected) 5f else 2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The open region decoders, and the tiles they can serve.
 *
 * Held for the life of the strip rather than opened per tile: opening a decoder re-reads the JPEG
 * index, which on a 41 744 px page is not free, and scrolling would pay it several times a second.
 */
private class PageDecoders(files: List<File>) : Closeable {

    private val decoders = files.map { file ->
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.absolutePath)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }.getOrNull()
    }

    private val sizes = files.map { file ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    val tiles: List<Tile> = buildList {
        sizes.forEachIndexed { page, size ->
            if (size == null) return@forEachIndexed
            var top = 0
            while (top < size.second) {
                val height = minOf(TILE, size.second - top)
                add(Tile(page, top, height, "$page:$top"))
                top += TILE
            }
        }
    }

    fun sizeOf(page: Int): Pair<Int, Int>? = sizes.getOrNull(page)

    fun decode(tile: Tile): Bitmap? {
        val decoder = decoders.getOrNull(tile.page) ?: return null
        val size = sizes.getOrNull(tile.page) ?: return null
        return runCatching {
            synchronized(decoder) {
                decoder.decodeRegion(
                    Rect(0, tile.top, size.first, tile.top + tile.height),
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565   // no alpha in a screenshot
                        inSampleSize = SAMPLE
                    },
                )
            }
        }.getOrNull()
    }

    override fun close() {
        decoders.forEach { runCatching { it?.recycle() } }
    }

    private companion object {
        /** Source rows per band. Small enough that a few live at once, big enough not to thrash. */
        const val TILE = 1200

        /**
         * Bands are decoded at half size.
         *
         * The strip is for checking a word against its pixels, not for pixel-peeping, and half a
         * 2048 px page is still 1024 px across a phone panel — sharper than the panel on the folded
         * one. It also quarters what each band costs in memory.
         */
        const val SAMPLE = 2
    }
}
