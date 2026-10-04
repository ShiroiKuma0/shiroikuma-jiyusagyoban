package com.opentasker.ui.gengoshima

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/*
 * The 言語島 board's pictures, drawn — the 健康 board's language: a dark gradient sky, a soft glow,
 * the yellow ink as the one bright thing. Blues, violets, teals and amber only: nothing here is told
 * apart by red against green (白い熊 is red-green colour-blind), and every picture is a SHAPE first.
 */

private val Ink = Color(0xFFFFFF00)
private val Sky = Color(0xFF7FD4FF)
private val Pale = Color(0xFFFFF59D)

private fun DrawScope.sky(top: Color, bottom: Color) =
    drawRect(Brush.verticalGradient(listOf(top, bottom)))

private fun DrawScope.glow(centre: Offset, radius: Float, colour: Color) =
    drawCircle(Brush.radialGradient(listOf(colour.copy(alpha = 0.55f), colour.copy(alpha = 0f)), centre, radius), radius, centre)

private fun DrawScope.line(points: List<Offset>, colour: Color, width: Float) {
    if (points.size < 2) return
    val p = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
    drawPath(p, colour, style = Stroke(width, cap = StrokeCap.Round))
}

/** An island: a low hill of land with a beach rim, sitting on a band of sea. */
private fun DrawScope.island(c: Offset, rw: Float, rh: Float, land: Color = Color(0xFF26A69A)) {
    drawOval(Color(0xFFFFE082), topLeft = c - Offset(rw * 1.08f, rh * 0.55f), size = Size(rw * 2.16f, rh * 1.1f))
    val hill = Path().apply {
        moveTo(c.x - rw, c.y)
        cubicTo(c.x - rw * 0.6f, c.y - rh * 1.6f, c.x + rw * 0.5f, c.y - rh * 1.9f, c.x + rw, c.y)
        close()
    }
    drawPath(hill, land)
}

private fun DrawScope.glyph(tm: TextMeasurer, text: String, centre: Offset, sizePx: Float, colour: Color) {
    val style = TextStyle(color = colour, fontSize = (sizePx / density / fontScale).sp, fontWeight = FontWeight.Bold)
    val m = tm.measure(text, style)
    drawText(m, topLeft = centre - Offset(m.size.width / 2f, m.size.height / 2f))
}

/** A waveform: [n] bars whose heights follow a slow swell, centred on [y]. */
private fun DrawScope.wave(x0: Float, x1: Float, y: Float, amp: Float, n: Int, colour: Color, width: Float, phase: Float = 0f) {
    for (i in 0 until n) {
        val x = x0 + (x1 - x0) * i / (n - 1)
        val a = amp * (0.25f + 0.75f * kotlin.math.abs(sin(i * 0.9f + phase)))
        line(listOf(Offset(x, y - a), Offset(x, y + a)), colour, width)
    }
}

