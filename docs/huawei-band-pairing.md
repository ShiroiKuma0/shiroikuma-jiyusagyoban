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

### Weather is a PUSH, not a request

The band displays weather that the phone sends it. There is no fetching involved on our side and no
HTTP proxying — captured 2026-08-22 by refreshing the band's weather screen with a decrypted snoop
running.

```
0x18/0x07   location   {1: <epoch>, 2: <latitude>, 3: <longitude>}
0x0f/0x01   weather    {  8: place name, plain ASCII — e.g. "Hodkovicky"
                          9: current temperature, °C
                         12: observation time, epoch seconds
                         16: humidity, %
                         17: high °C   18: low °C
                        129, 133: containers, believed to carry condition/icon codes }
```

**The coordinates are LITTLE-endian IEEE-754 doubles**, while every integer elsewhere in this
protocol is big-endian. Decoding them the same way as everything else yields 10⁻¹²⁹ rather than an
obviously wrong number, so the mistake does not announce itself.

The `129` and `133` containers are small integers with no anchor, and one sample cannot pin them.
The efficient way to map them is not to wait for different weather but to **push varied values and
watch the band's icon change** — which is available as soon as we can send this at all.

Separately, the band repeatedly asks for `hw.wearable.httpProxy` over `0x37/0x02` (topic
`2FB08EAB`) and gets no answer from Health either. It wants the phone to fetch URLs on its behalf.
That is a real capability and a real hazard — answering it makes our app the band's HTTP client for
arbitrary requests — and it is **not** needed for weather.

Opening the weather screen also makes the band fetch its weather **app** — numeric ids over `0x27`
with a version string, then a `0x28` file stream. That is app management, not data.

---

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
