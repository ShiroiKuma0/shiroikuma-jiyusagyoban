package com.opentasker.core.widget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Converts a **Tasker Widget V2** layout tree — the JSON stored in `arg13` of action code 461 — into
 * our [WidgetNode] schema, so a Tasker widget can be pasted straight into the visual editor.
 *
 * Tasker's tree is Glance/Compose-flavoured: `Column`/`Row`/`Box`/`Text`/`Image`/`Spacer`, with
 * `horizontalAlignment`/`verticalAlignment`, `fillMaxSize`/`fillMaxWidth`/`fillMaxHeight`,
 * `padding{start,top,end,bottom}`, `backgroundColor`, and on text `textSize` (often a string), `color`,
 * `font` (a **full file path**), `fontFamily`, `bold`, `italic`, `align`, `maxLines`.
 *
 * Lossy by design where we have no equivalent: `useMaterialYouColors` and a bare `fontFamily`
 * (without a concrete `font` path) are dropped — that text falls back to the widget's default face.
 * A `font` path is reduced to its file name, which is how our renderer resolves imported fonts.
 */
object TaskerWidgetImport {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Parse a Tasker Widget V2 layout JSON into our [WidgetNode]; null if it doesn't parse as an object tree. */
    fun convert(raw: String): WidgetNode? = runCatching {
        node(json.parseToJsonElement(raw).jsonObject)
    }.getOrNull()

    private fun node(o: JsonObject): WidgetNode = when (o.str("type")?.lowercase()) {
        "row" -> container(o, "row")
        "box" -> box(o)
        "text" -> text(o)
        "image" -> image(o)
        "spacer" -> spacer(o)
        else -> container(o, "column") // Column + anything unrecognised
    }

    // ---- containers --------------------------------------------------------------------------

    private fun container(o: JsonObject, kind: String): WidgetNode {
        val (w, h) = sizing(o)
        // Column: main axis = vertical, cross axis = horizontal (and vice-versa for Row).
        val mainRaw = if (kind == "row") o.str("horizontalAlignment") else o.str("verticalAlignment")
        val crossRaw = if (kind == "row") o.str("verticalAlignment") else o.str("horizontalAlignment")
        return WidgetNode(
            type = kind,
            width = w, height = h,
            padding = padding(o),
            background = background(o),
            arrange = arrange(mainRaw),
            align = cross(crossRaw),
            children = kids(o),
        )
    }

    private fun box(o: JsonObject): WidgetNode {
        val (w, h) = sizing(o)
        val content = anchor(o.str("contentAlignment"))
        // Tasker's Box contentAlignment pins every child; mirror it onto children that don't set their own.
        val children = kids(o).map { if (it.align == null && content != null) it.copy(align = content) else it }
        return WidgetNode(
            type = "box",
            width = w ?: "wrap", height = h ?: "wrap",
            padding = padding(o),
            background = background(o),
            children = children,
        )
    }

    private fun kids(o: JsonObject): List<WidgetNode> =
        (o["children"] as? JsonArray ?: return emptyList())
            .mapNotNull { (it as? JsonObject)?.let(::node) }

    // ---- leaves ------------------------------------------------------------------------------

    private fun text(o: JsonObject): WidgetNode = WidgetNode(
        type = "text",
        text = o.str("text"),
        size = o.num("textSize")?.roundToInt(),
        color = o.str("color"),
        font = o.str("font")?.let(::baseName),
        bold = o.bool("bold"),
        italic = o.bool("italic"),
        align = textAlign(o.str("align")),
        maxLines = o.num("maxLines")?.roundToInt(),
        padding = padding(o),
        background = background(o),
    )

    private fun image(o: JsonObject): WidgetNode {
        val (w, h) = sizing(o)
        return WidgetNode(
            type = "image",
            src = o.str("path") ?: o.str("uri") ?: o.str("image") ?: o.str("bitmap"),
            scale = when (o.str("contentScale")?.lowercase()) {
                "crop" -> "crop"
                "fillbounds", "fill" -> "fill"
                else -> "fit"
            },
            tint = o.str("tintColor") ?: o.str("tint"),
            width = w, height = h,
            padding = padding(o),
        )
    }

