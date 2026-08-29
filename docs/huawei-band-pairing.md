# HUAWEI Band 11 Pro — claiming the band, on the PC and on the phone

The authoritative reference for getting a HUAWEI Band 11 Pro out of its out-of-box wizard and onto a
working watch face **with no Huawei account and no Huawei software, ever**. Written from the
reverse-engineering session of 2026-08-21 (PC, BlueZ) and the Kotlin port verified on 白い熊's phone
on 2026-08-22.

> Source of truth for the shapes: `app/src/main/java/com/opentasker/core/huawei/*.kt`.
> The sequence is additionally pinned by `app/src/test/java/com/opentasker/core/huawei/
> HuaweiClientTest.kt` and `HuaweiSafetyGuardTest.kt`. Keep this file in sync when those change.

This document exists because **almost every failure here is silent**. The band answers `100000 OK` to
every individual command and then quietly returns to its pair screen. There is no error, no
exception, and no log line saying what was missing — so a wrong sequence is indistinguishable from a
right one until you look at the band's face. Three separate bugs during the phone port produced the
*same* symptom. Without the reasons recorded below, the next session rediscovers them one at a time.

---

## 1. The three gates

A setup succeeds only if **all three** hold. Any one missing produces the identical failure: the band
displays "open Huawei Health on the phone to pair" a few seconds later and reverts.

1. **A real Bluetooth pairing must exist**, confirmed by a human on the band. A platform that bonds
   silently without user confirmation will speak this entire protocol flawlessly and the band will
   still never leave its wizard.
2. **The full configuration set must be sent** — in particular `0x31`, `0x30`, `0x3F`, `0x3E`.
   Omitting any of them leaves the band stuck with every command still returning success.
3. **The companion must stay connected afterwards** until the band stops asking questions. It is not
   finished when the configuration set is.

### What the human sees

- **The band shows a plain yes/no.** It never displays a six-digit code — not on any run, on either
  host. Ask only for "accept the request on the band".
- **The PC shows no code either**; KDE raises an ordinary "allow and trust this device" dialog.
- **The phone shows Android's own pairing confirmation**, which cannot be suppressed:
  `setPairingConfirmation()` is guarded by `BLUETOOTH_PRIVILEGED`, held only by system apps. Two taps
  are required and that is not a defect.

---

## 2. The link

**Bluetooth Classic RFCOMM, not BLE.** Gadgetbridge classifies this model as an LE device; on real
hardware that is wrong, and it is the single most expensive wrong assumption available here.

| | |
|---|---|
| Transport | RFCOMM, channel 16 |
| SDP record | "Private COM", `82ff3820-8411-400c-b85a-55bdb32cf060` |
| Second record | `65847aa1-102a-05e1-43b6-e2a3965be547`, channel 17 — unused |
| Discovery | visible only via `sdptool records`, **not** `sdptool browse` |
| Framing | LPv2: `0x5A` magic, uint16 length, slice, serviceId, commandId, TLV, CRC16-XMODEM |

**The length field counts the slice byte** — `len(payload) + 1`. Off by one and the band silently
ignores every frame, with no error to say so.

The band is **single-connection**: while one host holds it, no other can reach it. Connect, do the
work, disconnect. A standing Bluetooth session on 白い熊's phone once cost **1322 mAh in a day**.

---

## 3. Authentication — HiChain3, and no account

Two passes over service `0x01` command `0x28`, each a small JSON state machine. All symmetric:
SHA-256, HMAC-SHA256, HKDF-SHA256, AES-GCM. No elliptic curve, no server, no network call.

```
LinkParams (0x01/0x01)
  → DeviceStatus (0x01/0x16)          ← allow 15 s; the default 6 s swallows it
  → SecurityNegotiation (0x01/0x33)   ← without this, Auth fails with 100004
  → PinCode (0x01/0x2C)               ← the band hands the PIN over itself
  → HiChain pass A, operationCode 1   ← bind; yields the authToken to persist
  → HiChain pass B, operationCode 2   ← auth; yields the session key
```

Traps that cost real time:

- **`messageId` is 4 for the bind pass and 3 for the auth pass.** Reusing 4 hangs pass B at step 4.
- **Hex is UPPERCASE throughout.** The PSK derives from the hex *string* of the PIN bytes; lowercase
  silently produces a different key and step 1 fails with nothing to explain why.
- **`authId` is 16 lowercase hex characters**, as Health uses. It is carried into the token maths.
- Token order differs between the passes: verify is `randPeer+randSelf+idSelf+idPeer`, ours is
  `randSelf+randPeer+idPeer+idSelf`.

A stored `authToken` survives the band reverting to its wizard — a band showing the pair screen will
still authenticate an existing bind. It does **not** survive the bond being removed.

`ACCOUNT_ID = "30420000025794403"` is an opaque identifier the band wants present. It is not a Huawei
account, nothing is registered, and no network call is involved in sending it.

---

## 4. The provisioning sequence

Exactly this, in exactly this order. Pinned by
`HuaweiClientTest.configure reproduces the reference provisioning sequence exactly, in order`.

| # | Service | Cmd | Name | Note |
|---|---|---|---|---|
| 1 | `0x01` | `0x07` | ProductInfo | a specific tag set, **not** `range(14)` |
| 2 | `0x01` | `0x05` | SetTime | |
| 3 | `0x01` | `0x05` | SetTime | **again — Health sends it twice** |
| 4 | `0x01` | `0x02` | SupportedServices | |
| 5 | `0x01` | `0x03` | SupportedCommands | |
| 6 | `0x01` | `0x37` | ExpandCapability | |
| 7 | `0x01` | `0x31` | **SettingRelated** | closes the wizard |
| 8 | `0x01` | `0x30` | **AcceptAgreements** | closes the wizard |
| 9 | `0x01` | `0x3F` | **ReverseCapabilities** | closes the wizard |
| 10 | `0x01` | `0x3E` | **SetUpDeviceStatus** | closes the wizard; **never answered** |
| 11 | `0x1A` | `0x04` | Account step | |
| 12 | `0x1A` | `0x05` | ExtendedAccount | carries `ACCOUNT_ID` + tag 3 |
| 13 | `0x1A` | `0x0A` | CountryCode | **never answered** |
| 14 | `0x1A` | `0x01` | SendAccount | |
| 15 | `0x1A` | `0x04` | Account step | again, closing the dance |

Payloads that were wrong in earlier hand-built attempts, and are now replayed verbatim from a
decrypted capture of Huawei Health:

- **AcceptAgreements is TWO blocks**, `user_license_agreement` and `device_information_management`.
  A three-block version does not work.
- **ReverseCapabilities is SEVEN bytes**, `0107FDF773FA29BF3B`. Gadgetbridge's six-byte
  `FDF733FA2937` is not what this band takes.
- **SetUpDeviceStatus carries the BAND's own name**, e.g. `HUAWEI Band 11 Pro-90F` — not the phone's.
- **SettingRelated** is `010002000300040005000600`.

**`0x3E` and `0x1A/0x0A` are fire-and-forget.** The band never answers them; Health gets no answer
either. Blocking on one burns the seconds the band gives us, which cost a whole evening once.

### SetTime's zone byte

Whole hours, offset-encoded: a negative offset is sent as `128 + |hours|`, and **the minutes byte is
always zero**. Passing the raw quotient and remainder looks equivalent and is not — west of UTC it
puts a negative number into an unsigned byte. (This also means half-hour zones are not represented;
that is what Health sends and what is proven.)

---

## 5. Staying connected afterwards — the step that is easiest to miss

**The band is not finished when the configuration set is.** From the reference run:

```
+ 3.4s  session established
+10.0s  configuration set complete
+14.0s  band asks PhoneInfo   ← a companion that has hung up loses it here
~+20s   watch face appears
+24s … +71s …  band still talking, gaps of up to 9 s
```

A companion that stops answering during that exchange is treated as **no companion at all**, and the
band reverts — with every command it received having returned success. This is invisible from the
host side, which is why `HuaweiSafetyGuardTest.theRunnerStaysConnectedAfterConfiguring` fails the
build if the runner ever stops serving after configuring.

Serve **until the band goes quiet**, not for a fixed period: floor 20 s, exit after 12 s of silence,
cap 45 s. The longest observed gap between messages is 9 s, so 12 s clears it; the watch face appears
about ten seconds after configuring, so 20 s is the safe floor. The PC script's 1.5 minutes was
logging for research, not a requirement — and holding the link longer than needed costs battery and
locks every other host out.

Silence must be measured on frames **received**, not frames answered: several of the band's messages
are deliberately unanswered, and counting only replies reads an ongoing conversation as silence.

### What to answer, and what not to

| Frame | Action |
|---|---|
| anything carrying the result tag (`0x7F`) | **never answer** — that is the band acknowledging us; replying ping-pongs forever (22 000 frames in six minutes, once) |
| `0x01/0x3D` WearStatus | **never answer** — a notification; replying makes it resend immediately (6 661 frames in 90 s) |
| `0x01/0x10` PhoneInfo | answer with the requested tag set |
| `0x01/0x38` PermissionCheck | answer **granted** (status 1). Gadgetbridge hardcodes 0 with a TODO conceding that is wrong |
| `0x1A/0x03`, `0x1A/0x04`, `0x1A/0x06` | answer OK |
| `0x25/0x01`, `0x34/0x01`, `0x1F/0x01`, `0x37/*` | ignore — the successful run ignored them too |

