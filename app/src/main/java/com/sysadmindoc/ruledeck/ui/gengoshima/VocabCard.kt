package com.sysadmindoc.ruledeck.ui.gengoshima

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.ruledeck.core.gengoshima.Translator
import com.sysadmindoc.ruledeck.core.gengoshima.VocabSearch
import com.sysadmindoc.ruledeck.core.jisho.JishoEntry
import com.sysadmindoc.ruledeck.core.storage.GengoshimaIslandEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaSentenceEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaVocabEntity

/** One 語彙 term with what belongs to it: its island and its example sentences, in island order. */
internal data class VocabEntry(
    val vocab: GengoshimaVocabEntity,
    val island: GengoshimaIslandEntity?,
    val examples: List<GengoshimaSentenceEntity>,
) {
    val jisho: JishoEntry? by lazy { JishoEntry.parse(vocab.jishoJson) }
}

/** Secondary text: the same ink as everything else, lighter — 辞書's rule of one colour. */
private const val SOFT = 0.65f

/**
 * A term's entry, laid out as 白い熊の辞書 lays out a heading — headword with its reading beside it
 * (drawn as the pitch pattern once 辞書 has told us it), small boxed markers — but with **白い熊's
 * own content first**: their translation, their note, their example sentences. What 辞書 knows comes
 * after, under a 「辞書」 row: folded when [jishoOpen] is false and opened by [onJishoToggle] (the
 * continuous list), always open when [onJishoToggle] is null (the single-term view).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VocabCard(
    entry: VocabEntry,
    others: List<Pair<GengoshimaIslandEntity?, GengoshimaSentenceEntity>>,
    englishFirst: Boolean,
    showIsland: Boolean,
    jishoOpen: Boolean,
    onJishoToggle: (() -> Unit)?,
    player: CardPlayer,
    onStar: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val v = entry.vocab
    val jisho = entry.jisho?.takeIf { it.found }
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)) {
                if (englishFirst) Text(v.meaning, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(v.term, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                    val reading = v.reading.ifBlank { jisho?.headwordReading.orEmpty() }
                    if (jisho != null && jisho.pitch.isNotEmpty() && (reading.isNotBlank() || v.term.isNotBlank())) {
                        jisho.pitch.forEach { PitchReading(VocabSearch.hiragana(reading.ifBlank { v.term }), it, Modifier.alignByBaseline()) }
                    } else if (reading.isNotBlank()) {
                        Text(reading, fontSize = 20.sp, color = ink.copy(alpha = SOFT), modifier = Modifier.alignByBaseline())
                    }
                    if (jisho != null) {
                        jisho.tags.forEach { Marker(it.name, Modifier.alignByBaseline()) }
                        jisho.frequency?.let { Marker("#$it", Modifier.alignByBaseline()) }
                    }
                    if (showIsland && entry.island != null) Marker(entry.island.nameJa.ifBlank { entry.island.nameEn }, Modifier.alignByBaseline())
                }
                if (!englishFirst) Text(v.meaning, fontSize = 20.sp)
            }
            IconButton(onClick = onStar) {
                Icon(
                    if (v.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (v.starred) "印を外す / unstar" else "印を付ける / star",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "共有 / share", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (v.note.isNotBlank()) Text(v.note, fontSize = 15.sp, color = ink.copy(alpha = SOFT))

        entry.examples.forEach { ExampleLine(it, v.term, null, player) }

        if (others.isNotEmpty()) {
            Text("他の文にも / also in", fontSize = 13.sp, color = ink.copy(alpha = SOFT), modifier = Modifier.padding(top = 4.dp))
            others.take(OTHERS_SHOWN).forEach { (island, s) -> ExampleLine(s, v.term, island?.let { it.nameJa.ifBlank { it.nameEn } }, player) }
            if (others.size > OTHERS_SHOWN) Text("　… 他 ${others.size - OTHERS_SHOWN} 文", fontSize = 13.sp, color = ink.copy(alpha = SOFT))
        }

        // ── what 辞書 knows ──
        val open = onJishoToggle == null || jishoOpen
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onJishoToggle != null) Modifier.clickable(onClick = onJishoToggle) else Modifier)
                .padding(top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (onJishoToggle == null) "辞書" else if (open) "辞書 ▾" else "辞書 ▸") +
                    when {
                        v.jishoAt == 0L -> "　まだ聞いていません / not asked yet"
                        jisho == null -> "　見出しなし / no heading"
                        open -> ""
                        else -> "　" + listOfNotNull(
                            jisho.pos.take(3).joinToString(" ").takeIf { it.isNotBlank() },
                            jisho.glosses.firstOrNull()?.senses?.firstOrNull(),
                        ).joinToString(" · ")
                    },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (open && jisho != null) JishoSection(jisho, v.term)
    }
}

private const val OTHERS_SHOWN = 5

/** One sentence under a term: a play button, the Japanese with furigana and the term in bold, the source beneath. */
@Composable
private fun ExampleLine(s: GengoshimaSentenceEntity, term: String, island: String?, player: CardPlayer) {
    val ink = MaterialTheme.colorScheme.onSurface
    val playable = s.audioPath.isNotBlank()
    Row(verticalAlignment = Alignment.Top) {
        IconButton(onClick = { player.toggle(s.id, false, s.audioPath) }, enabled = playable, modifier = Modifier.size(40.dp)) {
            Icon(
                if (player.playing == s.id to false) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = "再生 / play",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (playable) 1f else 0.35f),
            )
        }
        Column(Modifier.weight(1f)) {
            val tokens = remember(s.tokensJson) { Translator.tokens(s.tokensJson) }
            RubyLine(s.ja, tokens, term)
            Text(
                s.en + (island?.let { "　— $it" } ?: "") + if (playable) "" else "　（音声待ち / not voiced yet）",
                fontSize = 14.sp,
                color = ink.copy(alpha = SOFT),
            )
        }
    }
}

