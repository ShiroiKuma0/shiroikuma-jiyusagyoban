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
import com.opentasker.core.storage.GengoshimaIslandEntity
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
import kotlinx.coroutines.withContext

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

    @kotlinx.serialization.Serializable
    enum class StepState { WAIT, RUN, DONE, FAIL, SKIP }

    /** The run's steps; a run shows the ones it does ([newProgress]). */
    @kotlinx.serialization.Serializable
    enum class K { ADOPT, TRANSLATE, VOICE, ENGLISH, TIDY, EXPORT, ANKI }

    /**
     * What a run is for: the usual one, a full 暗記 sync on demand, the one-time adoption, or what the
     * island editor's changes need once it closes ([EDITOR]: 整理, the joined files, 暗記 — no Claude,
     * no 音声).
     */
    enum class Mode { GENERATE, FULL_SYNC, ADOPT, EDITOR }

    /** One of the run's steps, as the progress window draws it. */
    @kotlinx.serialization.Serializable
    data class Step(val title: String, val state: StepState = StepState.WAIT, val detail: String = "", val key: K? = null)

    /**
     * The whole run, for the progress window ([com.opentasker.ui.gengoshima.GengoshimaProgressActivity]).
     *
     * Kept after the run ends — with its [result] — until the window is dismissed, so reopening it
     * from the notification an hour later still says how it went (白い熊, 2026-09-30: the run ends on a
     * report and a Close button, like the satellite panel).
     */
    @kotlinx.serialization.Serializable
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

    private fun newProgress(mode: Mode = Mode.GENERATE, anki: Boolean = true) = Progress(
        steps = listOfNotNull(
            Step("暗記から取り込む / Take the notes from 暗記", key = K.ADOPT).takeIf { mode == Mode.ADOPT },
            Step("訳す / Translate", key = K.TRANSLATE).takeIf { mode != Mode.EDITOR },
            Step("音声 / Voice", key = K.VOICE).takeIf { mode != Mode.EDITOR },
            Step("英語の音声 / English voice", key = K.ENGLISH).takeIf { mode != Mode.EDITOR },
            Step("整理 / Tidy the directories", key = K.TIDY),
            Step("辞書用の一本 / Whole-island files for 辞書", key = K.EXPORT),
            Step("暗記と同期 / Sync with 暗記", key = K.ANKI).takeIf { anki || mode != Mode.GENERATE },
        ),
    )

    private fun step(key: K, state: StepState, detail: String? = null, percent: Int? = null) {
        _progress.value = _progress.value?.let { p ->
            p.copy(
                steps = p.steps.map { st -> if (st.key == key) st.copy(state = state, detail = detail ?: st.detail) else st },
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
        context.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE).edit().remove(REPORT_KEY).apply()
    }

    private const val REPORT_PREFS = "gengoshima_report"
    private const val REPORT_KEY = "last"
    private val reportJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * A finished run's outcome is kept on disk until 白い熊 closes it, so it outlives the process: a
     * run that ends while EMUI reaps the app, or long before anyone looks, still reports how it went.
     */
    private fun saveReport(context: Context, p: Progress) {
        runCatching {
            context.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE).edit()
                .putString(REPORT_KEY, reportJson.encodeToString(Progress.serializer(), p)).apply()
        }
    }

    /** Bring back the last unclosed outcome when nothing is in memory (see [saveReport]). */
    fun restore(context: Context) {
        if (_progress.value != null) return
        val text = context.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE).getString(REPORT_KEY, null) ?: return
        runCatching { reportJson.decodeFromString(Progress.serializer(), text) }.getOrNull()?.let { _progress.value = it }
    }

    data class Summary(
        val translated: Int,
        val voiced: Int,
        val failed: Int,
        val tidy: AudioTree.Tidy?,
        val error: String?,
        /** The 暗記 sync's own line, or null when there was none. */
        val anki: String? = null,
        /** An [Mode.EDITOR] run: nothing was translated or voiced, so the line does not say so. */
        val editorOnly: Boolean = false,
    ) {
        fun line(): String = listOfNotNull(
            "訳 $translated · 音声 $voiced".takeUnless { editorOnly },
            "失敗 $failed".takeIf { failed > 0 },
            tidy?.takeIf { it.moved + it.deleted + it.lost > 0 }?.let { "整理 移動 ${it.moved} 削除 ${it.deleted}" },
            anki?.let { "暗記 $it" },
        ).joinToString(" · ").ifEmpty { "変わったものはありません / nothing changed" } +
            (error?.let { " — $it" } ?: "")
    }

    /** Start in the background unless a run is already going. False = one is already running. */
    fun start(context: Context, settings: GengoshimaSettings, mode: Mode = Mode.GENERATE): Boolean {
        if (mutex.isLocked) return false
        // Shown at once, so the window that opens with it is never blank while the run spins up.
        _progress.value = newProgress(mode, settings.ankiSync)
        scope.launch { run(context.applicationContext, settings, mode) }
        return true
    }

    /**
     * 整理 alone — no Claude, no 音声 — for when the island editor closes: a reorder, a move or a
     * delete only renames or removes files, and that should not wait for the next full run. Skipped
     * while a run is going; that run ends in a 整理 of its own.
     */
    fun tidy(context: Context, settings: GengoshimaSettings) {
        // A run the editor's own 「訳して音声を作る」 just started ends in a 整理 and a sync of its own.
        if (_progress.value?.running == true) return
        val app = context.applicationContext
        scope.launch {
            // Anything for 暗記 — a deleted, moved or edited-and-ready sentence, a renamed island — is
            // a run with the progress window and its notification, like any other (白い熊, 2026-10-02);
            // only a change that touches nothing but the files stays quiet.
            if (settings.ankiSync && AnkiIslands.installed(app) && !mutex.isLocked) {
                val dao = OpenTaskerApp_NoHilt.db.gengoshimaDao()
                val plan = AnkiIslands.plan(false, settings, dao.islands(), dao.allSentences(), dao.tombstones(), lastIslandNames(app))
                if (!plan.empty && start(app, settings, Mode.EDITOR)) {
                    app.startActivity(com.opentasker.ui.gengoshima.GengoshimaProgressActivity.intent(app))
                    return@launch
                }
            }
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

    suspend fun run(context: Context, settings: GengoshimaSettings, mode: Mode = Mode.GENERATE): Summary {
        if (!mutex.tryLock()) return Summary(0, 0, 0, null, "already running")
        val app = context.applicationContext
        val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opentasker:gengoshima")
            .apply { acquire(WAKE_MS) }
        _progress.value = newProgress(mode, settings.ankiSync)
        val notes = Notes(app, if (mode == Mode.EDITOR) "言語島：暗記と同期中 / syncing with 暗記" else "言語島：作成中 / generating")
        try {
            val dao = OpenTaskerApp_NoHilt.db.gengoshimaDao()
            val adoptError = if (mode == Mode.ADOPT) adoptFromAnki(app, dao, settings, notes) else null
            val editor = mode == Mode.EDITOR
            val translated = if (editor) Translated(0, 0, null) else translateAll(dao, settings, notes)
            val (voiced, failed, renderError) = if (editor) Voiced(0, 0, null) else voiceAll(app, dao, settings, notes)
            val english = if (editor) Voiced(0, 0, null) else voiceEnglish(app, dao, settings, notes)
            notes.progress("整理 / tidying the directories", -1)
            step(K.TIDY, StepState.RUN, "ディレクトリを付け合わせています / matching the directories to the list", -1)
            val tidy = runCatching {
                AudioTree.reconcile(dao.islands(), dao.allSentences(), settings, dao::updateIsland, dao::updateSentence)
            }.onFailure {
                AppLogger.warn(TAG, "整理 failed: ${it.message}")
                step(K.TIDY, StepState.FAIL, it.message.orEmpty())
            }.getOrNull()
            tidy?.let {
                step(K.TIDY, StepState.DONE, "移動 ${it.moved} · 削除 ${it.deleted}" + if (it.lost > 0) " · 消えていた ${it.lost}" else "")
            }
            // After 整理, so every sentence is at its final path and in its final order.
            step(K.EXPORT, StepState.RUN, "つないでいます / joining", -1)
            runCatching {
                IslandExport.buildAll(dao.islands(), dao.allSentences(), settings) { name -> step(K.EXPORT, StepState.RUN, "つないでいます / joining — $name") }
            }.onSuccess {
                step(K.EXPORT, StepState.DONE, "${it.islands} 島 · 書き換え ${it.rewritten}")
                if (it.rewritten > 0) log("✓ 辞書用: ${it.rewritten} 島を書き換え")
            }.onFailure {
                step(K.EXPORT, StepState.FAIL, it.message.orEmpty())
                log("✕ 辞書用: ${it.message}")
            }
            // Last: 暗記 takes the sentences at their final text, audio and island.
            val anki = if (settings.ankiSync || mode != Mode.GENERATE) {
                notes.progress("暗記 / syncing with 暗記", -1)
                syncAnki(app, dao, settings, full = mode == Mode.FULL_SYNC, quiet = false)
            } else null
            val summary = Summary(
                translated.count, voiced + english.ok, failed + translated.failed + english.failed, tidy,
                adoptError ?: translated.error ?: renderError ?: english.error ?: anki?.error, anki?.line,
                editorOnly = mode == Mode.EDITOR,
            )
            finish(app, summary)
            notes.done(summary)
            AppLogger.info(TAG, "言語島 generation: ${summary.line()}")
            return summary
        } catch (e: Exception) {
            val summary = Summary(0, 0, 0, null, e.message ?: e.javaClass.simpleName)
            log("✕ ${summary.error}")
            finish(app, summary)
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

    private fun finish(app: Context, summary: Summary) {
        lastSummary = summary
        val ok = summary.error == null && summary.failed == 0
        _progress.value = _progress.value?.copy(
            running = false,
            ok = ok,
            result = if (ok) "できました — ${summary.line()}" else "終わらなかったものがあります — ${summary.line()}",
            percent = 100,
            finishedAt = System.currentTimeMillis(),
        )
        _progress.value?.let { saveReport(app, it) }
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
            step(K.TRANSLATE, StepState.SKIP, "訳すものはありません / nothing to translate")
            return Translated(0, 0, null)
        }
        if (s.apiKey.isBlank()) {
            val why = "no API key — set %Gengoshima_ApiKey in 日本語の設定 and run it"
            step(K.TRANSLATE, StepState.FAIL, why)
            log("✕ $why")
            return Translated(0, total, why)
        }
        step(K.TRANSLATE, StepState.RUN, "0 / $total", 0)
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
                        step(K.TRANSLATE, StepState.RUN, "$overall / $total · $detail", overall * 100 / total)
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
            var before = 0
            for (chunk in edited.chunked(CHUNK)) runCatching {
                Translator.annotate(chunk, s) { live ->
                    val (done, last) = Translator.completed(live.text)
                    val overall = before + done.coerceAtMost(chunk.size)
                    step(K.TRANSLATE, StepState.RUN, "手直し / edited $overall / ${edited.size}" + (last?.let { "\n$it" } ?: ""), overall * 100 / edited.size)
                    notes.progress("単語に分ける / splitting $overall / ${edited.size}", overall * 100 / edited.size)
                }
            }.onSuccess { map ->
                before += chunk.size
                val now = System.currentTimeMillis()
                chunk.forEach { sentence ->
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
                log("✓ 手直し $before / ${edited.size} 文")
            }.onFailure { e ->
                before += chunk.size
                failed += chunk.size
                error = e.message
                log("✕ 手直し: ${e.message}")
            }
        }
        step(K.TRANSLATE,
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
            step(K.VOICE, StepState.SKIP, "読ませるものはありません / nothing to voice")
            return Voiced(0, 0, null)
        }
        notes.progress("音声 / voicing 0 / ${jobs.size}", 0)
        step(K.VOICE, StepState.RUN, "白い熊 音声 を起こしています / waking 白い熊 音声 — 0 / ${jobs.size}", 0)
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
                    step(K.VOICE, StepState.RUN, voiceDetail())
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
                        step(K.VOICE, StepState.RUN, voiceDetail())
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
                    step(K.VOICE, StepState.RUN, voiceDetail(), pct)
                }
            } finally {
                ticker.cancel()
            }
        }
        if (!result.ok && OnseWarning.needsAttention(result.result)) OnseWarning.post(app, result.result)
        step(K.VOICE,
            if (result.ok && ok == jobs.size) StepState.DONE else StepState.FAIL,
            "$ok / ${jobs.size}" + if (result.ok) "" else " — ${result.result.removePrefix("ERROR:")}",
            100,
        )
        log(if (result.ok) "✓ 音声 $ok / ${jobs.size}" else "✕ 音声: ${result.result.removePrefix("ERROR:")}")
        // Anything not answered OK failed — including items a cancelled or refused batch never reached.
        return Voiced(ok, jobs.size - ok, if (result.ok) null else result.result.removePrefix("ERROR:"))
    }

    /**
     * The English reading of every sentence (addendum C1, 2026-10-01): 音声's Kokoro voice, a second
     * file beside the Japanese, for 暗記's Production cards and the player's English-first Recall.
     *
     * Asked of 音声 first (PING): without the English model every sentence would fail one by one, so
     * the step is skipped instead, saying why. Kokoro is about twice as slow as real time on the
     * phone (measured: first file ~10 s with the model load, then 5–8 s each), so this step is the
     * long one; the window shows each file as it lands.
     */
    private suspend fun voiceEnglish(app: Context, dao: GengoshimaDao, s: GengoshimaSettings, notes: Notes): Voiced {
        val islands = dao.islands().associateBy { it.id }
        data class Job(val sentence: GengoshimaSentenceEntity, val hash: String, val file: File)
        val jobs = dao.allSentences().mapNotNull { sentence ->
            if (sentence.en.isBlank() || sentence.state != GengoshimaSentenceEntity.STATE_READY) return@mapNotNull null
            val island = islands[sentence.islandId] ?: return@mapNotNull null
            val hash = enAudioHash(sentence.en, s)
            if (sentence.enAudioHash == hash && sentence.enAudioPath.isNotEmpty() && File(sentence.enAudioPath).exists()) return@mapNotNull null
            Job(sentence, hash, AudioTree.sentenceEnFile(island, sentence, s))
        }
        if (jobs.isEmpty()) {
            step(K.ENGLISH, StepState.SKIP, "読ませるものはありません / nothing to voice")
            return Voiced(0, 0, null)
        }
        step(K.ENGLISH, StepState.RUN, "白い熊 音声 に英語の声があるか確かめています / asking 音声 for English", 0)
        val pong = OnseRender.ping(app)
        if (pong == null || !pong.enInstalled) {
            val why = if (pong == null) "白い熊 音声 did not answer" else "English model not installed in 白い熊 音声 (main screen → English voices → ⤓)"
            step(K.ENGLISH, StepState.SKIP, "英語は飛ばしました / skipped — $why")
            log("– 英語: $why")
            return Voiced(0, 0, null)
        }
        log("→ 白い熊 音声 (英語 ${s.enVoice}): ${jobs.size} 文")
        grantTempAllowance(OnseRender.PACKAGE, (120L + jobs.size * 15L) * 1000L)?.let { AppLogger.info(TAG, it) }
        val byId = jobs.associateBy { it.sentence.id.toString() }
        var ok = 0
        val start = System.currentTimeMillis()
        val result = OnseRender.render(
            context = app,
            items = jobs.map { OnseRender.Item(it.sentence.id.toString(), it.sentence.en, it.file.absolutePath) },
            batchDir = File(s.dir),
            voice = OnseRender.Voice(lang = "en", enVoice = s.enVoice, speed = s.enSpeed, bitrateKbps = s.bitrateKbps),
            onSent = { step(K.ENGLISH, StepState.RUN, "英語の声を読み込み、一文目を作っています / loading the English voice\n${jobs[0].sentence.en}") },
        ) { reply ->
            val job = byId[reply.id] ?: return@render
            // Re-read: the Japanese step may have just written this row.
            val row = dao.sentence(job.sentence.id) ?: return@render
            if (reply.ok) {
                ok++
                dao.updateSentence(row.copy(enAudioPath = reply.outPath, enAudioHash = job.hash, enDurationMs = reply.durationMs))
                log("  ♪ en ${reply.index}/${reply.total}  ${reply.durationMs / 100 / 10.0}秒  ${job.sentence.en}")
            } else {
                log("✕ en ${reply.index}/${reply.total}  ${job.sentence.en}: ${reply.error}")
            }
            val pct = reply.index * 100 / reply.total.coerceAtLeast(1)
            val each = (System.currentTimeMillis() - start) / reply.index
            val eta = each * (reply.total - reply.index) / 1000
            val next = jobs.getOrNull(reply.index)?.sentence?.en
            notes.progress("英語 / English ${reply.index} / ${reply.total}", pct)
            step(K.ENGLISH, StepState.RUN, "${reply.index} / ${reply.total}" + (if (eta > 0) " · 残り約 ${eta}秒" else "") + (next?.let { "\n$it" } ?: ""), pct)
        }
        if (!result.ok && OnseWarning.needsAttention(result.result)) OnseWarning.post(app, result.result)
        step(K.ENGLISH, if (result.ok && ok == jobs.size) StepState.DONE else StepState.FAIL,
            "$ok / ${jobs.size}" + if (result.ok) "" else " — ${result.result.removePrefix("ERROR:")}", 100)
        return Voiced(ok, jobs.size - ok, if (result.ok) null else "英語: " + result.result.removePrefix("ERROR:"))
    }

    /** One 暗記 sync's outcome: [line] for the summary, [error] when it did not go through. */
    data class AnkiOutcome(val line: String?, val error: String?)

    private const val ANKI_PREFS = "gengoshima_anki"
    private const val ANKI_NAMES = "island_names"

    private fun lastIslandNames(app: Context): Map<String, String> =
        app.getSharedPreferences(ANKI_PREFS, Context.MODE_PRIVATE).getString(ANKI_NAMES, null)
            ?.lines()?.filter { '\t' in it }?.associate { it.substringBefore('\t') to it.substringAfter('\t') }
            .orEmpty()

    private fun rememberIslandNames(app: Context, names: Map<String, String>) {
        app.getSharedPreferences(ANKI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(ANKI_NAMES, names.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()
    }

    /**
     * Send 暗記 what changed (`delta`) or everything (`full`), and record what it took.
     *
     * Nothing to send is not a sync: the step says so and 暗記 is never woken for it. A sentence 暗記
     * could not apply keeps its old hash, so the next sync sends it again; its reason is logged. The
     * tombstones go only on an `OK` — `ERROR:` means 暗記 changed nothing. [quiet] is the editor's
     * after-close sync: no step, no log, no notification.
     */
    private suspend fun syncAnki(app: Context, dao: GengoshimaDao, s: GengoshimaSettings, full: Boolean, quiet: Boolean): AnkiOutcome? {
        fun show(state: StepState, detail: String, pct: Int? = null) { if (!quiet) step(K.ANKI, state, detail, pct) }
        if (!AnkiIslands.installed(app)) {
            show(StepState.SKIP, "白い熊 暗記 がありません / 暗記 is not installed")
            return null
        }
        val islands = dao.islands()
        val plan = AnkiIslands.plan(full, s, islands, dao.allSentences(), dao.tombstones(), lastIslandNames(app))
        if (plan.empty && !full) {
            show(StepState.SKIP, "変わったものはありません / nothing changed")
            return null
        }
        show(StepState.RUN, "まとめています / packing ${plan.sentences} 文", -1)
        val zip = File(app.cacheDir, "gengoshima-anki-sync.zip")
        return try {
            withContext(Dispatchers.IO) { AnkiIslands.writeZip(plan, zip) }
            grantTempAllowance(AnkiIslands.PACKAGE, 11L * 60 * 1000)?.let { AppLogger.info(TAG, it) }
            if (!quiet) log("→ 白い熊 暗記 (${if (full) "full" else "delta"}): ${plan.sentences} 文" +
                (if (plan.tombstones.isNotEmpty()) " · 削除 ${plan.tombstones.size}" else "") + " · ${zip.length() / 1024} KB")
            show(StepState.RUN, "白い熊 暗記 に渡しています / handing over ${plan.sentences} 文", -1)
            val reply = AnkiIslands.sync(app, zip) { done, total ->
                if (total > 0) show(StepState.RUN, "$done / $total 文", done * 100 / total)
            }
            if (!reply.ok) {
                val why = reply.result.removePrefix("ERROR:")
                show(StepState.FAIL, why)
                if (!quiet) log("✕ 暗記: $why")
                return AnkiOutcome(null, "暗記: $why")
            }
            val missed = reply.errors.map { it.first }.toSet()
            val bySid = plan.sent.keys
            for (id in bySid) {
                val row = dao.sentence(id) ?: continue
                if (row.uuid in missed) continue
                // Only if it still says what was sent — an edit made meanwhile goes next time.
                val island = dao.island(row.islandId) ?: continue
                val hash = AnkiIslands.ankiHash(row, island)
                if (hash == plan.sent[id]) dao.updateSentence(row.copy(ankiHash = hash))
            }
            if (plan.tombstones.isNotEmpty()) dao.clearTombstones(plan.tombstones)
            rememberIslandNames(app, plan.islandNames)
            val (added, updated, moved, deleted) = (reply.result.removePrefix("OK:").split("|") + List(4) { "0" }).take(4)
            val line = "追加 $added · 更新 $updated · 移動 $moved · 削除 $deleted" +
                if (missed.isNotEmpty()) " · 取れなかった ${missed.size}" else ""
            if (!quiet) {
                log("✓ 暗記: $line")
                val text = dao.allSentences().associateBy { it.uuid }
                reply.errors.forEach { (uuid, why) -> log("  ✕ ${text[uuid]?.en ?: uuid}: $why") }
            }
            show(if (missed.isEmpty()) StepState.DONE else StepState.FAIL, line, 100)
            AnkiOutcome(line, if (missed.isEmpty()) null else "暗記: ${missed.size} 文が取られませんでした")
        } catch (e: Exception) {
            show(StepState.FAIL, e.message.orEmpty())
            AnkiOutcome(null, "暗記: ${e.message}")
        } finally {
            zip.delete()
        }
    }

    /**
     * 「暗記から取り込む」: the hand-made deck becomes 言語島 islands, once.
     *
     * Each `Language Islands` note becomes a sentence — its English and Japanese exactly as written
     * there, in note-id order, filed under the island its recognition card's deck names. The
     * Japanese is kept as 白い熊's own (`jaEdited`), so it is only split into words, never
     * re-translated; both recordings are made fresh by the steps that follow. Each sentence keeps
     * the note's id, and the sync at the end of the run hands it back in `adopt`, so 暗記 takes over
     * the existing note — cards, scheduling and review history intact.
     *
     * Running it twice adds nothing: a note already adopted, or a sentence already saying the same
     * thing, is left alone. A note that cannot be taken is listed, never guessed at.
     */
    private suspend fun adoptFromAnki(app: Context, dao: GengoshimaDao, s: GengoshimaSettings, notes: Notes): String? {
        step(K.ADOPT, StepState.RUN, "白い熊 暗記 に尋ねています / asking 暗記 for its notes", -1)
        notes.progress("暗記から取り込む / reading 暗記", -1)
        if (!AnkiIslands.installed(app)) {
            step(K.ADOPT, StepState.FAIL, "白い熊 暗記 がありません / 暗記 is not installed")
            return "暗記 is not installed"
        }
        grantTempAllowance(AnkiIslands.PACKAGE, 3L * 60 * 1000)?.let { AppLogger.info(TAG, it) }
        val file = File(app.cacheDir, "gengoshima-anki-list.json")
        try {
            val reply = AnkiIslands.list(app, file)
            if (!reply.ok) {
                val why = reply.result.removePrefix("ERROR:")
                step(K.ADOPT, StepState.FAIL, why)
                log("✕ 暗記: $why")
                return "暗記: $why"
            }
            val listed = withContext(Dispatchers.IO) { AnkiIslands.parseList(file.readText()) }
            log("← 暗記: ${listed.size} 文")
            val islands = dao.islands().toMutableList()
            val byName = HashMap<String, GengoshimaIslandEntity>()
            islands.forEach { i -> listOf(i.nameJa, i.nameEn).filter { it.isNotBlank() }.forEach { byName.putIfAbsent(it.trim(), i) } }
            val existing = dao.allSentences()
            val byNid = existing.mapNotNull { r -> r.ankiNid?.let { it to r } }.toMap()
            val byText = existing.associateBy { it.en.trim() to it.ja.trim() }
            var newIslands = 0
            var added = 0
            var already = 0
            val misses = ArrayList<String>()
            val now = System.currentTimeMillis()
            for (note in listed) {
                if (note.english.isBlank() || note.japanese.isBlank()) {
                    misses += "nid ${note.nid}: 英語か日本語が空 / an empty field — 「${note.english.ifBlank { note.japanese }}」"
                    continue
                }
                val same = byNid[note.nid] ?: byText[note.english to note.japanese]
                if (same != null) {
                    if (same.ankiNid == null && same.ankiHash.isEmpty()) dao.updateSentence(same.copy(ankiNid = note.nid))
                    already++
                    continue
                }
                val leaf = AnkiIslands.islandOf(note, s)
                val island = byName.getOrPut(leaf) {
                    val pos = dao.lastIslandPosition() + 1
                    val row = GengoshimaIslandEntity(position = pos, nameEn = leaf, nameJa = leaf, createdAt = now)
                    newIslands++
                    log("  + 島 $leaf")
                    row.copy(id = dao.insertIsland(row))
                }
                dao.insertSentence(
                    GengoshimaSentenceEntity(
                        islandId = island.id,
                        position = dao.lastSentencePosition(island.id) + 1,
                        en = note.english,
                        ja = note.japanese,
                        state = GengoshimaSentenceEntity.STATE_ANNOTATE,
                        jaEdited = true,
                        ankiNid = note.nid,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                added++
            }
            misses.forEach { log("  ✕ $it") }
            val line = "島 +$newIslands · 文 +$added" + (if (already > 0) " · 既に $already" else "") +
                if (misses.isNotEmpty()) " · 取れなかった ${misses.size}" else ""
            log("✓ 取り込み: $line")
            step(K.ADOPT, if (misses.isEmpty()) StepState.DONE else StepState.FAIL, line, 100)
            return if (misses.isEmpty()) null else "取り込めなかった注記 ${misses.size}"
        } finally {
            file.delete()
        }
    }

    fun enAudioHash(text: String, s: GengoshimaSettings): String =
        MessageDigest.getInstance("SHA-1").digest("$text|${s.enVoiceKey}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The one notification a run keeps up to date, and replaces with its outcome at the end. */
    private class Notes(private val app: Context, private val title: String) {
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
                    .setContentTitle(title)
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
                    // Stays until 閉じる in the window (or a deliberate swipe): tapping it to read the
                    // outcome must not be what throws the outcome away.
                    .setAutoCancel(false)
                    .build(),
            )
        }
    }
}