---

## 6. Recipe — PC (BlueZ)

1. Band at its pair screen (factory reset, or released by its previous companion).
2. Register a BlueZ agent **before** `Pair()`, auto-accepting.
3. Initiate pairing **from the PC**. The band shows yes/no; 白い熊 accepts. KDE asks to allow/trust.
4. Immediately: RFCOMM connect to channel 16, then §3, §4, §5 with no human-speed gap.

Reference implementation: `.scratch/hw/` (gitignored — `firstrun.py`, `provision2.py`, `hichain3.py`,
`health_config2.py`), with `WORKING-RUN-2026-08-21.log` as the reference timing.

## 7. Recipe — phone (this app)

`huawei.pair` does all of it in **one action**, deliberately: the band gives a new companion only
seconds, so a flow with a human step between bonding and provisioning fails every time.

1. Band at its pair screen; phone not yet bonded to it.
2. `createBond()` from the app.
3. **Poll `device.bondState` every 250 ms.** Do **not** wait on `ACTION_BOND_STATE_CHANGED`: it is
   sent by the system UID, so a receiver registered `RECEIVER_NOT_EXPORTED` never sees it on this
   EMUI phone. The failure is silent and total — the bond completes, both confirmations are accepted,
   and the code waits forever for an event that will not arrive. Polling also distinguishes a refusal
   (`BOND_NONE` after bonding began) from a timeout, which the receiver could not.
4. On `BOND_BONDED`, pause ~1.5 s for the band to start serving the channel, then §3, §4, §5.

Two app-level requirements that are not obvious:

- **`TaskRunner`'s default 60 s action budget kills this**, mid-conversation, *and* cuts the run
  before it can write its own result variables — so the failure arrives with nothing to say what
  happened. `huawei.pair` and `huawei.sync` have explicit entries in `actionTimeoutMs`.
- **Publish a live `%<prefix>Phase`** (`pairing → connecting → handshake → device → configuring →
  serving`). A run that dies leaves the stage it died in behind. Without it, every diagnosis is
  guesswork; with it, "it failed the same way" became a precise location in one reading.

---

## 8. Never do these

- **Never remove the bond to "resync".** The band then believes it is still paired, stops
  advertising, and the link deadlocks. Releasing a companion is done **on the band**.
- **Never set the host un-pairable to stop it reconnecting.** The band's own attempts are then
  refused and it displays "pairing failed" — a self-inflicted symptom that looks like a band fault.
- **Never factory-reset to change companion.** The band's own Settings → Disconnect is enough:
  ~3 s to re-pair, and **the history survives**. A factory reset returns it to the wizard for nothing.
- **Never blind-replay a capture.** Reordering TLVs through a dict, or replaying the band's
  *responses* as commands, produces a run that looks plausible and does nothing.
- **Never re-send the configuration set on a routine sync.** It is the out-of-box set; a sync should
  connect, authenticate, read and disconnect.

---

## 10. The settings, and how to set them ourselves

Captured from Huawei Health on 2026-08-22 by toggling each switch with a decrypted btsnoop running
(method in §12). Every one of these is a plain write on the fitness service — no account, no cloud,
no Health involvement once the payload is known.

**Heart rate and blood oxygen are absent from a fresh band because these are OFF, not because they
are unreachable.** The band's own SpO₂ screen says so in as many words. Continuous heart rate began
recording the minute Health set `0x07/0x17`, at roughly one reading every five minutes.

| Command | Setting | On | Off |
|---|---|---|---|
| `0x07/0x16` | **truSleep** | `{1:'01'}` | `{1:'00'}` |
| `0x07/0x17` | **continuous heart rate** | `{1:'01'}` | `{1:'00'}` |
| `0x07/0x24` | **automatic SpO₂** | `{1:'01'}` | `{1:'00'}` |
| `0x07/0x1d` | high heart-rate alert | `{1:'01', 2:'78'}` — `0x78` = 120 bpm | `{1:'00'}` |
| `0x07/0x22` | low heart-rate alert | `{1:'01', 2:'28'}` — `0x28` = 40 bpm | `{1:'00'}` |
| `0x07/0x25` | low SpO₂ alert | `{1:'01', 2:'5A'}` — `0x5A` = 90 % | `{1:'00'}` |

The pattern is consistent: an **enable** command plus a companion **alert** command carrying its
threshold as a single byte. The off form of an alert drops the threshold byte entirely. SpO₂ writes
`0x24` then `0x25` when enabling and the reverse when disabling; the threshold commands are
independent of the enable and can be set on their own.

### Module features go through DataSync, not through a setting

Some features are not a byte on `0x07` but a configuration written to a JS module on the band over
`0x37/0x01`, addressed by package name:

| Feature | Module (phone ↔ band) | Config ids |
|---|---|---|
| **Emotions / stress** | `hw.health.emotion` ↔ `hw.watch.health.emotion` | `0x35AC8A3C` |
| Sleep breathing awareness (apnea) | `hw.health.apneajsmodule` ↔ `hw.watch.health.osa` | `0x35A97CE7`, `0x35A97CE8` |
| Pulse-wave arrhythmia analysis | `hw.health.ppgjsmodule` ↔ `hw.watch.health.ppg…` | `0x35A97CE2`, `0x35A97CE4`, `0x35A97CE9` |

Stress matters more than it looks: it is the one metric the Hume band provides that this band would
otherwise have lost in the migration. It is also not a like-for-like replacement but an upgrade —
Hume's "stress" is a lookup on its device-state byte and carries no independent information, whereas
this is derived from real HRV.

The payload is the DataSync container, and comparing an on against an off makes the whole thing
decodable rather than a blob to replay:

```
0x84 { 0x05 = configId (4 bytes)
       0x06 = action        1 = phone->band, 2 = band->phone
       0x07 = 01 01 <flag>  the last byte is on/off }

84 0E 05 04 35 A9 7C E8 06 01 01 07 03 01 01 01     apnea ON
84 0E 05 04 35 A9 7C E9 06 01 01 07 03 01 01 00     arrhythmia, flag 0
```

So there is **one primitive, not a recipe per feature**: write a config id with a flag, addressed to
the right module. The ids are NOT one tight block — apnea and arrhythmia sit in `0x35A97CE…` but
emotions is `0x35AC8A3C` — so a feature's id has to be captured rather than guessed from its
neighbours. The primitive is general; the ids are not derivable.

Arrhythmia analysis is **on-demand** — after activation the band shows a "Measure" button — so it
produces an event when pressed rather than a series. Recorded for completeness, not a data source.

---

### 10a. The band's display language — `0x0C/0x01`

**The band has no language setting of its own, and that is by design.** Huawei state it plainly:
*"The language cannot be set on your wearable device directly. Once the wearable device is connected
to a phone, the time and language settings on the phone will automatically sync to the wearable
device."* The Band 11 guide mentions language only in its pairing section; nothing in the on-device
Settings tree exposes it.

The companion pushes it, exactly once per pairing, right after announcing itself:

```
--> 0x0C/0x01  {1: "en-US", 2: 00}
<-- 0x0C/0x01  {127: 00 01 86 A0}          # 100000 — the band's success code
```

| Tag | Meaning |
|---|---|
| 1 | BCP-47 language tag, ASCII, `xx-YY` form |
| 2 | unit system — `00` metric, `01` imperial |

`0x0C/0x05` carries the temperature unit separately.

Three consequences worth keeping straight:

* **This is why 白い熊's band is in English.** It went to another phone for the capture in §12, that
  phone's Health pushed its own `en-US`, and our client had never sent this command — so the band
  simply kept the last word it was given. The first-run picker is real, but the first companion to
  connect overwrites it.
* **An unsupported language is not an error.** Outside mainland China the band falls back to English
  (inside it, to Simplified Chinese). A wrong tag therefore costs a language, not a band — which is
  why the action sends and reports rather than validating against a list we would have to keep in
  step with Huawei's firmware. Japanese is in Huawei's published support table.
* **It must be re-asserted.** Any companion the band meets can push its own locale over ours, so
  `configure()` re-sends the stored choice on every pairing — but only when one has been stored, so
  a language picked on the band itself is never silently overwritten by us.

**Two things about this command were wrong here until 2026-08-29, and both were found on the band
rather than by reading:**

* **There is no read. This is settled, not suspected.** `0x0C/0x01` with its tags left empty — this
  protocol's own "tell me rather than propose" idiom, the way `fileNegotiate` asks for a chunk size —
  returns nothing at all. Health never asks either; it assumes it is the band's only companion. The
  remaining hope was that the language rides along in product info, so `huawei.probe` now asks for
  **all 127 product-info tags** rather than the sixteen Health asks for. On 白い熊's band (2026-08-29)
  that returned **34 tags and not one language field**: versions, serials, MACs, model names, a
  signature — product identity, which a display language is not.

  ```
  tag 1 "1.0.0"   tag 3 "EA1CANDY.B49,VN1"   tag 7 "6.0.0.125(SP8C00M02)"   tag 9 "6KLTQ26326016653"
  tag 10 "Candy-B49FB"   tag 17 "CDY-B49"   tag 33 "6.0.0.125"   tag 38 "HarmonyOS 6.0.0.125"   …
  ```

  **So a companion cannot know the band's language at all.** Do not go looking again, and do not
  blind-probe command ids on a config service to find one — that is how a watch face got deleted by
  a command believed to select one. The UI's job is to say whose word it is showing.
