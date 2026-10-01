package com.opentasker.ui.gengoshima

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.GengoshimaPlaybackService
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_EN
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_GAP_UNTIL
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_ISLAND_ID
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_PAUSE_AFTER_MS
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_REPEAT
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_REPEATS
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_SENTENCE_ID
import com.opentasker.core.gengoshima.GengoshimaPlaybackService.Companion.EXTRA_TOKENS
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.core.gengoshima.Translator
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaPlayEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaSessionEntity
import com.opentasker.ui.components.SelectionChip
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「言語島 聴く」 — choosing what to hear, then hearing it on one giant screen.
 *
 * Phone screen only (白い熊, 2026-09-30): no Android Auto; in the car the phone sits on its mount and
 * this window is what shows — huge play/pause, the line being spoken with furigana, its English
 * beneath, and a tap on any word looks it up in 白い熊の辞書. It shows over the lock screen and keeps
 * the screen on while playing. The steering wheel and a headset drive it through the media session.
 *
 * Three modes, Mikel Hyperpolyglot's routine:
 * * **聴く / Listen** — the island straight through.
 * * **シャドーイング / Shadow ×N** — each sentence N times, with room after each to say it back.
 * * **思い出す / Recall** — the English first, a pause to say it in Japanese, then the audio.
 *
 * Order is entry order (sentences flow on from each other), or whole islands shuffled — never single
 * sentences, which would break every monologue apart.
 *
 * What was played is logged here, in the main process: the player itself runs in its own process
 * and touches no database (see [GengoshimaPlaybackService]).
 */
class GengoshimaPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Over the lock screen, and awake — this is the screen on the mount in the car.
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlayerRoot(onClose = { finish() })
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

enum class ListenMode(val label: String) { LISTEN("聴く"), SHADOW("シャドーイング"), RECALL("思い出す") }

/** The running session's log, kept across window re-creation (it lives as long as the process). */
private object ListenLog {
    var sessionId: Long? = null
    var startedAt: Long = 0
    var mode: ListenMode = ListenMode.LISTEN
    var islandIds: String = ""
    val plays = HashMap<Long, Int>()
    var listenedMs: Long = 0
    var lastItemStart: Long = 0
    /** The item being heard now: its sentence and its length. */
    var currentSentence: Long? = null
    var currentDurationMs: Long = 0

    /**
     * Close the item that was playing: credit the time actually spent on it, and count it as HEARD
     * only if most of it was. Skipping with "next" must not count (白い熊's first sessions: seven
     * sentences "played" in three seconds, which also passed as a full review of the island).
     */
    /** Time the current item has actually been PLAYING — a pause mid-sentence is not listening. */
    var heardMs: Long = 0
    var playingSince: Long? = null

    fun playing(isPlaying: Boolean, now: Long) {
        if (isPlaying) {
            if (playingSince == null) playingSince = now
        } else {
            playingSince?.let { heardMs += now - it }
            playingSince = null
        }
    }

    fun closeItem(now: Long) {
        val sid = currentSentence ?: return
        val spent = heardMs + (playingSince?.let { now - it } ?: 0L)
        val length = currentDurationMs.takeIf { it > 0 } ?: 60_000L
        listenedMs += minOf(spent, length)
        if (spent >= length * 6 / 10) plays[sid] = (plays[sid] ?: 0) + 1
        currentSentence = null
    }

    fun openItem(sid: Long?, durationMs: Long, now: Long, isPlaying: Boolean) {
        currentSentence = sid
        currentDurationMs = durationMs
        lastItemStart = now
        heardMs = 0
        playingSince = if (isPlaying) now else null
    }

    fun reset() {
        sessionId = null
        plays.clear()
        listenedMs = 0
        lastItemStart = 0
        currentSentence = null
        currentDurationMs = 0
        heardMs = 0
        playingSince = null
    }
}

@Composable
private fun PlayerRoot(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, GengoshimaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(future) }
    }
    val c = controller
    if (c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("…") }
        return
    }
    // Reopened from the notification while something is queued: straight back to it.
    var playing by remember { mutableStateOf(c.mediaItemCount > 0) }
    if (playing) {
        PlayingScreen(c, onStop = {
            finishSession(c)
            c.stop()
            c.clearMediaItems()
            playing = false
        }, onClose = onClose)
    } else {
        PickerScreen(onStart = { items, mode, islands ->
            startSession(mode, islands)
            c.setMediaItems(items)
            c.prepare()
            c.play()
            playing = true
        }, onClose = onClose)
    }
}