/**
 * A Japanese line word by word, furigana over every word that holds a kanji, the words of [term] in
 * bold. Without a word split that still spells the line (a sentence not yet through its run) the
 * plain text, [term] in bold where it stands.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RubyLine(ja: String, tokens: List<Translator.Token>, term: String, fontSize: TextUnit = 20.sp) {
    if (tokens.isEmpty() || tokens.joinToString("") { it.surface } != ja) {
        Text(
            buildAnnotatedString {
                var from = 0
                while (term.isNotEmpty()) {
                    val at = ja.indexOf(term, from)
                    if (at < 0) break
                    append(ja.substring(from, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(term) }
                    from = at + term.length
                }
                append(ja.substring(from))
            },
            fontSize = fontSize,
        )
        return
    }
    FlowRow {
        tokens.forEach { t ->
            val reading = t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading
            val ruby = if (t.surface.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) hiragana(reading) else ""
            val ours = term.isNotEmpty() && (t.base == term || t.surface == term)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ruby, fontSize = fontSize * 0.5f, color = MaterialTheme.colorScheme.onSurface.copy(alpha = SOFT), lineHeight = fontSize * 0.6f)
                Text(t.surface, fontSize = fontSize, fontWeight = if (ours) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** The morae of a kana reading: a small ゃゅょ or a small vowel belongs to the kana before it. */
internal fun morae(kana: String): List<String> {
    val out = ArrayList<String>()
    for (ch in kana) {
        if (ch in "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ" && out.isNotEmpty()) out[out.lastIndex] = out.last() + ch else out += ch.toString()
    }
    return out
}

/** Which morae are spoken high for downstep [n] (0 = 平板: low, then high to the end). */
internal fun pitchHigh(count: Int, n: Int): List<Boolean> = List(count) { i ->
    when (n) {
        0 -> i > 0
        1 -> i == 0
        else -> i in 1 until n
    }
}

/**
 * The reading drawn as its pitch pattern, 辞書's way: a line over every high mora, a stroke down the
 * right of the mora the pitch falls after, then the downstep number.
 */
@Composable
internal fun PitchReading(kana: String, downstep: Int, modifier: Modifier = Modifier, fontSize: TextUnit = 20.sp) {
    val parts = remember(kana) { morae(kana) }
    val high = remember(kana, downstep) { pitchHigh(parts.size, downstep) }
    val ink = MaterialTheme.colorScheme.onSurface
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        parts.forEachIndexed { i, mora ->
            Text(
                mora,
                fontSize = fontSize,
                color = ink,
                modifier = Modifier.drawBehind {
                    val w = 1.5.dp.toPx()
                    if (high[i]) drawLine(ink, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                    if (downstep > 0 && i == downstep - 1) drawLine(ink, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height * 0.45f), w)
                },
            )
        }
        Text(" [$downstep]", fontSize = fontSize * 0.6f, color = ink.copy(alpha = SOFT))
    }
}