* **A push sent on its own is ACKed and IGNORED.** "Right after announcing itself" is not narrative
  detail — it is the condition. Sent mid-session it returns 100000, the same success code as
  everything else, and the band does not change language: 白い熊 asked for English, was told it had
  switched, and the band stayed Japanese. `pushLocale()` therefore sends `SetUpDeviceStatus` first
  and only then the locale, reproducing the capture's adjacency. **Never report a language change
  from the ACK** — the band ACKs a locale it has no pack for just the same.

Ours is `huawei.language` (`locale`, `units`), driven by `%Huawei_BandLocale` from
`健康の設定 -- [727][01]` — deliberately a different setting from `%Huawei_Language`, which is the
language of *our window*. 白い熊 runs the band in Japanese and the report in English.

## 11. Where the health data actually lives

Three separate answers, and only one of them is the record service we started with.

### Per-minute records — `0x07/0x0A` count, `0x0B` fetch

Steps, calories and distance, plus **heart rate and SpO₂ once their toggles are on**. Our decoder
always had bits for those fields; they were simply never populated.

### Activity bouts — `0x07/0x0C` count, `0x0D` fetch

**Not sleep**, despite being labelled that way here for weeks on nothing but a guess. Each `0x83`
entry is `0x04 = 01` (constant), `0x06 = 00` (constant), `0x05 = <4-byte epoch start><2-byte
duration in MINUTES>`, non-overlapping — verified against a known 36-minute morning walk and a
scatter of one-to-three-minute night-time trips.

### Daily totals — `0x07/0x03`

Per-activity-type totals with **real units**: `0x05` steps, `0x06` kcal, `0x07` metres. Confirmed
against the band's own display (4290 steps / 134 kcal / 2797 m). This is what calibrates the raw
`calories` and `distance` fields carried in the per-minute records.

### Sleep and real HRV — named FILES over service `0x2c`

Neither is a record and neither answers a count, which is why every probe for them came back empty.
They are **bulk files**, pulled by name over a chunked transfer:

| File | Id | Holds |
|---|---|---|
| `sequence_data` | `0x16` | the continuous series — sleep staging and HRV. ~700 KB observed |
| `rrisqi_data.bin` | `0x10` | raw RR intervals with signal-quality index |

```
0x2c/0x01  {1:<filename>, 2:<id>, 5:<from epoch>, 6:<to epoch>, 12:<total size>}   request
0x2c/0x03  negotiate (block sizes 0x03D0, 0x1E80 observed)
0x2c/0x04  {1:<id>, 2:<offset>, 3:<length>, 4:<total>}                             request chunk
0x2c/0x05  data
0x2c/0x06  {1:<id>, 2:'01'}                                                        done
```

Health requests `sequence_data` **from one second after the last sleep session's wake time**, so the
`from` field is a cursor into a continuous store rather than a query window. It caches what it has
already pulled and asks only for the remainder; a request for data it already holds is answered
`0x00023281` rather than with bytes.

**Service `0x19` is an enable, not a query.** It answers `100000` to a payload carrying tag 1 and
returns no data at all — the RR intervals land in `rrisqi_data.bin`, not in a response.

### Health's own request shapes, which are not what we guessed

Read straight out of the decrypted capture. Our `fitnessCount` nests the range inside `0x81`; Health
does not, and the **tag numbers differ per command**:

```
0x07/0x0a  {129:'',  3:<from>, 4:<to>}      step count
0x07/0x0b  {129:'02 02 00 00'}              record index 0 — ZERO-BASED, confirmed independently
0x07/0x0c  {129:'',  3:<from>, 4:<to>}      activity-bout count
0x07/0x1e  {1:<from>, 2:<to>, 13:'00'}
0x07/0x1f  {2:<from>, 3:<to>, 4:'00'}
0x07/0x26  {1:<from>, 2:<to>}
```

`0x1e`, `0x1f` and `0x26` returned empty for Health too, so they are not a data path we are missing.

---

### 11c. Adding a watch face — the whole procedure

Faces cannot be built, only captured. Huawei Health downloads one as an encrypted `themeV2Cipher`
package, decrypts it, uploads the result and deletes the download, so the bytes that go over the air
exist nowhere on disk. And the band will not keep a face it was not told about: the announcement
carries a `contentSign` signed by Huawei's servers, which cannot be fabricated. **So this recovers
faces 白い熊 already owns; it cannot obtain new ones.** That is the whole point — rotating them
without re-pairing to the other phone.

1. Pair the band to the rooted Samsung and let Huawei Health take it.
2. **Record the byte offset of `/data/log/bt/btsnoop_hci.log` first**, so the capture is the install
   and nothing else. The log must include the session's HiChain handshake — the control frames are
   encrypted and the key is derived from it — so if the band is already connected, disconnect and
   reconnect it before installing.
3. Install **one** face. Two interleaved are far harder to separate.
4. Pull the log and splice its first 16 bytes (the btsnoop header) onto everything past the offset.
5. `python3 scripts/huawei-extract-watchface.py <log> <outdir>` → `<assetId>_<version>.bin` and
   `.json`. Each face is verified against the SHA-256 the phone itself sent the band; one that does
   not verify is skipped rather than written, because the band checks the same digest and a short
   file is refused outright.
6. Put both files where `%Huawei_Face` points and run `バンド文字盤（Huawei） -- [727]`. Or drop the
   ZIP into `%Huawei_FaceDir` and run the task with `%Huawei_Face` blank, which opens the library.

Steps 2–4 exist because the alternative — capturing everything and sifting — was tried and wastes
far more time than recording an offset.

#### `0x27/0x03` tag 3: 01 installs, 02 DELETES

There is **no "make this face active" command**, and believing there was one cost two days. Installing
is what puts a face on screen; Health never sends anything afterwards to select it.

What tag 3 = 02 does is remove the named face. Sending it after an install — which the fork did until
2026-08-23 — deletes the file that was just transferred: the band shows the new face for an instant,
drops back to the previous one, and the new face is absent from its own list. The symptom reads
exactly like a full buffer, which is the wrong diagnosis.

Settled by replaying all 33 of Health's installs out of one capture and diffing the band's face list
across each:

| after | the band's list |
|---|---|
| `tag3=01` | the asset **appears** — 11 faces become 12 |
| `tag3=02` | the named asset **disappears** — back to 11 |

Free space (`0x27/0x02` tag 9) falls by one on an install and rises by one on a delete, every time.
The band held 12 faces mid-swap in every one of those 33 installs, so a two-face limit does not exist.
What Health does after installing is delete **the previous** face, keeping the band pruned — never the
one it just sent.

```
0x27/0x02  {1: "", 6: 03}        ask what is installed
   <- {8: status, 9: free space, 129: repeated 0x82-prefixed records}
      each record: {3: assetId (10 ASCII digits), 4: version, 5: 05 = the face on screen, else 04}
0x27/0x03  {1: assetId, 2: version, 3: 01, 5: width, 6: height}   install (and show)
0x27/0x03  {1: assetId, 2: version, 3: 02}                        DELETE
```

**Tag 5 does not say where a face came from, and there is no "protected" flag in the record.** It was
briefly read as one on the strength of two records that happened to agree; seven of 白い熊's own faces
carry the same `04` as Huawei's, so a picker built on that reading locked them. What tag 5 tracks is
the CURRENT face: it becomes `05` on whichever face was last installed, and moves again when the face
is changed on the band by hand — confirmed across all 33 installs and several band-side switches.

So nothing predicts what the band will part with. Send the delete, then re-read the list and report
what the band actually did. (One record carries `05` in tag 5 alongside `2a`/tag 7 = `02`; that pair
is unexplained and does not track anything we drive.)

### The band never speaks first on our session (2026-08-25)

The satellite-assistance data expires every few hours and this app has never touched GNSS. The band
has an **Update** button on that screen, so the plan was to press it and read the request — a band
asking its companion for assistance data names the service and command that serve it, which is the
one thing that cannot be looked up, because the only other implementation is AGPL and this fork takes
no protocol constants from it.

`huawei.probe listen_sec=N` listens without sending. What it found, with the link proven alive by a
battery request every 30 s written into the same file:

* **Update pressed, twice, in a 5-minute window** — nothing.
* **Update pressed again in a fresh window** — nothing.
* **The band taken off the wrist and put back on** — nothing, though `0x01/0x3D` WearStatus is
  documented right here as a frame the band sends unprompted, from a session where it arrived 6 661
  times in 90 seconds.

So the band emits **no unsolicited frame at all** on a session of ours, including one it demonstrably
does emit on Health's. The link is up — the keep-alive round-trips every time — and the band still
does not speak first. That is a protocol-state difference, not a listening problem: our session
authenticates and asks questions, and something Health does to make the band *volunteer* is missing.
Whatever that is, the Health capture shows it.

Two false negatives were produced getting here, and both were ours. The first listen buffered its
report and wrote it only when the window closed, so killing it destroyed a capture that had already
caught 白い熊 pressing Update twice — it appends live now. The second swallowed every poll failure
into an empty list, so a link that had gone idle read exactly like a quiet band — it now proves the
link on a timer and says so in the file. **"Nothing was heard" is only evidence when it appears next
to proof that hearing worked.**

