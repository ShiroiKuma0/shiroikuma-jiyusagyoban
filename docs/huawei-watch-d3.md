# Huawei Watch D3 — moving 健康 from the Band 11 Pro

The Watch D3 replaces the Band 11 Pro as 健康's only recording device. Once our app has taken the
watch over, the Band is retired the way the Hume band was:

- its workspace tasks go;
- its code stays dormant;
- its data is kept forever.

Every chart continues from Hume → Band → Watch.

**There is no separate capture day.** The work starts the moment the watch is here and runs as one
live session (白い熊, 2026-10-17):

1. The watch goes on Huawei Health on the rooted Samsung, while the Band stays on our app.
2. Each step is captured, decrypted and summarised as it happens.
3. The watch moves to our app as soon as the pairing and the first data are understood.

Wearing both is a matter of **a couple of hours**. No night has to pass.

This file is the session checklist (§1–§2). What the session shows gets recorded in §3 onwards.

---

## 0. What is known before the watch arrives

- **Bluetooth:** dual-mode (BR + BLE, Bluetooth 6.0). The predecessor, the D2, speaks Huawei's Link
  Protocol over Classic RFCOMM, with the same SDP UUID as the Band (`82ff3820-…`). The D2 uses the
  standard HiChain auth.
- **Blood pressure:** nobody outside Huawei syncs it. The only public lead is a disabled stub:
  P2P (`0x34`) dictionary sync, class `10002`, field layout unknown. Gadgetbridge is AGPL, so it is
  used as a reference only and nothing is ported from it.
- **Possible second RFCOMM channel:** newer watches carry health data and file downloads on one.
  The full probe below looks for it.
- **The satellite update is not made redundant by the watch's GPS.** The Band has GPS too. Our
  satellite update is assistance data: it cut the Band's first fix from about 581 s to 13–20 s, and
  the device asks for it itself. Whether the D3 asks Health for the same thing is decided from this
  capture.

## 1. Before starting (minutes, not a day)

**Who does what:** 白い熊 acts only on the Samsung and the watch. Claude runs every command (`check`,
`watch`, every `mark`, the screenshots), announces each step, waits for 白い熊's “done”, and reads
out what the step produced before the next one starts. Nothing is typed in the terminal by 白い熊.

**The order is fixed: Samsung → log → watch.** The first pairing writes the handshake every later
frame is decrypted with. If the log is not running at that moment, the whole session is unreadable.

1. The watch is charged, but stays **switched off**.
2. On the Samsung:
   - Huawei Health is signed in with 白い熊's Huawei ID;
   - its permission prompts (Bluetooth, location, notifications) are accepted;
   - the region is set so that blood pressure and ECG are offered (both are country-gated);
   - every other Bluetooth device (earbuds etc.) is disconnected, so the log holds only the watch.
3. The Samsung (`R3CW70FPPDV`) is plugged into the PC over USB.
4. Claude runs:

   ```bash
   python3 scripts/huawei-capture-session.py check
   ```

   It must end with `ready`. That means:
   - root is present;
   - `dumpsys bluetooth_manager` reports a FULL snoop log;
   - Huawei Health is installed.

   If snoop is not full: Developer options → Bluetooth HCI snoop log → Enabled, then Bluetooth off
   and on, then check again. If the log is already near the ~24 MB rotation point, Bluetooth goes
   off and on anyway, so the session starts with room.
5. Claude starts `python3 scripts/huawei-capture-session.py watch` in the background and takes a
   `start` mark. **The first mark only sets the baseline**: each later mark is analysed against the
   one before it, so `start` must exist before the watch is touched.
6. The Band stays on our app on the Mate XT, worn and syncing as usual.

## 2. The live session

Every step runs the same way: Claude marks, tells 白い熊 the step, waits for “done”, marks again
(`python3 scripts/huawei-capture-session.py mark <label>`). **Every mark decrypts and summarises its
step on the spot** (`scripts/huawei-capture-analyze.py`): which service and command the step caused,
how many frames and bytes, and whether they decrypted. A step that produced nothing, or frames we
cannot read, is **redone at once** — never discovered later, so nothing has to be repeated another
day. `--dump` prints the TLVs.

**On a ROTATION message** from the watcher: disconnect and reconnect the watch in Health at once.
That writes a fresh handshake into the new log; without it every later frame is undecryptable.
Claude checks the next mark recovers keys again.

The order is by value, not by clock. Anything that needs a night (sleep, the 24-hour blood-pressure
series) is checked later against the watch's own screens once our app reads it, not waited for here.

