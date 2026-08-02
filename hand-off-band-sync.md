# Hand-off — 「接続」: sync the Hume Band's health history into 自由作業盤

Run this from `~/git/shiroikuma-jiyusagyoban` in a fresh chat. Read `CLAUDE.md` and
`.claude/skills/build-apk/SKILL.md` first, then `.claude/skills/literate-bundles/SKILL.md` before you
write the bundle at the end.

**From:** the band reverse-engineering session, 2026-08-02.
**State at hand-off:** the fork is at `0.2.79.2026-08-02.g915979d9+017`, `BUILD_NUMBER=18`, DB schema
version 20. Nothing about the band exists in this repo yet.

This file is self-contained. Every protocol fact below was **verified against the physical device**,
not merely read out of a decompiler — the golden frames in §3 are real bytes captured from 白い熊's
band on 2026-08-02, and the values they decode to were cross-checked against what the Hume app itself
displayed for the same period. Nine figures matched exactly.

---

## Why

白い熊 wears a **Hume Band V2**. Its data is trapped behind `com.elink.fittrackhealth.pro`, a
closed-source Flutter app that ships everything to Firestore and puts the interesting analysis behind
a paid remote AI service (`prof.humeconnect.com`). 白い熊 uses none of the web features and wants the
data to be his.

The band turns out to be wide open. **No pairing, no bonding, no auth key, no encryption.** A machine
that has never seen the band before can connect to it by MAC address and read every stored record.
The protocol is plaintext 16-byte frames with an additive checksum. This was proven by connecting from
a Linux box that had never been paired with it and pulling three days of history.

So rather than build a separate sync app, the band becomes a **「接続」 project inside 自由作業盤**.
This app already lives on the phone, already holds `MANAGE_EXTERNAL_STORAGE`, already has Room with a
time-series precedent (`run_logs`), and — the deciding argument — is an *automation* app, so exposing
sync as a `band.sync` Action lets 白い熊's own Profiles drive it. No hidden background service, no
surprise battery drain, and he keeps control.

**This hand-off delivers sync and measurement only. No charts.** The charts are
`hand-off-band-charts.md`, deliberately deferred: the band's buffer is small and its true capacity has
to be *measured* over several days of real use. This hand-off builds the instrument that measures it.

---

## The task

Add a `core/band/` package that speaks the Hume Band's BLE protocol, four Room tables to hold the
result, an append-only JSONL archive on storage, a `band.sync` Action, and a 「接続」 tab showing a
per-sync census. Then ship a literate 「接続」 project bundle whose `01` task holds every connection
setting as project-global variables.

---

## 1. The band, and the wire protocol (already mapped — but verify §9 on-device before trusting it)

