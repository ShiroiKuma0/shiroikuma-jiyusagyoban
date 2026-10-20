# Contract — walk capture: 自由作業盤 ⇄ 白い熊 kxkb ⇄ 言語島

Agreed 2026-10-04 between the 自由作業盤 chat and the kxkb chat, approved by 白い熊.

Sentences for 言語島 are spoken outside with the screen off, reviewed at home in kxkb (with Whisper's
corrections learned exactly as in dictation), and only then handed to 言語島 for island assignment and
translation.

```
物理鍵 (vol-down)                     kxkb                                 言語島
  triple: capture mode on/off          voice_capture_offer  ◀── clips ──  (自由作業盤)
  single: sentence start / save        review page: decode, correct, ✓
  → WAV per sentence (自由作業盤)       outbox ── gengoshima_intake ──▶  未分類 inbox → islands
```

Both directions are `ContentResolver.call()` on the receiver's existing automation door. Both doors
identify the caller by exact package, uid and pinned signing certificate (`AutomationCallers`, the same
file in both repos); a token is honoured only when the receiver's `automation_require_token` is on.
Every answer is a `Bundle` whose `result` is `OK…` or `ERROR:…`, returned, never thrown.

**Why `call()` and not broadcasts:** EMUI severs ordered-broadcast results and binders in broadcast
extras. `call()` is synchronous, instantiates the provider even when the app is not running, and its
returned Bundle is the receipt.

## 1. Clips: 自由作業盤 → kxkb — `voice_capture_offer`

Authority `shiroikuma.kxkb.automation`, method `voice_capture_offer`, `arg` unused.

Extras:

| key | type | meaning |
| --- | --- | --- |
| `items` | String (JSON array) | one object per clip, ≤ 10 per call |
| `fd_0` … `fd_9` | ParcelFileDescriptor | read-only descriptor of clip *i*, named by the item's `fd` |
| `token` | String, optional | only if kxkb requires one |

Item object:

```json
{"uuid":"…","fd":"fd_0","capturedAt":1791100000000,"durationMs":4210,
 "language":"en","sampleRate":16000,"channels":1,"bitsPerSample":16,
 "byteLength":134764,"sha256":"…"}
```

- **Format:** canonical 44-byte RIFF/`fmt `/`data` WAV, PCM16 little-endian, mono, 16 000 Hz —
  byte-for-byte the format of kxkb's corpus clips (`VoiceCorpusRepository.writeClip`), so an accepted
  clip moves into the corpus without re-encoding. `byteLength` is the whole file including the header.
- **Raw signal:** recorded with `AudioSource.UNPROCESSED` where supported, else `VOICE_RECOGNITION`; no
  AGC, no noise suppression, no normalising. kxkb peak-normalises at decode time and its confidence
  threshold is calibrated on that.