private fun startSession(mode: ListenMode, islands: List<Long>) {
    ListenLog.reset()
    ListenLog.mode = mode
    ListenLog.startedAt = System.currentTimeMillis()
    ListenLog.islandIds = islands.joinToString(",")
    ListenLog.lastItemStart = ListenLog.startedAt
    OpenTaskerApp_NoHilt.readyDb?.let { db ->
        kotlinx.coroutines.runBlocking {
            ListenLog.sessionId = db.gengoshimaDao().insertSession(
                GengoshimaSessionEntity(
                    startedAt = ListenLog.startedAt,
                    mode = mode.name.lowercase(),
                    islandIds = ListenLog.islandIds,
                ),
            )
        }
    }
}

/** Write the session's end, its played counts and minutes. Called on stop and on window close. */
private fun finishSession(c: MediaController? = null) {
    val id = ListenLog.sessionId ?: return
    ListenLog.closeItem(System.currentTimeMillis())
    val db = OpenTaskerApp_NoHilt.readyDb ?: return
    kotlinx.coroutines.runBlocking {
        val dao = db.gengoshimaDao()
        dao.updateSession(
            GengoshimaSessionEntity(
                id = id,
                startedAt = ListenLog.startedAt,
                endedAt = System.currentTimeMillis(),
                mode = ListenLog.mode.name.lowercase(),
                islandIds = ListenLog.islandIds,
                sentencesPlayed = ListenLog.plays.size,
                listenedMs = ListenLog.listenedMs,
            ),
        )
        dao.upsertPlays(ListenLog.plays.map { (sid, n) -> GengoshimaPlayEntity(id, sid, n) })
        // An island every sentence of which was heard in this session counts as one review of it
        // (spaced rotation — see Rotation). Half an island reviews nothing: it is one monologue.
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        ListenLog.islandIds.split(",").mapNotNull { it.toLongOrNull() }.forEach { islandId ->
            val island = dao.island(islandId) ?: return@forEach
            val ready = dao.sentences(islandId).filter { it.state == GengoshimaSentenceEntity.STATE_READY }.map { it.id }
            if (ready.isNotEmpty() && ready.all { it in ListenLog.plays }) {
                dao.updateIsland(com.opentasker.core.gengoshima.Rotation.review(island, ListenLog.mode.name.lowercase(), today, zone))
            }
        }
    }
    ListenLog.reset()
}

// ── the picker ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PickerScreen(onStart: (List<MediaItem>, ListenMode, List<Long>) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    val counts by dao.observeCounts().collectAsState(initial = emptyList())
    val ready = counts.associate { it.islandId to it.ready }
    val chosen = remember { mutableStateMapOf<Long, Boolean>() }
    var mode by remember { mutableStateOf(ListenMode.LISTEN) }
    var shuffle by remember { mutableStateOf(false) }
    val settings = remember { GengoshimaSettings.last(context) }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("言語島 — 聴く", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
        }
        Text("島 / Islands", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(islands, key = { it.id }) { i ->
                val n = ready[i.id] ?: 0
                val on = chosen[i.id] ?: (n > 0)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = n > 0) { chosen[i.id] = !on },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on && n > 0, onCheckedChange = { chosen[i.id] = it }, enabled = n > 0)
                    Column {
                        Text("${i.position}. ${i.nameJa.ifBlank { i.nameEn }}", fontSize = 18.sp)
                        Text(
                            "${i.nameEn} · $n 文" + (i.nextReview?.let { " · 次 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" } ?: " · 未復習 / never reviewed"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Spaced rotation's pick: every island due today, and every island never yet reviewed.
        val zone = remember { java.time.ZoneId.systemDefault() }
        val today = remember { java.time.LocalDate.now(zone) }
        val due = islands.filter { (ready[it.id] ?: 0) > 0 && com.opentasker.core.gengoshima.Rotation.isDue(it, today, zone) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { islands.forEach { chosen[it.id] = it in due } }, enabled = due.isNotEmpty()) {
                Text("今日の島 (${due.size}) / due today")
            }
            OutlinedButton(onClick = { islands.forEach { chosen[it.id] = true } }) { Text("全部 / all") }
        }
        Text("やり方 / Mode", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListenMode.entries.forEach { m ->
                SelectionChip(
                    label = if (m == ListenMode.SHADOW) "${m.label} ×${settings.shadowRepeats}" else m.label,
                    selected = mode == m,
                    onSelect = { mode = m },
                )
            }
        }
        Text("順番 / Order", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionChip(label = "入力順", selected = !shuffle, onSelect = { shuffle = false })
            SelectionChip(label = "島をシャッフル", selected = shuffle, onSelect = { shuffle = true })
        }
        val picked = islands.filter { (chosen[it.id] ?: ((ready[it.id] ?: 0) > 0)) && (ready[it.id] ?: 0) > 0 }
        Button(
            onClick = {
                scope.launch {
                    val order = if (shuffle) picked.shuffled() else picked
                    val sentences = order.flatMap { i -> dao.sentences(i.id).filter { it.state == GengoshimaSentenceEntity.STATE_READY && File(it.audioPath).exists() }.map { i to it } }
                    if (sentences.isEmpty()) return@launch
                    onStart(queue(sentences, mode, settings), mode, order.map { it.id })
                }
            },
            enabled = picked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) { Text("再生 / Play", fontSize = 22.sp) }
    }
}