/** A small boxed marker — ★, a frequency rank, an island — as on 辞書's heading line. */
@Composable
private fun Marker(text: String, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = SOFT)
    Text(
        text,
        fontSize = 12.sp,
        color = ink,
        maxLines = 1,
        modifier = modifier.border(1.dp, ink, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
    )
}

/** Everything 辞書 answered for one term, in its own order of importance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JishoSection(j: JishoEntry, term: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = ink.copy(alpha = SOFT)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (j.forms.isNotEmpty() || j.pos.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (j.forms.isNotEmpty()) Text(j.forms.joinToString("、"), fontSize = 17.sp, color = soft)
                j.pos.forEach { Marker(it) }
            }
        }
        j.glosses.forEach { g ->
            Text(g.dictionary, fontSize = 12.sp, color = soft)
            g.senses.forEachIndexed { i, sense -> Text("${i + 1}. $sense", fontSize = 16.sp) }
        }
        if (j.kanji.isNotEmpty()) {
            Text("漢字 / kanji", fontSize = 12.sp, color = soft)
            j.kanji.forEach { k ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(k.char, fontSize = 30.sp)
                    Column {
                        Text(k.meanings.joinToString(", "), fontSize = 16.sp)
                        Text(
                            listOfNotNull(
                                k.on.joinToString("・").takeIf { it.isNotEmpty() }?.let { "音 $it" },
                                k.kun.joinToString("・").takeIf { it.isNotEmpty() }?.let { "訓 $it" },
                            ).joinToString("　"),
                            fontSize = 14.sp, color = soft,
                        )
                        Text(
                            listOfNotNull(
                                k.strokes?.let { "$it 画" }, k.grade?.let { "学年 $it" }, k.jlpt?.let { "JLPT $it" }, k.frequency?.let { "#$it" },
                            ).joinToString(" · "),
                            fontSize = 12.sp, color = soft,
                        )
                    }
                }
            }
        }
        if (j.conjugation.isNotEmpty()) {
            Text("活用 / conjugation" + if (j.conjugationClass.isNotBlank()) "（${j.conjugationClass}）" else "", fontSize = 12.sp, color = soft)
            j.conjugation.forEach { c ->
                Column(Modifier.fillMaxWidth()) {
                    Text(c.form, fontSize = 12.sp, color = soft)
                    Text(
                        listOf(c.plain, c.plainNegative, c.polite, c.politeNegative).filter { it.isNotBlank() }.joinToString("　"),
                        fontSize = 16.sp,
                    )
                }
            }
        }
        if (j.examples.isNotEmpty()) {
            Text("辞書の例文 / 辞書's examples", fontSize = 12.sp, color = soft)
            j.examples.forEach { ex ->
                Column {
                    RubyLine(ex.ja, emptyList(), term, fontSize = 17.sp)
                    Text(ex.en, fontSize = 14.sp, color = soft)
                }
            }
        }
    }
}

/** A term as plain text, for sharing and the clipboard: 白い熊's own content, then 辞書's first line. */
internal fun vocabShareText(entry: VocabEntry): String = buildString {
    val v = entry.vocab
    val j = entry.jisho?.takeIf { it.found }
    append(v.term)
    if (v.reading.isNotBlank()) append('【').append(v.reading).append('】')
    j?.pitch?.takeIf { it.isNotEmpty() }?.let { append(' ').append(it.joinToString("") { n -> "[$n]" }) }
    append('\n').append(v.meaning).append('\n')
    if (v.note.isNotBlank()) append(v.note).append('\n')
    entry.examples.forEach { append('\n').append(it.ja).append('\n').append(it.en).append('\n') }
    j?.glosses?.firstOrNull()?.let { g ->
        append('\n').append(g.dictionary).append('\n')
        g.senses.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
    }
    j?.kanji?.takeIf { it.isNotEmpty() }?.let { ks ->
        append('\n')
        ks.forEach { k -> append(k.char).append(' ').append(k.meanings.joinToString(", ")).append('\n') }
    }
}.trimEnd()
