# 言語島 取込 — the import text format

What another chat hands over so that 白い熊 can paste vocabulary and sentences into 言語島 in one
go. Parser: `app/src/main/java/com/opentasker/core/gengoshima/VocabImport.kt` (tests:
`VocabImportTest`). Plain text, one item per line.

```
言語島 取込 1
島: 仕事 | Work | 丁寧

語: 会議【かいぎ】 = meeting; conference
  例: 会議は三時からです。 = The meeting starts at three.
  例: 会議に遅れてすみません。 = Sorry I am late for the meeting.
  注: formal; 打ち合わせ is the casual word

語: メール = e-mail

文: 明日は休みを取ります。 = I am taking tomorrow off.
```

## Lines

| Line | Alias | Content |
| --- | --- | --- |
| `言語島 取込 1` | — | The header, first line. With it on the clipboard the import window fills itself in. |
| `島:` | `island:` | `Japanese name \| English name \| register`. Only the first is required; a single non-Japanese name is taken as the English one. Every line after it belongs to this island until the next `島:`. |
| `語:` | `term:`, `word:` | `term【reading】 = translation`. The reading (hiragana) may also stand in `（）`, `()` or `[]`, and is left out for a word written in kana. Several senses are separated by `;`. |
| `例:` | `ex:`, `example:` | `Japanese sentence = English sentence` — an example of the term above it. |
| `注:` | `note:` | A note on the term above it. |
| `文:` | `sentence:` | `Japanese sentence = English sentence` — a sentence of the island that belongs to no term. |

## Rules

- **Japanese left, English right, split at the first ` = `** (space, equals sign, space). A later
  `=` belongs to the English. A tab or a full-width `＝` is accepted too.
- **The translation is mandatory.** A `語:`, `例:` or `文:` without one is reported with its line
  number and left out; nothing is dropped silently.
- The term is written in its **dictionary form** (会議, 食べる — not 食べた): that is what 白い熊の辞書
  is asked about, and its lookup is exact.
- Indentation, blank lines, list bullets (`- `, `* `, `1. `) and Markdown bold around the keyword
  are ignored; lines starting with `#` or `//` are comments.
- One text may hold several `島:` blocks.
- No furigana inside sentences: the app splits every sentence into words and reads them itself.

## What the import does

- An island is found by either of its names (case aside), otherwise created. The preview shows
  which, and lets 白い熊 send a block to another island.
- **Nothing already entered is entered again** — checked across every island, and within the text
  itself. A term is the same by spelling and reading, a sentence by its Japanese (spaces and the
  final 。 aside). The preview counts them, names the island each is in, and keeps them out of the
  list unless 「既にあるものも表示」 is on.
- A **term** goes into the island's vocabulary. A term handed in again is not duplicated: its
  translation and note follow the newer text, and any new examples are added.
- **Examples and sentences are appended to the end of the island**, in the order written, as
  ordinary sentences — voiced, played in Listen / Shadow / Recall, synced to 白い熊 暗記. An example
  stays tied to its term.
- The Japanese is kept **verbatim** and is never translated: the sentence goes in as already
  translated, with no word split yet.
- 「取り込んで音声を作る」 then starts the run, in this order: Japanese voice → source voice →
  **単語に分ける** (the words and their readings, for furigana and look-up) → 整理 → whole-island
  file → 辞書 lookup of the new terms → 暗記 sync. Listening is ready after the two voices.
- **Imported readings are kept in the split.** Wherever a term stands in a sentence as written it
  is one word with the reading handed in — never cut into parts, never read another way
  (`VocabReadings`, enforced on Claude's answer, not left to the prompt). An inflected form keeps
  the term's stem reading. If the voice read a term otherwise than its reading, that sentence is
  voiced once more with the reading spelled out.
- The island is decided in the preview and nowhere later: each block says whether it joins an
  existing island or creates one, 「島を変える」 sends one block elsewhere (an existing island, a
  new one, or back to the island the text names), 「すべての島を変える」 sends every block to one.
- 「中止」 (red) in the run's window ends the run; what was finished is kept.

## Three ways in

1. **Paste or open a file**: the board's 取り込む tile, 言語島 語彙 → メニュー → 取り込む, or 文入力 →
   取り込む; 「貼り付け」 or 「ファイルを開く」.
2. **Share**: share the text from any app to 「言語島 取り込む」.
3. **adb bridge** (from a PC chat, no typing):

   ```
   adb shell am broadcast -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver \
     -a shiroikuma.jiyusagyoban.action.GENGOSHIMA_IMPORT --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1 \
     --es shiroikuma.jiyusagyoban.extra.PATH <file in /sdcard/tmp> --es shiroikuma.jiyusagyoban.extra.GENERATE true
   ```

   The broadcast must be ordered and explicit, as for every bridge (`workspace-mirror` skill). Over
   the bridge a file with any unreadable line is refused whole, the lines named in the reply.

「直前の取り込みを取り消す」 in the 語彙 menu takes the last import back: its sentences (with their
recordings and 暗記 notes), its terms, and any island it created that is still otherwise empty.
