package com.opentasker.ui.charts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.band.BandMetric
import com.opentasker.ui.charts.huawei.HuaweiKeys
import com.opentasker.ui.theme.ThemePrefs

/**
 * Every number the charts used to hard-code, in one settable place.
 *
 * The renderers were written with their constants inline — `PREVIEW_HEIGHT = 132.dp`, `STROKE = 2f`,
 * `alpha = 0.28f`, a 132 dp preview and a 320 dp detail. Each was a reasonable guess made while
 * looking at one device, and every one of them is a thing 白い熊 might reasonably want different: a
 * taller preview, a thicker line, a fainter grid.
 *
 * So they live in [ThemePrefs] beside the rest of the app's appearance, and arrive here as one
 * immutable bundle. Two consequences worth knowing:
 *
 * - **[LocalChartStyle] is `static`.** A change re-composes every chart rather than only the readers,
 *   which is exactly right for a value that changes when a slider moves and never otherwise.
 * - **[PlotFrame][com.opentasker.ui.charts.render.PlotFrame] carries it into the draw scope.** The
 *   mark functions are `DrawScope` extensions, not composables, so they cannot read a
 *   CompositionLocal; the frame is built in the plot composable — which can — and hands the style
 *   down with the geometry.
 *
 * ## The colours are settable, and checked
 *
 * The shipped palette was not chosen by eye: it was validated for colour-blind separation and
 * contrast against this app's own surface, and the obvious "nicer" hypnogram — Hume's violet REM
 * beside blue — fails at ΔE 1.9 under protanopia. Letting anyone repaint it therefore has to come
 * with the check attached, which is why [PaletteCheck] exists and the customization screen runs it
 * live on whatever has been picked. Free to change; not free to break silently.
 */