- **Length:** a sentence is ≤ 28 s (Whisper's window is 30 s; the recorder auto-splits at 28 s) and
  ≥ 0.2 s (shorter is a stray press and is never offered).
- **Language:** `en` is a default, not a verdict; kxkb may re-decode in another language.

Reply `result`:

| result | meaning — and what 自由作業盤 does |
| --- | --- |
| `OK:<uuid>,<uuid>,…` | exactly these clips are copied into kxkb's `filesDir` **and fsynced** — delete them |
| `ERROR:locked` | kxkb's credential-protected storage is not available (before first unlock) — keep all, re-offer later |
| `ERROR:budget` | the capture inbox would exceed its size cap — keep all, re-offer later |
| `ERROR:items` | `items` could not be parsed — keep all, log |
| `ERROR:format:<uuid>` | single-item call whose clip failed validation — same as that uuid in `rejected` |
| other `ERROR:…` | caller refused, door off, … — keep all, show verbatim |

Alongside `OK:…` the reply MAY carry a second extra, **`rejected`** = `<uuid>:<reason>,<uuid>:<reason>`,
reasons from the closed set `format`, `sha256`, `length`, `nofd`, `io` (§5 of kxkb's copy). A partial
failure is the common case with ten clips per call, and this keeps the nine acknowledgements when one
clip fails. **There is no `duplicate` reason:** a uuid kxkb holds — or held once and has since reviewed
and deleted (its `seen.json` outlives the sentence) — is answered INSIDE `OK:`, because deleting our copy
is exactly right for it.

What kxkb validates (anything else is `rejected`): declared 16000 / 1 / 16 (else `format`, nothing
read); written bytes = `byteLength` and hash to `sha256`; a real RIFF/WAVE with a PCM16 mono 16 kHz
`fmt ` chunk (extra chunks allowed); `data` between 0.2 s and 30 s (`length`). `durationMs` is recomputed
from the bytes and used only as a cross-check. kxkb's `describe` reports `capture_budget_bytes` (512 MB)
and `capture_used_bytes`.

- a uuid in `OK` → delivered, delete it;
- a uuid in `rejected` → **do not retry automatically**: 自由作業盤 moves the clip to
  `gengoshima_capture/rejected/` and logs the reason (kept, never deleted silently);
- a uuid in neither → re-offer later.

`ERROR:locked`, `ERROR:budget` and caller refusals are whole-call conditions. kxkb's `describe` also
reports `capture_budget_bytes` / `capture_used_bytes` (optional for 自由作業盤 to read).

- `OK` never means "accepted for processing" — the copy happens synchronously inside `call()`.
- kxkb de-duplicates by `uuid` (a persisted seen-set), so a re-offer after a crash on either side is
  harmless. kxkb recomputes `durationMs` from the byte count; the field is a cross-check.
- **Ownership:** the clip belongs to 自由作業盤 until its uuid comes back in `OK`, to kxkb afterwards. A
  sentence dropped at review deletes its clip. The audio exists in exactly one place at every instant.
- **When 自由作業盤 offers:** when capture mode ends, and again on every engine start while clips remain.

## 2. Sentences: kxkb → 言語島 — `gengoshima_intake`

Authority `shiroikuma.jiyusagyoban.automation`, method `gengoshima_intake`, `arg` unused.

Extras:

| key | type | meaning |
| --- | --- | --- |
| `items` | String (JSON array) | ≤ 50 reviewed sentences per call |
| `token` | String, optional | only if 自由作業盤 requires one |

Item object:

```json
{"uuid":"…","text":"I walked to the river this morning.","recognized":"I walk to the river this morning",
 "language":"en","capturedAt":1791100000000}
```

- `uuid` is the capture uuid (the same one offered in §1). `text` is the final, reviewed sentence;
  `recognized` is Whisper's original output (kept for reference, may equal `text`).
- No audio travels in this direction — the clip stays in kxkb's voice corpus.

Reply `result`:

| result | meaning — and what kxkb does |
| --- | --- |
| `OK:<uuid>,<uuid>,…` | these uuids are durably in 言語島's inbox (newly stored **or already there**) — mark handed over |
| `ERROR:items` | the payload could not be parsed — keep all, log |
| other `ERROR:…` | caller refused, door off, … — keep all, retry later |

- 自由作業盤 de-duplicates by `uuid` (primary key of `gengoshima_inbox`), so kxkb's outbox may retry
  freely. A uuid already assigned to an island and translated is still answered as `OK`.
- **When kxkb sends:** a 「言語島へ送る」 pill on its review page, and automatically whenever the outbox is
  non-empty and a call succeeds.
- **What kxkb sends (+340):** `arg` null, extras only `items`, ≤ 50, oldest `capturedAt` first, always
  all five fields. No token (ours is off — tell kxkb before that ever changes). Only uuids named in `OK:`
  AND offered in that batch leave its queue; any other answer, or none, keeps the whole batch queued and
  shows 白い熊 our words verbatim. Keep pushes at once; 「言語島へ送る (n)」 pushes on demand.
- **Fallback (§6a of kxkb's copy)** — on `shiroikuma.kxkb.automation`, for the day a push is refused; not
  used in normal operation:
  - `gengoshima_pull`: optional `limit` extra (default and max 50). Reply `result = "OK:<n>"` plus an
    `items` extra holding the same array a push would send. Reading hands nothing over.
  - `gengoshima_ack`: `items` = a JSON array of uuid STRINGS. Reply `OK:<uuid>,…` naming those that were
    waiting and have now left. An unknown uuid is ignored (not remembered); garbage is `ERROR:items`;
    acking twice is harmless.

## 3. What 言語島 does with the inbox

Sentences land in `gengoshima_inbox` (Room v35), not in an island. The 未分類 page proposes an island for
each (one Claude call per batch: an existing island, or a new one with an English topic and register),
lets 白い熊 change one by tapping its island chip or several at once by multi-select, and inserts them
into their islands as `new` on 「すべて確定」. The normal 訳して音声を作る run translates and voices them.

## 4. Capture on the phone (自由作業盤, for reference)

- 物理鍵 vol-down **triple** toggles capture mode (it no longer reads the time). Entering it only arms
  it; nothing records until the first single press (2026-10-12).
- In capture mode a **single** vol-down press starts a sentence and the next saves it; the grabber
  consumes these presses so the volume does not change, screen on or off. With the screen on and the
  camera in front, the press is the camera's shutter instead (2026-10-12).
- Vibrations: armed = two 200 ms · start = one 200 ms buzz · passed 28 s (now a long recording, §9) = one 60 ms tick · saved = two short · capture mode ended =
  three short · error (mic lost, nothing saved) = one long 800 ms.
- Ending capture mode while a sentence records saves it first.

## 7. Amendment — the sentence's language is decided in the review (both sides, 2026-10-11)

§1 says `en` is "a default, not a verdict; kxkb may re-decode in another language", and §2's item object has
carried a `language` field from the start. Until now it was always `en`, because nothing could change it.
kxkb's Walk capture page now can: every captured sentence carries a language pill (English ⇄ čeština) and
the page has a "mark all" for a whole walk, and the language a sentence is set to is the language its clip
is decoded in — the Whisper decode, the word marks, the Czech clause commas, and what the voice corpus
learns from the sentence.

So §2's `language`:

- is present on every item and holds exactly `en` or `cs`;
- is **authoritative** — 白い熊's verdict in the review, and the language the audio was really decoded in,
  not the recorder's guess;
- is normalised before it can be sent: an unknown or blank code on an offered clip becomes `en`, which the
  review can then change. 言語島 never sees a third value.

For 言語島 that makes the inbox a mixed-language queue: the row's language must be stored and shown, the
island proposal must be told which language the sentence is in, a `cs` row translates cs→ja, and its
source-side voice is a Czech voice.

§1 is unchanged. A 自由作業盤 that wants to mark a whole walk at record time may send `"cs"` in the offer —
kxkb honours it as the sentence's starting language — but nothing requires it, and the review has the last
word either way. §2 still carries no audio: the clip belongs to kxkb's voice corpus once its uuid came back
in `OK:`, and a 言語島 that needs the recording itself would need a new section here, agreed first.

Switching a sentence's language DISCARDS its decode: a transcription belongs to the language it was made
in, and a Czech sentence read as English is not a sentence to correct but one to decode again. The page
asks before dropping one. Corrections already saved stay — they went into the voice corpus the moment they
were saved — and the recording is untouched, which is all a re-decode needs.

## 8. Amendment — the way back from the review (kxkb side, 2026-10-18)

白い熊 opens the review from the 言語島 board's 「録音確認」 tile, and expects Back to show the board
again. It did not, and no flag on either side could make it: **the board finishes itself the moment a
tile hands its task over** (`GengoshimaBoardActivity`, "two stacked windows is one too many" — right for
a tile that opens a window of 自由作業盤's own). By the time the review appears there is no board
anywhere — not behind us, not in another task, not in recents. An activity that no longer exists cannot
be revealed; it has to be **opened again**, and it is `exported="false"`, so kxkb cannot open it.

So the caller names a task to run on the way out, as a **third extra on the §1-style launch intent**
that opens the review:

| key | type | meaning |
| --- | --- | --- |
| `return_task` | String, optional | the name of a 自由作業盤 task, run when the review is left by Back, the toolbar arrow or Close |

kxkb runs it through 自由作業盤's **own exported** run-a-task trampoline —
`com.opentasker.widget.TaskRunActivity`, extras `com.opentasker.widget.TASK_NAME` and
`…SOURCE` (sent as `白い熊 kxkb`, so the run log says where it came from) — with the component pinned to
`shiroikuma.jiyusagyoban`, never an implicit intent. **This grants nothing:** that trampoline is exported
for launcher shortcuts, so every app on the phone may already run any task by name. The door still reads
nothing else, still opens exactly one page, and still hands over no data.

Nothing passed → the review closes exactly as it did before: no guessing at a screen to show instead.
Nothing passed is also what the space-slide menu's own entry to the page means.

**No code is needed in 自由作業盤.** The 「言語島 録音確認」 task's send-intent action already carries six
string extras: `extra1_key = return_task`, `extra1_value = <the name of the task whose action is
「言語島の盤」 / `gengoshima.board`>`. A task name rather than a component because the board is not
exported, and a name rather than an id because a name survives a re-import — 自由作業盤's own reason, in
`TaskRunActivity`.

Rejected on the way here, for the record: bringing 自由作業盤's launcher task forward (it shows
`MainActivity`, a different task from the board's `.gengoshimaboard` one — 白い熊 landed on the
Variables page); a `PendingIntent` from the caller (a send-intent action cannot build one); and keeping
the board alive behind the review (it is 自由作業盤's call, and the stack between two apps' tasks is not
a thing either side can rely on).

### 8a. The board's door (自由作業盤 side, 2026-10-18)

So that the return needs no configuration at all, 自由作業盤 also has a **door to the board**, the mirror of
kxkb's `WalkCaptureEntryActivity`:

| | |
| --- | --- |
| action | `shiroikuma.jiyusagyoban.action.OPEN_GENGOSHIMA_BOARD`, category `DEFAULT` |
| component | `com.opentasker.ui.gengoshima.GengoshimaBoardDoorActivity` in `shiroikuma.jiyusagyoban` |
| manifest | `exported="true"`, `Theme.NoDisplay`, `noHistory`, `excludeFromRecents`, `taskAffinity=""` |
| reads | **nothing** — its whole body is `GengoshimaBoardActivity.open(this)` then `finish()` |

A trampoline rather than an exported board or alias: a door that takes no extras can open exactly one
screen, so exporting it cannot be talked into opening another part of the app. No data crosses it in
either direction.

kxkb fires it, package-pinned and resolve-checked, as its **default** return whenever the review was
opened through its door; `return_task` still wins when a caller names one; and with the door missing
(an older 自由作業盤) the review falls back to a plain close. So the two halves land in either order.

## 9. Amendment — long recordings (自由作業盤 side 2026-10-19; kxkb side by hand-off)

白い熊 wants the walk capture's press to double as a plain audio recorder — "record this, keep it for
later", not transcribed — with no second key. So the recorder no longer cuts at 28 s: one press-to-press
is **one clip, however long** (up to 4 h, or until the phone's free storage falls under 200 MB, where the
clip is saved and the mode stays armed). Whisper still hears only 30 s, so what a long clip IS for is
decided in kxkb's review, not by the recorder.

Changes to §1, everything else unchanged:

- **Length:** `data` ≥ 0.2 s, no upper bound but the recorder's 4 h. A clip over **28 s is a long
  recording**: the item carries `"kind":"long"` (absent on a sentence), and it must never be decoded as
  one piece.
- **Batching:** a long recording is offered **alone** in its call (one descriptor), never with sentences.
- **An older kxkb** answers it `rejected` `length` (its pre-§9 size check, before anything is copied).
  自由作業盤 then **keeps** it pending — it is not a bad clip — and re-offers it on the next mode end or
  engine start. So the halves land in either order.
- **Salvage:** the recorder now rewrites the header every 5 s, and a recording cut off by a kill is
  saved on the next start as what was on disk. Its item carries `"salvaged":true`; it is otherwise
  ordinary.

kxkb's side (in its review):

- Store a long recording, show it as one row (duration, time, play / seek), and never auto-decode it.
- **Save as OGG** — Opus to a fixed folder, named by its `capturedAt` (`yyyy-MM-dd_HH-mm-ss.ogg`).
- **Split into sentences** — cut at the pauses (the VAD kxkb already carries), each piece ≤ 28 s, a hard
  cut only where 28 s holds no pause. Each piece is an ordinary sentence with a **new uuid**, `parent` =
  the long clip's uuid and `capturedAt` = parent + its offset, so §2 to 言語島 is unchanged.
- Both may be done; the long WAV goes when the last of them is done, or on **Drop** (confirmed).
- The capture budget (512 MB, about 4.6 h of audio) is kxkb's to raise; `ERROR:budget` keeps working
  as before (自由作業盤 keeps the clip and re-offers later).
