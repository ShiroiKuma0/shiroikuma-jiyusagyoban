package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import com.opentasker.core.band.BandMetric
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The order the cards appear in, top to bottom — 白い熊's, 2026-08-07.
 *
 * Steps and sleep first because they are the two you can act on; the band state index last because it
 * is the one that is not a measurement. Anything named here but absent from the data is skipped, and
 * anything present but not named never appears — so a metric added to the decoding table has to be
 * placed here deliberately rather than turning up at whatever position its record layout implies.
 */
private val CARD_ORDER = listOf(
    BandMetric.STEPS_MINUTE,
    MetricSpecs.KEY_SLEEP,
    BandMetric.TEMPERATURE,
    BandMetric.HEART_RATE,
    MetricSpecs.KEY_BLOOD_PRESSURE,
    BandMetric.SPO2,
    BandMetric.HRV,
)

/**
 * The 「健康」 main page.
 *
 * Sync state at the top, then one full-width card per metric. Tapping a card opens it full-screen.
 *
 * The layout follows 白い熊's brief exactly: graphs full width, **the numbers above each graph rather
 * than beside it** (Hume puts them to the left, which costs the plot a third of its width for two
 * lines of text), and nothing between the cards but space.
 */
@Composable
fun BandDashboardScreen(
    model: BandDashboardModel,
    contentPadding: PaddingValues,
    onOpenMetric: (String) -> Unit,
    /**
     * Flip the window's language, through `健康の設定 -- [727][01]`. Returns the reason it could not,
     * or null on success — by which time this screen has already been recomposed in the other
     * language, so the caller has nothing left to do.
     */
    onSwitchLanguage: suspend () -> Loc? = { null },
) {
    val state by model.state.collectAsState()
    val progress by model.progress.collectAsState()
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current

    // ONE viewport for every card, not one each. Cross-reading is the whole value of a stacked
    // column of health charts, and it is impossible if each chart is on its own clock — so the
    // crosshair's requirement and the viewport's own KDoc agree.
    val viewport = remember(state.bounds, style.defaultSpanMs) {
        ChartViewport(
            initialEndMs = state.bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
            initialSpanMs = style.defaultSpanMs,
        )
    }
    val crosshair = rememberCrosshairState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("sync") { SyncHeader(state, progress, onSync = model::sync, onSwitchLanguage = onSwitchLanguage) }

        state.index?.let { index ->
            item("index") { HealthIndexCard(index) { onOpenMetric(MetricSpecs.KEY_INDEX) } }
        }

        // Second, immediately under the index and above every chart, because 白い熊 asked to see
        // these pointers first (2026-08-09): the index says how things have been, this says what
        // last night cost and whether anything is outside normal today.
        item("recovery") {
            RecoveryCard(
                recovery = state.recovery,
                load = state.load,
                sri = state.sri,
                sleepScore = state.sleepScore,
                peak30Cadence = state.peak30Cadence,
                peakCadenceDay = state.peakCadenceDay,
                awakeMinutes = state.sleep?.sessions?.maxByOrNull { it.startMs }?.awake,
                regime = state.regime,
                feltToday = state.feltToday,
                feltNight = state.feltNight,
                recordedNight = state.recordedNight,
                feltEnabled = state.feltEnabled,
                onFelt = model::setFeltToday,
                registerNights = state.register?.rows?.size ?: 0,
                registerRated = state.register?.rows?.count { it.felt != null } ?: 0,
                onOpenRegister = { onOpenMetric(MetricSpecs.KEY_REGISTER) },
                onClick = { onOpenMetric(MetricSpecs.KEY_RECOVERY) },
            )
        }

        state.message?.let { m ->
            item("message") {
                Card(Modifier.fillMaxWidth()) {
                    Text(m[lang], Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // The card order is 白い熊's, stated card by card (2026-08-07), and it is NOT the order of
        // MetricSpecs.ALL — that list is a decoding table and its order is about record layouts. Sleep
        // and blood pressure are not entries in it at all, so a single explicit sequence is the only
        // way the three kinds of card can be interleaved as asked.
        for (key in CARD_ORDER) {
            when (key) {
                MetricSpecs.KEY_SLEEP -> state.sleep?.takeIf { !it.isEmpty }?.let { sleep ->
                    item(key) {
                        SleepPreviewCard(sleep, viewport, crosshair) { onOpenMetric(key) }
                    }
                }
                MetricSpecs.KEY_BLOOD_PRESSURE -> state.bloodPressure?.takeIf { !it.isEmpty }?.let { bp ->
                    item(key) {
                        BloodPressurePreviewCard(bp, viewport, crosshair) { onOpenMetric(key) }
                    }
                }
                else -> state.metrics.firstOrNull { it.spec.key == key }?.let { chart ->
                    item(key) {
                        MetricPreviewCard(chart, viewport, crosshair) { onOpenMetric(key) }
                    }
                }
            }
        }

        // Last, deliberately: the charts answer "what happened today", and this answers "was today
        // better than yesterday" — the question you reach for after looking, not before.
        if (state.days.size > 1) {
            item("days") { DailySummaryCard(state.days) }
        }
    }
}

/**
 * Last sync, headroom, and the button.
 *
 * A sync takes about ten seconds, most of it before the first byte arrives, so the feedback has to
 * start the instant the button is pressed and keep moving — a button that goes quiet for ten seconds
 * reads as broken. The phase, the current stream and a seconds counter all come from
 * `BandSyncState`, which is armed on the main thread before any coroutine is dispatched.
 */
@Composable
internal fun SyncHeader(
    state: DashboardState,
    progress: com.opentasker.core.band.BandSyncProgress,
    onSync: () -> Unit,
    onSwitchLanguage: suspend () -> Loc? = { null },
) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    val switch = rememberLanguageSwitch()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        BandText.lastSync[lang],
                        style = MaterialTheme.typography.labelMedium,
                        color = axisInk,
                    )
                    // The pill rides on the datetime's own line, to its right, with a little air
                    // between them (白い熊, 2026-08-20). Centred against the text rather than sharing
                    // its baseline: the chip's border is a box, and a box hung off a text baseline
                    // sits visibly low.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.status?.lastSuccessAtMillis?.let(::formatMillis)
                                ?: BandText.neverSynced[lang],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(10.dp))
                        LanguagePill(switch, onSwitchLanguage)
                    }
                    LanguageSwitchFailure(switch)
                    state.status?.headroom?.let { h ->
                        Text(
                            BandText.headroom[lang]
                                .format(h.depthSec / 3600.0, BandText.stream(h.stream, lang)),
                            style = MaterialTheme.typography.bodySmall,
                            color = axisInk,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SyncButton(progress.running, onSync)
                    BandBattery(state.status)
                }
            }

            if (progress.running || progress.phase == "done") {
                SyncProgressRow(progress)
            }

            state.status?.takeIf { it.lostSec > 0 }?.let {
                Text(
                    if (lang == BandLanguage.EN) {
                        BandText.lostWarning.en
                            .format(it.lostSec / 3600.0, BandText.streams(it.lostStreams, lang))
                    } else {
                        BandText.lostWarning.ja
                            .format(BandText.streams(it.lostStreams, lang), it.lostSec / 3600.0)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ChartPalette.BAND_CRITICAL,
                )
            }
        }
    }
}

