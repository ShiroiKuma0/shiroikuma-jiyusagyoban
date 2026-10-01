package com.opentasker.core.gengoshima

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.core.actions.grantTempAllowance
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.onse.OnseRender
import com.opentasker.core.onse.OnseWarning
import com.opentasker.core.storage.GengoshimaDao
import com.opentasker.core.storage.GengoshimaSentenceEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 言語島's generation: every sentence that needs it is translated, then voiced, then the tree is
 * tidied (整理). Runs by itself when the entry screen closes, and on demand from 「言語島 生成」.
 *
 * Shaped like the satellite build (`huawei.pgnss`): its own scope, so closing a window or ending the
 * task that started it does not abandon half a batch; a partial wake lock bounded in time; progress
 * on a notification rather than in a variable nobody watches. One run at a time — a second start
 * while one is going joins nothing and says so.
 *
 * **Each sentence is saved the moment it is done.** A translation is written as soon as its island's
 * answer is in; a file is marked ready on 音声's own per-item reply. So a run cut off halfway keeps
 * everything it finished, and the next run picks up exactly what is left.
 */
object GenerationRunner {

    private const val TAG = "Gengoshima"
    private const val CHANNEL = "gengoshima"
    private const val CHANNEL_NAME = "言語島"
    private const val NOTIFICATION_ID = 0x6E60
    private const val WAKE_MS = 45L * 60 * 1000

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** What the runner is doing now, one line, for the entry screen; null when idle. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    enum class StepState { WAIT, RUN, DONE, FAIL, SKIP }

    /** One of the run's three steps, as the progress window draws it. */
    data class Step(val title: String, val state: StepState = StepState.WAIT, val detail: String = "")