### Weather: the push and the forecast are ONE record (2026-08-25)

For two days `天気送信（Huawei）` reported "Weather pushed" after every run while the band's screen
kept Huawei Health's `Hodkovicky` dated 2026-08-23. This section used to blame the payload's shape.
It was the **count**, and the reason it took two days to find is that the app was not listening.

**The band treats `0x0F/0x01` (the current-weather push) and `0x0F/0x08` (the forecast) as a single
record.** An invalid forecast makes the band discard the push as well — after having already answered
the push with `100000`. So a push that never landed looked exactly like one that did.

**The forecast is refused unless it carries 24 hourly entries and at least 8 daily ones.** Measured
against the band, holding everything else fixed:

| hourly | daily | reply |
|---|---|---|
| 24 | 15 | `100000` |
| 24 | 14 | `100000` |
| 24 | 10 | `100000` |
| 24 | **9** | `100000` |
| 24 | **8** | `100000` |
| 24 | 7 | `115001` |
| 24 | 0 | `115001` |
| **23** | 15 | `115001` |
| 3 | 1 | `115001` — Health's own bytes, merely fewer of them |
| 0 | 15 | `115001` |

`115001` is the refusal. Every forecast this app ever sent carried one hour and one day.

**We were throwing the answer away.** `runCatching { session.sendLarge(SVC_WEATHER, 0x08, …) }` and
nothing else — the bytes left the phone, the call returned, and nobody asked the band what it
thought. Reading that one reply turned a two-day mystery into a fifteen-minute bisection. Anything
that sends and does not read is a place where this can happen again.

Two theories died on the way and are recorded so they are not re-run: it is **not** the sequence
(Health's three pre-push reads `0x0F/0x02` → `{1: FF}`, `0x06` → `{1: 00 0F}`, `0x0A` →
`{1: 00 00 00 03}` were reproduced exactly, and the forecast was still refused), and it is **not**
the place encoding (ASCII vs UTF-8 was refuted earlier and remains refuted).

### Three fields the capture could not name, and a screen could

A byte-level capture pins structure and cannot pin meaning: Health's tag 17 held 19 against a
current 17 °C, which is a perfectly plausible temperature. 白い熊 photographed all ten of the band's
weather pages, and each page is an oracle a capture cannot be. Sending deliberately absurd values
and reading the wrist settled three fields in one round:

| tag | proven by | meaning |
|---|---|---|
| `15` | sent 9 → page reads "9 / Strong" | **UV index**. Had been hard-coded to 3 and called unmapped, because all four captured pushes carried 3. |
| `16` | sent 33 → "33 %" | **humidity** |
| `17` | sent 100 → "100 km/h" | **wind speed, km/h** — not a temperature |
| hourly `7` | series falls to 0 at dusk | **hourly UV**; the band's "Highest level" is its maximum |
| `9` | sent 7 → headline "7°" | **current temperature** — it only ever appears once the record as a whole is accepted |

**The band computes sun and moon itself.** On an ACCEPTED forecast carrying no moonrise, no moonset
and no phase byte, the Moon page still read 02:33 / 19:07 and the Moon-phase page still read
"Waxing gibbous · 93 % · Day 14", correctly. It derives them from the `0x18/0x07` position frame.
Tags 22/23/26/27/30 are therefore optional; a 15-day list carrying only tags 18–21 is accepted.

**The band recomputes the day high from the hourly series.** Push container `133` said 39 and the
band showed 43 — the maximum of the 24 hourly temperatures sent. The low came from the push.

### The condition-icon table, read off the wrist (2026-08-25)

The band draws the icons and tells nobody, so the only instrument is 白い熊's eyes. Three forecasts
were pushed in which **every cell printed its own code as its temperature** — hour `n` carried
condition `n` and read `n°` — so any photograph, at any scroll position, labelled each icon
unambiguously and no counting was needed.

**Hourly tag 4 is the condition; tag 6 is not.** The first sweep varied tag 6 across 0…23 and all
24 cells drew the same icon while the daily list plainly separated cloud from rain. Health writes a
constant `1` into tag 4, which is exactly the sun-behind-cloud its captures always showed.

**Hourly and daily share ONE table** — 2 is a bare cloud in both, 3 rain in both, 7 a single drop in
both, 10 a downpour in both.

```
 0 sun behind cloud      12 downpour              24 sun behind cloud
 1 mostly sunny          13 snow, light           25 heavy rain
 2 bare cloud            14 snow                  26 snow
 3 rain                  15 snow                  27 snow, heavier
 4 thunderstorm          16 snow                  28 snow, heavy
 5 sun behind cloud      17 snow, heavy           29 blowing snow
 6 rain                  18 fog                   30 blowing snow
 7 one drop              19 mist                  31 blowing snow
 8 two drops             20 wind-blown haze       32 single flake
 9 rain                  21 light drops           33 thermometer + sun   (heat)
10 rain, heavier         22 light drops           34 thermometer + flake (cold)
11 rain, heavy           23 rain                  35 BARE WIND LINES
```

**The range is 0–35.** 36, 37 and **48** all draw the same plain sun, and 48 is far outside any
plausible table — so that sun is the out-of-range fallback, not a clear-sky icon. A wrong code
therefore fails *cheerfully*, rendering as fine weather, which is the kind of failure that hides.
Unrecognised words map to 0 and never into the fallback.

**There is no sleet or hail icon.** The set runs sun / cloud / rain / snow / fog / wind / thermometer
and stops; nothing depicts mixed or frozen-pellet precipitation. Codes 5 and 6 were read as "mixed
drops" in an earlier round and `sleet`/`hail` were mapped onto them; photographed side by side with a
known rain icon between them they are plainly a sun-behind-cloud and a plain rain. **Reading two
icons from photographs taken minutes apart is not a comparison** — putting the candidates adjacent
with a ruler between them is, and it overturned the earlier call.

`35` matters out of proportion to its size: nothing in 0…23 depicted wind, so `wind` had been falling
back to a bare cloud. The icon existed; the swept range simply had not reached it.

**The band clamps BOTH ends of today's range to the hourly series.** A push saying 2/2 with an hourly
strip running 1…35 displayed `35°/1°`. And **today's row draws its icon from the push, not from daily
tag 19**, which is why row one has been useless in every sweep.

**The day list shows SEVEN rows**, however many days are sent — a 15-row sweep wastes its last eight.

### `rrisqi_data.bin` is a standard HRV panel (2026-08-26)

Ten floats per ~60 s window, and for weeks only three had names. Decoded against the band's own
per-beat RR series (`sequence_data` 700021), **seven of the ten are now standard HRV metrics** and
the record reads as an ordinary panel rather than a Huawei invention:

| | | | |
|---|---|---|---|
| `f1` | a count | `f6` | **mean RR (ms)** — the anchor |
| `f2` | **SD of instantaneous HR (bpm)** | `f7` | **SD1/SD2**, the Poincaré ratio |
| `f3` | **RR range**, on the 20 ms grid | `f8` | **HF power**, 0.15–0.40 Hz (ms²) |
| `f4` | **not RR-derived** | `f9` | **LF power**, 0.04–0.15 Hz (ms²) |
| `f5` | **RMSSD (ms)** | `f10` | **not RR-derived** |

Verified by `scripts/rri-name-fields.py` over 257 paired windows. Every scale converges on 1 and
every error falls as the window alignment tightens (|computed mean − f6| ≤ 20 → 10 → 5 ms), which
is the signature of residual **pairing** error rather than a wrong model:

```
f2  hr_sd     0.993  1.9%   0.995  0.7%   0.996  0.5%
f5  rmssd     0.993  1.2%   0.995  0.7%   0.994  0.5%
f6  mean      0.990  0.7%   0.996  0.5%   0.999  0.3%
f7  sd1/sd2   0.985  2.1%   0.988  1.4%   0.990  1.2%
f8  hf        1.001  4.1%   1.013  2.4%   1.019  1.9%
f9  lf        1.094 10.8%   1.114  5.7%   1.126  4.5%
```

**The spectral convention had to be found, not assumed.** f8 and f9 first fitted at a common scale
of 0.248 — a shared constant, so a normalisation rather than two coincidences. A Hanning window sums
to n/2, so normalising by `n²` as if the window were rectangular is a factor of exactly 4, and
0.248 × 4 = 1. Linear detrend, 4 Hz interpolation of the tachogram, standard band edges.

**f9 keeps a 7–13 % scale offset that no band edge removes, and that is expected**: these windows are
~60 s, and 0.04 Hz is 2.4 cycles in 60 s, so LF is under-resolved by construction. The
identification stands; the exact reproduction is limited by window length.

**Two methodological traps, recorded because both nearly produced a false claim:**

*Absolute tolerance floors.* An earlier pass tested `within max(2% of the mean, 0.5)` and reported
"f7 ≈ CV in 93/124 windows". CV averages 0.05, so a floor of 0.5 was ten times the quantity and the
test could not fail. Every tolerance here is relative.

*Correlation proves nothing on this data.* Every fatigue-ish metric correlates with every other one
across these windows — which is precisely why six fields once looked plausible and none was proven.
A field is claimed only when the fit is scale-free: `field / metric` near-constant, and the constant
coming out at 1.