/**
 * The queue, with every pause the mode needs written onto the item it follows.
 *
 * Shadow: each sentence [GengoshimaSettings.shadowRepeats] times, each followed by room to say it —
 * its own length times [GengoshimaSettings.shadowPauseFactor]. Recall: after each sentence, room to
 * recall the NEXT one from its English before it is heard.
 */
internal fun queue(
    sentences: List<Pair<GengoshimaIslandEntity, GengoshimaSentenceEntity>>,
    mode: ListenMode,
    s: GengoshimaSettings,
): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    sentences.forEachIndexed { k, (island, sentence) ->
        val reps = if (mode == ListenMode.SHADOW) s.shadowRepeats.coerceIn(1, 20) else 1
        val next = sentences.getOrNull(k + 1)?.second
        for (r in 1..reps) {
            val pause = when (mode) {
                ListenMode.LISTEN -> 0L
                ListenMode.SHADOW -> (sentence.durationMs * s.shadowPauseFactor).toLong()
                ListenMode.RECALL -> if (next == null) 0L else (next.durationMs * s.shadowPauseFactor * 1.5).toLong() + 1_000L
            }
            out += MediaItem.Builder()
                .setMediaId("${sentence.id}#$r")
                .setUri(Uri.fromFile(File(sentence.audioPath)))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(sentence.ja)
                        .setArtist(sentence.en)
                        .setAlbumTitle(island.nameJa.ifBlank { island.nameEn })
                        .setExtras(
                            Bundle().apply {
                                putLong(EXTRA_SENTENCE_ID, sentence.id)
                                putLong(EXTRA_ISLAND_ID, island.id)
                                putLong(EXTRA_PAUSE_AFTER_MS, pause)
                                putString(EXTRA_EN, sentence.en)
                                putString(EXTRA_TOKENS, sentence.tokensJson)
                                putInt(EXTRA_REPEAT, r)
                                putInt(EXTRA_REPEATS, reps)
                                putLong(com.opentasker.core.gengoshima.GengoshimaPlaybackService.EXTRA_DURATION_MS, sentence.durationMs)
                            },
                        )
                        .build(),
                )
                .build()
        }
    }
    return out
}

