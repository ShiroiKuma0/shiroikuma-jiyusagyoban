package com.opentasker.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opentasker.ui.components.Button
import com.opentasker.ui.components.OutlinedButton

/**
 * The night score's screens — the trend above the table, the breakdown behind a score, and the
 * weights page. The arithmetic is all in [NightScore]; this file only draws it.
 */

/** How many nights the trend draws. A month is what "lately" means when reading a column of nights. */
const val SCORE_TREND_NIGHTS = 30

/**
 * The last month of scores as dots in their own step's colour, with the 7-night median through them.
 *
 * Dots rather than a joined line because a night with no score is a gap, not a slope; the median is
 * the one line, so the eye has one trend to follow and every night still shows where it fell.
 */
@Composable
fun NightScoreTrendCard(
    /** Oldest first, one per night: the morning key and its score. */
    nights: List<Pair<Long, NightScore.Score>>,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val shown = nights.takeLast(SCORE_TREND_NIGHTS)
    if (shown.none { it.second.value != null }) return
    val values = shown.map { it.second.value }
    val median = NightScore.rollingMedian(values)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    BandText.scoreTrendTitle[lang],
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                )
                shown.lastOrNull { it.second.value != null }?.let { (key, s) ->
                    Text(
                        BandText.scoreLatest[lang].format(nightDateParts(key, lang).first),
                        style = MaterialTheme.typography.bodySmall,
                        color = sectionNote,
                    )
                    Spacer(Modifier.width(6.dp))
                    ScoreChip(s)
                }
            }
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(
                    Modifier.fillMaxHeight().padding(end = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (label in listOf("100", "50", "0")) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = sectionNote)
                    }
                }
                val ink = sectionInk
                Canvas(Modifier.weight(1f).height(120.dp)) {
                    fun y(v: Double) = size.height * (1f - (v / 100.0).toFloat())
                    val dx = if (shown.size > 1) size.width / (shown.size - 1) else 0f
                    for (g in listOf(25.0, 50.0, 75.0)) {
                        drawLine(style.grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1f)
                    }
                    val path = Path()
                    var started = false
                    median.forEachIndexed { i, m ->
                        if (m == null) {
                            started = false
                        } else if (!started) {
                            path.moveTo(dx * i, y(m)); started = true
                        } else {
                            path.lineTo(dx * i, y(m))
                        }
                    }
                    drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
                    values.forEachIndexed { i, v ->
                        if (v != null) {
                            val c = Offset(dx * i, y(v.toDouble()))
                            drawCircle(Color.Black, 5.dp.toPx(), c)
                            drawCircle(ChartPalette.scale(NightScore.stepOf(v)), 3.5.dp.toPx(), c)
                        }
                    }
                }
            }
            Text(
                BandText.scoreTrendSub[lang].format(shown.size),
                style = MaterialTheme.typography.bodySmall,
                color = sectionNote,
            )
        }
    }
}

/** A score as the table's chip: the number on its own step's colour, `*` when partial. */
@Composable
fun ScoreChip(score: NightScore.Score) {
    val skin = scaleSkin(score.step, Color.Transparent, sectionNote)
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(skin.fill)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            score.value?.let { "$it" + if (score.partial) "*" else "" } ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = skin.ink,
            maxLines = 1,
        )
    }
}

/** What one night's score is made of — every cell, its step, its points and its share. */
@Composable
fun NightScoreBreakdownDialog(
    dateKey: Long,
    score: NightScore.Score,
    onOpenWeights: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    BandText.scoreBreakdownTitle[lang].format(nightDateFull(dateKey, lang)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                )
                ScoreChip(score)
            }
            score.step?.let {
                Text(Grade.word(it)[lang], style = MaterialTheme.typography.bodyMedium, color = sectionInk)
            }
            if (score.value == null) {
                Text(
                    BandText.scoreBreakdownNone[lang].format(
                        (score.share * 100).toInt(), (NightScore.MIN_SHARE * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sectionInk,
                )
            }
            val measured = score.components.filter { it.step != null }.sumOf { it.weight }
            for (c in score.components.filter { it.weight > 0 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.part.label[lang],
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = sectionInk,
                    )
                    if (c.step == null) {
                        Text(BandText.scoreMissing[lang], style = MaterialTheme.typography.bodySmall, color = sectionNote)
                    } else {
                        val skin = scaleSkin(c.step, Color.Transparent, sectionNote)
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(skin.fill)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text("${c.step}", fontWeight = FontWeight.Bold, color = skin.ink)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            BandText.scoreAdds[lang].format(
                                "${c.points}",
                                if (measured == 0) 0 else (c.weight * 100.0 / measured).toInt(),
                                score.contribution(c),
                            ),
                            Modifier.width(120.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = sectionInk,
                        )
                    }
                }
            }
            if (score.partial) {
                Text(BandText.scorePartialMark[lang], style = MaterialTheme.typography.bodySmall, color = sectionNote)
            }
            Text(BandText.scoreBreakdownHow[lang], style = MaterialTheme.typography.bodySmall, color = sectionNote)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                onOpenWeights?.let {
                    OutlinedButton(onClick = it) { Text(BandText.scoreWeightsOpen[lang]) }
                }
                Button(onClick = onDismiss) { Text(BandText.scoreClose[lang]) }
            }
        }
    }
}