### GNSS assistance: the band ASKS, and it asks for two different things (2026-08-26)

The band cannot fetch satellite assistance data itself. Without a companion feeding it, a GPS fix
takes minutes of cold search instead of seconds.

**The roles are reversed here.** Everywhere else in this file we ask and the band answers. Here the
band raises `0x1F/0x01`, names what it wants, and then drives a pull over `0x1C` in an order we do
not choose. `serveGnss` is a small server; the band decides which files it takes and we only offer.

```
<-- 0x1F/0x01  band: "I want assistance data"        --> ack 100000
--> 0x1F/0x02  "what?"   <-- band answers with a source string
--> 0x1F/0x03  {1: 03} "it is here"                  <-- band acks, then opens 0x1C
<-- 0x1C/0x01  "list what you have"  --> {1: "A;B;C"}      (NO result tag)
<-- 0x1C/0x02  transfer params: tag 3 unit=862, tag 4 block=6896
<-- 0x1C/0x03  "I want X"            --> {2: size, 3: CRC16}  (NO result tag)
<-- 0x1C/0x04  [offset, length)      --> ack + 80-byte token + offset echo
--> 0x1C/0x05  <seq byte><=862 raw bytes>  x N        NOT TLV
<-- 0x1C/0x06  "done"                --> ack
```

**Two rounds, two different questions.** Measured on the band:

| what the band is doing | it asks for | can we supply it? |
|---|---|---|
| its **Update** button pressed | `HW_EEV2/HW_PGNSS_PRED` — the predicted set, 6 files, 799 KB | only from captured files |
| it **needs a fix** (starting Outdoor Walk) | `higeo/v1/gnssinfo?type=0x0004/HW_AGNSS` — broadcast ephemeris | **yes, freshly, forever** |

On 2026-08-25 an Update press was offered the broadcast file in the same listing and **declined it**,
taking only the predicted six. On 2026-08-26 08:48:40, with Outdoor Walk starting, the band asked for
broadcast ephemeris and took `HW_AGNSS_RTCM_33` (7403 B) — our own download, 39 s after the watch
opened. So the trigger is **need, not reconnect**, which is what an earlier theory here said.

### Where the data comes from, and the one door that is shut

**Broadcast ephemeris is OPEN.** `https://geo-dre.platform.dbankcloud.com/higeo/v1/gnssinfo?type=0x0004`
answers a plain GET with no account, no token and no signature. The reply is gzip; unpacked it is
7403 bytes of RTCM 3 — 114 messages, 1019 GPS ×31, 1020 GLONASS ×24, 1042 BeiDou ×31, 1046
Galileo ×28 — the same size and the same composition as the file Huawei Health was captured handing
the band. `dre` is Europe; `drcn`, `dra`, `drru` are the other regions. The whole `type` bitmask was
swept: **nine valid bits, all RTCM, about 14 KB in total**, and nothing resembling the predicted set.

**The predicted set is SHUT.** `/higeo/v2/geoFile` answers `405` to a GET and `Invalid authorization`
to a POST. Health signs it: the disassembly shows the base string `"POST&/higeo/v2/geoFile&&" +
params + "&ak=" + key + "&timestamp=" + ts`, signed by `com.huawei.wisesecurity.ucs.credential` —
`applyCredential("com.huawei.hms.location")` then `CredentialSigner…signBase64()`. **The key is not
in the APK**: the credential is applied for at runtime from Huawei's servers and issued to
`com.huawei.health` against its package name and signing certificate. There is nothing to lift, and
reproducing it would mean impersonating that identity.

**What that costs.** Nothing while the phone is with the band: the band asks us for broadcast
ephemeris and we always have it. The predicted set is the cushion for when the band is ALONE and has
to fall back on a forecast — that is what expires.

### Weather is a PUSH, not a request

The band displays weather that the phone sends it. There is no fetching involved on our side and no
HTTP proxying. Captured 2026-08-22, re-captured 2026-08-23, and corrected against the band itself
2026-08-25.

```
0x18/0x07   position   {1: epoch, 2: latitude, 3: longitude}   little-endian doubles
0x0F/0x01   weather    { 129: {2: 01, 3: 01 03}  unmapped container, sent first
                         133: {6: LOW °C, 7: HIGH °C}          <- the day range lives HERE
                           8: place name, plain ASCII
                           9: current °C
                          10: unmapped, always 0
                          12: day marker — 07:00 local, EQUAL to daily[0]'s own timestamp
                          15: UV index
                          16: humidity %
                          17: wind speed km/h   FOUR bytes
                          18: unmapped          FOUR bytes }
0x0F/0x08   forecast   {129: 24 x tag 130 hourly, 144: >=8 x tag 145 daily}
0x0F/0x0B   done       {129: <empty>}          sent right after the forecast
0x0F/0x05   unit       {1: 01} Fahrenheit - {1: 00} Celsius
0x0F/0x0C   switch     {129: {2: 02}} off - {129: {2: 01}} on
```

Hourly entry (tag 130, 24 B): `3` hour epoch, `4` = 1, `5` °C, `6` condition, `7` UV, `8` apparent °C.
Daily entry (tag 145): `18` day epoch, `19` condition, `20` high, `21` low, and optionally `22`
sunrise, `23` sunset, `26` moonrise, `27` moonset, `30` phase.

**Corrections to what this file used to say.**

*The temperature unit is on the WEATHER service, not the locale one.* `0x0C/0x05` was a reasonable
guess from the locale command's own unit-system byte, and it is wrong: the switch is `0x0F/0x05`.

*Tags 17 and 18 are NOT the low and the high.* This file asserted that for weeks. They are four bytes
each, 17 is the wind speed, 18 is unmapped, and the day range is in container `133`. Writing a
temperature into 17 put the wind speed on the band's wind page and nothing anywhere near the range.

*There IS a "weather reports on" command, and this file used to deny it.* `0x0F/0x0C {129: {2: 02}}`
is the off direction, captured while toggling the switch; `{129: {2: 01}}` is the on direction, which
Health sends **when the companion re-establishes the weather session** — at pairing and after a
reconnect — and not when the switch itself is tapped. That is why toggling the switch on appeared to
send nothing: the capture was bounded by the tap, and the command belongs to a different event.

**The coordinates are LITTLE-endian IEEE-754 doubles**, while every integer elsewhere in this
protocol is big-endian. Decoding them the same way as everything else yields 10⁻¹²⁹ rather than an
obviously wrong number, so the mistake does not announce itself. Confirmed against a known
fix, which decoded correctly; the values themselves are redacted — they are a home address.

**Huawei Health commits a setting when you LEAVE its screen, not when you tap it.** Worth knowing
before capturing any other toggle: a capture bounded by the tap contains nothing, and the change then
lands in whatever slice happens to follow. Entering the weather screen alone costs ~15 KB of chatter
the first time, so each change is best captured twice — once after entering, once after backing out —
so the entry traffic can be told apart from the change.

Separately, the band repeatedly asks for `hw.wearable.httpProxy` over `0x37/0x02` (topic
`2FB08EAB`) and gets no answer from Health either. It wants the phone to fetch URLs on its behalf.
That is a real capability and a real hazard — answering it makes our app the band's HTTP client for
arbitrary requests — and it is **not** needed for weather.

Opening the weather screen also makes the band fetch its weather **app** — numeric ids over `0x27`
with a version string, then a `0x28` file stream. That is app management, not data.

---

### 11b. Sleep, stored and drawn

Schema **29** (additive) adds `huawei_sleep`: one row per stage block, keyed by its start second so
re-reading a night the band still holds overwrites rather than doubles it — which matters because
every sync deliberately asks for an overlapping window. `sessionStart` groups a night and is the
band's own bed time, NOT the first segment's start.

`huawei.sync` pulls the night after the record walk, in its own `sleep` phase, over **its own
three-day window** rather than the sample window. That is not a nicety: a routine sync asks for the
little that has happened since the last one — often under an hour — and last night falls entirely
outside that, so following the sample window produces a sync that succeeds and never once brings a
night. The summary now always says something about sleep, including when there was none; the first
attempt stored nothing and said nothing, which is exactly how it went unnoticed. Tolerant by design: a
night that will not parse reports itself and the sync still succeeds, because the samples were
already fetched and written and losing them to a sleep problem would be the wrong trade.

The card (`HuaweiSleepCard.kt`) is a lane hypnogram — deep at the bottom, awake at the top. **Stage
is carried by vertical position; colour only reinforces it.** That is not stylistic: the tightest
adjacent pair in the sleep palette, deep against light, measures CVD ΔE 8.4 against a target of 8.0,
so a chart that leaned on hue alone would be asking too much of it. The four colours are the
`ChartPalette.SLEEP_*` set that had sat unused since the Hume sleep UI was dropped, gated as a set by
`HuaweiSleepPaletteTest` (adjacent floors: CVD 8.4, normal 19.8). Reusing them also means both bands
will read identically in the eventual compare view.

Awake blocks outside the band's span are **drawn but not counted**, and the card says so — the
alternative is a reader silently failing to reconcile 34 minutes of awake with a 308-minute span.

### 11a. The file transfer — `0x2C`, where sleep and RR intervals live

