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

- 物理鍵 vol-down **triple** toggles capture mode (it no longer reads the time).
- In capture mode a **single** vol-down press starts a sentence and the next saves it; the grabber
  consumes these presses so the volume does not change, screen on or off.
- Vibrations: start = one 200 ms buzz · saved = two short · capture mode ended = three short · error
  (mic lost, nothing saved) = one long 800 ms.
- Ending capture mode while a sentence records saves it first.
