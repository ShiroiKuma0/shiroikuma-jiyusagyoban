package com.opentasker.core.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Our widget layout schema — a small, render-oriented tree. A superset of Tasker's Widget V2 JSON:
 * the flow containers (`column`/`row`) plus **`box`** (children layered with z-order, pinned by
 * `align` + `offset`) and **`free`** (children placed absolutely by `x`/`y`/`w`/`h` in dp). A
 * container's [defaults] are inherited by descendant text that doesn't override them, so a font /
 * colour is stated once. All measurements are **dp**; colours are `#RRGGBB` or `#AARRGGBB`.
 *
 * One flat node type (every field optional) keeps the JSON forgiving and easy to author by hand.
 */
@Serializable
data class WidgetNode(
    val type: String = "column",          // column | row | box | free | text | image | shape | spacer
    // sizing — "wrap" | "fill" | a dp number as string ("160"); null = sensible default per type
    val width: String? = null,
    val height: String? = null,
    val padding: Padding? = null,
    val background: Background? = null,
    // placement of THIS node inside its parent
    val align: String? = null,            // box child / column cross-axis: start|center|end + topStart..bottomEnd
    val offset: Offset? = null,           // box child nudge (dp)
    val x: Int? = null,                   // free child position (dp)
    val y: Int? = null,
    val w: Int? = null,                   // free child explicit size (dp)
    val h: Int? = null,
    // container
    val arrange: String? = null,          // main-axis: start|center|end|spaceBetween|spaceEvenly
    val gap: Int? = null,                 // dp between flow children
    val defaults: TextStyle? = null,      // inherited text style for descendants
    val children: List<WidgetNode> = emptyList(),
    // text
    val text: String? = null,
    val font: String? = null,             // imported font file name (see ThemeStore fonts), else default
    val size: Int? = null,                // text size, dp
    val color: String? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val maxLines: Int? = null,
    // image
    val src: String? = null,              // absolute file path
    val scale: String? = null,            // fit | crop | fill
    val tint: String? = null,
    // shape
    val shape: String? = null,            // rect | oval | line
    val stroke: String? = null,
    val strokeWidth: Int? = null,
    val corner: Int? = null,
)

@Serializable
data class Padding(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

@Serializable
data class Offset(val x: Int = 0, val y: Int = 0)

@Serializable
data class Background(
    val color: String? = null,
    val corner: Int = 0,
    val border: String? = null,
    val borderWidth: Int = 0,
)

@Serializable
data class TextStyle(
    val font: String? = null,
    val color: String? = null,
    val size: Int? = null,
    val align: String? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
)

object WidgetLayoutCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = false
    }

    fun decode(raw: String): WidgetNode? = runCatching { json.decodeFromString<WidgetNode>(raw) }.getOrNull()

    fun encode(node: WidgetNode): String = json.encodeToString(node)

    /** A simple placeholder layout shown by a freshly-placed widget until a task updates it. */
    val PLACEHOLDER = WidgetNode(
        type = "box",
        width = "fill",
        height = "fill",
        background = Background(color = "#000000", corner = 16),
        children = listOf(
            WidgetNode(type = "text", text = "白い熊 自由作業盤", color = "#FFFF00", size = 18, align = "center"),
        ),
    )
}
