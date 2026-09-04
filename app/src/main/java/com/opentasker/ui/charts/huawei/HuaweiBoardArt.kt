package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * The board's pictures, drawn rather than shipped.
 *
 * Two of the sixteen cards show real photographs — the earth watch face, and a piece of the map 地図
 * drew of a real walk — and these fourteen have to sit beside them without looking like clip art.
 * Drawn in Compose for the same reason every chart in this app is: a PNG is fixed at one size and one
 * theme, while these have to be sharp on a folded panel, survive a re-theme, and cost nothing in the
 * APK. It is also the only way they can share a palette with the charts they sit next to.
 *
 * ## The visual language
 *
 * Each picture is a lit ground, one bold subject, and nothing else. Colour carries mood, never
 * meaning: 白い熊 is red-green colour blind, so no picture asks a red thing to be told from a green
 * thing — the subject is always separable by SHAPE alone, and the palettes are blue/amber/violet
 * pairs rather than red/green ones. Read them in greyscale and every one still reads.
 */
private val Ink = Color(0xFFFFFF00)

/** A vertical wash — the ground every picture stands on. */
private fun DrawScope.sky(top: Color, bottom: Color) {
    drawRect(Brush.verticalGradient(listOf(top, bottom)), size = size)
}

/** A soft glow, for a sun, a signal source, or the heart of a subject. */
private fun DrawScope.glow(centre: Offset, radius: Float, colour: Color) {
    drawCircle(
        Brush.radialGradient(
            listOf(colour.copy(alpha = 0.85f), colour.copy(alpha = 0f)),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/** An arc of an ellipse — orbits, dials, the curve of a planet. */
private fun DrawScope.arcOf(rect: Rect, start: Float, sweep: Float, colour: Color, width: Float) {
    drawArc(
        colour, start, sweep, useCenter = false,
        topLeft = rect.topLeft, size = rect.size,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun DrawScope.polyline(points: List<Offset>, colour: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, colour, style = Stroke(width = width, cap = StrokeCap.Round))
}

/** The picture for a card, by key. Anything unknown falls back to a plain lit ground. */
@Composable
fun BoardArt(key: String, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        when (key) {
            // Two arrows chasing each other round a ring: the band and the phone, taking turns.
            "sync" -> {
                sky(Color(0xFF07203A), Color(0xFF0B3B54))
                glow(Offset(w * 0.5f, h * 0.5f), w * 0.42f, Color(0xFF1E88E5))
                val r = Rect(Offset(w * 0.28f, h * 0.22f), Size(w * 0.44f, h * 0.56f))
                arcOf(r, -20f, 200f, Color(0xFF7FD4FF), h * 0.055f)
                arcOf(r, 160f, 200f, Ink, h * 0.055f)
                listOf(0f to 1f, 180f to -1f).forEach { (deg, dir) ->
                    rotate(deg, pivot = r.center) {
                        val tip = Offset(r.right, r.center.y)
                        polyline(
                            listOf(
                                tip + Offset(-w * 0.05f, -h * 0.09f * dir),
                                tip,
                                tip + Offset(w * 0.05f, -h * 0.09f * dir),
                            ),
                            if (deg == 0f) Color(0xFF7FD4FF) else Ink, h * 0.05f,
                        )
                    }
                }
            }

            // The report: the shape of a real day — a filled area under a moving line.
            "report" -> {
                sky(Color(0xFF10132B), Color(0xFF241A46))
                val pts = listOf(0.06f to 0.72f, 0.2f to 0.55f, 0.34f to 0.66f, 0.48f to 0.34f,
                    0.62f to 0.46f, 0.76f to 0.22f, 0.94f to 0.36f)
                    .map { (x, y) -> Offset(w * x, h * y) }
                val area = Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(
                    listOf(Color(0xFF7C4DFF).copy(alpha = 0.75f), Color(0x007C4DFF))))
                polyline(pts, Ink, h * 0.045f)
                pts.forEach { drawCircle(Color(0xFFFFF59D), h * 0.035f, it) }
            }

            // A satellite over the limb of the earth, with the horizon lit from below.
            "sat" -> {
                sky(Color(0xFF04101F), Color(0xFF0A2540))
                glow(Offset(w * 0.5f, h * 1.15f), w * 0.75f, Color(0xFF1565C0))
                drawCircle(Color(0xFF0D47A1), w * 0.62f, Offset(w * 0.5f, h * 1.5f))
                arcOf(Rect(Offset(-w * 0.12f, h * 0.88f), Size(w * 1.24f, h * 1.24f)),
                    200f, 140f, Color(0xFF80DEEA), h * 0.05f)
                arcOf(Rect(Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.9f)),
                    195f, 150f, Ink.copy(alpha = 0.55f), h * 0.022f)
                val s = Offset(w * 0.66f, h * 0.3f)
                drawRoundRect(Ink, topLeft = s - Offset(w * 0.05f, h * 0.07f),
                    size = Size(w * 0.1f, h * 0.14f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.03f))
                listOf(-1f, 1f).forEach { d ->
                    drawRect(Color(0xFF7FD4FF), topLeft = s + Offset(d * w * 0.16f - w * 0.06f, -h * 0.05f),
                        size = Size(w * 0.12f, h * 0.1f))
                }
                polyline(listOf(s + Offset(0f, h * 0.08f), s + Offset(-w * 0.1f, h * 0.34f)),
                    Color(0xFFFFF59D), h * 0.022f)
            }

            // A satellite beaming straight down to a point on the ground — the immediate handover,
            // as against "sat", which is the same sky drawn as orbits laid in ahead of time. Warm
            // where that one is cold, so the pair reads as now-versus-later at a glance rather than
            // by its caption.
            "satnow" -> {
                sky(Color(0xFF0B1020), Color(0xFF1A2740))
                val src = Offset(w * 0.5f, h * 0.2f)
                val ground = h * 0.86f
                // The beam: a cone widening to the ground, brightest at its axis.
                drawPath(Path().apply {
                    moveTo(src.x, src.y)
                    lineTo(w * 0.18f, ground)
                    lineTo(w * 0.82f, ground)
                    close()
                }, Brush.verticalGradient(
                    listOf(Color(0xFFFFF59D).copy(alpha = 0.45f), Color(0x00FFF59D))))
                glow(Offset(w * 0.5f, ground), w * 0.42f, Color(0xFFFFC107))
                // Two rings on the ground, the outer one fainter: a fix landing.
                listOf(0.20f to 0.9f, 0.34f to 0.4f).forEach { (r, a) ->
                    arcOf(Rect(Offset(w * (0.5f - r), ground - h * r * 0.34f),
                        Size(w * r * 2f, h * r * 0.68f)), 0f, 360f,
                        Color(0xFFFFD54F).copy(alpha = a), h * 0.02f)
                }
                drawCircle(Color(0xFFFFF59D), h * 0.045f, Offset(w * 0.5f, ground))
                // The satellite itself, panels out, same body as "sat" so the pair is plainly kin.
                drawRoundRect(Ink, topLeft = src - Offset(w * 0.055f, h * 0.075f),
                    size = Size(w * 0.11f, h * 0.15f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.03f))
                listOf(-1f, 1f).forEach { d ->
                    drawRect(Color(0xFFFFD54F), topLeft = src + Offset(d * w * 0.17f - w * 0.06f, -h * 0.05f),
                        size = Size(w * 0.12f, h * 0.1f))
                }
            }

            // Sun behind a cloud. Amber against slate, never warm-against-green.
            "weather" -> {
                sky(Color(0xFF123047), Color(0xFF2B5872))
                glow(Offset(w * 0.68f, h * 0.3f), w * 0.4f, Color(0xFFFFC107))
                drawCircle(Color(0xFFFFD54F), h * 0.2f, Offset(w * 0.68f, h * 0.3f))
                listOf(Triple(0.3f, 0.62f, 0.17f), Triple(0.48f, 0.55f, 0.22f),
                    Triple(0.66f, 0.63f, 0.16f)).forEach { (x, y, r) ->
                    drawCircle(Color(0xFFE3F2FD), h * r, Offset(w * x, h * y))
                }
                drawRoundRect(Color(0xFFE3F2FD), topLeft = Offset(w * 0.22f, h * 0.62f),
                    size = Size(w * 0.56f, h * 0.2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.1f))
            }

            // A pin standing on a plane of streets.
            "place" -> {
                sky(Color(0xFF0E2A22), Color(0xFF12403A))
                for (i in 1..4) {
                    val y = h * (0.5f + i * 0.12f)
                    polyline(listOf(Offset(0f, y), Offset(w, y - h * 0.04f)),
                        Color(0xFF4DB6AC).copy(alpha = 0.45f), h * 0.012f)
                }
                for (i in 0..4) {
                    val x = w * (0.1f + i * 0.2f)
                    polyline(listOf(Offset(x, h * 0.55f), Offset(x + w * 0.06f, h)),
                        Color(0xFF4DB6AC).copy(alpha = 0.3f), h * 0.012f)
                }
                glow(Offset(w * 0.5f, h * 0.38f), w * 0.3f, Color(0xFFFFA726))
                val tip = Offset(w * 0.5f, h * 0.66f)
                drawCircle(Ink, h * 0.17f, Offset(w * 0.5f, h * 0.33f))
                drawCircle(Color(0xFF0E2A22), h * 0.07f, Offset(w * 0.5f, h * 0.33f))
                drawPath(
                    Path().apply {
                        moveTo(w * 0.5f - h * 0.15f, h * 0.4f)
                        lineTo(tip.x, tip.y); lineTo(w * 0.5f + h * 0.15f, h * 0.4f); close()
                    }, Ink,
                )
            }

            // Stacked source cards, the front one lit — choosing between providers.
            "source" -> {
                sky(Color(0xFF2A1533), Color(0xFF45204A))
                listOf(0.18f to 0.55f, 0.12f to 0.42f, 0.06f to 0.3f).forEachIndexed { i, (dx, dy) ->
                    val lit = i == 2
                    drawRoundRect(
                        if (lit) Ink else Color(0xFFCE93D8).copy(alpha = 0.5f - i * 0.12f),
                        topLeft = Offset(w * (0.18f + dx * 0.4f), h * dy),
                        size = Size(w * 0.5f, h * 0.3f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.06f),
                    )
                }
            }

            // A heart with its own trace crossing it.
            "sensors" -> {
                sky(Color(0xFF33101E), Color(0xFF5A1830))
                glow(Offset(w * 0.5f, h * 0.45f), w * 0.42f, Color(0xFFEC407A))
                val c = Offset(w * 0.5f, h * 0.42f)
                val r = h * 0.19f
                drawCircle(Color(0xFFFF80AB), r, c + Offset(-r * 0.85f, 0f))
                drawCircle(Color(0xFFFF80AB), r, c + Offset(r * 0.85f, 0f))
                drawPath(
                    Path().apply {
                        moveTo(c.x - r * 1.8f, c.y + r * 0.35f)
                        lineTo(c.x, c.y + r * 2.1f); lineTo(c.x + r * 1.8f, c.y + r * 0.35f); close()
                    }, Color(0xFFFF80AB),
                )
                polyline(
                    listOf(0.02f to 0.62f, 0.3f to 0.62f, 0.38f to 0.4f, 0.46f to 0.84f,
                        0.56f to 0.62f, 0.98f to 0.62f).map { (x, y) -> Offset(w * x, h * y) },
                    Ink, h * 0.05f,
                )
            }

            // Two speech bubbles, one filled and one outlined: the band saying it one way or the
            // other. Two plain circles were tried first and said nothing at all — a picture has to
            // be recognisable as a THING, not as a pair of shapes in the right colours.
            "lang" -> {
                sky(Color(0xFF10233F), Color(0xFF1B3A63))
                glow(Offset(w * 0.42f, h * 0.44f), w * 0.4f, Color(0xFF42A5F5))
                drawRoundRect(Ink, topLeft = Offset(w * 0.1f, h * 0.16f),
                    size = Size(w * 0.5f, h * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.14f))
                drawPath(
                    Path().apply {
                        moveTo(w * 0.2f, h * 0.55f); lineTo(w * 0.2f, h * 0.78f)
                        lineTo(w * 0.36f, h * 0.57f); close()
                    }, Ink,
                )
                drawRoundRect(Color(0xFF90CAF9), topLeft = Offset(w * 0.44f, h * 0.44f),
                    size = Size(w * 0.48f, h * 0.4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.13f),
                    style = Stroke(width = h * 0.05f))
                // Three ruled lines in the near bubble: text, without needing a font.
                for (i in 0..2) {
                    polyline(
                        listOf(Offset(w * 0.17f, h * (0.26f + i * 0.1f)),
                            Offset(w * (0.53f - i * 0.08f), h * (0.26f + i * 0.1f))),
                        Color(0xFF10233F), h * 0.045f,
                    )
                }
            }

            // Three sliders, one pushed further than the rest.
            "settings" -> {
                sky(Color(0xFF1A1A22), Color(0xFF2E2E3E))
                listOf(0.3f to 0.66f, 0.5f to 0.34f, 0.7f to 0.5f).forEach { (y, knob) ->
                    polyline(listOf(Offset(w * 0.12f, h * y), Offset(w * 0.88f, h * y)),
                        Color(0xFF9FA8DA).copy(alpha = 0.55f), h * 0.035f)
                    drawCircle(Ink, h * 0.085f, Offset(w * (0.12f + 0.76f * knob), h * y))
                }
            }

            // Two links closing on each other, a spark where they meet.
            "pair" -> {
                sky(Color(0xFF06282A), Color(0xFF0B4247))
                glow(Offset(w * 0.5f, h * 0.5f), w * 0.3f, Color(0xFF26C6DA))
                arcOf(Rect(Offset(w * 0.14f, h * 0.26f), Size(w * 0.44f, h * 0.48f)),
                    -60f, 300f, Ink, h * 0.06f)
                arcOf(Rect(Offset(w * 0.42f, h * 0.26f), Size(w * 0.44f, h * 0.48f)),
                    120f, 300f, Color(0xFF80DEEA), h * 0.06f)
            }

            // The same two links, parted, with the gap made the subject.
            "unpair" -> {
                sky(Color(0xFF2C1D08), Color(0xFF4A3210))
                arcOf(Rect(Offset(w * 0.06f, h * 0.26f), Size(w * 0.4f, h * 0.48f)),
                    -70f, 250f, Color(0xFFFFB74D), h * 0.06f)
                arcOf(Rect(Offset(w * 0.54f, h * 0.26f), Size(w * 0.4f, h * 0.48f)),
                    110f, 250f, Color(0xFFFFB74D), h * 0.06f)
                listOf(-1f, 1f).forEach { d ->
                    polyline(listOf(Offset(w * 0.5f, h * (0.5f + d * 0.1f)),
                        Offset(w * 0.5f, h * (0.5f + d * 0.26f))), Ink, h * 0.045f)
                }
            }

            // A pulse read off a grid — asking the band what it is.
            "probe" -> {
                sky(Color(0xFF071F1B), Color(0xFF0D3A32))
                for (i in 1..5) {
                    polyline(listOf(Offset(0f, h * i / 6f), Offset(w, h * i / 6f)),
                        Color(0xFF4DB6AC).copy(alpha = 0.25f), h * 0.008f)
                }
                polyline(
                    (0..40).map { i ->
                        val t = i / 40f
                        Offset(w * t, h * (0.5f - 0.3f * sin(t * 9f) * cos(t * 2.2f)))
                    }, Ink, h * 0.045f,
                )
                drawCircle(Color(0xFFFFF59D), h * 0.06f, Offset(w * 0.78f, h * 0.5f))
            }

            // Counted columns, tallest at the back — an inventory of what is stored.
            "census" -> {
                sky(Color(0xFF221436), Color(0xFF3B2258))
                listOf(0.35f, 0.62f, 0.48f, 0.86f, 0.7f).forEachIndexed { i, v ->
                    val x = w * (0.12f + i * 0.17f)
                    drawRoundRect(
                        if (i == 3) Ink else Color(0xFFB39DDB),
                        topLeft = Offset(x, h * (1f - v)),
                        size = Size(w * 0.11f, h * v),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
                    )
                }
            }

            // Sheets of raw bytes, the top one lit.
            "files" -> {
                sky(Color(0xFF13202E), Color(0xFF1F3648))
                listOf(2, 1, 0).forEach { i ->
                    drawRoundRect(
                        if (i == 0) Ink else Color(0xFF90A4AE).copy(alpha = 0.45f + i * 0.1f),
                        topLeft = Offset(w * (0.2f + i * 0.06f), h * (0.16f + i * 0.1f)),
                        size = Size(w * 0.52f, h * 0.6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.06f),
                    )
                }
                for (i in 0..3) {
                    polyline(
                        listOf(Offset(w * 0.27f, h * (0.3f + i * 0.11f)),
                            Offset(w * (0.4f + 0.2f * ((i * 7) % 3) / 2f), h * (0.3f + i * 0.11f))),
                        Color(0xFF13202E), h * 0.035f,
                    )
                }
            }

            // A face on a wrist: the fallback when the real earth face cannot be read.
            "faces" -> {
                sky(Color(0xFF0B1B2B), Color(0xFF14324A))
                // Straps first, so the body sits on them and it reads as a watch rather than as a
                // lozenge: the version without them looked like a power button.
                listOf(0.02f, 0.72f).forEach { y ->
                    drawRoundRect(Color(0xFF37474F), topLeft = Offset(w * 0.38f, h * y),
                        size = Size(w * 0.24f, h * 0.26f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f))
                }
                drawRoundRect(Color(0xFF2C3E50), topLeft = Offset(w * 0.28f, h * 0.16f),
                    size = Size(w * 0.44f, h * 0.68f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.16f))
                glow(Offset(w * 0.5f, h * 0.5f), w * 0.2f, Color(0xFF4FC3F7))
                arcOf(Rect(Offset(w * 0.33f, h * 0.24f), Size(w * 0.34f, h * 0.52f)),
                    0f, 360f, Color(0xFF4FC3F7).copy(alpha = 0.7f), h * 0.02f)
                polyline(listOf(Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.31f)), Ink, h * 0.035f)
                polyline(listOf(Offset(w * 0.5f, h * 0.5f), Offset(w * 0.63f, h * 0.56f)), Ink, h * 0.03f)
                drawCircle(Ink, h * 0.03f, Offset(w * 0.5f, h * 0.5f))
            }

            // A loaded bar, over the heart rate the session is actually made of.
            //
            // Read in greyscale it is still a bar: two dark discs, a light shaft, a line behind.
            // Nothing here asks a red thing to be told from a green one — the amber shaft against
            // the violet ground carries the whole picture, and the trace is a shape, not a hue.
            "lift" -> {
                sky(Color(0xFF1B1233), Color(0xFF3A1F4E))
                glow(Offset(w * 0.5f, h * 0.5f), w * 0.55f, Color(0xFF7C4DFF))
                // The pulse behind it, drawn first so the bar sits in front of its own reason.
                polyline(
                    listOf(0.04f to 0.70f, 0.16f to 0.66f, 0.24f to 0.80f, 0.32f to 0.52f,
                        0.42f to 0.72f, 0.56f to 0.68f, 0.66f to 0.78f, 0.76f to 0.58f,
                        0.86f to 0.72f, 0.98f to 0.68f).map { (x, y) -> Offset(w * x, h * y) },
                    Color(0xFFFF8A80).copy(alpha = 0.75f), h * 0.03f,
                )
                // The shaft, then the plates: a big one and a small one at each end, which is what
                // says "loaded" rather than "a stick".
                polyline(
                    listOf(Offset(w * 0.10f, h * 0.42f), Offset(w * 0.90f, h * 0.42f)),
                    Ink, h * 0.055f,
                )
                listOf(0.22f, 0.78f).forEach { x ->
                    drawRoundRect(
                        color = Color(0xFF9FA8DA),
                        topLeft = Offset(w * x - w * 0.035f, h * 0.42f - h * 0.20f),
                        size = Size(w * 0.07f, h * 0.40f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f),
                    )
                }
                listOf(0.32f, 0.68f).forEach { x ->
                    drawRoundRect(
                        color = Color(0xFFE8EAF6),
                        topLeft = Offset(w * x - w * 0.026f, h * 0.42f - h * 0.13f),
                        size = Size(w * 0.052f, h * 0.26f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.015f),
                    )
                }
            }

            // A band on a wrist and the pulse it is taking: 機能訓練 has no equipment and no
            // route, so what stands for it is the measuring itself.
            //
            // Separable by SHAPE — a ring with a line through it — rather than by hue, which is
            // what makes it tell apart from the lifting tile beside it in greyscale as well as in
            // colour.
            "rehab" -> {
                sky(Color(0xFF0E2A24), Color(0xFF17493C))
                glow(Offset(w * 0.5f, h * 0.52f), w * 0.5f, Color(0xFF26A69A))
                // The strap, drawn as an open ring so the pulse can pass through it.
                arcOf(
                    Rect(Offset(w * 0.22f, h * 0.18f), Size(w * 0.56f, h * 0.64f)),
                    35f, 290f, Color(0xFFB2DFDB), h * 0.075f,
                )
                polyline(
                    listOf(0.02f to 0.52f, 0.24f to 0.52f, 0.33f to 0.30f, 0.42f to 0.74f,
                        0.50f to 0.40f, 0.58f to 0.58f, 0.68f to 0.52f, 0.98f to 0.52f)
                        .map { (x, y) -> Offset(w * x, h * y) },
                    Ink, h * 0.055f,
                )
                drawCircle(Color(0xFFFFF59D), h * 0.045f, Offset(w * 0.42f, h * 0.74f))
            }

            // A path across country: the fallback when no walk has a map yet.
            "walks" -> {
                sky(Color(0xFF13331B), Color(0xFF1E5B2E))
                drawCircle(Color(0xFF66BB6A).copy(alpha = 0.35f), w * 0.3f, Offset(w * 0.2f, h * 0.8f))
                drawCircle(Color(0xFF43A047).copy(alpha = 0.35f), w * 0.26f, Offset(w * 0.85f, h * 0.7f))
                polyline(
                    listOf(0.1f to 0.9f, 0.3f to 0.6f, 0.45f to 0.72f, 0.62f to 0.36f,
                        0.8f to 0.48f, 0.92f to 0.2f).map { (x, y) -> Offset(w * x, h * y) },
                    Ink, h * 0.055f,
                )
                drawCircle(Color(0xFFFFF59D), h * 0.07f, Offset(w * 0.92f, h * 0.2f))
            }

            else -> sky(Color(0xFF1A1A22), Color(0xFF2E2E3E))
        }
    }
}