| | |
|---|---|
| Advertised name | `Hume Band V2 A13A` |
| Address | `D5:A7:06:DC:A1:3A` (static random; **could change if the band is factory-reset**) |
| Firmware | `0.0.2.5` |
| Pairing / bonding | **none — do not call `createBond()`** |
| GATT service | `0000fff0-0000-1000-8000-00805f9b34fb` |
| Write characteristic | `0000fff6-0000-1000-8000-00805f9b34fb` (write + write-no-response) |
| Notify characteristic | `0000fff7-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

### Command frames — always exactly 16 bytes

| byte | meaning |
|---|---|
| `[0]` | opcode |
| `[1]` | mode — `0x00` START, `0x02` CONTINUE, **`0x99` DELETE (never send)** |
| `[2..3]` | zero |
| `[4..9]` | BCD year, month, day, hour, minute, second |
| `[10..14]` | zero |
| `[15]` | checksum = `(bytes 0..14 summed) and 0xFF` |

BCD means the decimal digits are read as hex nibbles: year 2026 → `0x26`, August → `0x08`, the 2nd →
`0x02`. The encoder is literally `Integer.parseInt(value.toString(), 16)`.

```
GET TIME          41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41
HR from 2026-07-28 55 00 00 00 26 07 28 00 00 00 00 00 00 00 00 AA
CONTINUE (HR)     55 02 00 00 00 00 00 00 00 00 00 00 00 00 00 57
```

**Hard-won constraints — do not "improve" these:**

- **A CONTINUE frame carries a ZERO date.** Bytes `[4..9]` are zero, *not* a repeat of the start date.
  Proven from the Hume app's own code: it calls `GetDetailSleepDataWithMode(ModeContinue, "")` and
  `insertDateValue` returns early on an empty string, leaving those bytes untouched.
- **`requestMtu(247)` is mandatory.** The default ATT MTU of 23 gives a 20-byte notification payload.
  Sleep frames are **130 bytes**; heart-rate frames are 240. Without an MTU exchange you will receive
  truncated garbage that still parses into plausible-looking numbers. The Hume app itself requests 153.
  If the granted MTU − 3 < 130, **skip the `0x53` stream and record the reason** rather than parse
  fragments.
- **Paging is frame-counted, not byte-counted.** After every **50** received frames, send CONTINUE.
- **The stream ends when a frame's last byte is `0xFF`.** Terminator frames observed in the wild are
  exactly two bytes (`53ff`, `54ff`, `5cff`) and carry no records.

### Streams

| opcode | stream | frame size seen | record stride | has data on this firmware |
|---|---|---|---|---|
| `0x51` | daily totals | 110 | 27 | yes |
| `0x52` | detail activity | 225 | 25 | yes |
| `0x53` | sleep | **130 = ONE record** | — | yes |
| `0x55` | heart rate | 240 | 10 | yes |
| `0x56` | HRV + stress + BP | 240 | 15 | yes |
| `0x65` | temperature | 242 | 11 | yes |
| `0x66` | SpO₂ | 240 | 10 | yes |
| `0x54` | dynamic HR | 2 | — | **no — dead legacy slot** |
| `0x5C` | workout | 2 | — | **no — only fills if a workout is started from the phone** |
| `0x5E` | 1-second HR | 2 | — | **no — needs firmware ≥ 0.0.3.9** |
| `0x60` | manual SpO₂ | 2 | — | **no — dead legacy slot** |
| `0x62` | temperature history | 2 | — | **no — dead legacy slot** |

Request all twelve anyway. The five empty ones cost one round-trip each, and if a firmware update ever
lights one up we want the census to notice. **Do not** treat an empty stream as an error.

Info queries — single 16-byte reply, no paging:

| opcode | meaning | reply |
|---|---|---|
| `0x04` | device info | — |
| `0x13` | battery | `[1]` = percent |
| `0x22` | MAC | `[1..6]` |
| `0x27` | firmware | `[1..4]` → `"0.0.2.5"` |
| `0x41` | band clock | `[1..6]` BCD |
| `0x42` | user info | — |
| `0x4B` | step goal | `[1..2]` LE16 |
| `0x57` | alarms | — |

---

## 2. Record layouts

Records tile from offset 0 at the stride, and **each record repeats a 3-byte prefix** (`opcode`,
sequence index, `0x00`) — so the field offsets below are *within each record*, not within the frame.

```
0x55  heart rate        [3..8] BCD datetime · [9] bpm
0x56  HRV               [3..8] BCD datetime · [9] HRV ms · [10] vascular age · [11] heart rate
                        · [12] stress · [13] systolic · [14] diastolic
0x66  SpO2              [3..8] BCD datetime · [9] percent
0x65  temperature       [3..8] BCD datetime · [9..10] LE16 × 0.1 °C
0x52  detail activity   [3..8] BCD datetime · [9..10] steps LE16 · [11..12] kcal LE16 ÷100
                        · [13..14] km LE16 ÷100 · [15..24] TEN per-minute step counts
0x51  daily totals      [2..4] BCD date · [5..8] steps LE32 · [9..12] unknown LE32
                        · [13..16] distance LE32 ÷100 · [17..20] calories LE32 ÷100
                        · [21..26] see the warning below
0x53  sleep             [3..8] BCD start · [9] minute count (≤120) · [10..] one stage byte per minute
```

**Sleep stage codes: `1` = deep, `2` = light, `3` = REM, `5` = awake.** Store these **raw**. The Hume
plugin re-codes them to `1`=deep `2`=light `3`=awake `4`=REM before its Dart layer sees them; if you
mix the two numbering schemes you will silently swap REM and awake. Code `4` has never been observed —
count it as unknown and log if it appears.

**`0x51`'s tail is not fully understood.** The field at `[9..12]` is labelled `ExerciseMinutes` in the
vendor SDK but reads 2662 / 4601 / 303 against 6235 / 10044 / 794 steps — roughly 0.4× steps, so the
label is wrong. The vendor SDK claims a step goal at `[21..22]`, but the real frames read `0x0000`
there while `0x4B` reports 10000. **Store `[9..12]` and `[21..26]` raw under neutral names** and do not
build anything on them.

---

## 3. Golden frames — real bytes from the device, use these as test vectors

These came off 白い熊's band on 2026-08-02 at 15:23 local. Every decoded value below was verified.

```
0x55 heart rate, first 20 bytes of a 240-byte frame
  550000260802152034 49  550100260802151530 4a
  → record 0: 2026-08-02 15:20:34, 73 bpm
  → record 1: 2026-08-02 15:15:30, 74 bpm

0x56 HRV, first 30 bytes of a 240-byte frame
  560000260802151930 45 4f 00 4f 00 00
  → 2026-08-02 15:19:30 · HRV 69 · vascular 79 · HR 0 · stress 79 · SBP 0 · DBP 0
  560200260802151530 2c 2f 4a 2f 72 40
  → 2026-08-02 15:15:30 · HRV 44 · vascular 47 · HR 74 · stress 47 · SBP 114 · DBP 64

