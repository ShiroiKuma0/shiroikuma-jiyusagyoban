package com.sysadmindoc.ruledeck.ui.gengoshima

import com.opentasker.ui.gengoshima.HeadingLine
import com.opentasker.ui.gengoshima.VocabRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.sysadmindoc.ruledeck.core.gengoshima.Translator
import com.sysadmindoc.ruledeck.core.storage.GengoshimaIslandEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaSentenceEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaVocabEntity
import com.sysadmindoc.ruledeck.ui.components.PaperTab
import com.sysadmindoc.ruledeck.ui.components.PaperTabBar
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme

/**
 * 言語島 語彙: the paper tabs, the two heading lists and an entry — before 辞書 has answered, and
 * after, folded and open. Rendered because the phone is normally locked.
 */
private val WORK = GengoshimaIslandEntity(id = 1, position = 1, nameEn = "Work", nameJa = "仕事", createdAt = 0)

private const val JISHO = """{"term":"会議","reading":"かいぎ","found":true,"headword":"会議","headword_reading":"かいぎ",
"pitch":[1,3],"forms":["會議"],"pos":["n","vs","vi"],
"glosses":[{"dictionary":"Jitendex.org [2026-10-03]","senses":["meeting; conference; session; assembly","council; convention; congress"]},
{"dictionary":"JMdict [2026-10-01]","senses":["meeting; conference; session; assembly; council; convention; congress"]}],
"frequency":412,"tags":[{"name":"★","notes":"priority form"}],
"kanji":[{"char":"会","meanings":["meeting","meet","party","association"],"on":["カイ","エ"],"kun":["あ.う","あ.わせる"],"strokes":6,"grade":2,"jlpt":4,"frequency":12},
{"char":"議","meanings":["deliberation","consultation","debate"],"on":["ギ"],"kun":[],"strokes":20,"grade":4,"jlpt":2,"frequency":101}],
"examples":[{"ja":"会議は終わった。","en":"The meeting is over."},{"ja":"会議は来週に延期された。","en":"The meeting was put off until next week."}],
"conjugation_class":"vs","conjugation":[{"form":"Non-past","plain":"会議する","plain_negative":"会議しない","polite":"会議します","polite_negative":"会議しません"}]}"""

private fun tokens(vararg t: Pair<String, String>) =
    Translator.tokensJson(t.map { (surface, reading) -> Translator.Token(surface, base = surface, reading = reading) })

private val EXAMPLES = listOf(
    GengoshimaSentenceEntity(
        id = 1, islandId = 1, position = 15, en = "The meeting starts at three.", ja = "会議は三時からです。",
        tokensJson = tokens("会議" to "カイギ", "は" to "", "三時" to "サンジ", "から" to "", "です" to "", "。" to ""),
        state = GengoshimaSentenceEntity.STATE_READY, audioPath = "/x.ogg", vocabId = 1, createdAt = 0, updatedAt = 0,
    ),
    GengoshimaSentenceEntity(
        id = 2, islandId = 1, position = 16, en = "Sorry I am late for the meeting.", ja = "会議に遅れてすみません。",
        state = GengoshimaSentenceEntity.STATE_ANNOTATE, vocabId = 1, createdAt = 0, updatedAt = 0,
    ),
)

private fun kaigi(jisho: String = "") = VocabEntry(
    GengoshimaVocabEntity(
        id = 1, islandId = 1, position = 1, term = "会議", reading = "かいぎ", meaning = "meeting; conference",
        note = "formal; 打ち合わせ is the casual word", starred = true, jishoJson = jisho, jishoAt = if (jisho.isEmpty()) 0 else 1,
        createdAt = 0, updatedAt = 0,
    ),
    WORK, EXAMPLES,
)

private fun plain(id: Long, term: String, reading: String, meaning: String) = VocabEntry(
    GengoshimaVocabEntity(id = id, islandId = 1, position = id.toInt(), term = term, reading = reading, meaning = meaning, createdAt = 0, updatedAt = 0),
    WORK, emptyList(),
)

private val LIST = listOf(kaigi(), plain(2, "資料", "しりょう", "materials; documents"), plain(3, "締め切り", "しめきり", "deadline"), plain(4, "メール", "", "e-mail"))

private val TABS = listOf(PaperTab(0, "全部", 31), PaperTab(1, "仕事", 12), PaperTab(2, "相撲", 19), PaperTab(3, "健康", 0))

@Composable
private fun Frame(content: @Composable () -> Unit) {
    RuleDeckTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(widthDp = 400)
@Composable
fun VocabHeadingsJaEn() = Frame {
    PaperTabBar(TABS, selectedId = 1, onSelect = {})
    LIST.forEach { HeadingLine(VocabRow(it, null), englishFirst = false, showIsland = false) {}; HorizontalDivider() }
}

@PreviewTest
@Preview(widthDp = 400)
@Composable
fun VocabHeadingsEnJa() = Frame {
    PaperTabBar(TABS, selectedId = 0, onSelect = {})
    listOf("conference" to LIST[0], "deadline" to LIST[2], "documents" to LIST[1], "meeting" to LIST[0]).forEach { (sense, e) ->
        HeadingLine(VocabRow(e, sense), englishFirst = true, showIsland = true) {}; HorizontalDivider()
    }
}

@PreviewTest
@Preview(widthDp = 400)
@Composable
fun VocabCardBeforeJisho() = Frame {
    val player = remember { CardPlayer() }
    VocabCard(kaigi(), emptyList(), englishFirst = false, showIsland = false, jishoOpen = false, onJishoToggle = {}, player = player, onStar = {}, onShare = {})
}

@PreviewTest
@Preview(widthDp = 400)
@Composable
fun VocabCardJishoFolded() = Frame {
    val player = remember { CardPlayer() }
    VocabCard(kaigi(JISHO), emptyList(), englishFirst = false, showIsland = true, jishoOpen = false, onJishoToggle = {}, player = player, onStar = {}, onShare = {})
}

@PreviewTest
@Preview(widthDp = 400, heightDp = 1100)
@Composable
fun VocabCardJishoOpen() = Frame {
    val player = remember { CardPlayer() }
    VocabCard(kaigi(JISHO), emptyList(), englishFirst = false, showIsland = false, jishoOpen = true, onJishoToggle = null, player = player, onStar = {}, onShare = {})
}
