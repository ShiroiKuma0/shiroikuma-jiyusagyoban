# 言語島 (gengoshima) — the Japanese language-islands suite

The design record of 言語島 as it is **built**: what it is for, how the four apps divide the work,
what 白い熊 decided and why, every setting, the file layout, the device facts it rests on, how it
was verified, and what is still open. It replaces the planning file the work started from
(`~/.claude/plans/cosmic-strolling-bubble.md`, base plan 2026-09-29 + addendum 2026-10-01); where
the build differs from that plan, this document says what was built.

Status (2026-10-02, jiyusagyoban +023): complete. Every planned part is built and verified on the
phone, apart from the items in [Open](#open).

## What it is for

Mikel Hyperpolyglot's *language islands*: 白い熊 writes their own English sentences, grouped into
**islands** — short ordered monologues on one topic — has them translated into natural spoken
Japanese and voiced, and listens daily: shadow each sentence several times, review, then recall
(hear the English, say the Japanese). Islands rotate on a spaced schedule. The same sentences are
also studied as flashcards in 白い熊 暗記 and with the pop-up dictionary in 白い熊の辞書.

## The four apps

| App | Package | Role | Contract |
| --- | --- | --- | --- |
| 白い熊 自由作業盤 | `shiroikuma.jiyusagyoban` | **The suite.** Data, Claude translation, generation, player, editor, statistics, the sync to 暗記, the files for 辞書. Reached through the 日本語 [227] project. | — |
| 白い熊 音声 | `shiroikuma.onse` | Renders speech to OGG/Opus files at the paths it is given: Japanese with VOICEVOX **No.7 / 読み聞かせ** (style 31), English with **Kokoro-82M** (sherpa-onnx), voice **am_michael**. 48 kbps. | `~/git/shiroikuma-onse/docs/sister-app-contract-onse-render.md` |
| 白い熊 暗記 | `shiroikuma.anki` | Holds the islands as `Language Islands` notes (Recognition + Production cards). Door methods `islands.list` and `islands.sync`. | `~/git/shiroikuma-anki/docs/sister-app-contract-anki-islands.md` |
| 白い熊の辞書 | `shiroikuma.jisho` | Studies a whole island as one OGG + SRT with its pop-up dictionary: intent `shiroikuma.jisho.intent.action.STUDY_AUDIO` (extras `path`, `title`), since jisho 1.5.0+058. | — |

All three sister packages are in jiyusagyoban's `<queries>`.

**Why this split** (base plan): VOICEVOX and Kokoro need ONNX runtimes, and jiyusagyoban already
carries its own for OCR — a separate 音声 avoids the clash and stays reusable by other apps (it is
also a system TTS voice). The suite itself lives in jiyusagyoban, built like 健康 — thin tasks that
each open a native Compose screen — so it shares the variables, profiles and the one backup.

## 白い熊's decisions

| Question | Decision | Why |
| --- | --- | --- |
| Islands | One island per topic, sentences in entry order; shuffle reorders whole islands only | An island is a monologue — its order is its meaning |
| Translation | Claude (default **Opus 5.5**, effort `high`), natural spoken register set per island; earlier sentences go along as context | One island must not drift between です and だ |
| Japanese voice | VOICEVOX No.7 / 読み聞かせ, speed 1.15 | The voice 白い熊 already used for their subtitles |
| English voice | Kokoro **am_michael** inside 音声, no third app | 白い熊's pick from the PC audition (af_heart was expected) |
| Bitrate | **48 kbps** (was 32) | 白い熊 heard the difference (2026-10-01) |
| Generation | An explicit 「訳して音声を作る」 button with a progress window, not a silent start on close | 白い熊 wants to see each step and its outcome |
| Editing | English edits re-translate; Japanese edits are kept verbatim (`jaEdited`) and only re-split and re-voiced; per-word reading overrides steer VOICEVOX | Their Japanese is authoritative once they have touched it |
| Car | The phone screen is the player (giant UI, over the lock screen); no Android Auto | Steering-wheel next/previous move by sentence |
| Modes | Listen / Shadow ×N / Recall. **Recall plays English → a pause → Japanese**; Listen and Shadow can add the English after the Japanese | The method's three steps |
| 暗記 | Adopt the hand-made deck (keep its review history), re-voice everything, fix the 46 misfiled cards; afterwards a delta sync after every change | One source of truth (言語島), 暗記 follows |
| Dialogs | Every dialog wears the yellow border; a finished run's result stays until **閉じる** — Back only hides it | A result read in the car must not vanish |
| Words | Never "folder" — always "directory" | 白い熊's standing rule |

## Settings — 日本語の設定 -- [227][01]

`var.set` steps, each with a JP/EN label; the 71 task (日本語 ⇨ 起動) loads them. They are
MixedCase project variables, so a task outside 日本語 cannot read them: every 言語島 action copies
them into `GengoshimaSettings` (`core/gengoshima/GengoshimaSettings.kt`) when it runs, and work
started later (the generation after a screen closes) uses that copy. **A re-import of the settings
task replaces it**: the bundle generator carries the live values over from a fresh export, and the
API key must never be blanked.

| Variable | Default | Meaning |
| --- | --- | --- |
| `%Gengoshima_ApiKey` | — | Anthropic API key |
| `%Gengoshima_Model` | `claude-opus-5-5` | Claude model |
| `%Gengoshima_Effort` | `high` | Claude effort |
| `%Gengoshima_Speaker` | `31` | VOICEVOX style (No.7 / 読み聞かせ) |
| `%Gengoshima_Speed` | `1.15` | Japanese speed |
| `%Gengoshima_Pitch` | `0` | Japanese pitch |
| `%Gengoshima_Intonation` | `1.0` | Japanese intonation |
| `%Gengoshima_Gap` | `0.25` | Silence 音声 adds after each sentence (s) |
| `%Gengoshima_ShadowRepeats` | `5` | Repeats per sentence in Shadow |
| `%Gengoshima_ShadowPauseFactor` | `1.2` | Shadow/Recall pause as a multiple of the sentence's length |
| `%Gengoshima_Dir` | `/sdcard/〇/[227] 日本語/[227][727] 言語島` | Root of the audio tree |
| `%Gengoshima_IslandDirPattern` | `{no} {name_ja} — {name_en}` | Island directory name |
| `%Gengoshima_SentenceFilePattern` | `{no} {ja}` | Japanese file name |
| `%Gengoshima_SentenceFileEnPattern` | `{no} {ja} [en]` | English file name |
| `%Gengoshima_IslandFileName` | `000 島全体` | The whole-island OGG/SRT for 辞書 |
| `%Gengoshima_NumberWidth` | `3` | Digits of `{no}` |
| `%Gengoshima_NameMaxChars` | `40` | Truncation of the text in a name |
| `%Gengoshima_IslandPause` | `0.5` | Silence before the first and between sentences in the whole-island file (s) |
| `%Gengoshima_OpusKbps` | `48` | Bitrate (both languages) |
| `%Gengoshima_EnVoice` | `am_michael` | Kokoro voice |
| `%Gengoshima_EnSpeed` | `1.0` | English speed |
| `%Gengoshima_AnkiSync` | `on` | Sync to 暗記 after every run and every editor close |
| `%Gengoshima_AnkiRoot` | `言語島々` | Root deck |
| `%Gengoshima_AnkiRecognition` | `認識` | Recognition subtree |
| `%Gengoshima_AnkiProduction` | `製作` | Production subtree |

Any voice parameter change re-voices every sentence on the next run (it is part of each file's
hash); a pattern change only renames.

## Tasks (project 日本語 -- [227])

The 71 → 01 → 37 trio, then: **言語島 文入力** (`gengoshima.entry`), **言語島 生成**
(`gengoshima.generate`), **言語島 聴く** (`gengoshima.listen`), **言語島 編集**
(`gengoshima.islands`), **言語島 統計** (`gengoshima.stats`), and in the 暗記 group
**暗記と同期** (`gengoshima.anki_sync`, a full sync) and **暗記から取り込む**
(`gengoshima.anki_adopt`, the one-time adoption). The bundle is generated by
`.scratch/build-gengoshima-bundle.py`.

## Data — Room v33 → v34

`core/storage/.../GengoshimaDao.kt`. **The database is authoritative; the audio tree is a view.**

- `gengoshima_islands` — position, `nameEn`, `nameJa`, `register`, status, SM-2 fields, `dirName`
  (where its files are now), `uuid` (v34; what 暗記's decks are keyed by).
- `gengoshima_sentences` — island, position, `en`, `ja`, `tokensJson`
  (`[{surface, base, reading, reading_override?, gloss}]`), state
  (`new → translated → ready`, `annotate` = Japanese edited by hand, `error`), `jaEdited`,
  `audioPath`/`audioHash`/`durationMs`; v34 adds `uuid` (the 暗記 note's tag), `enAudioPath`/
  `enAudioHash`/`enDurationMs`, `ankiHash` (what 暗記 last took) and `ankiNid` (the note it was
  adopted from).
- `gengoshima_sessions`, `gengoshima_plays` — listening history (a sentence counts as heard at
  ≥ 60 % played).
- `gengoshima_tombstones` (v34) — uuids of deleted sentences/islands not yet told to 暗記.

Migrations are registered by hand (`DatabaseMigrations.kt`, `MIGRATION_32_33`, `MIGRATION_33_34`);
`python3 scripts/check-room-migration.py --all` passes. The backup category `gengoshima` carries all
five tables.

## Files

```
/sdcard/〇/[227] 日本語/[227][727] 言語島/
  001 仕事の友達話 — Work - friends' talk/
    000 島全体.ogg                       ← every sentence joined, for 辞書
    000 島全体.srt                       ← one cue per sentence
    001 仕事も楽しくなきゃね.ogg          ← Japanese
    001 仕事も楽しくなきゃね [en].ogg     ← English
    …
  002 ロシア/
    …
```

- **One OGG per sentence and language.** An edit re-voices that sentence only.
- **整理** (`AudioTree.reconcile`) makes the tree equal the database: renames directories and files
  (through temporary names, so a swap cannot overwrite), deletes what nothing references, clears the
  path of a file gone missing so the next run re-voices it. It never re-voices. An island named the
  same in both languages (one taken from 暗記) is not named twice.
- **The whole-island file** (`OggConcat`, `IslandExport`) is the sentence files re-paginated into ONE
  logical Ogg stream — no decoding, no re-encoding, every CRC recomputed — with real Opus silence
  (`%Gengoshima_IslandPause`) before the first and between sentences. **Each SRT cue starts at the
  start of the pause before its sentence** and ends where the sentence ends. Verified exact; 辞書's
  remaining stop overshoot on these files is 辞書's own timer (libVLC TimeChanged every ~260 ms) and
  belongs to the jisho repo.
- Names: forbidden characters become their full-width twins, at most 150 bytes.

## Generation — `GenerationRunner`

One run at a time, in its own scope, with a partial wake lock (45 min) and one notification
(id `0x6E60`) that reopens the progress window from any app. Each sentence is saved the moment it is
done, so a cut-off run keeps what it finished.

Steps (the window shows those the run does):

1. **暗記から取り込む** — adoption only (below).
2. **訳す** — Claude, streamed (adaptive thinking, summarized), in chunks of 20 with the island's
   earlier sentences as context, structured output (`output_config.format` json_schema),
   `fallbacks: "default"`, retries on 429/5xx (`core/claude/ClaudeClient.kt`, `Translator.kt`). Hand
   edits are re-split ("annotate"), also in chunks of 20. The window shows the thinking summary and
   each sentence as it lands.
3. **音声** — one RENDER batch to 音声 for every sentence whose file is missing or whose hash differs.
4. **英語の音声** — the same with `lang=en`; skipped with the reason if 音声's PING says English is
   not installed.
5. **整理**, 6. **辞書用の一本** — as above.
7. **暗記と同期** — below.

Starts from the entry screen's or the editor's 「訳して音声を作る」 and from the 生成 task. Closing the
island editor runs a short version (整理, 辞書用の一本, 暗記と同期) **with the same window and
notification whenever 暗記 has something to receive**; a change that only touches files stays quiet.

**The outcome stays.** Back and 隠す only hide the window; the result and its notification stay until
閉じる, and the finished result is saved to disk, so it survives EMUI reaping the process
(2026-10-02: a result closed by Back in the car was lost). The steps and the log scroll; the
result and 閉じる are always on screen.

## 音声 (render)

`core/onse/OnseRender.kt`, per the 音声 contract: wake the process with the data door's `describe`
(`content://shiroikuma.onse.automation`), PING (`en_installed`, `en_voices`, `battery_exempt`,
`version`), then the `RENDER` broadcast with a batch JSON (`{id, text, out_path}`), `speaker`,
`speed`, `pitch`, `intonation`, `bitrate_kbps`, and for English `lang=en`, `en_voice`; one
`event=item` reply per sentence, then `event=done`. A short temporary foreground-start allowance is
granted first (`cmd deviceidle tempwhitelist` through Shizuku). A reading override is sent as the
word's katakana, which VOICEVOX reads verbatim. When 音声 cannot be started, a notification says why
and opens 音声 to set the exemption (`OnseWarning`).

## 暗記 (islands sync)

`core/gengoshima/AnkiIslands.kt`, per the 暗記 contract: `call()` on
`content://shiroikuma.anki.automation` with a `ParcelFileDescriptor`, `OK:<job_id>` at once, one
terminal broadcast later (`GENGOSHIMA_ANKI_REPLY`), progress on `GENGOSHIMA_ANKI_PROGRESS`.

- **Notes are keyed by the sentence's uuid** (tag `li::uuid::<uuid>`) and upserted, so an edit keeps
  the cards' scheduling. Card ord 0 → `<root>::<recognition>::<island>`, ord 1 →
  `<root>::<production>::<island>`; island decks are named by the island's Japanese name.
- **Delta** (after every run and editor close): sentences whose `ankiHash` (island uuid + both texts +
  both audio hashes) moved, the tombstones, and every island (so a rename always reaches its decks).
  Only ready sentences go. Audio travels as `audio/<uuid>-{ja,en}.ogg`; 暗記 stores it as
  `li_<sha1>.ogg` and moves media no note references any more to its media trash.
- **Full** (「暗記と同期」): every sentence; 暗記 deletes any `li::uuid::` note no sentence owns.
- **Nothing to send is not a sync** — 暗記 is not woken for it.
- **Adoption** (「暗記から取り込む」, once): `islands.list` → islands from the recognition decks,
  sentences in note-id order, text taken as plain (HTML references resolved — including hex ones like
  `&#x27;`, missed before +023 and repaired in place on the next run), Japanese kept as 白い熊's own,
  both voices made fresh; the end-of-run sync hands each note back in `adopt`. Running it again adds
  nothing twice; notes that cannot be taken are listed in the window.

## Player, editor, statistics

- **Player** (`GengoshimaPlayerActivity` + `GengoshimaPlaybackService`): Media3 ExoPlayer behind a
  MediaSession in its own process `:gengoshima` (a player fault cannot take the engine down; the
  application skips its start-up there). Pauses are the service's, per item (`pauseAfterMs`), so the
  mode decides them; next/previous move by sentence. Recall: English, a think pause
  (`length × factor × 1.5 + 1 s`), then Japanese, which is hidden while you answer. Shows over the
  lock screen; tapping a word looks it up in 辞書.
- **Editor** (`GengoshimaIslandsActivity`): reorder, move, delete, edit English or Japanese, per-word
  readings; island actions are pills (edit, 辞書で学ぶ, delete).
- **Statistics** (`GengoshimaStatsActivity`): calendar, sessions with times, time of day, streaks,
  per-island progress, what is due (`Rotation.kt`: SM-2 per island; a session that plays every
  sentence of an island is one review, Recall counting most).

## Device facts

- **EMUI drops a cold background start of 音声 unless 音声 is exempt from battery optimisation.**
  Wake it (`describe`, or the `WarmActivity`), then PING, then RENDER. The exemption is set on
  白い熊's phone; 音声 itself asks for it when it is missing.
- **Kokoro (English) takes about 5–8 s per sentence on the Mate XT, plus about 10 s to load the
  model** for the first one — a whole re-voice of 150 sentences is a long run.
- Room migrations that add a column with a default must spell the `DEFAULT` out (the check script
  cannot read Kotlin string templates, so the SQL is literal).
- Re-importing a settings task replaces it; values are carried over from a fresh export.

## Verification

- **音声:** RENDER `lang=en` batches produce Opus/OGG (ffprobe: opus, 48 kHz); the Japanese was
  re-voiced at 48 kbps; cold renders work with the exemption.
- **Adoption (2026-10-01), checked in a pulled copy of 暗記's `collection.anki2`:** 157 `Language
  Islands` notes, all tagged, none untagged — the 150 hand-made ones under their original note ids
  plus 7 new; all 314 cards in the right trees (**0 misplaced — the 46 misfiled 相撲 cards fixed**);
  the 46 reviewed cards and their 142 review-log rows intact; 314 `li_*` recordings.
- **Sync test (2026-10-02, 白い熊 in the editor, compared against snapshots):** two edits updated in
  place with due date, interval and state unchanged; a deletion removed the note and both cards; the
  6 recordings no longer used went to 暗記's media trash, nothing orphaned; a new sentence became one
  note with both cards in the right decks; an island rename (仕事の話 → 仕事の友達話) renamed both
  decks and moved its 14 cards with their scheduling; the review log unchanged. The 19 adopted
  sentences still holding `&#x27;` were repaired: 19 English re-voiced, 19 notes updated, no card
  touched.
- **Recall with English:** passed in 白い熊's own on-phone test (2026-10-02, as reported by the 音声/暗記 chat).
- Unit tests: `GengoshimaTest`, `AnkiIslandsTest`, `OggConcatTest`, `RotationStatsTest`, `KanaTest`,
  `OnseRenderTest`, `ClaudeClientTest`.

## Open

- **音声 is not in the 保存復元 batch.** jiyusagyoban has `shiroikuma.onse` in `<queries>` but no
  「保存 ⇨ shiroikuma.onse」 roster task yet.
- **The adoption's own sync never recorded what 暗記 took**, so the first editor sync after it re-sent
  all 156 sentences (harmless — 暗記 changed only what differed — and self-healed). The cause could
  not be found; the adoption's report was the one lost in the car. Watch for it if adoption is ever
  run again.
- **辞書's cue-stop overshoot** on the whole-island file is 辞書's timer; the fix waits for 白い熊's go
  in the jisho chat.
