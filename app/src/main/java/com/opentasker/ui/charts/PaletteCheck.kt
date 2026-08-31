package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The palette validator, in Kotlin, running on device.
 *
 * The shipped chart colours were validated once, on a laptop, with the data-viz skill's Node script;
 * that record lives in [ChartPalette]'s KDoc. The moment the colours became settable, a check that
 * only ran at authoring time stopped being worth much — 白い熊 can now pick a violet REM beside a blue
 * one, which is precisely the pairing the original validation rejected at ΔE 1.9 under protanopia.
 *
 * So the same arithmetic runs here, live, on whatever has been picked. This is a **faithful port of
 * the measurable checks**, not an approximation: the same OKLab conversion, the same
 * Machado–Oliveira–Fernandes (2009) severity-1.0 CVD transforms, the same thresholds. Two checks from
 * the original are deliberately absent — "hues assigned in fixed order" and "values come from the
 * documented palette" are structural rules about *how* a palette was built, and a settable palette
 * has by definition left that path.
 *
 * The verdict is advisory. Nothing here refuses a colour: it reports, in the customization screen,
 * beside a one-tap restore. A check that blocked would be a check 白い熊 would learn to work around.
 */
object PaletteCheck {

    // Thresholds, verbatim from the skill's validator.
    private const val DARK_L_MIN = 0.48
    private const val DARK_L_MAX = 0.67
    private const val CHROMA_FLOOR = 0.10
    private const val CVD_TARGET = 8.0     // adjacent pairs, min(protan, deutan)
    private const val CVD_FLOOR = 6.0      // below target but legal WITH secondary encoding
    private const val NORMAL_FLOOR = 15.0  // hard gate: full-colour readers must separate them too
    private const val CONTRAST_MIN = 3.0   // WCAG, vs the chart surface

    /** This app's own card surface — what the charts are actually drawn on. */
    const val SURFACE = 0xFF0D0D0D.toInt()

    enum class Verdict { PASS, WARN, FAIL }

    data class Finding(val verdict: Verdict, val check: String, val detail: String)

    data class Report(val findings: List<Finding>) {
        val verdict: Verdict
            get() = when {
                findings.any { it.verdict == Verdict.FAIL } -> Verdict.FAIL
                findings.any { it.verdict == Verdict.WARN } -> Verdict.WARN
                else -> Verdict.PASS
            }

        val failures: List<Finding> get() = findings.filter { it.verdict != Verdict.PASS }
    }

    /**
     * Run every measurable check over one named palette.
     *
     * [entries] is `label to ARGB`, **in the order they are drawn**, because the CVD check is on
     * adjacent pairs: a stacked or laddered chart only ever asks a reader to separate neighbours, and
     * demanding all-pairs separation on eight series is a gate no eight-hue palette clears.
     */
    fun validate(entries: List<Pair<String, Int>>, surface: Int = SURFACE): Report {
        if (entries.size < 2) return Report(emptyList())
        val findings = mutableListOf<Finding>()

        // --- lightness band: too dark and it vanishes into the surface, too light and it glares.
        val offBand = entries.filter { (_, c) -> lightness(c).let { it < DARK_L_MIN || it > DARK_L_MAX } }
        findings += if (offBand.isEmpty()) {
            Finding(Verdict.PASS, "明度", "all ${entries.size} inside L $DARK_L_MIN–$DARK_L_MAX")
        } else {
            Finding(
                Verdict.WARN, "明度",
                offBand.joinToString(", ") { (n, c) -> "$n L=${fmt(lightness(c))}" },
            )
        }

        // --- chroma floor: below it a hue reads as grey, and identity by colour stops working.
        val lowChroma = entries.filter { (_, c) -> chroma(c) < CHROMA_FLOOR }
        findings += if (lowChroma.isEmpty()) {
            Finding(Verdict.PASS, "彩度", "all ${entries.size} ≥ $CHROMA_FLOOR")
        } else {
            Finding(
                Verdict.WARN, "彩度",
                lowChroma.joinToString(", ") { (n, c) -> "$n C=${fmt(chroma(c))} — reads grey" },
            )
        }

        // --- CVD separation between neighbours. This is the check that catches violet-beside-blue.
        var worstCvd = Double.MAX_VALUE
        var worstCvdPair = ""
        var worstNormal = Double.MAX_VALUE
        var worstNormalPair = ""
        for (i in 0 until entries.size - 1) {
            val (n1, c1) = entries[i]
            val (n2, c2) = entries[i + 1]
            val cvd = min(deltaE(c1, c2, Cvd.PROTAN), deltaE(c1, c2, Cvd.DEUTAN))
            if (cvd < worstCvd) { worstCvd = cvd; worstCvdPair = "$n1 / $n2" }
            val normal = deltaE(c1, c2, null)
            if (normal < worstNormal) { worstNormal = normal; worstNormalPair = "$n1 / $n2" }
        }
        findings += when {
            worstCvd >= CVD_TARGET ->
                Finding(Verdict.PASS, "色覚", "worst adjacent ΔE ${fmt1(worstCvd)} ($worstCvdPair)")
            worstCvd >= CVD_FLOOR -> Finding(
                Verdict.WARN, "色覚",
                "$worstCvdPair ΔE ${fmt1(worstCvd)} — legal only because both carry a label",
            )
            else -> Finding(
                Verdict.FAIL, "色覚",
                "$worstCvdPair ΔE ${fmt1(worstCvd)} — indistinguishable to a red-green reader",
            )
        }

        // --- and the same neighbours under ordinary colour vision. A pair that only separates for
        // CVD simulation is a pair nobody can read; this gate is the one that cannot be waived.
        findings += if (worstNormal >= NORMAL_FLOOR) {
            Finding(Verdict.PASS, "識別", "worst adjacent ΔE ${fmt1(worstNormal)} ($worstNormalPair)")
        } else {
            Finding(
                Verdict.FAIL, "識別",
                "$worstNormalPair ΔE ${fmt1(worstNormal)} — too close to tell apart at all",
            )
        }

        // --- contrast against the surface the mark is drawn on.
        val lowContrast = entries.filter { (_, c) -> contrast(c, surface) < CONTRAST_MIN }
        findings += if (lowContrast.isEmpty()) {
            Finding(Verdict.PASS, "コントラスト", "all ≥ ${fmt1(CONTRAST_MIN)}:1 vs the card")
        } else {
            Finding(
                Verdict.WARN, "コントラスト",
                lowContrast.joinToString(", ") { (n, c) -> "$n ${fmt1(contrast(c, surface))}:1" },
            )
        }

        return Report(findings)
    }