// ── playing ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayingScreen(c: MediaController, onStop: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var index by remember { mutableIntStateOf(c.currentMediaItemIndex) }
    var isPlaying by remember { mutableStateOf(c.isPlaying) }
    var ended by remember { mutableStateOf(c.playbackState == Player.STATE_ENDED) }
    var gapUntil by remember { mutableLongStateOf(c.sessionExtras.getLong(EXTRA_GAP_UNTIL, 0L)) }
    var showEn by remember { mutableStateOf(ListenLog.mode != ListenMode.RECALL) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(c) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val t = System.currentTimeMillis()
                ListenLog.closeItem(t)
                val ex = mediaItem?.mediaMetadata?.extras
                ListenLog.openItem(ex?.getLong(EXTRA_SENTENCE_ID), ex?.getLong(com.opentasker.core.gengoshima.GengoshimaPlaybackService.EXTRA_DURATION_MS) ?: 0L, t, c.isPlaying)
                index = c.currentMediaItemIndex
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                ListenLog.playing(playing, System.currentTimeMillis())
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (ended) ListenLog.closeItem(System.currentTimeMillis())
            }
        }
        c.addListener(listener)
        // The first item never transitions IN, so it is opened here.
        if (ListenLog.currentSentence == null && ListenLog.plays.isEmpty()) {
            val ex = c.currentMediaItem?.mediaMetadata?.extras
            ListenLog.openItem(ex?.getLong(EXTRA_SENTENCE_ID), ex?.getLong(com.opentasker.core.gengoshima.GengoshimaPlaybackService.EXTRA_DURATION_MS) ?: 0L, System.currentTimeMillis(), c.isPlaying)
        }
        onDispose { c.removeListener(listener) }
    }
    // Session extras arrive through the controller; read them on a light tick along with the clock.
    LaunchedEffect(c) {
        while (true) {
            now = System.currentTimeMillis()
            gapUntil = c.sessionExtras.getLong(EXTRA_GAP_UNTIL, 0L)
            delay(250)
        }
    }

    val count = c.mediaItemCount
    if (count == 0) return
    val item = c.getMediaItemAt(index.coerceIn(0, count - 1))
    val extras = item.mediaMetadata.extras ?: Bundle()
    val inGap = gapUntil > now
    // During a Recall gap the line to show is the one coming NEXT: say it before you hear it.
    val shown = if (inGap && ListenLog.mode == ListenMode.RECALL && index + 1 < count) c.getMediaItemAt(index + 1) else item
    val shownExtras = shown.mediaMetadata.extras ?: Bundle()
    val ja = shown.mediaMetadata.title?.toString().orEmpty()
    val en = shownExtras.getString(EXTRA_EN).orEmpty()
    val tokens = Translator.tokens(shownExtras.getString(EXTRA_TOKENS).orEmpty())
    val sentenceIds = (0 until count).map { c.getMediaItemAt(it).mediaMetadata.extras?.getLong(EXTRA_SENTENCE_ID) ?: 0L }
    val distinct = sentenceIds.distinct()
    val sentenceNo = distinct.indexOf(extras.getLong(EXTRA_SENTENCE_ID)) + 1
    val hideJa = ListenLog.mode == ListenMode.RECALL && inGap

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.mediaMetadata.albumTitle?.toString().orEmpty(),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                )
                val rep = extras.getInt(EXTRA_REPEATS, 1)
                Text(
                    "${ListenLog.mode.label} · $sentenceNo / ${distinct.size}" +
                        if (rep > 1) " · ${extras.getInt(EXTRA_REPEAT, 1)} / $rep 回" else "",
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onStop() }) { Text("止める", fontSize = 18.sp) }
            TextButton(onClick = { finishSession(); c.stop(); c.clearMediaItems(); onClose() }) { Text("閉じる", fontSize = 18.sp) }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (inGap) {
                Text(
                    when (ListenLog.mode) {
                        ListenMode.RECALL -> "日本語で言ってみて / say it in Japanese · ${(gapUntil - now + 999) / 1000}"
                        else -> "言ってみて / your turn · ${(gapUntil - now + 999) / 1000}"
                    },
                    fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (hideJa) {
                Text("…", fontSize = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FuriganaLine(ja, tokens) { word ->
                    c.pause()
                    lookUp(context, word)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (showEn || hideJa) {
                Text(en, fontSize = 22.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showEn = !showEn }) { Text(if (showEn) "英語を隠す / hide English" else "英語を出す / show English") }
        }

        if (ended) Text("おしまい / finished", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(Icons.Filled.SkipPrevious, "前 / previous", 76.dp) { c.seekToPrevious() }
            BigButton(Icons.Filled.Replay, "もう一度 / replay", 76.dp) {
                // The start of this sentence, whichever repeat is playing.
                var k = c.currentMediaItemIndex
                val sid = sentenceIds.getOrNull(k)
                while (k > 0 && sentenceIds[k - 1] == sid) k--
                c.seekTo(k, 0L)
                c.play()
            }
            BigButton(Icons.Filled.SkipNext, "次 / next", 76.dp) { c.seekToNext() }
        }
        BigButton(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (isPlaying) "一時停止 / pause" else "再生 / play",
            150.dp,
            Modifier.align(Alignment.CenterHorizontally),
        ) {
            if (isPlaying || inGap) c.pause() else {
                if (ended) c.seekTo(0, 0L)
                c.play()
            }
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * The Japanese line, word by word, with furigana over every word that holds a kanji — and each word
 * a button: tapping it looks up its DICTIONARY form (白い熊, 2026-09-30).
 */
@Composable
private fun FuriganaLine(ja: String, tokens: List<Translator.Token>, onWord: (String) -> Unit) {
    if (tokens.isEmpty() || tokens.joinToString("") { it.surface } != ja) {
        Text(ja, fontSize = 36.sp, textAlign = TextAlign.Center, lineHeight = 48.sp)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        tokens.forEach { t ->
            val reading = (t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading)
            val ruby = if (t.surface.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) hiragana(reading) else ""
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(enabled = t.base.isNotBlank() || t.surface.isNotBlank()) { onWord(t.base.ifBlank { t.surface }) }
                    .padding(horizontal = 1.dp),
            ) {
                Text(ruby, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.surface, fontSize = 36.sp)
            }
        }
    }
}

internal fun hiragana(katakana: String): String = buildString {
    for (ch in katakana) append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch)
}

/** Hand one word to 白い熊の辞書 — the same "look up" any app's text selection offers. */
private fun lookUp(context: Context, word: String) {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        .setPackage("shiroikuma.jisho")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
