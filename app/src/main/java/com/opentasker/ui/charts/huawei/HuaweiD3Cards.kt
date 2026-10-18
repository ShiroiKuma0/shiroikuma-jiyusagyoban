package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiEcg
import com.opentasker.ui.charts.BandText
import com.opentasker.ui.charts.BloodPressureChart
import com.opentasker.ui.charts.BloodPressurePlot
import com.opentasker.ui.charts.ChartCard
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.CrosshairState
import com.opentasker.ui.charts.InfoBody
import com.opentasker.ui.charts.InfoHeading
import com.opentasker.ui.charts.LegendEntry
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.crosshairInput
import com.opentasker.ui.charts.rememberChartGestureModifier
import com.opentasker.ui.charts.rememberCrosshairState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Watch D3's own measurements that are not a per-minute series: blood pressure (a cuff
 * reading at a time) and ECG (a 30-second recording). The per-minute ones — skin temperature,
 * arterial stiffness — are ordinary [HuaweiMetricSpecs] rows and use the shared cards.
 *
 * Both draw exactly what the watch stored: no averaging, no smoothing, nothing interpolated between
 * readings. Health's own grades are stated as text, never as colour alone (白い熊 reads red–green
 * poorly; see ChartPalette).
 */
object HuaweiD3Text {
    val bloodPressure = Loc("Blood Pressure", "血圧")
    val bpReadings = Loc("Readings", "測定一覧")
    val pulse = Loc("pulse", "脈拍")
    val ecg = Loc("ECG", "心電図")
    val ecgRecordings = Loc("%d recordings", "%d 件")
    val meanHr = Loc("mean %d bpm", "平均 %d bpm")
    val seconds = Loc("%d s", "%d 秒")
    val sinus = Loc("Sinus rhythm", "洞調律")
    val inconclusive = Loc("Inconclusive", "判定不能")
    val otherVerdict = Loc("Watch verdict 0x%02X", "時計の判定 0x%02X")
    val bpWhat = Loc(
        "Blood pressure measured by the HUAWEI WATCH D3's own cuff, on the wrist: the systolic "
            + "(upper) and diastolic (lower) pressure of one reading, and the pulse the cuff counted.",
        "HUAWEI WATCH D3 の手首のカフで測った血圧。一回の測定の収縮期（上）と拡張期（下）、"
            + "そしてカフが数えた脈拍。",
    )
    val bpHow = Loc(
        "Each dumbbell is one hour: its top is that hour's systolic, its bottom the diastolic. One "
            + "reading makes a bar from its systolic to its diastolic; several in one hour stretch it "
            + "to their extremes. Nothing is averaged — the list below gives every reading.",
        "一本が一時間。上端がその時間の収縮期、下端が拡張期。一回の測定なら収縮期から拡張期までの"
            + "一本、同じ時間に何回かあれば、その最も外側まで伸びる。平均はしない — 下の一覧に全件。",
    )
    val bpRead = Loc(
        "For reference, the European (ESH) office categories: optimal below 120/80, normal "
            + "120–129/80–84, high-normal 130–139/85–89, and 140/90 or more is high. A wrist reading "
            + "depends on posture: the watch at heart height, sitting, arm resting, after five quiet "
            + "minutes. Compare readings taken the same way at the same time of day.",
        "参考：欧州（ESH）の診察室血圧の区分は、至適 120/80 未満、正常 120–129/80–84、"
            + "正常高値 130–139/85–89、140/90 以上が高血圧。手首の値は姿勢に左右される — 時計を"
            + "心臓の高さに、座って腕を置き、五分静かにしてから。同じ時間帯に同じやり方で測った値どうしを"
            + "比べること。",
    )
    val bpCaveat = Loc(
        "**Only the D3 measures blood pressure here.** The Band 11 Pro had no cuff, and the Hume "
            + "band's figures were a clamped estimate that never left ±10 mmHg of 120/70, so neither "
            + "is chained in. ABPM (the watch's 24-hour mode) has not been run yet; whether its "
            + "readings arrive the same way is still to be seen.",
        "**ここで血圧を測るのは D3 だけ。** Band 11 Pro にはカフが無く、Hume の値は 120/70 から "
            + "±10 mmHg を出ない推定値だったので、どちらもつないでいない。時計の 24 時間測定（ABPM）は"
            + "まだ試しておらず、同じ形で届くかは未確認。",
    )
    val ecgWhat = Loc(
        "A single-lead ECG recorded by the HUAWEI WATCH D3: 30 seconds with a finger on the "
            + "watch, analysed on the watch itself. Each recording is drawn whole, in 5-second rows "
            + "like paper strips.",
        "HUAWEI WATCH D3 で記録した一誘導の心電図。時計に指を当てて 30 秒、解析は時計そのものが"
            + "行う。一件ずつ全体を、紙の記録のように 5 秒ずつの行で描く。",
    )
    val ecgCaveat = Loc(
        "**The verdict is the watch's, not ours.** The trace is drawn as stored, auto-scaled per "
            + "recording, so its height is not in millimetres per millivolt and is not for "
            + "diagnosis. An inconclusive verdict usually means the finger moved or did not rest "
            + "on the electrode.",
        "**判定は時計のもので、こちらのものではない。** 波形は保存されたまま、一件ごとに自動で"
            + "縮尺を合わせて描くので、高さは mm/mV ではなく、診断には使えない。判定不能はたいてい、"
            + "指が動いたか電極に乗っていなかったということ。",
    )

