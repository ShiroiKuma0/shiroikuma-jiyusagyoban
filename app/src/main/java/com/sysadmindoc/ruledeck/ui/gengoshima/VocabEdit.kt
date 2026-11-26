package com.sysadmindoc.ruledeck.ui.gengoshima

import com.opentasker.ui.gengoshima.GengoshimaVocabActivity
import com.opentasker.ui.gengoshima.hiragana
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.core.gengoshima.Translator
import com.sysadmindoc.ruledeck.core.gengoshima.VocabImport
import com.sysadmindoc.ruledeck.core.storage.GengoshimaDao
import com.sysadmindoc.ruledeck.core.storage.GengoshimaIslandEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaSentenceEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaVocabEntity
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.components.AlertDialog
import com.sysadmindoc.ruledeck.ui.components.Button
import com.sysadmindoc.ruledeck.ui.components.OutlinedButton
import kotlinx.coroutines.launch

/** What 白い熊 writes about a term: the four fields of an entry and the island it is filed under. */
internal data class VocabDraft(
    val term: String,
    val reading: String,
    val meaning: String,
    val note: String,
    val islandId: Long,
)

/**
 * Changing 語彙 by hand — a term added from a word of a sentence, an entry corrected on its card, an
 * example written under it. The same rule as the import holds throughout: nothing that is already
 * there is entered twice. A term is the same term when it is written the same, whatever its island;
 * a sentence is the same sentence when its Japanese is ([VocabImport.sentenceKey]).
 */
internal object VocabEdits {

    /** The term already filed under this spelling, in any island. */
    suspend fun existing(dao: GengoshimaDao, term: String): GengoshimaVocabEntity? =
        dao.allVocab().firstOrNull { it.term == term.trim() }