    /**
     * The whole run, for the progress window ([com.opentasker.ui.gengoshima.GengoshimaProgressActivity]).
     *
     * Kept after the run ends — with its [result] — until the window is dismissed, so reopening it
     * from the notification an hour later still says how it went (白い熊, 2026-09-30: the run ends on a
     * report and a Close button, like the satellite panel).
     */
    data class Progress(
        val steps: List<Step>,
        val percent: Int = -1,
        val log: List<String> = emptyList(),
        val running: Boolean = true,
        val ok: Boolean? = null,
        val result: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long? = null,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private fun newProgress() = Progress(
        steps = listOf(
            Step("訳す / Translate"),
            Step("音声 / Voice"),
            Step("整理 / Tidy the directories"),
            Step("辞書用の一本 / Whole-island files for 辞書"),
        ),
    )

    private fun step(i: Int, state: StepState, detail: String? = null, percent: Int? = null) {
        _progress.value = _progress.value?.let { p ->
            p.copy(
                steps = p.steps.mapIndexed { k, st -> if (k == i) st.copy(state = state, detail = detail ?: st.detail) else st },
                percent = percent ?: p.percent,
            )
        }
    }

    private fun log(line: String) {
        _progress.value = _progress.value?.let { it.copy(log = (it.log + line).takeLast(600)) }
    }

    /**
     * The window was closed on a finished run: forget it and take the notification down with it.
     * A running one is never dismissed this way — closing the window only hides it.
     */
    fun dismiss(context: Context) {
        if (_progress.value?.running == true) return
        _progress.value = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    data class Summary(
        val translated: Int,
        val voiced: Int,
        val failed: Int,
        val tidy: AudioTree.Tidy?,
        val error: String?,
    ) {
        fun line(): String = buildString {
            append("訳 $translated · 音声 $voiced")
            if (failed > 0) append(" · 失敗 $failed")
            tidy?.let { if (it.moved + it.deleted + it.lost > 0) append(" · 整理 移動 ${it.moved} 削除 ${it.deleted}") }
            error?.let { append(" — ").append(it) }
        }
    }

    /** Start in the background unless a run is already going. False = one is already running. */
    fun start(context: Context, settings: GengoshimaSettings): Boolean {
        if (mutex.isLocked) return false
        // Shown at once, so the window that opens with it is never blank while the run spins up.
        _progress.value = newProgress()
        scope.launch { run(context.applicationContext, settings) }
        return true
    }

    /**
     * 整理 alone — no Claude, no 音声 — for when the island editor closes: a reorder, a move or a
     * delete only renames or removes files, and that should not wait for the next full run. Skipped
     * while a run is going; that run ends in a 整理 of its own.
     */
    fun tidy(context: Context, settings: GengoshimaSettings) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                val dao = OpenTaskerApp_NoHilt.db.gengoshimaDao()
                runCatching {
                    AudioTree.reconcile(dao.islands(), dao.allSentences(), settings, dao::updateIsland, dao::updateSentence)
                }.onSuccess { AppLogger.info(TAG, "言語島 整理: moved ${it.moved}, deleted ${it.deleted}, lost ${it.lost}") }
                    .onFailure { AppLogger.warn(TAG, "言語島 整理 failed: ${it.message}") }
                // A reorder or a deletion changes the joined island too.
                runCatching { IslandExport.buildAll(dao.islands(), dao.allSentences(), settings) }
                    .onFailure { AppLogger.warn(TAG, "言語島 辞書用 failed: ${it.message}") }
            } finally {
                mutex.unlock()
            }
        }
    }

    suspend fun run(context: Context, settings: GengoshimaSettings): Summary {
        if (!mutex.tryLock()) return Summary(0, 0, 0, null, "already running")
        val app = context.applicationContext
        val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opentasker:gengoshima")
            .apply { acquire(WAKE_MS) }
        _progress.value = newProgress()
        val notes = Notes(app)
        try {
            val dao = OpenTaskerApp_NoHilt.db.gengoshimaDao()
            val translated = translateAll(dao, settings, notes)
            val (voiced, failed, renderError) = voiceAll(app, dao, settings, notes)
            notes.progress("整理 / tidying the directories", -1)
            step(2, StepState.RUN, "ディレクトリを付け合わせています / matching the directories to the list", -1)
            val tidy = runCatching {
                AudioTree.reconcile(dao.islands(), dao.allSentences(), settings, dao::updateIsland, dao::updateSentence)
            }.onFailure {
                AppLogger.warn(TAG, "整理 failed: ${it.message}")
                step(2, StepState.FAIL, it.message.orEmpty())
            }.getOrNull()
            tidy?.let {
                step(2, StepState.DONE, "移動 ${it.moved} · 削除 ${it.deleted}" + if (it.lost > 0) " · 消えていた ${it.lost}" else "")
            }
            // After 整理, so every sentence is at its final path and in its final order.
            step(3, StepState.RUN, "つないでいます / joining", -1)
            runCatching {
                IslandExport.buildAll(dao.islands(), dao.allSentences(), settings) { name -> step(3, StepState.RUN, "つないでいます / joining — $name") }
            }.onSuccess {
                step(3, StepState.DONE, "${it.islands} 島 · 書き換え ${it.rewritten}")
                if (it.rewritten > 0) log("✓ 辞書用: ${it.rewritten} 島を書き換え")
            }.onFailure {
                step(3, StepState.FAIL, it.message.orEmpty())
                log("✕ 辞書用: ${it.message}")
            }
            val summary = Summary(translated.count, voiced, failed + translated.failed, tidy, translated.error ?: renderError)
            finish(summary)
            notes.done(summary)
            AppLogger.info(TAG, "言語島 generation: ${summary.line()}")
            return summary
        } catch (e: Exception) {
            val summary = Summary(0, 0, 0, null, e.message ?: e.javaClass.simpleName)
            log("✕ ${summary.error}")
            finish(summary)
            notes.done(summary)
            return summary
        } finally {
            _status.value = null
            runCatching { if (wake.isHeld) wake.release() }
            mutex.unlock()
        }
    }

    /** The last finished run's summary, for a caller that waited on [progress]. */
    @Volatile
    var lastSummary: Summary? = null
        private set

    private fun finish(summary: Summary) {
        lastSummary = summary
        val ok = summary.error == null && summary.failed == 0
        _progress.value = _progress.value?.copy(
            running = false,
            ok = ok,
            result = if (ok) "できました — ${summary.line()}" else "終わらなかったものがあります — ${summary.line()}",
            percent = 100,
            finishedAt = System.currentTimeMillis(),
        )
    }

    private data class Translated(val count: Int, val failed: Int, val error: String?)

    /** Every island with an untranslated sentence gets one call; the answer is saved at once. */
    /**
     * Sentences per Claude call. An island can hold a hundred; one call for all of them is minutes
     * of silence and a single failure that loses the lot, so it goes in chunks — each chunk carries
     * everything already translated as context, so the monologue still reads as one.
     */
    private const val CHUNK = 20

    /**
     * Every island with an untranslated sentence is sent in chunks; each answer is saved at once.
     *
     * Streamed, and reported as it streams (白い熊, 2026-09-30: "we need to see how far along we
     * are"): while Claude is still deciding, the window shows its thinking summary and the seconds
     * spent; once it writes, the count moves sentence by sentence with the line just written.
     */
    private suspend fun translateAll(dao: GengoshimaDao, s: GengoshimaSettings, notes: Notes): Translated {
        var count = 0
        var failed = 0
        var error: String? = null
        val islands = dao.islands()
        val waiting = islands.associate { i -> i.id to dao.sentences(i.id).count { it.state == GengoshimaSentenceEntity.STATE_NEW && !it.jaEdited } }
        val total = waiting.values.sum()
        val anyEdited = dao.allSentences().any { it.state == GengoshimaSentenceEntity.STATE_ANNOTATE }
        if (total == 0 && !anyEdited) {
            step(0, StepState.SKIP, "訳すものはありません / nothing to translate")
            return Translated(0, 0, null)
        }
        if (s.apiKey.isBlank()) {
            val why = "no API key — set %Gengoshima_ApiKey in 日本語の設定 and run it"
            step(0, StepState.FAIL, why)
            log("✕ $why")
            return Translated(0, total, why)
        }
        step(0, StepState.RUN, "0 / $total", 0)
        for (islandRow in islands) {
            if (total == 0) break
            var island = islandRow
            val chunks = dao.sentences(island.id)
                .filter { it.state == GengoshimaSentenceEntity.STATE_NEW && !it.jaEdited }
                .chunked(CHUNK)
            for ((k, pending) in chunks.withIndex()) {
                val all = dao.sentences(island.id) // fresh: the previous chunk's Japanese is context now
                val name = island.nameJa.ifBlank { island.nameEn }
                val part = if (chunks.size > 1) " (${k + 1}/${chunks.size})" else ""
                notes.progress("訳す / translating $count / $total — $name", count * 100 / total)
                log("→ Claude: $name$part — ${pending.size} 文")
                val started = System.currentTimeMillis()
                var shown = 0
                val answer = runCatching {
                    Translator.translate(island, all, pending, s) { live ->
                        val secs = (System.currentTimeMillis() - started) / 1000
                        val (done, last) = Translator.completed(live.text)
                        val detail = if (live.phase == "thinking") {
                            val thought = live.thinking.trim().lineSequence().lastOrNull { it.isNotBlank() }?.take(140)
                            "$name$part — 考えています / thinking… ${secs}秒" + (thought?.let { "\n💭 $it" } ?: "")
                        } else {
                            "$name$part — 書いています / writing ${done} / ${pending.size} · ${secs}秒" +
                                (last?.let { "\n$it" } ?: "")
                        }
                        val overall = count + done.coerceAtMost(pending.size)
                        step(0, StepState.RUN, "$overall / $total · $detail", overall * 100 / total)
                        // Each finished line into the log as it lands — the "CLI output".
                        if (done > shown && last != null) {
                            shown = done
                            log("  ${count + done}/$total  $last")
                        }
                        notes.progress("訳す / translating $overall / $total — $name", overall * 100 / total)
                    }
                }.getOrElse { e ->
                    failed += pending.size
                    error = e.message
                    val now = System.currentTimeMillis()
                    pending.forEach { dao.updateSentence(it.copy(error = "訳: ${e.message}".take(300), updatedAt = now)) }
                    log("✕ $name$part: ${e.message}")
                    continue
                }
                if (island.nameJa.isBlank() && answer.island_name_ja.isNotBlank()) {
                    island = island.copy(nameJa = answer.island_name_ja.trim())
                    dao.updateIsland(island)
                }
                val byId = answer.sentences.associateBy { it.id }
                val now = System.currentTimeMillis()
                for (sentence in pending) {
                    val line = byId[sentence.id]
                    if (line == null || line.ja.isBlank()) {
                        failed++
                        dao.updateSentence(sentence.copy(error = "訳: missing from Claude's answer", updatedAt = now))
                        continue
                    }
                    dao.updateSentence(
                        sentence.copy(
                            ja = line.ja.trim(),
                            tokensJson = Translator.tokensJson(line.tokens),
                            state = GengoshimaSentenceEntity.STATE_TRANSLATED,
                            error = "",
                            updatedAt = now,
                        ),
                    )
                    count++
                }
                val secs = (System.currentTimeMillis() - started) / 1000
                log("✓ ${island.nameJa.ifBlank { island.nameEn }}$part: ${pending.size} 文 · ${secs}秒")
            }
        }
        // Hand-edited Japanese: split into words again (the text itself is kept exactly).
        val edited = dao.allSentences().filter { it.state == GengoshimaSentenceEntity.STATE_ANNOTATE }
        if (edited.isNotEmpty()) {
            log("→ Claude: 手直しした日本語 ${edited.size} 文を単語に分ける / re-splitting ${edited.size} edited")
            runCatching {
                Translator.annotate(edited, s) { live ->
                    val (done, last) = Translator.completed(live.text)
                    step(0, StepState.RUN, "手直し / edited ${done} / ${edited.size}" + (last?.let { "\n$it" } ?: ""))
                }
            }.onSuccess { map ->
                val now = System.currentTimeMillis()
                edited.forEach { sentence ->
                    map[sentence.id]?.let { tokens ->
                        dao.updateSentence(
                            sentence.copy(
                                tokensJson = Translator.tokensJson(tokens),
                                state = GengoshimaSentenceEntity.STATE_TRANSLATED,
                                error = "",
                                updatedAt = now,
                            ),
                        )
                        count++
                    }
                }
                log("✓ 手直し ${edited.size} 文")
            }.onFailure { e ->
                failed += edited.size
                error = e.message
                log("✕ 手直し: ${e.message}")
            }
        }
        step(
            0,
            if (failed == 0) StepState.DONE else StepState.FAIL,
            "$count / ${total + edited.size}" + if (failed > 0) " — 失敗 $failed: $error" else "",
            100,
        )
        return Translated(count, failed, error)
    }

    fun audioHash(text: String, s: GengoshimaSettings): String =
        MessageDigest.getInstance("SHA-1").digest("$text|${s.voiceKey}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class Voiced(val ok: Int, val failed: Int, val error: String?)

    /**
     * Every translated sentence whose file is missing, or says something else than it should (the
     * text, a reading or the voice changed), goes to 音声 in ONE batch, in island order.
     */
    private suspend fun voiceAll(app: Context, dao: GengoshimaDao, s: GengoshimaSettings, notes: Notes): Voiced {
        val islands = dao.islands().associateBy { it.id }
        data class Job(val sentence: GengoshimaSentenceEntity, val text: String, val hash: String, val file: File)
        val jobs = dao.allSentences().mapNotNull { sentence ->
            if (sentence.ja.isBlank()) return@mapNotNull null
            if (sentence.state != GengoshimaSentenceEntity.STATE_TRANSLATED &&
                sentence.state != GengoshimaSentenceEntity.STATE_READY &&
                sentence.state != GengoshimaSentenceEntity.STATE_ERROR
            ) return@mapNotNull null
            val island = islands[sentence.islandId] ?: return@mapNotNull null
            val text = Translator.speechText(sentence.ja, sentence.tokensJson)
            val hash = audioHash(text, s)
            val fresh = sentence.audioHash == hash && sentence.audioPath.isNotEmpty() && File(sentence.audioPath).exists()
            if (fresh) {
                if (sentence.state != GengoshimaSentenceEntity.STATE_READY) {
                    dao.updateSentence(sentence.copy(state = GengoshimaSentenceEntity.STATE_READY, error = ""))
                }
                return@mapNotNull null
            }
            Job(sentence, text, hash, AudioTree.sentenceFile(island, sentence, s))
        }
        if (jobs.isEmpty()) {
            step(1, StepState.SKIP, "読ませるものはありません / nothing to voice")
            return Voiced(0, 0, null)
        }
        notes.progress("音声 / voicing 0 / ${jobs.size}", 0)
        step(1, StepState.RUN, "白い熊 音声 を起こしています / waking 白い熊 音声 — 0 / ${jobs.size}", 0)
        log("音声: ${jobs.size} 文…")
        grantTempAllowance(OnseRender.PACKAGE, (120L + jobs.size * 30L) * 1000L)?.let { AppLogger.info(TAG, it) }
        val byId = jobs.associateBy { it.sentence.id.toString() }
        var ok = 0
        var failed = 0
        // What the window says between replies. 音声 answers once per FINISHED file, so without a
        // ticker the line would sit still for the whole of each synthesis — and the first one also
        // loads the voice (白い熊, 2026-09-30: step 2 needs to show how far along it is too).
        val renderStart = System.currentTimeMillis()
        var sent = false
        var done = 0
        var firstAt: Long? = null
        var lastAt = renderStart
        fun voiceDetail(): String {
            val now = System.currentTimeMillis()
            val total = jobs.size
            if (done == 0) {
                val secs = (now - renderStart) / 1000
                return if (!sent) "白い熊 音声 を起こしています / waking 白い熊 音声 · ${secs}秒"
                else "声を読み込み、一文目を作っています / loading the voice, making 1 / $total · ${secs}秒\n${jobs[0].sentence.ja}"
            }
            if (done >= total) return "$done / $total"
            // Per-file pace from the files after the first, which alone carries the model load.
            val each = firstAt?.let { f -> if (done >= 2) (lastAt - f) / (done - 1) else (lastAt - renderStart) / done }
                ?: 0L
            val eta = each * (total - done) / 1000
            val since = (now - lastAt) / 1000
            return "作成中 / making ${done + 1} / $total · ${since}秒" +
                (if (eta > 0) " · 残り約 ${eta}秒 / ~${eta}s left" else "") +
                "\n${jobs[done].sentence.ja}"
        }
        val result = kotlinx.coroutines.coroutineScope {
            val ticker = launch {
                while (true) {
                    step(1, StepState.RUN, voiceDetail())
                    kotlinx.coroutines.delay(1_000)
                }
            }
            try {
                OnseRender.render(
                    context = app,
                    items = jobs.map { OnseRender.Item(it.sentence.id.toString(), it.text, it.file.absolutePath) },
                    batchDir = File(s.dir),
                    voice = OnseRender.Voice(
                        speaker = s.speaker,
                        speed = s.speed,
                        pitch = s.pitch,
                        intonation = s.intonation,
                        gapPre = "0",
                        gapPost = s.gap,
                        bitrateKbps = s.bitrateKbps,
                    ),
                    onSent = {
                        sent = true
                        log("→ 白い熊 音声: ${jobs.size} 文")
                        step(1, StepState.RUN, voiceDetail())
                    },
                ) { reply ->
                    val job = byId[reply.id] ?: return@render
                    val now = System.currentTimeMillis()
                    val took = (now - lastAt) / 100 / 10.0
                    if (reply.ok) {
                        ok++
                        // A re-voice replaces the old file atomically at the NEW path; if the
                        // sentence's name changed, the old file is now a stray that 整理 removes.
                        dao.updateSentence(
                            job.sentence.copy(
                                state = GengoshimaSentenceEntity.STATE_READY,
                                audioPath = reply.outPath,
                                audioHash = job.hash,
                                durationMs = reply.durationMs,
                                error = "",
                                updatedAt = now,
                            ),
                        )
                        log("  ♪ ${reply.index}/${reply.total}  ${reply.durationMs / 100 / 10.0}秒の音声 · 作成 ${took}秒  ${job.sentence.ja}")
                    } else {
                        failed++
                        dao.updateSentence(
                            job.sentence.copy(
                                state = GengoshimaSentenceEntity.STATE_ERROR,
                                error = "音声: ${reply.error.orEmpty()}".take(300),
                                updatedAt = now,
                            ),
                        )
                        log("✕ ${reply.index}/${reply.total}  ${job.sentence.ja}: ${reply.error}")
                    }
                    done = reply.index
                    if (firstAt == null) firstAt = now
                    lastAt = now
                    val pct = reply.index * 100 / reply.total.coerceAtLeast(1)
                    notes.progress("音声 / voicing ${reply.index} / ${reply.total}", pct)
                    step(1, StepState.RUN, voiceDetail(), pct)
                }
            } finally {
                ticker.cancel()
            }
        }
        if (!result.ok && OnseWarning.needsAttention(result.result)) OnseWarning.post(app, result.result)
        step(
            1,
            if (result.ok && ok == jobs.size) StepState.DONE else StepState.FAIL,
            "$ok / ${jobs.size}" + if (result.ok) "" else " — ${result.result.removePrefix("ERROR:")}",
            100,
        )
        log(if (result.ok) "✓ 音声 $ok / ${jobs.size}" else "✕ 音声: ${result.result.removePrefix("ERROR:")}")
        // Anything not answered OK failed — including items a cancelled or refused batch never reached.
        return Voiced(ok, jobs.size - ok, if (result.ok) null else result.result.removePrefix("ERROR:"))
    }

    /** The one notification a run keeps up to date, and replaces with its outcome at the end. */
    private class Notes(private val app: Context) {
        /** Tapping it, running or finished, brings the progress window back — from any app. */
        fun openWindow(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            com.opentasker.ui.gengoshima.GengoshimaProgressActivity.intent(app),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        private val nm = app.getSystemService(NotificationManager::class.java)
        private val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        private var last = 0L

        init {
            if (allowed) nm?.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
        }

        fun progress(text: String, percent: Int) {
            _status.value = text
            if (!allowed) return
            val now = System.currentTimeMillis()
            if (percent in 1..99 && now - last < 1_000) return
            last = now
            nm?.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(ContextCompat.getColor(app, R.color.notification_accent))
                    .setContentTitle("言語島：作成中 / generating")
                    .setContentText(text)
                    .setContentIntent(openWindow())
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                    .build(),
            )
        }

        fun done(summary: Summary) {
            if (!allowed) return
            val bad = summary.error != null || summary.failed > 0
            nm?.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(ContextCompat.getColor(app, R.color.notification_accent))
                    .setContentTitle(if (bad) "言語島：終わらなかったものがあります" else "言語島：できました / done")
                    .setContentText(summary.line())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary.line()))
                    .setContentIntent(openWindow())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