    fun verdict(code: Int): Loc = when (code) {
        HuaweiEcg.VERDICT_SINUS -> sinus
        HuaweiEcg.VERDICT_INCONCLUSIVE -> inconclusive
        else -> Loc(otherVerdict.en.format(code), otherVerdict.ja.format(code))
    }
}

private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun stamp(ms: Long, zone: ZoneId): String = WHEN.format(Instant.ofEpochMilli(ms).atZone(zone))

// --- blood pressure ----------------------------------------------------------------------------

@Composable
fun HuaweiBloodPressureCard(
    chart: BloodPressureChart,
    viewport: ChartViewport,
    crosshair: CrosshairState,
    onClick: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    ChartCard(
        title = HuaweiD3Text.bloodPressure[lang],
        headline = chart.headline,
        unit = "mmHg",
        // A real cuff reading, so none of the Hume card's "not a measurement" chip.
        band = null,
        subtitle = BandText.bpRanges[lang].format(chart.systolicRange, chart.diastolicRange),
        accent = ChartPalette.BLOOD_PRESSURE,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BloodPressurePlot(
                chart, viewport,
                Modifier.height(style.previewHeight).crosshairInput(crosshair, viewport),
                showAxes = false, crosshair = crosshair,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendEntry(style.systolic, BandText.systolic[lang])
                LegendEntry(style.diastolic, BandText.diastolic[lang])
            }
        }
    }
}