The fitness service hands out fixed-shape records by index. Sleep and the per-beat RR intervals are
not records: they are **files**, fetched by name.

| File | Type | Holds | Takes an id? |
|---|---|---|---|
| `sequence_data` | `0x16` | sleep | **yes** — it is a container |
| `rrisqi_data.bin` | `0x10` | per-beat RR intervals | no |

```
--> 0x2C/0x01  {1: name, 2: type, 5: from, 6: to, [12: id]}
<-- 0x2C/0x01  {1: name, 2: type, 3: type, 4: SIZE, 127: result}
--> 0x2C/0x03  {1: type, 2: '', 3: '', 5: ''}          # empty tags: "what chunk size?"
<-- 0x2C/0x03  {1: type, 2: 0x15, 3: 0x03D0, 4: 0x1E80, 5: 0x02}
--> 0x2C/0x04  {1: type, 2: offset, 3: size, [4: id]}  # "send it"
<-- 0x2C/0x05  type(1) | offset(4 BE) | data … | 1 trailing byte    # pushed, unprompted
--> 0x2C/0x06  {1: type, 2: 01}                        # "I have it all"
<-- 0x2C/0x06  {127: ok, 1: type}
```

Points that cost time to establish:

* **`0x2C/0x05` is NOT TLV.** It is a header plus raw file bytes. TLV-parsing it does not fail — it
  yields plausible nonsense, a tag 0 holding 450 bytes beside a tag 198 holding two — because
  arbitrary binary parses as tag/length pairs. `HuaweiSession.decryptBytes` exists so nothing reads
  file bytes through the TLV path.
* **The header is five bytes, and the band sends one byte MORE than it declares.** Corrected
  2026-08-22: the earlier reading — a six-byte header with an unknown trailer — fits the byte counts
  equally well and is wrong. Both transfers carry `size + 1` (643 for a declared 642; a final slice
  ending at 7525 of a declared 7524), and the overflow byte rides in the last slice rather than
  arriving on its own. Treating the declared size as the length cost the first captured night its
  final segment's high byte — zero that time, so nothing looked broken. The client now treats the
  declared size as a **minimum**: it decides when the transfer is complete, not how much is kept.
* **The band pushes.** After `0x2C/0x04` there is no per-chunk acknowledgement; chunks arrive until
  the file is complete.
* **Result 144001 means "nothing for that window".** It is an answer, not a fault — an empty night
  and a broken request must never look alike.
* **`0x1C` is not this.** It runs concurrently during pairing and carries `HW_PGNSS_BDS` /
  `HW_PGNSS_GLONASS` GNSS almanacs *phone → band*. It is the largest traffic in the capture and has
  nothing to do with health data.

**Answered 2026-08-22** by running `バンド書類（Huawei） -- [727]` against the real band. All four
streams returned data, `rrisqi_data.bin` for the first time — and the 9804-byte transfer exercised
multi-chunk reassembly on the wire, not just in tests.

Every `sequence_data` stream opens with the same 33-byte header: `00`, the file size as a uint32, the
stream id, then flags and padding. Records start at `0x21` with a **start/end epoch pair**.

| id | that run | shape |
|---|---|---|
| `700013` | 642 B | **SLEEP** — one record, 2026-08-21 23:55 → 08-22 05:03 (5 h 08 m) |
| `700004` | 7845 B | same record shape, single moment (14:07:03 → 14:07:03) |
| `700021` | 9804 B | denser; a record header then a long blob of repeating 4-byte groups |
| `rrisqi_data.bin` | 312 B | 48-byte header, then **66-byte records** |

**`sequence_data` 700013 decodes fully — confirmed against the band's own Sleep screen.** The file
stores no totals at all (290, 308, 83, 157 and 50 appear nowhere in its 642 bytes), so Health
computes the summary from a segment list, and so do we. After the `0x81` configuration container the
tail is an array of **little-endian uint32 pairs: duration in seconds, then stage.** Every header
field above it is big-endian; the endianness really does flip mid-file.

| code | stage | night of 2026-08-21 | band's screen |
|---|---|---|---|
| 1 | light | 157 min | 2 h 37 min ✓ |
| 2 | REM | 50 min | 50 min ✓ |
| 3 | deep | 83 min | 1 h 23 min ✓ |
| 4 | awake | 34 min | — (12 min before bed time, 4 after waking, 18 within) |

**The header brackets the SLEEP, not the segment array.** The segments run 324 min against a
declared span of 308; the excess is exactly a 12-minute awake block before bed time and a 4-minute
one after waking. So the array is anchored by its **first non-awake segment**, which begins at the
declared start — and the last non-awake segment then ends exactly on the declared wake time, which
is the file's own confirmation that the alignment is right. Anchoring at the declared start instead
(the obvious reading) leaves every total correct and shifts the whole hypnogram twelve minutes late,
a failure no summary figure would reveal.

Light + REM + deep = 290 min = the headline **4 h 50 min**, to the minute. Note the numbering does
NOT run deep-to-light: the plausible guess swaps deep with light and still draws a convincing
hypnogram, which is why this was checked rather than assumed. Awake totals 34 min, and that now reconciles exactly: 12 min before bed time
plus 4 after waking are outside the declared span, leaving the 18 min within it that
span-minus-asleep implies.

`rrisqi_data.bin` records are: start epoch, end epoch, a few flag bytes, then **ten IEEE-754 BE
float32 fields**. Two are pinned against Huawei Health's own lists (2026-08-22):

| field | offset | meaning | how it was established |
|---|---|---|---|
| 1 | +19 | **count of valid intervals** | Health publishes a window only when this is ≳17 — a clean 9/9 split between the three records it listed (32, 19, 20) and the six it omitted (16, 14, 12, 9, 9, 7) |
| 6 | +39 | **mean RR interval, ms, quantised to 20 ms** | `60000/f6` reproduces Health's heart rate across 8 overlapping points at **RMSE 2.15 bpm** — and it is the ONLY fit under 6 bpm across all 66 byte positions and both endiannesses. At ~720 ms the 20 ms grid is itself worth ~2.3 bpm, so the residual IS the quantisation |

Field 3 (+27) is the other 20 ms-quantised field and is always smaller than field 6, so it is very
likely the shortest interval in the window. **Health's HRV number is NOT any stored field** — of
three overlapping entries, one (35 ms at 15:10) appears nowhere in its record at any offset or
encoding, so Health derives it. Two fields each matched one of the other two, which is what
coincidence looks like; nothing here is decoded on that basis.

### The four empty pulls were our request order, not the band (2026-08-25)

`rrisqi_data.bin` answered `nothing (100004)` four times across two days, and that was read as the
band holding no RR data — which grew into a hypothesis that `SVC_RRI` (0x19) had to be switched on
before it would record any, testable only by waiting.

It was none of that. **`huawei.files` asked for rrisqi LAST**, after every `sequence_data` id.
`700004` alone is 1.16 MB and does not finish — 378691 bytes after 8 rounds on 2026-08-25 07:05 —
and the file channel degrades under exactly that kind of use. Everything requested after it came
back empty. The counter-evidence had been in plain sight the whole time: `storeRri` fetches the same
file with nothing queued ahead of it, and the sync had been reporting **291 RR windows** while the
diagnostic reported nothing.

Asked first, on a channel rested ten minutes, rrisqi returned **20377 bytes in 1.7 seconds**:
`(20377 − 48) / 66 = 308` records exactly, which is also the layout's own confirmation.
`HuaweiFilesAction` now requests it first.

### 308 windows: what survives, and what does not

The corpus below is 308 windows spanning 2026-08-22 14:22 → 2026-08-25 06:51, against the **nine**
the paragraphs above were written from. Two claims made from nine do not survive:

| claim, from 9 windows | on 308 |
|---|---|
| f7 is smaller than every other field, in every window | 306/308 — a near-invariant, not an invariant |
| **f2 < f1 always** | **280/308 — false as an absolute** |
| f4, f5, f10 < f3 < f6 | 307/308 |
| f3 < f6 | 308/308 ✓ |
| f3 and f6 on the 20 ms grid | 308/308 ✓ |
| byte 13 ≥ the valid count, never equal | **308/308, zero equalities** ✓ — much stronger now |
| f2, f5, f8, f9 fall as more beats are detected | ✓ r = −0.61, −0.75, −0.69, −0.79 (f3 does too, at −0.66, which nine windows did not show) |

Windows arrive every **10 min** (median; p10 6, p90 20), so the band records continuously and always
has.

### `sequence_data` 700021 IS the per-beat series — and it names two fields (2026-08-25)

700021 was the last undecoded stream: *"a record header then a long blob of repeating 4-byte
groups"*, not fixed-stride, layout open. Pulled alone on a rested channel it returned **97789 bytes**
in 9 seconds — ten times the 9804-byte capture it had been described from, which was itself a
starved pull.

The layout is simple once the file is whole:

| part | shape |
|---|---|
| file header | `00`, size as BE uint32, stream id as BE uint32 (`0x000AAE75` = 700021) |
| record | 44-byte header: start/end epoch as BE uint32 at +0/+4, an LE echo of the start at +0x24 |
| beats | **(uint16 LE interval in ms, uint16 LE quality)**, repeated to the next record header |