@Composable
fun GsBoardArt(key: String, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        when (key) {
            // A recorded voice, checked: the waveform of a sentence with a tick over it.
            "review" -> {
                sky(Color(0xFF07203A), Color(0xFF123B5C))
                glow(Offset(w * 0.45f, h * 0.55f), w * 0.45f, Color(0xFF1E88E5))
                wave(w * 0.12f, w * 0.7f, h * 0.58f, h * 0.2f, 13, Sky, w * 0.022f)
                line(listOf(Offset(w * 0.66f, h * 0.36f), Offset(w * 0.75f, h * 0.48f), Offset(w * 0.92f, h * 0.2f)), Ink, h * 0.06f)
            }

            // The tray: sentences waiting to be sorted into islands.
            "inbox" -> {
                sky(Color(0xFF15122E), Color(0xFF2A1F52))
                glow(Offset(w * 0.5f, h * 0.7f), w * 0.5f, Color(0xFF7C4DFF))
                listOf(0.22f, 0.34f, 0.46f).forEachIndexed { i, y ->
                    drawRoundRect(Pale.copy(alpha = 0.9f - i * 0.15f), topLeft = Offset(w * (0.24f + i * 0.03f), h * y),
                        size = Size(w * (0.5f - i * 0.06f), h * 0.07f), cornerRadius = CornerRadius(h * 0.03f))
                }
                val tray = Path().apply {
                    moveTo(w * 0.14f, h * 0.58f); lineTo(w * 0.3f, h * 0.58f); lineTo(w * 0.36f, h * 0.68f)
                    lineTo(w * 0.64f, h * 0.68f); lineTo(w * 0.7f, h * 0.58f); lineTo(w * 0.86f, h * 0.58f)
                    lineTo(w * 0.86f, h * 0.86f); lineTo(w * 0.14f, h * 0.86f); close()
                }
                drawPath(tray, Color(0xFF5E35B1))
                drawPath(tray, Ink, style = Stroke(h * 0.03f))
            }

            // Writing by hand: lined paper, a sentence, the pen still on it.
            "entry" -> {
                sky(Color(0xFF0E1A2B), Color(0xFF1D3047))
                drawRoundRect(Color(0xFFF5F1E6), topLeft = Offset(w * 0.16f, h * 0.14f), size = Size(w * 0.56f, h * 0.74f),
                    cornerRadius = CornerRadius(h * 0.04f))
                listOf(0.32f, 0.47f, 0.62f, 0.77f).forEach { y ->
                    line(listOf(Offset(w * 0.22f, h * y), Offset(w * 0.66f, h * y)), Color(0xFF90A4AE), h * 0.012f)
                }
                line(listOf(Offset(w * 0.24f, h * 0.29f), Offset(w * 0.6f, h * 0.29f)), Color(0xFF1A237E), h * 0.03f)
                line(listOf(Offset(w * 0.24f, h * 0.44f), Offset(w * 0.5f, h * 0.44f)), Color(0xFF1A237E), h * 0.03f)
                rotate(-40f, pivot = Offset(w * 0.66f, h * 0.5f)) {
                    drawRoundRect(Ink, topLeft = Offset(w * 0.5f, h * 0.46f), size = Size(w * 0.38f, h * 0.09f),
                        cornerRadius = CornerRadius(h * 0.02f))
                    drawRect(Color(0xFF455A64), topLeft = Offset(w * 0.44f, h * 0.475f), size = Size(w * 0.06f, h * 0.06f))
                }
            }

            // English becomes Japanese, and is spoken: A → あ with sound coming off it.
            "generate" -> {
                sky(Color(0xFF071A24), Color(0xFF0D3440))
                glow(Offset(w * 0.68f, h * 0.5f), w * 0.4f, Color(0xFF00ACC1))
                glyph(tm, "A", Offset(w * 0.22f, h * 0.5f), h * 0.42f, Sky)
                line(listOf(Offset(w * 0.36f, h * 0.5f), Offset(w * 0.5f, h * 0.5f)), Pale, h * 0.04f)
                line(listOf(Offset(w * 0.45f, h * 0.42f), Offset(w * 0.51f, h * 0.5f), Offset(w * 0.45f, h * 0.58f)), Pale, h * 0.04f)
                glyph(tm, "あ", Offset(w * 0.64f, h * 0.5f), h * 0.42f, Ink)
                listOf(0.16f, 0.26f).forEach { r ->
                    drawArc(Ink.copy(alpha = 0.7f), -45f, 90f, false, topLeft = Offset(w * 0.64f, h * 0.5f) - Offset(w * r, w * r),
                        size = Size(w * r * 2, w * r * 2), style = Stroke(h * 0.03f, cap = StrokeCap.Round))
                }
            }

            // The play tile: an island at dusk under a big play button.
            "listen" -> {
                sky(Color(0xFF0B1636), Color(0xFF2B2160))
                glow(Offset(w * 0.5f, h * 0.45f), w * 0.3f, Color(0xFF7C4DFF))
                drawRect(Color(0xFF0D47A1), topLeft = Offset(0f, h * 0.78f), size = Size(w, h * 0.22f))
                island(Offset(w * 0.2f, h * 0.8f), w * 0.1f, h * 0.12f)
                island(Offset(w * 0.82f, h * 0.82f), w * 0.08f, h * 0.1f, Color(0xFF00897B))
                val c = Offset(w * 0.5f, h * 0.44f)
                val r = h * 0.3f
                drawCircle(Ink, r, c)
                val tri = Path().apply {
                    moveTo(c.x - r * 0.32f, c.y - r * 0.48f); lineTo(c.x + r * 0.52f, c.y); lineTo(c.x - r * 0.32f, c.y + r * 0.48f); close()
                }
                drawPath(tri, Color(0xFF1A1440))
            }

            // Shadowing: their voice and yours, the same shape a beat apart.
            "shadow" -> {
                sky(Color(0xFF101B33), Color(0xFF1C2E52))
                wave(w * 0.12f, w * 0.78f, h * 0.4f, h * 0.15f, 12, Sky, w * 0.024f)
                wave(w * 0.22f, w * 0.88f, h * 0.68f, h * 0.15f, 12, Ink, w * 0.024f)
                glyph(tm, "×3", Offset(w * 0.86f, h * 0.18f), h * 0.18f, Pale)
            }

            // Recall: the English shown, a gap to fill, then the Japanese.
            "recall" -> {
                sky(Color(0xFF1A1030), Color(0xFF33204F))
                glow(Offset(w * 0.7f, h * 0.45f), w * 0.35f, Color(0xFFFFB300))
                drawRoundRect(Color(0xFF283593), topLeft = Offset(w * 0.08f, h * 0.24f), size = Size(w * 0.34f, h * 0.44f),
                    cornerRadius = CornerRadius(h * 0.05f))
                glyph(tm, "EN", Offset(w * 0.25f, h * 0.46f), h * 0.2f, Sky)
                glyph(tm, "?", Offset(w * 0.52f, h * 0.46f), h * 0.32f, Pale)
                drawCircle(Ink, h * 0.2f, Offset(w * 0.78f, h * 0.44f))
                glyph(tm, "あ", Offset(w * 0.78f, h * 0.44f), h * 0.24f, Color(0xFF1A1030))
                line(listOf(Offset(w * 0.72f, h * 0.72f), Offset(w * 0.84f, h * 0.72f)), Pale, h * 0.04f)
            }

            // Editing the islands: a little archipelago with a pencil over it.
            "edit" -> {
                sky(Color(0xFF062033), Color(0xFF0B4058))
                island(Offset(w * 0.25f, h * 0.55f), w * 0.11f, h * 0.12f)
                island(Offset(w * 0.55f, h * 0.75f), w * 0.13f, h * 0.13f, Color(0xFF00897B))
                island(Offset(w * 0.72f, h * 0.42f), w * 0.09f, h * 0.1f)
                rotate(35f, pivot = Offset(w * 0.6f, h * 0.3f)) {
                    drawRoundRect(Ink, topLeft = Offset(w * 0.4f, h * 0.26f), size = Size(w * 0.36f, h * 0.08f),
                        cornerRadius = CornerRadius(h * 0.02f))
                    val tip = Path().apply { moveTo(w * 0.4f, h * 0.26f); lineTo(w * 0.33f, h * 0.3f); lineTo(w * 0.4f, h * 0.34f); close() }
                    drawPath(tip, Pale)
                }
            }

            // Statistics: a calendar of listening days and a rising line of reviews.
            "stats" -> {
                sky(Color(0xFF10132B), Color(0xFF241A46))
                for (row in 0 until 3) for (col in 0 until 7) {
                    val on = (row * 7 + col) % 3 != 1
                    drawRoundRect(if (on) Color(0xFF5C6BC0) else Color(0xFF2A2F55),
                        topLeft = Offset(w * (0.1f + col * 0.115f), h * (0.52f + row * 0.13f)), size = Size(w * 0.09f, h * 0.1f),
                        cornerRadius = CornerRadius(h * 0.02f))
                }
                val pts = listOf(0.1f to 0.4f, 0.3f to 0.34f, 0.5f to 0.28f, 0.7f to 0.2f, 0.9f to 0.12f).map { (x, y) -> Offset(w * x, h * y) }
                line(pts, Ink, h * 0.04f)
                pts.forEach { drawCircle(Pale, h * 0.03f, it) }
            }

            // 暗記: two flash cards with the arrows that pass between them.
            "anki" -> {
                sky(Color(0xFF0A1A2E), Color(0xFF16324F))
                rotate(-8f, pivot = Offset(w * 0.32f, h * 0.5f)) {
                    drawRoundRect(Color(0xFF3949AB), topLeft = Offset(w * 0.14f, h * 0.24f), size = Size(w * 0.3f, h * 0.48f),
                        cornerRadius = CornerRadius(h * 0.05f))
                    glyph(tm, "島", Offset(w * 0.29f, h * 0.48f), h * 0.22f, Sky)
                }
                rotate(8f, pivot = Offset(w * 0.68f, h * 0.5f)) {
                    drawRoundRect(Color(0xFF00838F), topLeft = Offset(w * 0.56f, h * 0.24f), size = Size(w * 0.3f, h * 0.48f),
                        cornerRadius = CornerRadius(h * 0.05f))
                    glyph(tm, "暗", Offset(w * 0.71f, h * 0.48f), h * 0.22f, Pale)
                }
                line(listOf(Offset(w * 0.4f, h * 0.16f), Offset(w * 0.6f, h * 0.16f)), Ink, h * 0.035f)
                line(listOf(Offset(w * 0.55f, h * 0.1f), Offset(w * 0.61f, h * 0.16f), Offset(w * 0.55f, h * 0.22f)), Ink, h * 0.035f)
                line(listOf(Offset(w * 0.6f, h * 0.84f), Offset(w * 0.4f, h * 0.84f)), Ink, h * 0.035f)
                line(listOf(Offset(w * 0.45f, h * 0.78f), Offset(w * 0.39f, h * 0.84f), Offset(w * 0.45f, h * 0.9f)), Ink, h * 0.035f)
            }

            // Taking the 暗記 deck in once: cards flowing down onto an island.
            "adopt" -> {
                sky(Color(0xFF0C1630), Color(0xFF0B3A4F))
                listOf(0.18f to -12f, 0.36f to 0f, 0.54f to 12f).forEach { (x, a) ->
                    rotate(a, pivot = Offset(w * (x + 0.1f), h * 0.28f)) {
                        drawRoundRect(Color(0xFF3949AB), topLeft = Offset(w * x, h * 0.12f), size = Size(w * 0.2f, h * 0.3f),
                            cornerRadius = CornerRadius(h * 0.04f))
                    }
                }
                line(listOf(Offset(w * 0.46f, h * 0.48f), Offset(w * 0.46f, h * 0.64f)), Ink, h * 0.04f)
                line(listOf(Offset(w * 0.4f, h * 0.58f), Offset(w * 0.46f, h * 0.65f), Offset(w * 0.52f, h * 0.58f)), Ink, h * 0.04f)
                drawRect(Color(0xFF0D47A1), topLeft = Offset(0f, h * 0.84f), size = Size(w, h * 0.16f))
                island(Offset(w * 0.46f, h * 0.86f), w * 0.2f, h * 0.12f)
            }

            // Settings: a gear.
            "settings" -> {
                sky(Color(0xFF14171F), Color(0xFF262B36))
                val c = Offset(w * 0.5f, h * 0.5f)
                val r = h * 0.26f
                for (i in 0 until 8) {
                    rotate(i * 45f, pivot = c) {
                        drawRoundRect(Ink, topLeft = Offset(c.x - r * 0.18f, c.y - r * 1.32f), size = Size(r * 0.36f, r * 0.5f),
                            cornerRadius = CornerRadius(r * 0.06f))
                    }
                }
                drawCircle(Ink, r, c)
                drawCircle(Color(0xFF262B36), r * 0.42f, c)
            }

            else -> sky(Color(0xFF14171F), Color(0xFF262B36))
        }
    }
}