@Composable
fun HuaweiBloodPressureDetailScreen(
    chart: BloodPressureChart,
    readings: List<HuaweiBpReading>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    bounds: LongRange = 0L..0L,
    nowMs: Long = System.currentTimeMillis(),
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    val viewport = remember(style.defaultSpanMs) {
        // A cuff reading is a few a day at most: open on three days, not on the dashboard's one —
        // and not on a week, where each hour's dumbbell narrows to three pixels. Ending at NOW, not
        // at the last reading: a page that stops at yesterday's reading hides that today has none.
        ChartViewport(initialEndMs = nowMs, initialSpanMs = 3 * 86_400_000L)
    }
    val crosshair = rememberCrosshairState()
    val span = if (bounds.last > bounds.first) bounds else viewport.startMs..viewport.endMs
    val gestures = rememberChartGestureModifier(
        onZoom = { viewport.zoomAround(viewport.plotWidthPx / 2f, it, span) },
        onPan = { viewport.panBy(it.x, span) },
    )
    val accent = ChartPalette.BLOOD_PRESSURE

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }
        item("plot") {
            SectionCard(accent = accent) {
                SectionTitle(HuaweiD3Text.bloodPressure[lang], accent)
                Text("${chart.headline} mmHg", style = MaterialTheme.typography.headlineSmall, color = accent)
                BloodPressurePlot(
                    chart, viewport,
                    Modifier.fillMaxWidth().height(style.detailHeight).then(gestures),
                    crosshair = crosshair,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendEntry(style.systolic, BandText.systolic[lang])
                    LegendEntry(style.diastolic, BandText.diastolic[lang])
                }
            }
        }
        item("readings") {
            SectionCard(accent = accent) {
                SectionTitle(HuaweiD3Text.bpReadings[lang], accent)
                readings.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stamp(r.tMs, zone), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${r.systolic.toInt()}/${r.diastolic.toInt()}",
                            style = MaterialTheme.typography.bodyMedium, color = accent)
                        r.pulse?.let {
                            Text("${HuaweiD3Text.pulse[lang]} ${it.toInt()}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item("info") {
            SectionCard(accent = accent) {
                SectionTitle(HuaweiD3Text.bloodPressure[lang], accent)
                val ja = lang.tag.startsWith("ja")
                InfoHeading(if (ja) "これは何か" else "What it is")
                InfoBody(HuaweiD3Text.bpWhat[lang])
                InfoHeading(if (ja) "どう描いているか" else "How it is drawn")
                InfoBody(HuaweiD3Text.bpHow[lang])
                InfoHeading(if (ja) "どう読むか" else "How to read it")
                InfoBody(HuaweiD3Text.bpRead[lang])
                InfoHeading(if (ja) "分かっていないこと" else "What we do not know")
                InfoBody(HuaweiD3Text.bpCaveat[lang])
            }
        }
    }
}

// --- ECG ---------------------------------------------------------------------------------------

/** The ECG trace colour: the heart rate's red family would sit on the blood-pressure magenta. */
private val ECG_COLOR = ChartPalette.STEPS

@Composable
fun HuaweiEcgCard(recordings: List<HuaweiEcgRecording>, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val zone = remember { ZoneId.systemDefault() }
    val latest = recordings.firstOrNull() ?: return
    SectionCard(accent = ECG_COLOR, onClick = onClick) {
        SectionTitle(HuaweiD3Text.ecg[lang], ECG_COLOR)
        Text(HuaweiD3Text.verdict(latest.verdict)[lang],
            style = MaterialTheme.typography.headlineSmall, color = ECG_COLOR)
        NoteText(
            stamp(latest.startMs, zone) + " · " + HuaweiD3Text.meanHr[lang].format(latest.meanHr) +
                " · " + HuaweiD3Text.ecgRecordings[lang].format(recordings.size),
        )
        // Five seconds from the middle of the latest recording: the start is where the electrodes
        // settle, and the least representative part of it.
        val mid = ((latest.samples.size / latest.sampleRate.coerceAtLeast(1)) / 2 - 2).coerceAtLeast(0)
        EcgStrip(latest, fromSec = mid, seconds = 5, modifier = Modifier.fillMaxWidth().height(64.dp))
    }
}