Variable beat count per record is exactly why it looked strideless. The series validates against
itself: **sum(intervals) lands within 25 s of the record's declared span in 266 of 267 records**, and
quality is 100 for 20059 of 21502 beats, 50 for 1293, 0 for 150 — the `sqi` the filename has carried
all along.

**Those totals were 263 of 266 until 2026-08-27, and the missing records were ours, not the band's.**
The band writes a page index into every 976th byte of this file — that byte is the page's own number
rather than data (see `HuaweiPagedFile.kt`). A 97789-byte capture carries 100 of them: 88 in a
quality field's spare high byte, and the rest in record headers, two of which took the low byte of a
big-endian start and left the record unfindable, so its beats were handed to the record before it.
Reading the headers by their little-endian echo at `+0x24` — the one copy of the start a stamp
cannot also have taken — recovers them, and the quality column then comes back as exactly
{100, 50, 0} with no impossible value left anywhere in it.

**No stamp has ever landed in an interval**, and that is structural rather than lucky: a record
header is 44 bytes and a beat is 4, so beats sit at offsets congruent to 1 (mod 4) while stamps sit
at 0 — each one falls on the same byte of the same field every time. Every metric below is computed
from intervals, so none of them was ever reading a damaged number.

**This is ground truth that needs neither Huawei Health nor a second phone.** RMSSD, SDNN, pNN50, the
range — all computable here, for every window, and comparable against the ten rrisqi fields. Pairing
is confirmed by the anchor rather than assumed: a 700021 record counts as the same episode as an
rrisqi window only when the mean interval computed from its beats lands within one 20 ms step of f6.
On those pairs f6 vs the computed mean is **r = +0.992**, which is what a correct pairing looks like.

| field | name | evidence |
|---|---|---|
| **f5** | **RMSSD, ms** | agrees to within **1 % in 69 of 124** confirmed pairs, ratio **0.9927** — a scale of one, not a fitted multiple. A shuffled null sits at 57 % median error, and no beat-selection rule (all plausible / quality = 100 / quality ≥ 50) moves the 69 |
| **f3** | **RR range (max − min), on the 20 ms grid** | exact in **76 of 142**; and the reading it replaces, the shortest interval, matches in **0 of 124** |

**f3 was carried as "very likely the shortest interval". It is not** — the minimum never matches,
and the range matches exactly in half the windows. That is what a per-beat series is for.

The remaining six — f2, f4, f7, f8, f9, f10 — match no standard metric. The best fits are f2 ≈ 0.65 ×
CV% at 10 % median error and f8 ≈ 1.67 × mean absolute successive difference at 29 %, against nulls
of 47 % and 69 %: better than chance, nowhere near a name. **f4 and f10 match nothing at all** (every
correlation under 0.1), which is itself worth recording, because they are the two that rise at night
— a quantity that tracks sleep but not any within-window statistic is likely computed over a longer
horizon than the window it is stored on.

Why only half the windows match even for a named field: the two files do not always describe the same
episode. rrisqi windows run 55–57 s with a median valid count of 21; 700021 records run 60 s (93 s
later in the file) with a median of 66 beats. Where they coincide the agreement is near-exact; where
they do not, nothing agrees, the anchor included. Tightening the start offset does not shift the
split, so it is the episodes themselves, not the alignment.

### Physiology as ground truth: two fields behave like HRV, the rest like noise

The file spans two nights, and f6 is established — so its own nocturnal dip anchors the split without
needing Huawei Health. Median heart rate is **68.2 bpm** between 00:00 and 06:00 against **76.9**
between 09:00 and 22:00, which is the dip, so the partition is sound.

HRV rises in sleep. Of the ten fields, **only f4 (×1.13) and f10 (×1.05) rise at night**, alongside
mean RR itself (×1.13). Every other field falls, by a lot: f2 ×0.64, f5 ×0.61, f7 ×0.66, f8 ×0.67,
f3 ×0.71.

That is two behavioural classes, and it is the first thing to separate these fields that did not come
from a displayed number:

* **f4 and f10 rise at night** while everything else falls. Read at the time as the signature of an
  HRV measure — **wrongly**, see *f4 and f10 are not RR-derived* below, which had per-beat data to
  test it against and this paragraph did not.
* **f2, f5, f7, f8 fall at night AND fall as detected beats rise.** Sleep is when the wrist is still
  and the signal is cleanest, so an artefact or dispersion-of-error measure falls exactly then. This
  is what the earlier correlation against byte 13 was already pointing at.
* **f9 is nearly flat (×0.89)** and belongs to neither class on this evidence.

**This weakens the one "very likely" in the paragraphs above.** f3 was read as the shortest interval
in the window. A cleaner night should RAISE a minimum — fewer spurious short beats — and f3 instead
falls by 29 %. A spread on the RR grid, driven by artefacts, fits that direction; a minimum does not.
Not disproved, but it should no longer be carried as very likely.

No field is a function of any other, on 308 windows as on nine: the closest are f8 ≈ c·f5² (r = 0.76)
and f7 ≈ c·f5/f6 (r = 0.75), and both have ratios that wander over a 30-fold range, so neither is an
identity.

### f4 and f10 are not RR-derived at all (2026-08-25)

Because they rise at night while every other field falls, f4 and f10 were read above as the
HRV-shaped pair, and then — when they turned out to correlate with no within-window statistic — as
values computed over a longer horizon than the window they are stored on. **Both readings are
wrong**, and the per-beat series settles it.

**A long-horizon value cannot jump between adjacent windows**, and these do:

| field | lag-1 autocorrelation | median change between adjacent windows |
|---|---|---|
| f6 (mean RR, established, per-window) | +0.706 | 2.6 % |
| **f4** | **+0.031** | **59.7 %** |
| **f10** | **+0.132** | 34.9 % |

f4 is the *least* autocorrelated field in the file. A 24-hour quantity sampled ten minutes apart
would be nearly identical between neighbours; f4 shares essentially nothing with its predecessor.
Sweeping trailing spans from 1 minute to 24 hours against SDNN, RMSSD, pNN50 and mean interval
confirms it from the other side: f4 and f10 stay under |r| = 0.16 at **every** span, while f2, f7 and
f8 peak at the shortest spans and decay as the span grows, which is what a per-window quantity does.

They are not functions of the beats at all. Against everything the per-beat series can produce for
their own window — SDNN, RMSSD, pNN50, SD1, SD2, min, max, range, IQR, CV, beat counts, the fraction
of beats at each quality level, the fraction rejected — **every correlation for f4 and f10 is under
0.16, and most are under 0.05.** Every other unknown field correlates with several of them at 0.4 to
0.8.

So the honest reading is: **f4 and f10 are computed from something other than the RR intervals**, and
merely stored alongside them. That accounts for both facts at once — no relationship to any beat
statistic, and a systematic day/night difference — in a way neither earlier reading did. Motion,
skin temperature, SpO2 and PPG perfusion all differ between sleep and wake and none of them would
track RR dispersion. Which one, this file cannot say.

This removes f4 and f10 from the HRV candidate list rather than advancing them, which is the useful
kind of negative: it is the only pair whose night-time rise now needs no HRV explanation.

**The remaining fields are unnamed, and the structure below is why they will stay that
way until there is ground truth.** Nine unique windows exist across all three captures (2026-08-22,
14:22 → 17:24, each 55–56 s). Everything here holds in all nine.

| finding | evidence |
|---|---|
| **f7 is smaller than every other field, in every window** | range `0.41…1.52`, and the only field that is. A ratio or an index rather than a quantity |
| **f2 < f1 always** | `3.3…12.6` against a count of `7…32` |
| **f3 is 20 ms-quantised and f4, f5, f10 all sit below it** | so `f4, f5, f10 < f3 < f6`, and f3 shares f6's RR-domain grid |
| **f2, f5, f8 and f9 all FALL as more beats are detected** | r = −0.80, −0.85, −0.68, −0.80 against the byte at record offset 13. The signature of dispersion measures over a noisy series: cleaner signal, less spread |
| **byte 13 is ≥ the valid count in all nine windows, and never equal to it** | `16 36 53 42 51 41 53 29 51` against `14 16 32 19 9 9 12 7 20`. A candidate **detected**-interval counter, with valid/detected running 0.18…0.88 |
| **no field is a function of any other** | an exhaustive search over `fi ≈ c·fj`, `c·fj·fk` and `c·fj/fk` at 8 % tolerance returns nothing. These are independent statistics, not derived columns |

Ranges, for anyone matching against a displayed number: f2 `3.3…12.6`, f3 `220…520` (×20),
f4 `1.3…71.4`, f5 `44.6…124.1`, f7 `0.41…1.52`, f8 `43.5…1442`, f9 `38.3…7008`, f10 `22.0…74.9`.

### The file channel: what was fixed, and what turns out to be the band's own

The `0x2C` transfer reported "N of M bytes never arrived" and that was read as loss. Three things
were wrong on our side and one thing is the band's.

**Ours, all fixed.**