/**
 * The weights page: a slider per cell, with its default, its reasoning, its own correlation with
 * how 白い熊 woke, and a way to its history. The score's correlation at the top moves with the
 * sliders, so a change is judged by what it does rather than by how it feels.
 *
 * A slider writes on RELEASE, not on every frame of the drag — the draft is local until then, and
 * the correlation follows the draft live.
 */
@Composable
fun NightScoreWeightsPage(
    rows: List<SessionRegister.NightRow>,
    weights: Map<NightScore.Part, Int>,
    contentPadding: PaddingValues,
    onChange: (Map<NightScore.Part, Int>) -> Unit,
    /** Null for a cell with no history page; the pill is not drawn then. */
    onOpenMarker: ((RecoveryMarker) -> Unit)?,
    onBack: () -> Unit,
    onSwitchLanguage: suspend () -> Loc? = { null },
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    var draft by remember(weights) { mutableStateOf(weights) }
    val fit = remember(rows, draft) { NightScore.calibration(rows, draft) }
    val perPart = remember(rows) { NightScore.perPart(rows) }
    var proposal by remember { mutableStateOf<Map<NightScore.Part, Int>?>(null) }
    var noProposal by remember { mutableStateOf(false) }
    val total = draft.values.sum()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(
            BandText.scoreWeightsTitle[lang],
            hasInfo = false,
            onBack = onBack,
            onInfo = {},
            onSwitchLanguage = onSwitchLanguage,
        )
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(BandText.scoreWeightsIntro[lang], style = MaterialTheme.typography.bodyMedium, color = sectionNote)
            Text(
                fit?.let { BandText.scoreFit[lang].format(it.rho, it.lo, it.hi, it.n) }
                    ?: BandText.scoreFitNone[lang].format(NightScore.MIN_FIT_NIGHTS),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            Text(BandText.scoreFitHelp[lang], style = MaterialTheme.typography.bodySmall, color = sectionNote)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    draft = NightScore.DEFAULTS
                    onChange(NightScore.DEFAULTS)
                }) { Text(BandText.scoreDefaults[lang]) }
                OutlinedButton(onClick = {
                    val s = NightScore.suggest(rows)
                    proposal = s
                    noProposal = s == null
                }) { Text(BandText.scoreSuggest[lang]) }
            }
            if (noProposal) {
                Text(BandText.scoreSuggestNone[lang], style = MaterialTheme.typography.bodySmall, color = sectionInk)
            }

            for (part in NightScore.Part.entries) {
                val w = draft[part] ?: part.default
                // The settings-row shape (black-yellow theme): bold label, the explanation under it,
                // a thin rule down the left of the whole item.
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(sectionNote))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                part.label[lang],
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = sectionInk,
                            )
                            val marker = part.marker
                            if (marker != null && onOpenMarker != null) {
                                OutlinedButton(
                                    onClick = { onOpenMarker(marker) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 32.dp),
                                ) { Text(BandText.scoreGraph[lang]) }
                            } else if (marker == null) {
                                Text(BandText.scoreNoGraph[lang], style = MaterialTheme.typography.bodySmall, color = sectionNote)
                            }
                        }
                        Slider(
                            value = w.toFloat(),
                            onValueChange = { draft = draft + (part to it.toInt()) },
                            onValueChangeFinished = { onChange(draft) },
                            valueRange = 0f..NightScore.MAX_WEIGHT.toFloat(),
                            steps = NightScore.MAX_WEIGHT - 1,
                            colors = SliderDefaults.colors(
                                thumbColor = sectionInk,
                                activeTrackColor = sectionInk,
                                inactiveTrackColor = sectionInk.copy(alpha = 0.25f),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent,
                            ),
                        )
                        Text(
                            BandText.scoreWeightShare[lang].format(w, if (total == 0) 0 else (w * 100.0 / total).toInt()) +
                                "  ·  " + BandText.scoreDefaultIs[lang].format(part.default) +
                                (perPart[part]?.let { "  ·  " + BandText.scorePartFit[lang].format(it.rho, it.n) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = sectionInk,
                        )
                        Text(part.why[lang], fontSize = 13.sp, color = sectionNote)
                    }
                }
            }
        }
    }

    proposal?.let { p ->
        val now = fit?.let { "%.2f".format(it.rho) } ?: "—"
        val then = NightScore.calibration(rows, p)?.let { "%.2f".format(it.rho) } ?: "—"
        val nights = rows.count { it.felt != null && it.night != null }
        Dialog(onDismissRequest = { proposal = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    BandText.scoreSuggestTitle[lang],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                )
                for (part in NightScore.Part.entries) {
                    Row {
                        Text(part.label[lang], Modifier.weight(1f), color = sectionInk)
                        Text("${draft[part] ?: part.default} → ${p[part]}", fontWeight = FontWeight.Bold, color = sectionInk)
                    }
                }
                Text(
                    BandText.scoreSuggestBody[lang].format(nights, then, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = sectionNote,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = { proposal = null }) { Text(BandText.scoreCancel[lang]) }
                    Button(onClick = {
                        draft = p
                        onChange(p)
                        proposal = null
                    }) { Text(BandText.scoreApply[lang]) }
                }
            }
        }
    }
}