@Composable
fun HuaweiEcgDetailScreen(
    recordings: List<HuaweiEcgRecording>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }
        items(recordings, key = { it.startMs }) { rec ->
            SectionCard(accent = ECG_COLOR) {
                SectionTitle(stamp(rec.startMs, zone), ECG_COLOR)
                Text(HuaweiD3Text.verdict(rec.verdict)[lang],
                    style = MaterialTheme.typography.titleMedium, color = ECG_COLOR)
                val secs = (rec.samples.size / rec.sampleRate.coerceAtLeast(1))
                NoteText(
                    HuaweiD3Text.meanHr[lang].format(rec.meanHr) + " · " +
                        HuaweiD3Text.seconds[lang].format(secs) + " · " + rec.analysisVersion,
                )
                for (row in 0 until (secs + 4) / 5) {
                    EcgStrip(rec, fromSec = row * 5, seconds = 5,
                        modifier = Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
        item("info") {
            SectionCard(accent = ECG_COLOR) {
                SectionTitle(HuaweiD3Text.ecg[lang], ECG_COLOR)
                val ja = lang.tag.startsWith("ja")
                InfoHeading(if (ja) "これは何か" else "What it is")
                InfoBody(HuaweiD3Text.ecgWhat[lang])
                InfoHeading(if (ja) "分かっていないこと" else "What we do not know")
                InfoBody(HuaweiD3Text.ecgCaveat[lang])
            }
        }
    }
}

/**
 * [seconds] of one recording from [fromSec], drawn as stored: every sample, one polyline, on a grid
 * of 0.2 s (heavy) and 0.04 s (light) like ECG paper. The vertical scale is the recording's own
 * 0.5th–99.5th percentile, shared by every strip of that recording so the rows compare.
 */
@Composable
fun EcgStrip(rec: HuaweiEcgRecording, fromSec: Int, seconds: Int, modifier: Modifier = Modifier) {
    val scale = remember(rec.startMs) { ecgScale(rec.samples, rec.sampleRate) }
    val grid = Color(0x1AFFFFFF)
    val gridHeavy = Color(0x33FFFFFF)
    Canvas(modifier) {
        val rate = rec.sampleRate.coerceAtLeast(1)
        val from = (fromSec * rate).coerceIn(0, rec.samples.size)
        val to = ((fromSec + seconds) * rate).coerceIn(from, rec.samples.size)
        val pxPerSec = size.width / seconds
        // The paper grid: a line every 0.2 s, stronger on the second. ECG paper's 0.04 s lines are
        // left out — at a phone's width they are eight pixels apart and read as hatching.
        for (k in 0..seconds * 5) {
            val x = (k * 0.2 * pxPerSec).toFloat()
            drawLine(if (k % 5 == 0) gridHeavy else grid, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        if (to - from < 2) return@Canvas
        val (lo, hi) = scale
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        fun y(v: Float) = (size.height - ((v - lo) / span * size.height)).toFloat().coerceIn(0f, size.height)
        // Every sample, unless there are more than two per pixel: then each pixel column draws its
        // own min and max, which keeps every QRS peak instead of skipping it by decimation.
        val path = Path()
        val n = to - from
        val perPx = n / size.width
        if (perPx <= 2f) {
            for (i in from until to) {
                val x = (i - from) / rate.toFloat() * pxPerSec
                if (i == from) path.moveTo(x, y(rec.samples[i])) else path.lineTo(x, y(rec.samples[i]))
            }
        } else {
            val cols = size.width.toInt().coerceAtLeast(1)
            for (c in 0 until cols) {
                val a = from + (c * n / cols)
                val b = (from + ((c + 1) * n / cols)).coerceAtLeast(a + 1).coerceAtMost(to)
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (i in a until b) { val v = rec.samples[i]; if (v < lo) lo = v; if (v > hi) hi = v }
                val x = c.toFloat()
                if (c == 0) path.moveTo(x, y(lo)) else path.lineTo(x, y(lo))
                path.lineTo(x, y(hi))
            }
        }
        drawPath(path, ECG_COLOR, style = Stroke(width = 1.5f))
    }
}

/**
 * The vertical range a recording is drawn on: its 0.5th–99.5th percentile, padded by a tenth.
 *
 * The first second is left out of the measurement (not out of the drawing): the electrodes settle
 * in it, and on 2026-10-17's first recording that settling reached −3.5 × 10⁷ against beats of
 * about ±6 × 10⁵ — scaled to it, the whole recording drew as a flat line. Samples outside the
 * range are clipped to the strip's edge, so the settling still shows as what it is.
 */
internal fun ecgScale(samples: FloatArray, sampleRate: Int): Pair<Double, Double> {
    if (samples.isEmpty()) return 0.0 to 1.0
    val skip = if (samples.size > 3 * sampleRate) sampleRate else 0
    val sorted = samples.copyOfRange(skip, samples.size).also { it.sort() }
    val lo = sorted[(sorted.size * 0.005).toInt().coerceIn(0, sorted.size - 1)].toDouble()
    val hi = sorted[(sorted.size * 0.995).toInt().coerceIn(0, sorted.size - 1)].toDouble()
    val pad = (hi - lo) * 0.1
    return (lo - pad) to (hi + pad)
}