| # | Step | Check before moving on | Ground truth to keep |
|---|---|---|---|
| 1 | `pair` — switch the watch on and pair it **from inside Health** (Devices → Add), never from the Samsung's Bluetooth settings. Accept the prompts on the watch and the Samsung, through to the watch face. | **Keys recovered > 0.** If not, the pairing is redone now (factory reset of the watch if needed), before anything else. | — |
| 2 | `firmware` — let Health install the update it offers: it is the firmware our app will talk to. It is large and **will rotate the log**, which is harmless because the pairing copy is already saved. Afterwards, disconnect and reconnect the watch in Health (`reconnect` mark). | The `reconnect` mark recovers keys from the new log. | the firmware version from the watch's About screen |
| 3 | `sync-all` — a manual sync of every data type | frames decrypted | screenshots of each data page |
| 4 | `bp-spot` — one cuff measurement, then sync | frames decrypted | screenshot of Health's reading |
| 5 | `ecg` — one ECG recording, then sync | frames decrypted | screenshot of the result |
| 6 | `features-on` — skin temperature, arterial stiffness, stress/emotion, automatic SpO2, HRV, sleep apnea, 24-hour BP, each switched on | frames decrypted | — |
| 7 | `walk` — a short outdoor walk with GPS; before it, Health's satellite (AGPS) update served to the watch | frames decrypted | screenshot of the route and the distance |
| 8 | `face`, `weather`, `language` | frames decrypted | — |
| 9 | `sync-end` — a last sync after everything above, with the hours of wear in it | frames decrypted | screenshots of temperature, BP, HR, SpO2 |

`python3 scripts/huawei-capture-session.py status` between steps shows the bytes each step added.

**Independent ground truth:** Health → Me → privacy → *download your data*. Huawei's export carries
the same readings, and it is what our decoding gets checked against, value by value.

Screenshots are taken by Claude — screencap works on the Samsung (it is not EMUI):
`adb -s R3CW70FPPDV exec-out screencap -p > <name>.png`. They go with the capture in
`.scratch/d3/capture/`.

As soon as §3.1–§3.3 are understood (transport, auth, first-run set), the watch can move to our app
(§4). It does not wait for every data stream to be decoded: those can be decoded from our own pulls,
checked against the watch's screens. The Band is worn until our first sync from the watch checks out.

## 3. Decoding — live, and continued on the PC

The tools are the ones that decoded the Band; they live in `.scratch/` and are generic for any Link
Protocol device unless noted:

| Tool | What it does |
|---|---|
| `.scratch/hw/btsnoop.py` | btsnoop → RFCOMM → frames → keys → AES-GCM. Keys are derived from the pairing in the log; no root needed for that. |
| `.scratch/hw3/census.py` | The decrypted, reassembled frame list (`frames.pkl`). Everything below reads it. |
| `.scratch/hw3/caps.py` | The device's own SupportedServices / SupportedCommands replies. |
| `.scratch/hw3/timeline.py` | Service/command pairs in time order. Read the first-run set off it. |
| `.scratch/hw3/tally.py` | Frame counts and bytes per service/command. |
| `.scratch/hw3/final.py` | The implemented / supported comparison. Its sets are hard-coded for the Band; generalise it first. |
| `.scratch/hw3/p2p.py` | P2P (`0x34`) module names. |
| `.scratch/hw3/dd*.py` | Dictionary-sync records — where blood pressure is expected. |
| `scripts/huawei-extract-gnss.py` | The satellite files Health served, and a transcript. |
| `scripts/huawei-extract-watchface.py` | Faces sent over `0x27`/`0x28`. |

Each mark already summarises its own step. For deeper work, slice by the marks in
`.scratch/d3/capture/marks.json` (`huawei-capture-analyze.py <copy> --from <epoch> --to <epoch> --dump`).

What to record here, step by step:

1. Transport and auth:
   - RFCOMM channel(s), and whether a second socket is used;
   - authVersion and deviceSupportType;
   - the pairing prompt.
2. The D3's SupportedServices / SupportedCommands / ExpandCapability, against the Band's.
3. The first-run configuration set: its order, its payloads, and which commands never answer.
4. Each data stream — service, file or P2P class; its layout; its timestamps:
   - blood pressure (spot and ABPM), ECG, skin temperature, arterial stiffness;
   - sleep, RRI;
   - workouts and GPS.
5. Satellite assistance: is it the same `0x1F` ask, the same `HW_PGNSS_*` / `HW_AGNSS_RTCM_33` files,
   the same EEV2 layout and token as the Band?
6. The watch-face format: screen size, theme version, thumbnail.

### 3.7 Findings from the live session (2026-10-17)

**The device.** Model `RIV-B10` (“Riva”), HarmonyOS; Health updated it from `7.0.0.103(SP25C00M00)`
to **`7.0.0.109(SP25C00M01)`** (0x01/0x07 tag 7). **Two Bluetooth addresses:** Classic (BR/EDR) **`70:90:B7:D7:37:44`** (0x01/0x07 tag 5) — the one
Health pairs on (SSP “just works”, IO capability NoInputNoOutput) and the one `huawei.pair` must use — and
BLE `F0:91:B7:D7:37:44` (tag 34). A Classic `createBond` to the BLE address is refused by the watch. The
pairing decrypts with the existing tools: 4 keys from the first pairing, 2 more from the re-auth after
the update's restart, 0 undecryptable frames throughout.