    private fun spacer(o: JsonObject): WidgetNode {
        val (w, h) = sizing(o)
        return WidgetNode(type = "spacer", width = w, height = h)
    }

    // ---- field helpers -----------------------------------------------------------------------

    /** fillMaxSize/Width/Height + numeric width/height → our "fill" | "<dp>" strings. */
    private fun sizing(o: JsonObject): Pair<String?, String?> {
        val all = o.bool("fillMaxSize") == true
        val w = when {
            all || o.bool("fillMaxWidth") == true -> "fill"
            else -> o.num("width")?.roundToInt()?.toString()
        }
        val h = when {
            all || o.bool("fillMaxHeight") == true -> "fill"
            else -> o.num("height")?.roundToInt()?.toString()
        }
        return w to h
    }

    private fun padding(o: JsonObject): Padding? {
        val p = o["padding"] as? JsonObject ?: return null
        val all = p.num("all")?.roundToInt()
        val hor = p.num("horizontal")?.roundToInt()
        val ver = p.num("vertical")?.roundToInt()
        val left = p.num("start")?.roundToInt() ?: p.num("left")?.roundToInt() ?: hor ?: all ?: 0
        val right = p.num("end")?.roundToInt() ?: p.num("right")?.roundToInt() ?: hor ?: all ?: 0
        val top = p.num("top")?.roundToInt() ?: ver ?: all ?: 0
        val bottom = p.num("bottom")?.roundToInt() ?: ver ?: all ?: 0
        return if (left == 0 && top == 0 && right == 0 && bottom == 0) null else Padding(left, top, right, bottom)
    }

    private fun background(o: JsonObject): Background? {
        val color = o.str("backgroundColor") ?: return null
        val corner = o.num("cornerRadius")?.roundToInt() ?: 0
        return Background(color = color, corner = corner)
    }

    /** Main-axis arrangement → "start" | "center" | "end" (the only values our renderer flows on). */
    private fun arrange(v: String?): String? = when (norm(v)) {
        null -> null
        "center" -> "center"
        "end" -> "end"
        else -> "start"
    }

    /** Cross-axis child alignment → "start" | "center" | "end". */
    private fun cross(v: String?): String? = when (norm(v)) {
        null -> null
        "center" -> "center"
        "end" -> "end"
        else -> "start"
    }

    /** Text alignment Left/Center/Right → our "start" | "center" | "end". */
    private fun textAlign(v: String?): String? = when (norm(v)) {
        null -> null
        "center" -> "center"
        "end" -> "end"
        else -> "start"
    }

    /** Box content/anchor like "TopStart"/"Center"/"BottomEnd" → our ALIGNS token. */
    private fun anchor(v: String?): String? {
        val s = v?.lowercase()?.replace("_", "") ?: return null
        val top = "top" in s; val bottom = "bottom" in s
        val startSide = "start" in s || "left" in s
        val endSide = "end" in s || "right" in s
        return when {
            top && endSide -> "topEnd"
            top && startSide -> "topStart"
            top -> "top"
            bottom && endSide -> "bottomEnd"
            bottom && startSide -> "bottomStart"
            bottom -> "bottom"
            endSide -> "end"
            startSide -> "start"
            else -> "center"
        }
    }

    /** Collapse a Compose alignment token to start/center/end/spaceBetween/spaceEvenly, or null. */
    private fun norm(v: String?): String? {
        val s = v?.lowercase()?.replace("_", "") ?: return null
        return when {
            "spacebetween" in s -> "spaceBetween"
            "spaceevenly" in s || "spacearound" in s -> "spaceEvenly"
            "center" in s -> "center"
            "end" in s || "bottom" in s || "right" in s -> "end"
            else -> "start"
        }
    }

    /** Last path segment of a font file path, trimmed (our renderer resolves imported fonts by file name). */
    private fun baseName(path: String): String =
        path.trim().substringAfterLast('/').substringAfterLast('\\').trim()

    // ---- JsonObject accessors (tolerant of string/number/bool variance) ----------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String): Float? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.floatOrNull ?: p.contentOrNull?.trim()?.toFloatOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
