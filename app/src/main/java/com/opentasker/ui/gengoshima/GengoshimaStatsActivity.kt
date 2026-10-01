package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.ListeningStats
import com.opentasker.core.gengoshima.Rotation
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaSessionEntity
import com.opentasker.ui.charts.ANNOTATION_INK
import com.opentasker.ui.charts.DayGrid
import com.opentasker.ui.charts.DayGridCell
import com.opentasker.ui.charts.DayGridStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「言語島 統計」 — how much, when, how far along, and what is due.
 *
 * * **Totals** — days in a row, the longest run, time listened, sessions, sentences and islands.
 * * **The calendar** — the same day calendar as 機能訓練: a yellow day is a day listened to, its
 *   minutes in the corner; tap a day for its sessions with their start and end times.
 * * **Time of day** — when the listening actually happens, hour by hour.
 * * **Per island** — sentences voiced, how often the least-played sentence has been heard, its last
 *   and next review, and ● on the ones due today.
 */
class GengoshimaStatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatsScreen(onClose = { finish() })
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaStatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The rest-day fill 機能訓練 uses, so one calendar looks like the other. */
private val REST = Color(0xFF2A2A2A)

@Composable
private fun StatsScreen(onClose: () -> Unit) {
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val sessions by dao.observeSessions().collectAsState(initial = emptyList())
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    val sentences by dao.observeAllSentences().collectAsState(initial = emptyList())
    val plays by dao.observePlays().collectAsState(initial = emptyList())
    val stats = remember(sessions) { ListeningStats.summarise(sessions, zone, today) }
    var picked by remember { mutableStateOf<Long?>(today.toEpochDay()) }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            Text("言語島 — 統計", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
        }

        Card("合計 / Totals") {
            val ready = sentences.count { it.state == GengoshimaSentenceEntity.STATE_READY }
            Line("連続 / in a row", "${stats.streak} 日" + if (stats.longest > stats.streak) "（最長 ${stats.longest} 日）" else "")
            Line("聴いた時間 / listened", ListeningStats.duration(stats.totalMs))
            Line("回数 / sessions", "${stats.sessions}")
            Line("文 / sentences", "$ready / ${sentences.size} 音声あり")
            Line("島 / islands", "${islands.size}")
        }

        Card("毎日 / Every day") {
            val first = sessions.minOfOrNull { it.startedAt }
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?: today.minusWeeks(3)
            val from = first.minusWeeks(0).with(DayOfWeek.MONDAY).toEpochDay()
            val cells = (from..today.toEpochDay()).map { day ->
                val ms = stats.perDay[day] ?: 0L
                val on = ms > 0
                DayGridCell(
                    epochDay = day,
                    fill = if (on) ANNOTATION_INK else REST,
                    ink = if (on) Color.Black else ANNOTATION_INK.copy(alpha = 0.8f),
                    bold = on,
                    badge = if (on) "${(ms + 59_999) / 60_000}" else null,
                )
            }
            DayGrid(days = cells, zone = zone, onTap = { picked = it }, gridStyle = DayGridStyle.DAYS)
            Text("黄色の日 = 聴いた日、角の数字 = 分。/ Yellow = a day listened to; the corner number is minutes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            picked?.let { day -> DaySessions(day, sessions, islands, zone) }
        }

        Card("時間帯 / Time of day") {
            HourBars(stats.perHour)
        }

        Card("島ごと / Per island") {
            val playsBy = plays.associate { it.sentenceId to it.plays }
            val fmt = DateTimeFormatter.ofPattern("M/d")
            islands.forEach { i ->
                val mine = sentences.filter { it.islandId == i.id }
                val ready = mine.filter { it.state == GengoshimaSentenceEntity.STATE_READY }
                val least = ready.minOfOrNull { playsBy[it.id] ?: 0 } ?: 0
                val due = ready.isNotEmpty() && Rotation.isDue(i, today, zone)
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        (if (due) "● " else "") + "${i.position}. ${i.nameJa.ifBlank { i.nameEn }}",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = if (due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${statusLabel(i.status)} · 音声 ${ready.size}/${mine.size} · 各文 最少 ${least} 回" +
                            " · 前回 ${i.lastReview?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: "—"}" +
                            " · 次 ${i.nextReview?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: "今日 / today"}" +
                            (if (i.intervalDays >= 1) " · 間隔 ${i.intervalDays.toInt()} 日" else ""),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("● = 今日の島（復習の日）。島の全文を一度に聴くと一回の復習になる。\n● = due today. Hearing every sentence of an island in one session counts as one review.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusLabel(s: String) = when (s) {
    "new" -> "新しい / new"
    "shadowed" -> "シャドーイング済 / shadowed"
    "recalled" -> "思い出せた / recalled"
    "rotating" -> "定着・巡回中 / rotating"
    else -> s
}

@Composable
private fun DaySessions(day: Long, sessions: List<GengoshimaSessionEntity>, islands: List<GengoshimaIslandEntity>, zone: ZoneId) {
    val date = LocalDate.ofEpochDay(day)
    val those = sessions.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == date }
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val names = islands.associate { it.id to it.nameJa.ifBlank { it.nameEn } }
    Text(date.format(DateTimeFormatter.ofPattern("M月d日（E）", java.util.Locale.JAPANESE)), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    if (those.isEmpty()) {
        Text("この日は聴いていません / nothing that day", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    those.forEach { s ->
        val start = Instant.ofEpochMilli(s.startedAt).atZone(zone).format(hm)
        val end = s.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(hm) } ?: "…"
        val mode = when (s.mode) { "shadow" -> "シャドーイング"; "recall" -> "思い出す"; else -> "聴く" }
        val where = s.islandIds.split(",").mapNotNull { it.toLongOrNull()?.let(names::get) }.joinToString("・")
        Text("$start–$end · $mode · ${ListeningStats.duration(ListeningStats.listened(s))} · ${s.sentencesPlayed} 文 · $where", fontSize = 14.sp)
    }
}

/** 24 bars of minutes by clock hour, yellow on black — one hue, so no reading depends on colour. */
@Composable
private fun HourBars(perHour: LongArray) {
    val max = (perHour.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val ink = ANNOTATION_INK
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width / 24f
        for (h in 0 until 24) {
            val frac = perHour[h].toFloat() / max
            val barH = (size.height - 2f) * frac
            drawRect(ink, topLeft = Offset(h * w + w * 0.15f, size.height - barH), size = Size(w * 0.7f, barH))
        }
        drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
    }
    Row(Modifier.fillMaxWidth()) {
        listOf(0, 6, 12, 18).forEach { h -> Text("${h}時", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
    }
    val peak = perHour.indices.maxByOrNull { perHour[it] }
    if (peak != null && perHour[peak] > 0) {
        Text("いちばん多いのは ${peak}時台 / most listening at $peak:00", fontSize = 13.sp)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