0x66 SpO2   660000260802152034 60          → 2026-08-02 15:20:34, 96 %
0x65 temp   650000260802145900 6c01        → 2026-08-02 14:59:00, 0x016c = 364 → 36.4 °C

0x52 detail activity, one 25-byte record
  5200002608021519314500e30105002400210000000000000000
  → 2026-08-02 15:19:31 · 69 steps · 4.83 kcal · 0.05 km
  → per-minute [36,0,33,0,0,0,0,0,0,0]  — note the sum is 69, matching the record total

0x51 daily totals, three 27-byte records from one 110-byte frame
  51 00 260802 5b180000 660a0000 5f020000 94b20000 0000 18000000
  → 2026-08-02 · 6235 steps · 6.07 km · 457.16 kcal
  51 01 260801 3c270000 f9110000 64030000 210b0100 0000 24000000
  → 2026-08-01 · 10044 steps · 8.68 km · 683.85 kcal
  51 02 260731 1a030000 …                → 2026-07-31 · 794 steps

0x53 sleep, one 130-byte frame
  530000 260802052857 16 020202050505050202020202020202020202020202020000…
  → start 2026-08-02 05:28:57 · 22 minutes · light,light,light,awake×4,light…

info replies
  13 4c …   → battery 76 %
  27 00000205 …  → firmware "0.0.2.5"
  22 d5a706dca13a …  → D5:A7:06:DC:A1:3A
  41 260802152305 …  → band clock 2026-08-02 15:23:05
  4b 1027 …  → step goal 10000 (LE16 0x2710)
  57 ff      → no alarms (bare terminator)
```

The per-minute counts in `0x52` are **assumed to run forward** from the record's timestamp
(`t+0 … t+9`). Verify this — backward would shift every step sample by nine minutes. It is the one
record field in this document that has not been independently confirmed.

Measured sampling cadences: **heart rate 120 s · HRV 120 s · SpO₂ 600 s · temperature 1800 s.**

---

## 4. Architecture — and why it is split this way

This repo has **no Robolectric and no MockK** (`app/build.gradle.kts` `testImplementation` is junit +
mockwebserver + work-testing only). Anything you want JVM-tested must therefore be Android-free. That
single constraint drives the whole layout: **all protocol, paging and parsing logic is pure Kotlin,
and the only file that touches `android.bluetooth` is a dumb pipe with no logic in it.**

```
core/band/
  BandProtocol.kt       pure   frame encode/decode, BCD, checksum, opcode + mode enums
  BandRecords.kt        pure   per-stream record parsers
  BandStreamMachine.kt  pure   paging state machine (50-frame CONTINUE, 0xFF terminator)
  BandCensus.kt         pure   per-stream stats, buffer-capacity estimation
  BandJsonlCodec.kt     pure   one line per record, String in / String out
  BandJsonlWriter.kt    io     append-only writer, java.io only
  BandRetention.kt      pure+  retention policy, mirrors core/storage/RunLogRetention.kt
  BandSettings.kt       android  SharedPreferences store
  BandGattClient.kt     android  ★ THE ONLY FILE IMPORTING android.bluetooth.* ★
  BandSyncEngine.kt     android  orchestration; owns the Mutex + app-scoped CoroutineScope
  BandSyncState.kt      android  object + StateFlow<BandSyncProgress>
core/storage/  BandSampleDao.kt  BandDailyDao.kt  BandSleepDao.kt  BandSyncDao.kt
core/actions/BandSyncAction.kt
core/engine/BandPruneWorker.kt
ui/screens/BandScreen.kt      VM + Factory + Composable in one file
```

**Do not add a dependency.** `verifyResolvedDependencyPolicy` requires a version-catalog entry plus a
SHA-256 in `gradle/verification-metadata.xml` (which does not exist in this checkout), and there is an
F-Droid readiness gate on top. Use platform `android.bluetooth.*`. No BLE wrapper library, no charting
library.

---

## 5. Making the delete command structurally impossible

Mode `0x99` erases history on the band. It must not be a thing this app can express — not "we are
careful not to call it", but *unrepresentable*.

```kotlin
/**
 * Frame mode. The firmware also defines a DESTRUCTIVE erase mode; it is deliberately ABSENT from this
 * enum and there is no Int-taking frame builder, so no call site in the app can express it. This KDoc
 * line is the only place in core/band that mentions it — BandSafetyGuardTest fails the build if that
 * stops being true.
 */
enum class BandReadMode(internal val raw: Int) { START(0x00), CONTINUE(0x02) }

data class BandCommand(val stream: BandStream, val mode: BandReadMode, val at: BandLocalTime?)

