package com.opentasker.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 運動と回復 — the grid and the list.
 *
 * The grid answers "where does the training sit, and do the bad nights follow it" at a glance; the
 * list underneath carries the numbers, each session already paired with the night that followed it.
 * See [SessionRegister] for why the pairing runs forwards and why there is no correlation anywhere.
 *
 * Expect it to look sparse for a fortnight. That is the honest state of a register with one session
 * in it, and padding it out with derived numbers is precisely what this whole feature refuses to do.
 */
@Composable
fun SessionRegisterScreen(
    register: SessionRegister.Register?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(BandText.registerTitle[lang], hasInfo = false, onBack = onBack, onInfo = {})

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (register == null || register.entries.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        BandText.registerEmpty[lang],
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = style.axisText,
                    )
                }
            }

            register?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Grid(r.days, zone)
                        Text(
                            BandText.registerLegend[lang],
                            style = MaterialTheme.typography.labelSmall,
                            color = style.axisText,
                        )
                    }
                }

                r.contrast?.let { c ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                BandText.registerContrast[lang].format(
                                    c.afterSession.roundToInt(), c.nAfterSession,
                                    c.afterRest.roundToInt(), c.nAfterRest,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                BandText.registerContrastNote[lang],
                                style = MaterialTheme.typography.labelSmall,
                                color = style.axisText,
                            )
                        }
                    }
                } ?: run {
                    if (r.entries.isNotEmpty()) {
                        Text(
                            BandText.registerContrastWaiting[lang].format(SessionRegister.MIN_CONTRAST_NIGHTS),
                            style = MaterialTheme.typography.labelSmall,
                            color = style.axisText,
                        )
                    }
                }

                r.entries.forEach { EntryCard(it, zone) }
            }
        }
    }
}

/**
 * Five weeks, Monday first, each day a bar over a dot row.
 *
 * Bar height is session load; the dots are how many markers were outside usual on the night that
 * started that day. Reading downward gives the thing worth seeing: whether the dots follow the bars.
 */
@Composable
private fun Grid(days: List<SessionRegister.DayCell>, zone: ZoneId) {
    val style = LocalChartStyle.current
    val maxLoad = days.mapNotNull { it.sessionLoad }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val labels = remember(zone) { DateTimeFormatter.ofPattern("d").withZone(zone) }

    // Pad the head so the first column really is Monday.
    val first = days.firstOrNull() ?: return
    val firstDow = Instant.ofEpochMilli(first.epochDay * 86_400_000L).atZone(zone).dayOfWeek.value // 1=Mon
    val cells: List<SessionRegister.DayCell?> = List(firstDow - 1) { null } + days

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in listOf("月", "火", "水", "木", "金", "土", "日")) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(d, style = MaterialTheme.typography.labelSmall, color = style.axisText)
                }
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val cell = week.getOrNull(i)
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (cell == null) {
                            Spacer(Modifier.height(38.dp))
                        } else {
                            Text(
                                labels.format(Instant.ofEpochMilli(cell.epochDay * 86_400_000L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = style.axisText,
                            )
                            // The bar: 4 dp of track always, filled in proportion to the session.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(style.grid),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                cell.sessionLoad?.takeIf { it > 0 }?.let { load ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height((20.0 * (load / maxLoad)).coerceIn(3.0, 20.0).dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(ChartPalette.HEART_RATE),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                val n = cell.adverseCount
                                if (n == null) {
                                    Box(Modifier.size(5.dp))
                                } else {
                                    repeat(3) { k ->
                                        val on = k < n
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (on) {
                                                        if (n >= 2) ChartPalette.BAND_SERIOUS else ChartPalette.BAND_WARN
                                                    } else {
                                                        style.grid
                                                    },
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(e: SessionRegister.Entry, zone: ZoneId) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val day = remember(zone) { DateTimeFormatter.ofPattern("MM-dd").withZone(zone) }
    val time = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(Instant.ofEpochMilli(e.session.startMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    BandText.registerSession[lang].format(
                        time.format(Instant.ofEpochMilli(e.session.startMs)),
                        e.session.minutes,
                        e.metMinutes.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                e.peakHr?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        BandText.registerPeak[lang].format(it.roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = style.axisText,
                    )
                }
            }
            val night = e.night
            if (night == null) {
                Text(
                    BandText.registerNoNight[lang],
                    style = MaterialTheme.typography.labelSmall,
                    color = style.axisText,
                )
            } else {
                Text(
                    "→ " + listOfNotNull(
                        night.nocturnalHr.value?.let {
                            "${BandText.markerNocturnalHr[lang]} ${it.roundToInt()}" +
                                (night.nocturnalHr.delta?.let { d -> " (%+d)".format(d.roundToInt()) } ?: "")
                        },
                        night.sleep.value?.let {
                            "${BandText.markerSleep[lang]} ${(it / 60).toInt()}h${(it % 60).roundToInt()}m"
                        },
                        night.felt.value?.let { "${BandText.markerFelt[lang]} ${it.roundToInt()}" },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = style.axisText,
                )
                Text(
                    when (night.adverseCount) {
                        0 -> BandText.recoveryAllUsual[lang]
                        1 -> BandText.recoveryOneOff[lang]
                        else -> BandText.recoveryTwoOff[lang].format(night.adverseCount)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (night.adverseCount == 0) ChartPalette.BAND_GOOD else ChartPalette.BAND_WARN,
                )
            }
        }
    }
}