/**
 * The band's own charge, beside the sync button.
 *
 * It is read over BLE while a sync is connected and nowhere else, so it is only ever as fresh as the
 * last sync — which is why the age is printed next to it rather than left to be assumed. "76 %" that
 * turns out to be from yesterday is worse than no number, because it reads as current.
 *
 * The bar is coloured by the reserved status roles and always carries the figure, so the state never
 * rests on hue alone.
 */
@Composable
private fun BandBattery(status: com.opentasker.core.band.BandStatus?) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    val pct = status?.batteryPct
    if (pct == null) {
        Text(
            BandText.bandBatteryUnknown[lang],
            style = MaterialTheme.typography.labelSmall,
            color = axisInk,
        )
        return
    }
    val tint = when {
        pct <= 15 -> ChartPalette.BAND_CRITICAL
        pct <= 30 -> ChartPalette.BAND_WARN
        else -> ChartPalette.BAND_GOOD
    }
    val ageHours = status.batteryAgeHours(System.currentTimeMillis())
    val age = when {
        ageHours == null || ageHours < 1.0 -> BandText.bandBatteryFresh[lang]
        ageHours < 24.0 -> BandText.bandBatteryAgeHours[lang].format(ageHours)
        else -> BandText.bandBatteryAgeDays[lang].format(ageHours / 24.0)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                BandText.bandBattery[lang],
                style = MaterialTheme.typography.labelSmall,
                color = axisInk,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$pct%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
        // A short bar rather than a battery glyph: the figure is already there, and the bar is for
        // seeing at a glance that it is getting short.
        Box(
            Modifier
                .width(BATTERY_BAR_WIDTH)
                .height(4.dp)
                .clip(CircleShape)
                .background(axisInk.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
        Text(age, style = MaterialTheme.typography.labelSmall, color = axisInk)
    }
}

private val BATTERY_BAR_WIDTH = 72.dp

@Composable
private fun SyncButton(running: Boolean, onSync: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (running) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
            .clickable(enabled = !running, onClick = onSync)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                BandText.syncNow[LocalBandLanguage.current],
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun SyncProgressRow(progress: com.opentasker.core.band.BandSyncProgress) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    // A seconds counter, because "connecting" for eight seconds with no movement looks stuck even
    // when it is working perfectly.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.running) {
        while (progress.running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val seconds = if (progress.startedAtMillis > 0) (now - progress.startedAtMillis) / 1000 else 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buildString {
                append(phaseLabel(progress.phase, lang))
                if (progress.stream.isNotBlank()) append(" · " + BandText.stream(progress.stream, lang))
                if (progress.streamCount > 0) append(" (${progress.streamIndex + 1}/${progress.streamCount})")
                if (progress.running) append(" · " + BandText.seconds[lang].format(seconds))
                if (progress.records > 0) {
                    append(" · " + BandText.recordsOf[lang].format(progress.inserted, progress.records))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = axisInk,
        )
        if (progress.running) {
            LinearProgressIndicator(
                progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            )
        } else if (progress.message.isNotBlank()) {
            Text(progress.message, style = MaterialTheme.typography.bodySmall, color = axisInk)
        }
    }
}

private fun phaseLabel(phase: String, lang: BandLanguage) = when (phase) {
    "starting" -> BandText.phaseStarting[lang]
    "connecting" -> BandText.phaseConnecting[lang]
    "device" -> BandText.phaseDevice[lang]
    "reading" -> BandText.phaseReading[lang]
    "done" -> BandText.phaseDone[lang]
    else -> phase
}

/** The card wrapper every metric shares: title, numbers ABOVE, then the plot. */
@Composable
fun ChartCard(
    title: String,
    headline: String,
    unit: String,
    band: BandRung?,
    subtitle: String,
    accent: Color,
    onClick: (() -> Unit)? = null,
    plot: @Composable () -> Unit,
) {
    val style = LocalChartStyle.current
    SectionCard(accent = accent, onClick = onClick) {
        SectionTitle(title, accent) { band?.let { BandChip(it) } }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = style.headlineSize),
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.axisText,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        plot()
        NoteText(subtitle)
    }
}

/**
 * The ringed "i".
 *
 * Defined once and used by both the dashboard cards and the detail header, so the two cannot drift
 * apart. The ring is the theme accent rather than a literal yellow, so it follows a re-theme.
 *
 * It carries the same tap as whatever it sits on rather than a different one: the ring is a signpost
 * saying "there is an explanation behind this", not a separate control.
 */
@Composable
fun InfoCircle(
    diameter: androidx.compose.ui.unit.Dp = 36.dp,
    onClick: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, accent, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "i",
            style = if (diameter < 32.dp) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

/** A status chip. The label always travels with the colour — state never rests on hue alone. */
@Composable
fun BandChip(rung: BandRung) {
    val lang = LocalBandLanguage.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(rung.color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(rung.label[lang], style = MaterialTheme.typography.labelSmall, color = rung.color)
    }
}

private fun formatMillis(millis: Long): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(millis))