@Immutable
data class ChartStyle(
    // --- sizes -------------------------------------------------------------------------------
    val previewHeight: Dp,
    val detailHeight: Dp,
    val cardGap: Dp,
    val axisTextSize: TextUnit,
    val headlineSize: TextUnit,
    // --- marks -------------------------------------------------------------------------------
    val lineWidth: Dp,
    val dotSize: Dp,
    val capsuleWidth: Dp,
    val barWidth: Dp,
    val dumbbellWidth: Dp,
    /** Stage block height as a fraction of its hypnogram row. */
    val hypnogramBand: Float,
    val cornerRadius: Dp,
    // --- ink ---------------------------------------------------------------------------------
    val grid: Color,
    val axisText: Color,
    val gapTint: Color,
    val fillAlpha: Float,
    val glowAlpha: Float,
    // --- behaviour ---------------------------------------------------------------------------
    val showGrid: Boolean,
    val showDots: Boolean,
    val showRejected: Boolean,
    val showGaps: Boolean,
    val defaultSpanMs: Long,
    val curve: ChartCurveMode,
    // --- series ------------------------------------------------------------------------------
    val heartRate: Color,
    val bandState: Color,
    val spo2: Color,
    val temperature: Color,
    val steps: Color,
    /** 安静時心拍 — Huawei only. Defaults to slot 4; see [ChartPalette.RESTING_HEART_RATE]. */
    val restingHr: Color,
    val systolic: Color,
    val diastolic: Color,
    val sleepDeep: Color,
    val sleepLight: Color,
    val sleepRem: Color,
    val sleepAwake: Color,
) {
    /**
     * A metric's colour, by the same key its [MetricSpec] carries.
     *
     * Keyed rather than stored on the spec because the spec is a compile-time table and the colour is
     * now runtime state. Anything unrecognised falls back to the validated default for that slot.
     */
    fun colorFor(key: String): Color = when (key) {
        BandMetric.HEART_RATE -> heartRate
        BandMetric.HRV, BandMetric.STRESS -> bandState
        BandMetric.SPO2 -> spo2
        BandMetric.TEMPERATURE -> temperature
        BandMetric.STEPS_MINUTE, BandMetric.STEPS_BUCKET -> steps

        // The Huawei band's keys are prefixed because its storage names collide with the Hume
        // band's — both call heart rate "hr" and blood oxygen "spo2". Without the prefix both
        // devices would silently resolve to the same colour, which is not cosmetic for a
        // red-green-deficient reader comparing two bands.
        HuaweiKeys.HEART_RATE -> heartRate
        HuaweiKeys.SPO2 -> spo2
        HuaweiKeys.STEPS -> steps
        HuaweiKeys.RESTING_HR -> restingHr

        // Anything else from the Huawei band reads GREY, deliberately, and this arm must stay
        // ABOVE the general fallback. Its raw fields and undecoded feature bits are numbers whose
        // meaning we do not know; borrowing a series colour would dress them as measurements.
        else -> if (key.startsWith(HuaweiKeys.PREFIX)) ChartPalette.UNKNOWN else ChartPalette.BAND_INDEX
    }

    /** Sleep stage colour, keyed to the band's RAW codes — see [ChartPalette.sleepStage]. */
    fun sleepStage(rawCode: Char): Color = when (rawCode) {
        '1' -> sleepDeep
        '2' -> sleepLight
        '3' -> sleepRem
        '5' -> sleepAwake
        else -> ChartPalette.UNKNOWN
    }

    /** The series colours in the one order [PaletteCheck] cares about — adjacent pairs. */
    val seriesColors: List<Pair<String, Color>>
        get() = listOf(
            "心拍" to heartRate,
            "バンド状態指数" to bandState,
            "血中酸素" to spo2,
            "体温" to temperature,
            "歩数" to steps,
        )

    /**
     * The Huawei screen's colours, in the order its cards are drawn.
     *
     * A separate list because the two screens are validated separately: this one is four series and
     * its own adjacency, and 安静時心拍 sits last for the reason recorded on
     * [ChartPalette.RESTING_HEART_RATE].
     */
    val huaweiSeriesColors: List<Pair<String, Color>>
        get() = listOf(
            "歩数" to steps,
            "心拍" to heartRate,
            "血中酸素" to spo2,
            "安静時心拍" to restingHr,
        )

    val sleepColors: List<Pair<String, Color>>
        get() = listOf(
            "深い" to sleepDeep,
            "浅い" to sleepLight,
            "REM" to sleepRem,
            "覚醒" to sleepAwake,
        )

    val bloodPressureColors: List<Pair<String, Color>>
        get() = listOf("収縮期" to systolic, "拡張期" to diastolic)

    companion object {
        fun from(p: ThemePrefs) = ChartStyle(
            previewHeight = p.chartPreviewHeightDp.dp,
            detailHeight = p.chartDetailHeightDp.dp,
            cardGap = p.chartCardGapDp.dp,
            axisTextSize = p.chartAxisTextSp.sp,
            headlineSize = p.chartHeadlineSp.sp,
            lineWidth = p.chartLineWidthDp.dp,
            dotSize = p.chartDotSizeDp.dp,
            capsuleWidth = p.chartCapsuleWidthDp.dp,
            barWidth = p.chartBarWidthDp.dp,
            dumbbellWidth = p.chartDumbbellWidthDp.dp,
            hypnogramBand = p.chartHypnogramBandPct / 100f,
            cornerRadius = p.chartCornerRadiusDp.dp,
            grid = Color(p.chartGridColor).copy(alpha = p.chartGridOpacityPct / 100f),
            axisText = Color(p.chartAxisTextColor),
            gapTint = Color(p.chartGridColor).copy(alpha = p.chartGapTintPct / 100f),
            fillAlpha = p.chartFillOpacityPct / 100f,
            glowAlpha = p.chartGlowOpacityPct / 100f,
            showGrid = p.chartShowGrid,
            showDots = p.chartShowDots,
            showRejected = p.chartShowRejected,
            showGaps = p.chartShowGaps,
            defaultSpanMs = p.chartDefaultSpanHours * 3_600_000L,
            curve = runCatching { ChartCurveMode.valueOf(p.chartCurveMode) }
                .getOrDefault(ChartCurveMode.PCHIP),
            heartRate = Color(p.chartColorHeartRate),
            bandState = Color(p.chartColorBandState),
            spo2 = Color(p.chartColorSpo2),
            temperature = Color(p.chartColorTemperature),
            steps = Color(p.chartColorSteps),
            restingHr = Color(p.chartColorRestingHr),
            systolic = Color(p.chartColorSystolic),
            diastolic = Color(p.chartColorDiastolic),
            sleepDeep = Color(p.chartColorSleepDeep),
            sleepLight = Color(p.chartColorSleepLight),
            sleepRem = Color(p.chartColorSleepRem),
            sleepAwake = Color(p.chartColorSleepAwake),
        )

        /** The shipped, validated defaults — what a chart looks like before anyone touches a slider. */
        val DEFAULT = from(ThemePrefs.DEFAULT)
    }
}

/**
 * Static because the whole chart tree wants re-drawing when any of it changes, and nothing reads it
 * often enough for the finer-grained local to earn its bookkeeping.
 */
val LocalChartStyle = staticCompositionLocalOf { ChartStyle.DEFAULT }