object BandProtocol {
    const val FRAME_SIZE = 16
    /** The ONLY frame builder. There is deliberately no encode(opcode: Int, mode: Int, …). */
    fun encode(command: BandCommand): ByteArray
    fun checksum(frame: ByteArray): Byte
    fun toBcd(value: Int): Byte
    fun fromBcd(b: Byte): Int                            // throws on invalid nibbles
    fun readBcdDateTime(src: ByteArray, off: Int): Long? // yyyyMMddHHmmss, null if not valid BCD
}
```

`BandGattClient.send()` takes a `BandCommand` — there is **no** `send(ByteArray)` and no public
`writeCharacteristic` wrapper, so a caller has nothing to hand it but a value built from that
two-member enum. At the single write chokepoint, before the GATT call:

```kotlin
val frame = BandProtocol.encode(command)
require(frame.size == BandProtocol.FRAME_SIZE)
require((frame[1].toInt() and 0xFF) in ALLOWED_MODE_BYTES) { "refusing mode 0x%02X".format(frame[1]) }
require(frame[15] == BandProtocol.checksum(frame))
```

Then `app/src/test/java/com/opentasker/core/band/BandSafetyGuardTest.kt`, in this repo's established
source-guard style (see `AccessibilitySourceTest`, `ExportedReceiverHardeningTest` — they read source
as text and assert on it):

- no `0x99` / `153` / `0b10011001` anywhere under `core/band/` outside that one KDoc line
- `BandReadMode.entries` is exactly `[START, CONTINUE]` with raw values 0 and 2
- exactly one file under `core/band/` contains `import android.bluetooth`
- no `fun encode(… mode: Int …)` exists
- the strings `erase`, `factoryReset`, `clearBand` appear nowhere in `core/band/`

There is no delete UI, no delete action, and `band.sync` has no clear/erase/reset argument.

---

## 6. Schema — migration 20 → 21

Four tables. Read `core/storage/RunLogDao.kt` first; it is the time-series precedent and this should
look like a sibling of it.

```kotlin
object BandMetric {
    const val HEART_RATE = "hr"; const val HRV = "hrv"; const val VASCULAR = "vascular"
    const val HRV_HEART_RATE = "hrv_hr"; const val STRESS = "stress"
    const val SYSTOLIC = "sbp"; const val DIASTOLIC = "dbp"
    const val TEMPERATURE = "temp"; const val SPO2 = "spo2"
    const val STEPS_MINUTE = "steps_min"; const val STEPS_BUCKET = "steps_10m"
    const val CALORIES_BUCKET = "kcal_10m"; const val DISTANCE_BUCKET = "dist_10m"
}

@Entity(tableName = "band_samples", primaryKeys = ["metric", "localTs"],
        indices = [Index(value = ["metric", "epochMs"])])