    /**
     * File a new term, with [exampleId] — the sentence it was picked out of — as its first example
     * when that sentence is not some other term's example already. Returns the term's id; an
     * existing term is returned as it stands, untouched.
     */
    suspend fun add(dao: GengoshimaDao, draft: VocabDraft, exampleId: Long?): Long {
        existing(dao, draft.term)?.let { return it.id }
        val now = System.currentTimeMillis()
        val id = dao.insertVocab(
            GengoshimaVocabEntity(
                islandId = draft.islandId,
                position = dao.lastVocabPosition(draft.islandId) + 1,
                term = draft.term.trim(),
                reading = draft.reading.trim(),
                meaning = draft.meaning.trim(),
                note = draft.note.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        exampleId?.let { dao.sentence(it) }?.takeIf { it.vocabId == null }
            ?.let { dao.updateSentence(it.copy(vocabId = id, updatedAt = now)) }
        return id
    }

    /**
     * Store a corrected entry. A move to another island puts the term last there and closes the gap
     * it leaves; its example sentences stay in the island they are in. A changed spelling or reading
     * makes 辞書's cached answer someone else's, so it is dropped and asked for again.
     */
    suspend fun save(dao: GengoshimaDao, vocab: GengoshimaVocabEntity, draft: VocabDraft) {
        val moved = draft.islandId != vocab.islandId
        val renamed = draft.term.trim() != vocab.term || draft.reading.trim() != vocab.reading
        dao.updateVocab(
            vocab.copy(
                islandId = draft.islandId,
                position = if (moved) dao.lastVocabPosition(draft.islandId) + 1 else vocab.position,
                term = draft.term.trim(),
                reading = draft.reading.trim(),
                meaning = draft.meaning.trim(),
                note = draft.note.trim(),
                jishoJson = if (renamed) "" else vocab.jishoJson,
                jishoAt = if (renamed) 0 else vocab.jishoAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (moved) dao.vocabOf(vocab.islandId).forEachIndexed { k, v ->
            if (v.position != k + 1) dao.updateVocab(v.copy(position = k + 1))
        }
    }

    /** What [addExample] did with the sentence it was given. */
    enum class Example { ADDED, LINKED, TAKEN }

    /**
     * An example under [vocab]. A sentence already in 言語島 is not written a second time: it becomes
     * this term's example ([Example.LINKED]) unless it is another term's ([Example.TAKEN]). A new one
     * goes last in the term's island exactly as an imported one does — 白い熊's Japanese kept as
     * written, voiced as it stands, split into words afterwards.
     */
    suspend fun addExample(dao: GengoshimaDao, vocab: GengoshimaVocabEntity, ja: String, en: String): Pair<Example, Long> {
        val now = System.currentTimeMillis()
        val key = VocabImport.sentenceKey(ja)
        dao.allSentences().firstOrNull { VocabImport.sentenceKey(it.ja) == key }?.let { had ->
            if (had.vocabId != null && had.vocabId != vocab.id) return Example.TAKEN to had.id
            if (had.vocabId == null) dao.updateSentence(had.copy(vocabId = vocab.id, updatedAt = now))
            return Example.LINKED to had.id
        }
        val id = dao.insertSentence(
            GengoshimaSentenceEntity(
                islandId = vocab.islandId,
                position = dao.lastSentencePosition(vocab.islandId) + 1,
                en = en.trim(),
                ja = ja.trim(),
                state = GengoshimaSentenceEntity.STATE_TRANSLATED,
                jaEdited = true,
                vocabId = vocab.id,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return Example.ADDED to id
    }
}

/** Ask 辞書 about every term it has not been asked about yet — outliving the dialog that added one. */
internal fun askJisho(app: Context) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        runCatching { com.sysadmindoc.ruledeck.core.gengoshima.VocabJisho.fetch(app, RuleDeckApp_NoHilt.db.gengoshimaDao(), all = false) }
    }
}

/** The terms opened last, newest first — what the 「最近」 tab lists. Kept between openings. */
internal class VocabRecent(context: Context) {
    private val prefs = context.getSharedPreferences("gengoshima_vocab_ui", Context.MODE_PRIVATE)
    var ids by mutableStateOf(prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() })
        private set

    fun viewed(id: Long) {
        if (ids.firstOrNull() == id) return
        ids = (listOf(id) + ids.filter { it != id }).take(KEPT)
        prefs.edit().putString(KEY, ids.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "recent"
        const val KEPT = 60
    }
}

/** The entry's four fields and its island. 訳 is mandatory, as it is in an import. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VocabEditDialog(
    title: String,
    initial: VocabDraft,
    islands: List<GengoshimaIslandEntity>,
    onDismiss: () -> Unit,
    onSave: (VocabDraft) -> Unit,
) {
    var term by remember { mutableStateOf(initial.term) }
    var reading by remember { mutableStateOf(initial.reading) }
    var meaning by remember { mutableStateOf(initial.meaning) }
    var note by remember { mutableStateOf(initial.note) }
    var islandId by remember { mutableStateOf(initial.islandId) }
    val ready = term.isNotBlank() && meaning.isNotBlank() && islands.any { it.id == islandId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("語 / term")) })
                OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("読み（ひらがな）/ reading")) })
                OutlinedTextField(meaning, { meaning = it }, Modifier.fillMaxWidth(), label = { Text(tr("訳 — 複数は ; で / translation, senses split by ;")) })
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(tr("注 / note")) })
                Text(tr("島 / island"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    islands.forEach { i ->
                        TogglePill(i.nameJa.ifBlank { i.nameEn }, i.id == islandId, choice = true) { islandId = i.id }
                    }
                }
                if (meaning.isBlank()) Text(tr("訳が要ります / a translation is required"), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(VocabDraft(term, reading, meaning, note, islandId)) }, enabled = ready) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** A new example for a term: the Japanese and its English, both mandatory. */
@Composable
internal fun ExampleAddDialog(term: String, onDismiss: () -> Unit, onSave: (ja: String, en: String) -> Unit) {
    var ja by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$term」の例文を追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ja, { ja = it }, Modifier.fillMaxWidth(), label = { Text("日本語") })
                OutlinedTextField(en, { en = it }, Modifier.fillMaxWidth(), label = { Text(tr("English")) })
                Text(
                    tr("島の最後に普通の文として入り、音声が付きます。\nIt joins the island as an ordinary sentence and is voiced."),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(ja, en) }, enabled = ja.isNotBlank() && en.isNotBlank()) { Text("追加") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * 「語彙に追加」 for one word of a sentence — a long press on it in the player or the island editor.
 * The word is offered in its dictionary form, with the reading the sentence gives it when the word
 * stands there in that form, and the sentence's island; the sentence becomes its first example. A
 * word that is filed already is not offered again: its entry opens instead.
 */
@Composable
internal fun AddTermFromWord(token: Translator.Token, sentenceId: Long, islandId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { RuleDeckApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val term = token.base.ifBlank { token.surface }
    var islands by remember { mutableStateOf<List<GengoshimaIslandEntity>?>(null) }
    androidx.compose.runtime.LaunchedEffect(term) {
        val had = VocabEdits.existing(dao, term)
        if (had != null) {
            context.startActivity(GengoshimaVocabActivity.intent(context, had.id))
            onDone()
        } else {
            islands = dao.islands()
        }
    }
    val list = islands ?: return
    val reading = if (token.base.isBlank() || token.base == token.surface) {
        hiragana(token.reading_override?.takeIf { it.isNotBlank() } ?: token.reading).takeIf { it != term }.orEmpty()
    } else ""
    VocabEditDialog(
        title = "語彙に追加",
        initial = VocabDraft(term, reading, "", "", islandId),
        islands = list,
        onDismiss = onDone,
        onSave = { draft ->
            scope.launch {
                VocabEdits.add(dao, draft, sentenceId.takeIf { it > 0 })
                askJisho(context.applicationContext)
                onDone()
            }
        },
    )
}