**Capture tools.** The Samsung writes btsnoop timestamps in its LOCAL time (CEST, +7200 s), so the
analyser takes `--utc-offset` (the session script passes the phone's `date +%z`). The Fold has two
displays, so `huawei-capture-session.py shot <label>` writes one PNG per display.

**Firmware goes over Wi-Fi Direct.** The 72 MB image (0x09/0x0E tag 2 = `0x044E76D3`) was not in the
Bluetooth log: 0x09 negotiated it (0x09/0x09 `1:01 2:01`), then the Samsung formed a Wi-Fi P2P group
as owner with the watch as its one client for 81 s. btsnoop cannot see that link, so every step is also
checked against `dumpsys wifip2p` for a new `AP_STA_CONNECTED_EVENT`.

**Already in the pairing:** satellite assistance on the Band's own services (`0x1C`, 845 KB; `0x1F`),
~180 P2P (`0x34/0x01`) frames each way, ~530 KB over the file service (`0x28/0x06`).

**Heart settings** (each flipped both ways, every reply `127:000186A0` = OK):

| Setting | Command | Payload |
|---|---|---|
| Continuous monitoring on / off (= Smart mode) | `0x07/0x17` | tag 1 `01` / `00` |
| Real-time mode | `0x07/0x1C` | tag 1 `01`; choosing Smart again sends only `0x07/0x17 01` |
| High heart rate alert | `0x07/0x1D` | tag 1 `01` (on), tag 2 = bpm (`0x78` = 120, `0x82` = 130) |
| Low heart rate alert | `0x07/0x22` | tag 1 `01` (on), tag 2 = bpm (`0x28` = 40, `0x2D` = 45) |

Turning monitoring off greys the alerts to “Disable” on screen but sends nothing for them; turning
it on again sends only `0x07/0x17 01`, and the watch keeps the alert values.

**Module messages (`0x37`) carry app-to-app traffic**: tag 1 = sender, tag 2 = receiver, the body
in tag 131 (0x37/0x01) or tag 5 (0x37/0x02). Filtering 0x37 as chatter hides real features, so the
session lists every sender → receiver pair per step (`.scratch/d3/modules.py`).

**ECG.** Health's ECG is a separate certified app (“ECG analysis alert software”, model ECG-1). The
consent and permission screens send nothing to the watch. Once the permissions are granted, the phone's
`hw.health.ecganalysis` talks to the watch's own **`hw.watch.health.ecganalysis`**, which reports
`V1.0.0.27`, `2026.9` and a UDI `(01)06941487275335(8012)1.0.0.27` — the watch carries its own
certified analysis. **ACTIVATE is one 0x37/0x01 message** to that module (tag 131:
`…07 1A 01 01 01 02 15 "hw.health.ecganalysis" 08 08 <ms timestamp>`).

**ECG recordings are a file, pulled on request.** A measurement sends nothing by itself; the
recordings wait on the watch until Health's ECG app opens. Then the phone pulls
**`ecg_analysis_data.bin`** over the ordinary file-download service (`0x2C`, the Band's sleep-file
path): file type `0x18`, chunk 1000 B (`0x03E8`), window 8000 B (`0x1F40`), finished with
`0x2C/0x06 1:18 2:01`. The file holds every recording on the watch (60 112 B with one, 120 168 B with
two), so it is cumulative. The first pull stalled at 40 000 B — the watch stopped answering
`0x2C/0x04` — and Health simply asked again a minute later.

Layout (big-endian), checked on both recordings:

- a 57-byte file header (`00 00 01 D5 48 00 08 00 00 18 …`, then `hw.health.ecganalysis`);
- per recording, 60 056 bytes:
  - start and end, Unix seconds (u32 each; 30 s apart);
  - the sample byte count, u32 = 60 000;
  - a 44-byte block: `V1.0.0.27` (the watch's analysis version), zeros, then **two bytes that differ
    per recording** (`40 52` and `01 50`) and zeros. the first is the **watch's verdict** —
    `0x01` = sinus rhythm, `0x40` = inconclusive (confirmed against the watch's own results); the
    second is the **mean heart rate** (`0x52` = 82, `0x50` = 80; ≈ 82 beats counted in the second
    trace's 29 s). Other verdicts (A-fib, premature beats …) are still unseen codes;
  - **15 000 samples, float32, 500 Hz** — a clean single-lead trace with clear QRS complexes after
    removing the baseline drift (the raw values carry a large offset, −3.5·10⁷ … 2.9·10⁶).

**The app-message frame (0x37/0x01, tag 131).** `84 <len>` wraps: tag 5 (4 B) = **the attribute
id**, tag 6 = a small header (`01 01` set, `01 02` query), tag 7 = the value block (`<id> <len> <v>`
items). One shape for every feature:

| App (watch side) | Attribute | Meaning | Value |
|---|---|---|---|
| `hw.watch.health.ecganalysis` | `35A97CE5` | ECG activate | `1A 01 01 01 02 15 "hw.health.ecganalysis" 08 08 <ms>` |
| | `35A97CE6` | status query → `07 03 01 01 01` (activated) | |
| | `35A97CED` | version query → `V1.0.0.27`, `2026.9`, UDI | |
| `hw.watch.health.arrhythmia` (from `hw.health.ppgjsmodule`) | `35A97CE2` | automatic arrhythmia detection | `07 03 01 01 00` off / `01` on |
| | `35A97CE9` | arrhythmia alerts | `07 03 01 01 00` off / `01` on |

**Arterial stiffness: P2P dictionary class 400017 (`0x061A91`)**, pushed by the watch at once, the
same way as blood pressure (announced by `dic.event`, then a `0x34/0x01` record). Measured 20:45:55:

| Field id | Value | Meaning |
|---|---|---|
| 400017238 | 6.526719… | **pulse wave velocity, m/s** (the watch showed 6.5) |
| 400017567 | 83.0 | heart rate, bpm (to confirm) |
| 400017498 | 1.0 | grade: Health showed **Normal** (< 8 m/s); Slightly stiff 8–10 and Stiff ≥ 10 presumably 2 and 3 |
| 400017423 | 0.0 | unknown |

No waveform is ever sent: opening Health's page only re-queries the status, and “Show more” adds an
age-group PWV curve that Health draws from the profile's age. The record is everything Health has.

Activation differs from the ECG's: `hw.health.pwv → com.huawei.health.pwv` sets its status attribute
`35A97CEA` to `07 03 01 01 01` with a ms timestamp (tag 8), and the watch echoes it.

After each dictionary event Health re-sends the current settings unasked (`0x0F/0x05 00`,
`0x07/0x1C`, `0x07/0x1D`, `0x07/0x22`, and an `ecganalysis` `35A97CEE` message) — the same values,
not a change.

**Health's automatic sync** ran during the arrhythmia flips: the same fitness/sleep/stress sequence
the Band answers (`0x07/0x03`, `0x07/0x0A`–`0x0D`, `0x07/0x1E`/`0x1F`/`0x26`, `0x16/0x01`/`0x04`,
`0x17/0x07`, 23 × `0x07/0x27` each answered `000186A7` as on the Band). A second dictionary event
announced class `0x030D45` = **200005** (to identify).

**Blood pressure: P2P dictionary sync, class 10002 (`0x2712`)** — the one public lead, confirmed. A
spot measurement on the watch is pushed at once, unasked:
`hw.watch.health.p2p → hw.unitedevice.dic.event` (0x37/0x02) announces it, then the watch's
`hw.watch.health.filesync` sends `hw.unitedevice.datadictionarysync` a `0x34/0x01` record (tag 7):

| Field id | Value (IEEE double) | Meaning |
|---|---|---|
| 2006 | 129.0 | systolic, mmHg (all three confirmed on the watch: 129/88, pulse 81) |
| 2007 | 88.0 | diastolic, mmHg |
| 10002537 | 81.0 | pulse, bpm |
| 10002789, 10002698 | 1.0, 1.0 | unknown flags |
| 10002462 | 4096.0 | unknown (bit field?) |

The record also holds the measurement time twice (tags 5 and 0x0C, ms since 1970). Each 0x34 message
is acknowledged by the receiver with tag 1 = `03` and the same tag 2 sequence number.
`hw.watch.health.bp.benefit` asks `hw.unitedevice.benefit` for a paid service (`{"resultCode":101,
"isHasBenefit":0,"isHasSignDoctor":0}`); not needed.

Switching heart rate to Real-time in Health (done by 白い熊 during this step) sent `0x07/0x1C 01`
and re-sent both alerts (`0x07/0x1D`, `0x07/0x22`) with it.

**Skin temperature: `0x07/0x2A`**, a plain command: `1:01 2:<on> 3:FFFF` — tag 2 `01` on / `00`
off; tag 1 and tag 3 (`FFFF`, likely “no interval”) unchanged. Off from the factory, so the page
was empty until it was switched on (left on, 2026-10-17 20:57).

**Blood-pressure page.** Opening it starts Health's full sync. The scheduled-measurement page holds
only reminders, and never inflates the cuff. Opening it WRITES three default plans to the watch, all
`"enabled":0` — 08:00 daily, 20:00 daily, 09:00–12:00 every 30 min (P2P `hw.unitedevice.bloodpressure` ↔
`hw.watch.health.bp`, cmd `35A97D1C`; the reconnect queries them, answer `{"reminderPlans":[]}`). Not wanted. The ABPM page is an introduction only — ABPM is started on
the watch (ABPM → Start plan) and runs 24 h, measuring automatically every 30 min at night; it is the
ONLY automatic cuff measurement the D3 has. Health's state query (`hw.health.dynamicBp` →
`hw.watch.health.dynamicbp`, `35A97D07`) answered an empty plan on 2026-10-17. Not run in the session, to be read from our own sync (probably the same class
10002 with one of the flag fields marking it).

**`0x07/0x02` = the user profile**, re-sent whenever the Heart or Temperature page opens
(`3:1A 6:47 7:8D 8:0 9:0 4:07D00101 11:FFFFD8F0`); to decode.

**SpO2 and TruSleep use the Band's own commands**, already in `HuaweiCommands`:
`0x07/0x24` automatic SpO2 (`00`/`01`), `0x07/0x25` low-SpO2 alert (`1:01 2:<percent>`, `0x5A` = 90;
`1:00` when automatic SpO2 goes off), `0x07/0x16` TruSleep (`00`/`01`). So are the heart-rate ones
(`0x07/0x17`, `0x07/0x1D`, `0x07/0x22`). Opening the sleep page sends `0x07/0x18 01` (unknown;
not in our Band code). Health's sleep settings are all phone-side (routine, sounds) and send nothing.

**Sleep apnea is its own watch app**, `hw.watch.health.osa`, driven by `hw.health.apneajsmodule`:
`35A97CE7` = status (query → activated, with its activation timestamp), `35A97CE8` = the switch
(`07 03 01 01 00` off / `01` on) — Health's “Sleep breathing awareness”. Turning TruSleep OFF also
switched it off; turning TruSleep back ON did **not** switch it on again. Switching it on sends only
`35A97CE8 = 01` (TruSleep was already on, so no `0x07/0x16`). Left ON 2026-10-17 21:11.

**Emotion / stress is its own watch app**, `hw.watch.health.emotion`, driven by
`hw.health.emotion`: attribute **`35AC8A3C`** = Health's “Additional emotions/stress records”
(`07 03 01 01 00` off / `01` on; left on). On the Band, emotion was unreachable.

**The sync pulls the dictionary classes one by one.** Health asks `hw.watch.health.filesync` for
each class it knows (a `0x34/0x01` tag 7 = `01` + TLVs: tag 1 `01`, tag 2 = class, tag 5 = “since” ms,
tag 6 = now ms); the reply carries the class's records in a `0x83` container of `0x84` records (tag 5 /
`0x0C` = time ms, `0x87` = fields of `0x88 {9: field id, 10: IEEE double}`), or an empty `83 00`.
TLV lengths are base-128 varints; tag 7 starts with a version byte. Decoder: `.scratch/d3/dict.py`.
Classes asked on 2026-10-17: 10002, 200003, 200005, 300002, 400011–400026, 500001–500010 (part).

| Class | Field | Meaning | Evidence |
|---|---|---|---|
| **400012** | 400012430 | **skin temperature, °C** | 32.5 at 21:18 = Health's screen (a measurement on the watch's app) |
| 200003 | 200003586 | one value **per minute**, 260.3–263.0 | not temperature (Health showed nothing for it); started 20:47:59, before the temperature switch; fits **barometric altitude in m** (to confirm) |
| 300002 | ~20 fields | **daily summary** from 00:00 | 300002242 = 10000 (step goal?), 300002409 = 4722 → 4772 between two syncs (steps today?), 300002263 = 270000 … |

Continuous skin temperature had produced no class-400012 record 25 min after being switched on; the
first value came from a manual measurement. Its continuous cadence is still to be seen.

**Watch face: the Band's container, a bigger screen.** A free store face (“Wild Race”, hitop id
2186431433, v2.1.1, 1 904 559 B) went over Bluetooth through the file-upload service (`0x28`, 2 037
`0x28/0x06` chunks; `0x27` around it), not Wi-Fi Direct; `scripts/huawei-extract-watchface.py`
recovers it unchanged (the copy was deleted 2026-10-18: Wild Race is a TRIAL face that switches itself off on the watch after three minutes, so it is no use to keep). The `.bin` is the container of
`docs/huawei-watch-face-format.md` §2 (version 2, `16 + L1 + L2 + L3 == filesize`, 181 resources).
The largest image is **408 × 480** — the D3's screen — and the thumbnail **240 × 282** (Band: 286 × 482
and 172 × 290). The resource headers do not read as the Band's `family / format` pairs; to check.