    // --- colour science ---------------------------------------------------------------------

    private enum class Cvd { PROTAN, DEUTAN }

    /** Machado, Oliveira & Fernandes (2009), severity 1.0, in linear RGB. */
    private val MACHADO = mapOf(
        Cvd.PROTAN to arrayOf(
            doubleArrayOf(0.152286, 1.052583, -0.204868),
            doubleArrayOf(0.114503, 0.786281, 0.099216),
            doubleArrayOf(-0.003882, -0.048116, 1.051998),
        ),
        Cvd.DEUTAN to arrayOf(
            doubleArrayOf(0.367322, 0.860646, -0.227968),
            doubleArrayOf(0.280085, 0.672501, 0.047413),
            doubleArrayOf(-0.011820, 0.042940, 0.968881),
        ),
    )

    private fun linear(argb: Int): DoubleArray {
        fun s2l(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return doubleArrayOf(
            s2l(((argb shr 16) and 0xFF) / 255.0),
            s2l(((argb shr 8) and 0xFF) / 255.0),
            s2l((argb and 0xFF) / 255.0),
        )
    }

    private fun oklab(rgb: DoubleArray): DoubleArray {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun simulate(rgb: DoubleArray, kind: Cvd): DoubleArray {
        val m = MACHADO.getValue(kind)
        fun clamp(c: Double) = max(0.0, min(1.0, c))
        return DoubleArray(3) { i -> clamp(m[i][0] * rgb[0] + m[i][1] * rgb[1] + m[i][2] * rgb[2]) }
    }

    /** Euclidean distance in OKLab, ×100. A null [kind] means ordinary colour vision. */
    private fun deltaE(a: Int, b: Int, kind: Cvd?): Double {
        val la = linear(a).let { if (kind != null) simulate(it, kind) else it }
        val lb = linear(b).let { if (kind != null) simulate(it, kind) else it }
        val oa = oklab(la)
        val ob = oklab(lb)
        return 100.0 * hypot(hypot(oa[0] - ob[0], oa[1] - ob[1]), oa[2] - ob[2])
    }

    fun lightness(argb: Int): Double = oklab(linear(argb))[0]

    fun chroma(argb: Int): Double = oklab(linear(argb)).let { hypot(it[1], it[2]) }

    /** OKLab hue angle in degrees — reported, never gated on. */
    fun hue(argb: Int): Double =
        oklab(linear(argb)).let { ((atan2(it[2], it[1]) * 180.0 / Math.PI) % 360 + 360) % 360 }

    fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int) = linear(c).let { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }
        val la = lum(a)
        val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Distance in OKLab ×100 under ordinary vision — exposed for tests and the info readout. */
    fun separation(a: Int, b: Int): Double = deltaE(a, b, null)

    /** The worst of protan and deutan — the number the CVD check gates on. */
    fun cvdSeparation(a: Int, b: Int): Double = min(deltaE(a, b, Cvd.PROTAN), deltaE(a, b, Cvd.DEUTAN))

    private fun fmt(v: Double) = ((v * 1000).toInt() / 1000.0).toString()
    private fun fmt1(v: Double) = (abs(v * 10).toInt() / 10.0).toString()
}
