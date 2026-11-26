package com.sysadmindoc.ruledeck.core.gengoshima

import android.content.Context
import com.sysadmindoc.ruledeck.core.jisho.JishoLookup
import com.sysadmindoc.ruledeck.core.storage.GengoshimaDao

/**
 * What 白い熊の辞書 knows about each 語彙 term — pitch accent, kanji, glosses, further examples —
 * asked once per term through its lookup door and kept in `gengoshima_vocab.jishoJson`, so the
 * cards never wake 辞書. A miss is kept too (`found:false`), so it is not asked again on every run;
 * 「辞書を更新」 asks for everything afresh.
 */
object VocabJisho {

    data class Outcome(val asked: Int, val found: Int, val error: String?) {
        val line get() = "聞いた $asked · 見つかった $found" + (error?.let { " — $it" } ?: "")
    }

    /** Terms still to ask about — [all] = every term, as 「辞書を更新」 wants. */
    suspend fun pending(dao: GengoshimaDao, all: Boolean = false) = if (all) dao.allVocab() else dao.vocabWithoutJisho()

    suspend fun fetch(
        context: Context,
        dao: GengoshimaDao,
        all: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val terms = pending(dao, all)
        if (terms.isEmpty()) return Outcome(0, 0, null)
        if (!JishoLookup.supported(context)) return Outcome(0, 0, "辞書: 未対応 / this 辞書 has no lookup door (needs 1.6.0+033)")
        var asked = 0
        var found = 0
        for (batch in terms.chunked(JishoLookup.MAX_ITEMS)) {
            onProgress(asked, terms.size)
            val items = batch.map { JishoLookup.Item(it.term, it.reading) }
            var answer = JishoLookup.lookup(context, items)
            // The contract's one retry, for a start-up failure or a timeout.
            if (answer is JishoLookup.Answer.Failed && answer.retryable) answer = JishoLookup.lookup(context, items)
            when (answer) {
                is JishoLookup.Answer.Failed -> return Outcome(asked, found, "辞書: ${answer.reason}")
                is JishoLookup.Answer.Ok -> {
                    val now = System.currentTimeMillis()
                    batch.zip(answer.items).forEach { (term, item) ->
                        // Read again: the term may have been edited or deleted while 辞書 was working.
                        val current = dao.vocab(term.id) ?: return@forEach
                        dao.updateVocab(current.copy(jishoJson = item.toString(), jishoAt = now))
                        if (item["found"]?.toString() == "true") found++
                    }
                    asked += batch.size
                }
            }
        }
        onProgress(asked, terms.size)
        return Outcome(asked, found, null)
    }
}