**Language:** at pairing Health sent `0x0C/0x01` `1:en-US 2:00`, the Band's command and layout.
**Weather:** pushed unasked during the ECG step on the Band's service (`0x0F/0x01`–`0x0B`, location
name in `0x0F/0x01` tag 8) together with the phone's position (`0x18/0x07`).

### 3.9 Takeover, 2026-10-17 21:51

Unpairing in Health left the watch on its normal face; a **factory reset** on the watch brought the
pairing screen. `huawei.pair` (task `時計接続（D3） -- [727]`) on the **Classic** address then paired,
bound and provisioned it with the Band's first-run set — “paired and provisioned — answered 6
requests” — and the watch went to its face. The Band stayed the active device and kept syncing.

`huawei.probe full=true` (task `時計棚卸し（D3） -- [727]`): auth version 1, device support type 4;
SDP lists a second Huawei UUID beside `82ff3820-…`: **`65847aa1-102a-05e1-43b6-e2a3965be547`**.
Services answered 128, supported 30, three not in the Band's list (0x01, 0x21, 0x28).

**The census lies, as the Band's did.** It answers NO for 0x1C / 0x1F (satellite — Health used both
tonight), omits 0x2C / 0x37 (files, app messages — both used), and lists no 0x07/0x2A (skin
temperature — accepted). Never gate a step on it.