data class BandSampleEntity(
    val metric: String,
    /** yyyyMMddHHmmss, straight from the frame's BCD bytes. THE dedupe key. */
    val localTs: Long,
    /** localTs resolved in the phone's zone at sync time. Plotting convenience only. */
    val epochMs: Long,
    val value: Double,
    val syncId: Long,
)
```

**The dedupe key is the band's own wall clock, never epoch millis.** `localTs` is derived byte-for-byte
from the frame with no timezone involved, so re-syncing the same record in a different zone, or across
a DST fall-back hour, produces the identical key. Epoch millis as the key would silently double every
row once a year, in the ambiguous hour. `epochMs` is stored alongside and is disposable.

A bonus falls out: calendar bucketing is integer division, which is DST-correct by construction —
`localTs / 100` = minute, `/ 10000` = hour, `/ 1000000` = day.

`band_daily` is keyed on `localDate` (yyyyMMdd). `band_sleep` is keyed on `startLocalTs` and stores
`stages` as a `String` of raw digit characters — same bytes on disk as a `ByteArray`, readable in a DB
browser and in the JSONL, and **no `TypeConverter`** (this database has none and should keep having
none). `band_syncs` holds one row per sync with the census as JSON.

**A `0x53` frame is 130 bytes and stages start at `[10]`, so one record covers at most 120 minutes.**
A night is therefore several segments, not one row. `band_sleep` stores *segments*; the UI stitches
contiguous ones. Say so in the KDoc — this is the kind of thing that gets mis-modelled once and hurts
forever.

Conflict strategy differs per table, and the differences are load-bearing:

| table | strategy | why |
|---|---|---|
| `band_samples` | `@Insert(onConflict = IGNORE)` returning `List<Long>` | immutable measurements; `-1` = already had it |
| `band_daily` | REPLACE | today's row grows all day — we saw 4,709 steps at 13:25 become 6,235 at 15:23 |
| `band_sleep` | **extend only if longer** | a re-read of an in-progress night must never shorten a completed one |

### The migration

Follow the recipe documented at the bottom of `core/storage/DatabaseMigrations.kt`. Add
`MIGRATION_20_21` after `MIGRATION_19_20`, add it to `getAllMigrations()`, extend the doc block, bump
`OPEN_TASKER_DATABASE_SCHEMA_VERSION` to 21 in `AppDatabase.kt`, add the four entities and four DAO
accessors.

**Write the migration SQL by copying Room's own output, not by hand.** Build once so KSP emits
`app/schemas/com.opentasker.core.storage.AppDatabase/21.json`, then lift each entity's `createSql` out
of it, substitute the literal table name for `${TABLE_NAME}`, and paste. Hand-written SQL is how you
get an identity-hash mismatch at runtime — the file's own comments warn about exactly this. Commit
`21.json`.

**Landmine, leave it alone:** `verifyRoomSchema` in `app/build.gradle.kts:475` hardcodes
`currentVersion = 8`, and `app/schemas/…/18.json` is missing from an otherwise complete set. Because
the gate only checks 1..8, the gap is invisible and bumping the DB to 21 does not break the build. The
tempting "keep it current" edit to `currentVersion = 21` **fails instantly on the missing 18**, which
cannot be regenerated without pinning the DB back to 18 and rebuilding. Leave the gate at 8 and note
it in the commit message.

---

## 7. The BLE layer

### Connection lifecycle — connect, drain, disconnect. Never hold the link.

白い熊 cares about this specifically: a standing Bluetooth session on this phone once drained
1322 mAh in a day. The sync is seconds long and the link closes immediately after.

1. `BluetoothAdapter.getRemoteDevice(address)` — **no scan**. The band is unbonded and addressed by
   MAC, so `BLUETOOTH_SCAN` is not needed by the sync path at all.
2. `connectGatt(app, autoConnect = false, callback, TRANSPORT_LE)`. **`autoConnect = false` is a
   battery decision, not a latency one** — `true` parks a background connection request indefinitely,
   which is precisely the standing session we are avoiding.
3. `requestMtu(247)`, wait for `onMtuChanged`, record the granted value in the census row.
4. `discoverServices()` → `fff0` / `fff6` / `fff7`.
5. `setCharacteristicNotification(fff7, true)` **and** write the CCCD. Both API branches are needed at
   `minSdk 26`: API 33+ `writeDescriptor(desc, value)`, below `desc.value = …; writeDescriptor(desc)`.
6. Info queries first (`0x27`, `0x13`) — they stamp the census so a firmware change invalidates the
   capacity series rather than silently poisoning it.
7. Streams in order, most valuable first, so a late timeout still banks the important data:
   `0x55` → `0x56` → `0x66` → `0x65` → `0x53` → `0x51` → `0x52`, then the five expected-empty ones.
8. `finally { gatt.disconnect(); gatt.close() }` — **unconditionally, on every path.** A leaked
   `BluetoothGatt` is the classic cause of `status 133` on the next connect, and this app will connect
   several times a day.

Serialise every GATT operation — one outstanding op at a time, a `Mutex` inside `BandGattClient` with
callbacks completing a `CompletableDeferred`. Override **both** `onCharacteristicChanged` overloads
(API 33+ delivers only the `ByteArray` variant) and guard against double delivery.

Lint runs with `abortOnError = true` and there is no baseline. Silence `MissingPermission` the way
`LocationContextSourceImpl` already does — an explicit `ContextCompat.checkSelfPermission` next to a
`@SuppressLint`, because you need the permission check anyway to return a good failure message.

### The paging state machine (pure, fully testable)

```kotlin
enum class BandStreamEnd { TERMINATOR, IDLE_TIMEOUT, FRAME_CAP, ERROR }
sealed interface BandStreamStep {
    data object Await : BandStreamStep
    data class SendContinue(val command: BandCommand) : BandStreamStep
    data class Done(val reason: BandStreamEnd) : BandStreamStep
}
```

Rules, in this order:

1. **Slice records first, then check the terminator.** Each record is validated by its BCD date field;
   a record whose nibbles are not valid BCD, or which is all `0x00`/`0xFF`, is discarded. This makes
   the ordering safe either way: if a terminator frame ever *does* carry real records we keep them, and
   if it is a pure sentinel every slice fails validation and nothing is invented.
2. `if (frame.last() == 0xFF.toByte()) return Done(TERMINATOR)`
3. `frames++; if (frames % 50 == 0) return SendContinue(continueCommand)`
4. `if (frames >= 4000) return Done(FRAME_CAP)` — runaway guard
5. else `Await`

Timeouts, all `withTimeoutOrNull`: connect 15 s · discover 10 s · MTU 5 s (non-fatal) · CCCD 5 s ·
per-frame idle 6 s · whole session 180 s. One retry on GATT status 133/8 with a `close()` between.
**A stream that times out is recorded and the sync continues to the next stream** — the band's buffer
is the real risk, so banking six streams beats aborting on one.

### Progress

`object BandSyncState` holding a `StateFlow<BandSyncProgress>` — the same object-with-StateFlow idiom
as `ThemeStore`, initialised from `OpenTaskerApp_NoHilt.onCreate()`. Nothing else in this app uses DI;
do not introduce it here. Also publish `%BAND_Phase`, `%BAND_Pct`, `%BAND_Records` into the variable
store as the sync runs (the `SpeedTestAction` precedent), so a Scene bound to those names animates
with no polling.

### No foreground service

A manual sync is 10–30 s with the app visible, and an Action invoked from a Profile already runs inside
`AutomationService`, which is an FGS. Plain GATT needs no FGS type on Android 14+. The one genuine
risk — the app is backgrounded mid-sync and frozen — is solved by running on an **application-scoped**
`CoroutineScope` inside `BandSyncEngine` rather than the ViewModel's, so backgrounding cannot cancel
it. If field testing later shows backgrounded syncs dying, route the manual button through the
existing `AutomationService` scope; **do not** add a second FGS.

---

## 8. The `band.sync` Action

`id = "band.sync"`, `category = ActionCategory.SYSTEM`, editor category `"Health"`.

| arg | type | default | meaning |
|---|---|---|---|
| `from` | TEXT | `auto` | `auto` = last success minus overlap, else 3 days back; or a day count; or `yyyy-MM-dd HH:mm:ss` |
| `streams` | TEXT | blank | comma list; blank = all enabled |
| `address` | TEXT | blank | MAC override; blank = `BandSettings.address` |
| `prefix` | TEXT | `BAND_` | variable prefix |
| `timeout_sec` | NUMBER | `180` | coerced 15..600 |
| `backup` | CHECKBOX | on | write the JSONL batch |
| `store` | TEXT | blank | variable to receive the summary |

`from=auto` deliberately re-requests a **30-minute overlap**. Overlap is free — the dedupe key
discards it — whereas asking for too little loses data permanently.

Put the parsing in a pure `BandSyncArgs.parse(args): Result<BandSyncArgs>` so it is JVM-testable
without a `Context`.

**Result semantics:** completed with data → `Success`. Completed with every stream terminating
immediately → **`Success`**; "nothing new" is not a failure. Some streams timed out but others banked
data → `Success` with a warning. A sync already in flight (`Mutex.tryLock()` fails) → **`Skip`** —
`TaskRunner` already treats `Skip` as non-failing. Permission missing, adapter off, or everything
errored → `Failure`.

### All five registration sites — every one is load-bearing

Trace `net.speedtest` through the repo; `grep -rn "net.speedtest" --include=*.kt` returns exactly these.

1. `core/actions/BandSyncAction.kt` — the implementation.
2. `core/RuntimeRegistries.kt` — add to `registerBuiltInActions()`.
3. `core/actions/ActionMetadata.kt` — the editor metadata block.
4. **`core/capabilities/AutomationSensitivity.kt`** — mandatory, and the one that is easy to miss. An
   id absent from this registry makes `ActionCapabilityRegistry.get()` return `unknownAction`, so
   `canAdd == false` and **the editor silently refuses to add the action**. Put `band.sync` in
   `dataAccessActionIds` (it reads personal health data) and `deviceControlActionIds`. **Not**
   `destructiveActionIds`.
5. `core/capabilities/ActionCapabilities.kt` — an entry at `CapabilityLevel.RequiresSetup` with a new
   `CapabilityRequirement.Bluetooth`. That new enum member must be threaded through **six exhaustive
   `when (req)` blocks** in `core/capabilities/CapabilityState.kt`. `isMet` →
   `SDK_INT < 31 || checkSelfPermission(BLUETOOTH_CONNECT) == GRANTED`; `settingsIntent` →
   `ACTION_APPLICATION_DETAILS_SETTINGS`, same as `Microphone`.

Miss 2 or 3 and `RuntimeRegistriesTest` fails the build — that gate already exists and is doing its
job. Miss 4 and nothing fails; the action just cannot be added. Don't assume the registries are where
this document says; locate them.

No manifest change is needed. `BLUETOOTH_CONNECT` and `MANAGE_EXTERNAL_STORAGE` are already declared
(`AndroidManifest.xml:43-50, 68`), and no scan means `BLUETOOTH_SCAN` stays unused by this path.

---

## 9. JSONL archive

**Location:** default `/sdcard/〇/[979] バックアップ/[979][60792] 白い熊 自由作業盤/band/` — the backup
root `CLAUDE.md` already designates for this app — **but the directory is a setting**, overridable from
the 「接続」 project's `01` task. Resolve it the way `core/transfer/WorkspaceTransferReceiver.kt`
resolves paths: an absolute path is used as-is, a bare name lands under the default.

**Naming:** one file per calendar month, `band_2026-08.jsonl`. An explicit "export everything" writes a
fresh dated file `band_full_2026-08-02_15-23-05.jsonl` — per `CLAUDE.md`'s full-datetime rule. Nothing
is ever rewritten in place.

**One line per record**, four shapes — a sync writes one header, N records, one census line:

```jsonl
{"t":"sync","id":41,"at":"2026-08-02T15:23:05+02:00","zone":"Europe/Berlin","addr":"D5:A7:06:DC:A1:3A","fw":"0.0.2.5","batt":76,"mtu":247,"from":20260730000000,"src":"action","v":1}
{"t":"s","m":"hr","ts":20260802152034,"e":1785683                 ,"v":73,"sid":41}
{"t":"d","date":20260802,"steps":6235,"dist":6070,"kcal":457.16,"sid":41}
{"t":"z","start":20260802052857,"n":22,"stages":"222555522222222222222","deep":0,"light":18,"rem":0,"awake":4,"sid":41}
{"t":"census","id":41,"ok":true,"ms":18422,"streams":{"hr":{"frames":33,"records":782,"inserted":31,"dup":751,"oldest":20260801183000,"newest":20260802152034,"end":"TERMINATOR"}}}
```

Decode with `core/storage/StorageJson.kt` — the tolerant instance — so a line written by a newer build
never breaks an older reader. That is exactly why that file exists.

**The consistency rule.** `@Insert(onConflict = IGNORE)` returns `-1` for every row already present.
That single return value is simultaneously the duplicate counter *and* the archive filter:

```kotlin
val ids = dao.insertIgnoringDuplicates(rows)
val fresh = rows.filterIndexed { i, _ -> ids[i] != -1L }
// … transaction commits FIRST …
if (args.backup) writer.appendAll(fresh.map(BandJsonlCodec::encode))
```

Invariant: **DB-inserted ⇔ JSONL-written**, one line each, no duplicates across syncs ever. A day of
re-syncing the same window adds zero lines. DB first and file second is deliberate — if the file write
fails the DB is still correct and the census records `backup:"failed:<reason>"`; the reverse order
would leave orphan lines pointing at rows that do not exist. A kill mid-write can leave a torn final
line; every line is standalone JSON, so the reader drops an unparseable trailing line and continues.
**Do not** try to fix that with temp-file-and-rename, which is incompatible with append.

---

## 10. The census — this is the point of this hand-off

The band's buffers are small. Roughly 600 HRV records at a 120-second cadence is about 20 hours, and
older data is silently overwritten. **Nothing deletes it — we proved the Hume app never sends the
delete mode and only ever erases on an explicit Factory Reset — so this is the band's own ring buffer.**
Its true depth per stream is unknown and can only be measured over days of real use.

Per stream, per sync, stored in `band_syncs.statsJson`:

```kotlin
@Serializable
data class BandStreamStat(
    val frames: Int, val pages: Int, val records: Int,
    val inserted: Int, val duplicates: Int,
    val oldestLocalTs: Long? = null,   // ◀ the buffer floor
    val newestLocalTs: Long? = null,
    val expectedRecords: Int = 0,      // gap since previous sync ÷ this stream's cadence
    val lostRecords: Int = 0,          // max(0, expected - inserted)   ◀ the money metric
    val elapsedMs: Long = 0, val end: String = "", val error: String? = null,
)
val BAND_CADENCE_SEC = mapOf("hr" to 120, "hrv" to 120, "spo2" to 600, "temp" to 1800, "detail" to 60)
```

What it answers: ask for 7 days and get 19 hours back ⇒ the buffer is 19 hours. `lostRecords > 0` on a
sync ⇒ the previous gap exceeded the buffer. The largest gap with zero loss is a lower bound and the
smallest gap with loss is an upper bound; after a few days of varied gaps they converge, per stream.

`BandCensus.summarize(syncs): List<BandCapacityEstimate>` returning
`(stream, lowerBoundHours, upperBoundHours, maxRecordsSeen, confidence)` must be a **pure function** —
that is what makes the whole thing testable without a device.

**`band_syncs` is never pruned.** It is a few rows a day and its entire value is the multi-day series.
Put that in its KDoc so a future tidy-up does not eat it.

---

## 11. Retention and pruning of our own data

Copy `core/storage/RunLogRetention.kt` and `core/engine/RunLogPruneWorker.kt` almost line for line:
`BandRetentionPolicy(maxAgeDays = 400, maxSamples = 3_000_000)`, options Short 90 d / Standard 400 d /
Everything 1825 d, a `PeriodicWorkRequestBuilder<BandPruneWorker>(24, HOURS)` with
`ExistingPeriodicWorkPolicy.KEEP` enqueued from `OpenTaskerApp_NoHilt.onCreate()` on the line after
`RunLogPruneWorker.enqueue(this)`. Pruning is safe because the JSONL is the unbounded archive.

---

## 12. The 「接続」 tab

`ui/screens/BandScreen.kt`, following `ui/screens/ContextInspectorScreen.kt` exactly — ViewModel +
`ViewModelProvider.Factory` + Composable in one file, receiving only `db` and `contentPadding`, and
bypassing the 1096-line `ActiveAutomationViewModel` entirely.

Contents: a **Sync now** button with live progress, the band's battery/firmware/clock, per-stream
record counts with oldest and newest timestamps, a **staleness warning** when the oldest record on the
band is approaching the measured buffer limit, and the **census table** — the last 14 syncs with gap,
records, inserted, lost and oldest-record age. That table is the instrument.

Thread `OpenTaskerScreen` through: the enum (~229), `icon()` (~265, `Icons.Filled.MonitorHeart` —
`material-icons-extended` is already a dependency), `headerDetail` (~703), the tab-actions `when`
(~1040 — `emptyList()`, the sync button lives inside the screen), and the dispatch `when` (~1330).
The last screen is persisted **by name**, so adding an enum entry is ordinal-safe.

**Tab label: 「接続」.** (白い熊, 2026-08-02 — he also chose 「健康」 for the charts tab that
`hand-off-band-charts.md` adds later. Two tabs, two names, on purpose: this one is the plumbing, that
one is the data.)

**No charts in this hand-off.** Numbers and tables only.

---

## 13. The 「接続」 project bundle

Ship a literate `OpenTaskerBundle` per `.claude/skills/literate-bundles/SKILL.md` — **bilingual
comments, Japanese first, blank line, then the English translation**; everything referenced by name,
never numeric id; an `itemMeta` note on every task; the minimal reimport set. `adb push` it to
`/sdcard/tmp/` with a full `yyyy-MM-dd_HH-mm-ss` stamp and state the exact filename in your handover
message.

- **`01 接続設定`** — the config task, and the single place 白い熊 edits anything about the
  connection. Sets project-global variables: `%Band_Address` = `D5:A7:06:DC:A1:3A`,
  `%Band_BackupDir`, `%Band_Streams`, `%Band_OverlapMinutes` = `30`, `%Band_TimeoutSec` = `180`.
  Every one documented on its own `var.set` action label, bilingually.
- **`02 同期`** — runs `band.sync` with those variables and flashes the summary.
- **`03 同期状態`** — reports oldest record age per stream and warns if the buffer margin is thin.

Per `CLAUDE.md`: **never** tell 白い熊 to build these by hand.

---

## Done =

白い熊 can open 自由作業盤, go to the 「接続」 tab with the band on his wrist nearby, press **Sync
now**, and watch it connect, pull every stream, and land the data — with a per-stream record count, a
`band_2026-08.jsonl` appearing under the configured backup directory, and a census row.

Then, pressing **Sync now** a second time immediately: every stream reports ~100 % duplicates and
**zero** new JSONL lines. That proves the dedupe key end-to-end and is the single most important
acceptance check in this document.

Cross-check once against the Hume app: heart-rate min/max for today should match exactly. (They did on
2026-08-02: 54–111 bpm from both.)

Build with the **build-apk** skill, and deliver — that skill's standing authorization applies, so
build and install without asking. **Build-only until 白い熊 says "Push".**

---

## Verify these on device before building on them

Ranked by how much breaks if the assumption is wrong.

1. **The granted MTU, and that one notification equals one frame.** Everything downstream assumes it.
   Log the granted value into every census row. If notifications turn out to be fragmented, the
   frame-counted paging rule loses its meaning and you need a reassembly layer — come back and say so
   rather than working around it.
2. **`0x52`'s ten per-minute counts** — forward from the record timestamp, or backward? Assumed forward.
3. **`0x51`'s `[9..12]` and `[21..26]`** — stored raw under neutral names on purpose; do not name them
   after the vendor SDK's labels, which are demonstrably wrong.
4. **Sleep stage `4`** has never been observed. Count as unknown, log if it appears.
5. **`0x55` carries two interleaved series** — the 120 s periodic samples (seconds always `:30`) plus
   an extra sample at each SpO₂ measurement (seconds `:14`/`:34`). If you can distinguish them
   cheaply, tag the source at write time with a `source` column; the charts hand-off wants it.

---

## Repo hygiene

Follow this repo's own `CLAUDE.md`. No `Co-Authored-By: Claude` trailer and no Anthropic attribution
line — end the commit message at the last line of the body. Never commit or push unprompted; build-only
until 白い熊 says "Push". Every `adb push` goes to `/sdcard/tmp/` with a full `yyyy-MM-dd_HH-mm-ss`
stamp, and **never delete an APK from the phone**. Run `adb` with `dangerouslyDisableSandbox: true`,
and `adb disconnect` at the end of the delivery batch.

Add a `docs/hume-band-protocol.md` capturing §1–§3 of this file. The protocol is this fork's own
reverse-engineering and losing it would be expensive. `hand-off-band-charts.md` will reference it.

This hand-off is transient — delete it once the work is accepted.