1. **`FILE_NEGOTIATE`'s answer was computed and discarded.** The band offers `chunk 976, max 7808,
   mode 2`, and those numbers explain everything below. They are now carried into the result.
2. **`fileStart` has always taken an `offset`, and it was only ever called with `0`.** The band
   serves a bounded run of chunks per START and stops; restarting from the lowest missing byte is
   how the rest is fetched. This alone took a 3-day `700004` from 195201 bytes to 421632.
3. **A short transfer threw its data away.** 390 KB was assembled and then dropped because it was
   not whole. It now returns `Result.Partial` — a distinct type, so a caller that has not thought
   about incompleteness will not compile — and the bytes are written as `*.partial.bin`. The summary
   line says so too; before, a partial and a complete transfer differed only by a word buried in the
   filename.

**Every stopping point is a whole number of 7808-byte units**, which is the band's declared `max`:

```
195201 = 25 blocks + 1     390402 = 50 blocks + 2     421634 = 54 blocks + 2
declared 695389 = 89.06 blocks  ← the declared size is NOT block-aligned
```

**The band's, and not fixable from here: the amount served is arbitrary.** It is not a size limit,
and persistence does not help. Riding out short windows (5 stalls, a 1.2 s pause between) produced a
byte-identical result. And the decisive test runs the other way from every hypothesis:

| asked for | declared | received | rounds |
|---|---|---|---|
| 3 days | 695389 | 421632 (54 blocks) | 4 |
| **1 day** | **265674** | **62464 (8 blocks)** | **9** |

A *smaller* request returned *less* data, after twice as many rounds. So the truncation tracks
neither the request window nor the declared size. Across one morning the same file yielded 25, 25,
50, 54, 54 and 8 blocks — and the worst result came last, after two large pulls minutes earlier,
which is at least consistent with the service degrading under repeated use. **Spacing attempts out
is the only lever left, and it is a usage change rather than a code one.**

**The bytes that do arrive are sound.** The partial file has no chunk-aligned holes: its 116 zero
runs are 64–378 bytes, none a multiple of 976, drifting steadily in phase — record padding, not
loss. It ends exactly on a block boundary.

### The bridge held a broadcast open past Android's limit — fixed

Both `バンド書類（Huawei）` runs above raised an **ANR**, each exactly one minute after starting:
`Broadcast of Intent … RUN_TASK … WorkspaceTransferReceiver`. `goAsync()` is not a licence to take
as long as the work takes — a background broadcast must be finished within **60 s** or the system
faults the app, and it names the broadcast rather than the work, so it reads as a broken receiver
instead of a slow job.

The reply is now bounded and the work is not: at 45 s the receiver releases the broadcast with
`still running`, and the job carries on in the background where it can take as long as the band
needs. Every fast command — which is almost all of them, at tens of milliseconds — keeps its
synchronous result. Verified: the same pull that produced two ANRs produces none.

### Why the file is empty, and the one lead worth following

~~`rrisqi_data.bin` has answered `nothing (100004)` on **four** attempts across two days, including one on a channel rested 28 minutes that returned `sequence_data`'s best-ever 60 blocks. So it is not fatigue and not the transfer: the band has no RR data to give.~~

**Superseded 2026-08-25** — see *The four empty pulls were our request order, not the band*. The band was recording throughout; `huawei.files` asked for rrisqi after a 1.16 MB file that never finished.

It had some on 2026-08-22 — 312 B, nine windows — and the note written then says that capture
happened right after the band was told to record RR intervals. **Nothing in this app ever tells it
that.** `SVC_RRI` (0x19) is referenced only by the probe.

The probe's own sweep is the lead. Asked today, service 0x19 answers:

```
0x19/0x01  empty       100013          ← rejected
0x19/0x01  tlv(1)      100000 (OK)
0x19/0x01  tlv(1,=1)   100000 (OK)     ← shaped exactly like "enable = 1"
0x19/0x02  …           same pattern
```

An empty payload is refused and a tag-1 payload is accepted, on both commands. That is the shape of
a switch, and `tlv(1, 0x01)` is the obvious candidate for turning per-beat recording on.

**Running the probe therefore probably just enabled it** — which is precisely why `huawei.probe`'s
sweep field warns that it may set something on the band. The test is time, not code: leave the band
worn for some hours and pull again. Data means the switch is real and belongs in
`バンド計測設定（Huawei）` as a deliberate setting rather than a side effect of a diagnostic; still
nothing means the command is a query and the lead is dead.

**Until the file returns, the seven unnamed fields cannot be advanced at all** — not by more windows
and not by ground truth, because both need the file. That is a change from "we lack a number the band
displays": the blocker is now upstream of that, and it is the tractable one.

**Pinning them needs the values the band itself displays for those same windows**, and that is
currently blocked from both ends. There is no Huawei Health here any more, and **the band no longer
returns the file at all**: asked on 2026-08-24 for the last three days it answered
`rrisqi_data.bin — nothing (100004)`, having returned 312 B on 2026-08-22. So the sample cannot be
grown either. Nine windows, no ground truth, no derivable relations — the honest state is that the
space is narrowed and the naming is not available.

In `700013`/`700004` the record's timestamp pair is followed by a nested TLV container (tag `0x81`
with a VarInt length) whose inner blocks carry ids in the `0x29B9xxxx` range. **`700021` is not
fixed-stride** — a 35-byte guess aligns only the first record and produces nonsense timestamps after
it, so its layout is still open.

**The capture tooling, twice repaired (2026-08-22).** `btsnoop.py` originally sorted frames by byte
offset *within* a direction, which is not time order — with 1968 phone frames against 713 band ones,
replies appeared before their own requests. It now sorts by the btsnoop record timestamp; **do not
infer a sequence from a dump made before that.**

It also concatenated raw ACL payloads, so any LPv2 frame larger than about a kilobyte was split
across several RFCOMM PDUs and had their headers sitting *inside* it, failing CRC and vanishing
silently. It now reassembles L2CAP PDUs using the ACL PB flag and strips RFCOMM UIH framing. The
difference:

| direction | before | after |
|---|---|---|
| band → phone | 713 frames, 81.2 % of bytes | 719 frames, **92.1 %** |
| phone → band | 1968 frames, 64.0 % | 2707 frames, **99.1 %** |

`0x2C/0x05` went from 2 recovered frames (the last chunk of each transfer) to 7, and the phone→band
upload channel `0x28/0x06` from 374 KB to 1.05 MB. The phone→band direction is the one a watch-face
upload travels on, which is why this had to be fixed before capturing one.

## 12. Capturing Huawei Health

Worth writing down because two non-obvious things make the difference between a readable capture and
a useless one.

**The phone must actually support snooping.** `dumpsys bluetooth_manager | grep -i snoop` must report
`FULL`. On 白い熊's EMUI phone the Developer-options toggle is cosmetic and the property is
SELinux-locked; a rooted Samsung works and writes to `/data/log/bt/btsnoop_hci.log`.

**The capture must include the pairing.** Keys are recovered by replaying Health's own derivation —
the PIN is encrypted under the universal digest secret, and the HiChain steps are plaintext JSON —
so a capture that starts after Health has paired yields nothing but ciphertext.

**Per-toggle attribution is by snapshot, not by rotating the log.** The log is append-only: pull a
copy before the toggle and another after, and diff the decoded frame lists. Rotating it mid-session
risks losing the handshake and with it every payload.

### Two bugs that made the earlier capture look like a failure

The 24 MB capture from 2026-08-21 decrypted **0 of 94** frames and was written off as unusable. Both
causes were in the tooling:

1. **The two HiChain passes interleave.** Health runs bind and auth with different request ids and
   their frames alternate in the log. A single-slot state machine has the auth pass overwrite the
   bind pass before it has yielded the authToken the auth pass needs — so the operational key is
   never derived and every phone→band payload stays opaque. Key the state by **request id** and defer
   derivation until all frames are in, bind first.
2. **The authToken's AAD is a random challenge.** It cannot be recovered from ciphertext alone — but
   it travels in the host's `encData`, sealed under the pass-1 session key, which *is* derivable.
   Decrypt that, use it as the AAD, and the token falls out.

With both fixed, 1870 of 1993 frames decrypt.

---

## 9. What is proven, and what is not

**Proven:** a factory-reset band reaches a working watch face in ~15 s from the PC and ~30 s from the
phone, with no Huawei account and no Huawei software; a stored bind survives across sessions; history
survives a companion change; step records read back over service `0x07` count-then-index.

**Not yet done:** the `0x2c` file-transfer client, which is what stands between us and both sleep and
real HRV — see §11. Once it exists, `sequence_data` and `rrisqi_data.bin` are the last two data
sources on this band we know of and do not read.

**`0x07/0x0C`–`0x0D` is NOT sleep** — it was labelled that on a guess and decoded on 2026-08-22 as
**activity bouts**: `0x05` carries a 4-byte epoch start and a 2-byte duration in minutes,
non-overlapping, confirmed against a known morning walk. Sleep lives elsewhere.

**Heart rate and SpO₂ are absent because the band is not recording them**, not because they are
unreachable: they occupy bits in the per-minute records the step service already returns, and the
band's own SpO₂ screen says automatic measurement must be enabled first. The same may be true of
truSleep — `SettingRelated` confirms the band *supports* truSleep/RRI/GPS, which is not the same as
having them switched on. The band's ring-buffer depth is **measured,
never assumed** — `huawei_syncs.oldestReturnedSeconds` records the oldest sample each sync actually
returned, and the reported figure is a **floor** ("at least N h observed"), never a capacity.

**This band is open by accident.** It is a J-Style/Joint ODM board whose SDK leaked years before
Huawei rebadged it. There is no upstream to fix us if a firmware update changes any of the above.