**Variables collide:** the pair task and the Band's automatic sync both write `HUAWEI_*`; the Band
sync started seconds after the pairing and overwrote its result. D3 tasks need their own prefix.

### 3.10 Faces: the D3 ENFORCES the signed store record (2026-10-17)

The opposite of the Band (`docs/huawei-watch-face-format.md` §1). Installed from our app over the
Classic address, all sent in full, then `wf/3 r=100014` (not the Band's 100007 “no room”):

| Face | Store record | Result |
|---|---|---|
| Wild Race 2186431433, untouched | its own (D3 `deviceId`) | **installed** |
| MZ DIGICOLOR 7184120553, untouched | genuine, but the Band's (`deviceId` differs) | refused 100014 |
| Wild Race renamed (new id, new hash) | Wild Race's, now mismatched | refused 100014 |
| 相撲字時計 D3 7953832255 (layout C) | MZ DIGICOLOR's | refused 100014 |

`watchContentHash` = SHA-256 of the `.bin` (checked on both faces); the record (`hitopId`,
`watchContentHash`, `deviceId`, …) is signed by Huawei (`contentSign`). So our transfer works and the
watch verifies the record.

**Health sends tag 3 = `05` to the D3** (`01` to the Band). +048 exposes both knobs on
`huawei.watchface` (`record` = store / none / empty / unsigned, `mode` = the tag-3 byte). Every
combination tried on 相撲字時計 D3 was sent in full and refused with the same 100014 at the end:
store+5, none+1, none+5, unsigned+1, unsigned+5, empty+5. There is no unsigned path through the
ordinary install.

**What is known outside (research, 2026-10-17):** Huawei documents ONE way to test an unpublished
face on a watch — Huawei Health signed in with an ID holding the “HUAWEI Themes certified designer
(watch face)” permission, Themes → Watch Face → Mine → Add watch faces, a `.hwt` from Theme Studio
(developer.huawei.com, “watch-face-test”). Presumably Huawei's server then signs it for that one
watch. Gadgetbridge (#6005) reaches the same wall on HarmonyOS 6.1+ watches and names the same record
fields; a Coolapk report claims an unstable bypass (sleep mode on, a third-party face current, a
hooked Health on a rooted phone; faces vanish on restart). No public source names 100014.

The D3 build itself is done and kept: `scripts/sumoji-build-d3.py` (layout C, gaps 4, mark
re-derived and measured), packed to `.scratch/d3/sumoji/pack/7953832255_2.1.1.bin`.

### 3.11 The dictionary answers only after Health's reconnect (2026-10-18)

Our first syncs (+049 – +053) sent the dictionary queries byte for byte as Health does; the watch
acknowledged every one (`03 … cf`) and answered none, where it answers Health within 5 ms. Ruled out
one at a time, none of them enough on its own: the message counter, the "since" value, the
privacy switch / sync-device / blood-pressure-status messages Health sends at pairing (+052), and
Health's full account step (+053).

**What fixed it (+054): Health's whole reconnect**, replayed before anything is asked —
`HuaweiD3Reconnect`, about thirty commands in Health's order and with Health's payloads, from its
reconnect of 2026-10-17 19:48:25. The first sync with it brought 1 771 values from 1 621 records,
30/30 classes answered: blood pressure 129/88/81 (20:17:21), arterial stiffness 6.53 m/s grade 1,
skin temperature every minute from 21:18, the per-minute class 200003, the daily summaries from
2026-10-15, and the night classes 500007 / 500010.

The likeliest single cause is the **country feature file**, `feature_CZ_device.txt` (2 913 B, file id
`0x0F` on the upload service `0x28`, SHA-256 `d02ff995…`), which Health uploads on every reconnect:
it declares which health features the phone side supports — blood pressure (ABPM thresholds
135/85, 120/70, 130/80), ECG (CE), arterial stiffness, the health check, emotion, plateau care,
arrhythmia, sleep apnea. It is Huawei's file and holds nothing personal; the app carries it as
`assets/huawei/feature_CZ_device.txt`. Not isolated — the whole replay ships, because it is what
Health does and it costs about two seconds.

Left out of the replay: the language (`0x0C/0x01` — connecting never changes it), the user profile
(`0x07/0x02`, personal values), and what the sync asks for itself. The account ID (`0x1A/0x05`,
`0x1A/0x01`) is 白い熊's own and private: it is `%Huawei_AccountId` in 「健康の設定」, handed over
as the `account` argument of the pair and sync actions and stored only in the app's private
settings. It was in the code from the fork's start and was purged from the GitHub history on
2026-10-18.

### 3.11a Pulse-wave arrhythmia is class 500007 (2026-10-18)

The watch's own screen on 2026-10-18 17:13: "7-day stats, total 37 times — no abnormalities 37,
suspected A-fib 0", 3 on the 17th and 34 on the 18th. Class **500007** matches it record for record:
3 on 2026-10-17 (22:36, 22:50, 23:40) and the rest on the 18th, every ~10 min through the night.
Fields: `2200` = 1 on every record, `500007088` = 0 (the result — 0 = no abnormality, to confirm once
an abnormal one exists), `500007278` = the measurement time again, in Unix seconds. Kept as raw
`dict_500007_*` until named; not shown on any page yet. (Class 400017 is arterial stiffness, not
arrhythmia.)

### 3.12 The sync answers the watch's satellite asks (2026-10-18)

The walk of 2026-10-18 had a fix in its first second. The orbits were not the reason: our own
predicted set agrees with Huawei's to 2–7 m (GPS), 4–7 m (Galileo) and 5–17 m (GLONASS) over the
60 hours both cover, and the broadcast file comes from the same Huawei endpoint. The reason was
**timing**: Health answers the watch's ask on every connection, and had served a fresh broadcast
file at 10:16, while our sync never answered at all.

**The asks**, decoded from Health's captures (pairing 2026-10-17 19:29, reconnect 2026-10-18 10:39).
The watch raises `0x1F/0x01` by itself, seconds after a companion connects and only for what has gone
stale, and names what it wants in its answer to `0x1F/0x02` (tag 129 → tag 6):

| source | Health's answer |
| --- | --- |
| `higeo/v1/gnssinfo?type=0x0004/HW_AGNSS` | ready `03`, lists `HW_AGNSS_RTCM_33` alone |
| `com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2/HW_PGNSS_PRED;` | ready `03`, lists the six `HW_PGNSS_*` |
| `higeo/v1/gnssIonInfo/<lat>,<lon>/HW_PRECSION_ION` | ready **`04`** — declined, nothing listed |

**The block token** ahead of the file name in every `0x1C/0x04` answer is the **SHA-256 of the
device's Bluetooth address**, upper-case hex, colons removed — the Band's constant and the D3's
each hash to their own address. Until +061 the Band's was sent to every device.

**What the sync does (+061)** — `HuaweiGnssServer`, `HuaweiGnssSupply`:
- right after Health's reconnect it waits up to 3 s for an ask, and answers every round the watch
  raises; at the end it answers any ask raised meanwhile. Nothing is announced unasked;
- the broadcast file is **downloaded at the moment of the ask** (a few KB, a second); if the
  download fails, the stored copy is used while under four hours old, otherwise the ask is declined;
- the forecast is the set in the store (built by 衛星 生成), handed over only while it has at least
  6 h left — a dead set is never given;
- the ionosphere ask is declined, as Health declines it;
- each round is a `SYNC ·` line in `served-log.txt`; a taken forecast writes `band-holds.txt` and arms
  the expiry reminder, as the 衛星 tasks do;
- `HuaweiClient.pump` puts satellite frames back in the queue instead of dropping them.

No standing connection: the watch is answered whenever the sync connects (every four hours, and on
each manual sync). Health holds the link open and so answers the moment the watch asks.

**When the watch asks for a forecast (measured 2026-10-18 – 20, local times).** At the first sync
more than **24 hours after it received** the set it holds — the age of the data does not enter. Health
handed over a set on 10-17 at 19:28 (its blocks starting 20:00); the watch asked at the 20:00 sync on
10-18 (24.5 h) and took ours. The 20:00 sync on 10-19, exactly 24.0 h after that, got no ask although
the data was then 28 h old; the 00:00 sync on 10-20 (28 h) did. Since a receipt is itself at a
sync slot, the ask lands on the slot 28 hours later and moves four hours later each day. The old
nightly 00:00 build ran two minutes after the 00:00 sync and so handed over a day-old set, which the
watch — counting 72 h from receipt — over-claimed by that day.

**When the phone builds (+070, `GnssRebuildSchedule`).** 30 minutes before the first local sync
slot after 24 h from the later of the last take and the last successful build; the profile
`衛星 毎晩生成` is gone and the alarm runs `衛星 予定生成 -- [727]`. Firing plans a retry 30 minutes
on, which a successful build replaces with the next plan. The plan is re-armed on engine start and
written to `rebuild-plan.txt` beside `built-log.txt`.

### 3.8 Health's graph pages to rebuild in 健康 (白い熊, 2026-10-17)

Every Health page that has a graph gets its counterpart in our data-graph pages. Screenshots of each
are kept in `.scratch/d3/capture/shot_*` as the reference:

| Health page | Screenshot | Contents |
|---|---|---|
| Heart (Day / Week / Month / Year) | `heart-tab` | HR range, last value, resting HR, day curve; HR / HRV toggle |
| ECG records | `ecg-list` | per record: verdict, mean HR, time, trace preview; counts per verdict |
| Pulse wave arrhythmia (Day / Week / Month / Year) | `pwa-open` | measurements per hour, coloured by result; totals per result |
| Arterial stiffness | `as-page`, `as-show-more` | last PWV on a 3-band gauge (< 8 / 8–10 / ≥ 10 m/s), grade, advice; age-group comparison curve |
| Elasticity trend (Month / Year) | `as-elasticity-trend` | PWV per measurement over the month, average, min–max |

| Blood pressure (Day / Week / Month / Year) | `bp-page`, `bp-page-lower` | avg BP and pulse; systolic / diastolic points on shaded bands; “resting BP” class counts; max / min with grades and reference ranges; ABPM entry |
| Skin temperature (Day / …) | `temp-page` | (empty until switched on) |
| SpO2 (Day / Week / Month / Year) | `spo2-page` | avg; bars per half-hour on < 70 / 70–89 / ≥ 90 % bands; low-SpO2 marker; last value, range |
| Sleep (Day / Week / Month / Year) | `sleep-page` | hypnogram (deep / light / REM / awake); score; week of durations; phase times; graded against references |
| Emotional wellbeing — Emotion | `stress` | marks through the day per pleasant / neutral / unpleasant; dominant emotion and shares |
| Emotional wellbeing — Stress | `stress-tab` | bars per sample, 0–100, coloured low / normal / medium / high; avg and last |

The Heart, SpO2, Sleep and Stress pages showed the **Band's** August history (it was paired to Health
then), so the same days exist in 健康 to compare our rendering against.

## 4. Taking the watch over

1. Release the watch from Health through its own Settings → Disconnect. Never by removing the bond.
2. Pair it to the Mate XT with `huawei.pair`:
   - `address` = the watch;
   - `make_active` = false, so the Band carries on syncing until the watch's sync is proven.

   Each device keeps its own bind (`HuaweiSettings`, per address since 2026-10-17).
3. Run `huawei.probe` with `full=true` on the watch's address. It asks about every service, every
   command and the expand bitmap. It saves them per device and writes
   `<report>_capabilities.json`; compare that with §3.2.
4. Run the first sync. Count stored rows against Health's screenshots and the export before trusting
   it.
5. Then `make_active` (or `HuaweiSettings.setAddress`), and the Band's retirement.

## 5. Already in place (Step 0, 2026-10-17)

- **The Hume history is never pruned again.** `BandPruneWorker` is cancelled at startup; it would
  have started deleting 2026-07 data in 2027-08.
- **Settings are per device**, keyed by Bluetooth address:
  - bind, name, locale, face names, file-channel timing, capabilities;
  - the Band's stay in the original prefs file;
  - `huawei.pair make_active` chooses the device that tasks without an address talk to.
- **Each device has its own sync window.** `HuaweiSyncRunner.status(db, address)` counts only that
  device's syncs.
- **Capability replies are kept** (`HuaweiCapabilities`, decoded and tested against the Band's
  capture), where they used to be thrown away.

  Not yet used to skip sync steps. The Band answers the locale service although its census says no,
  so gating waits for the D3's real answers.
- **`huawei.probe full=true`**: the full census described in §4.
- **The device picker** also lists bonded `HUAWEI WATCH…` devices.
- **`scripts/huawei-capture-session.py`**: the check / watch / mark / status tool for §1–§2, with
  each mark decrypted and summarised at once by `scripts/huawei-capture-analyze.py`. Checked
  against a Band capture: 4 keys recovered, 3721 frames, 0 undecryptable.
