# Hand-off — 接続 (data-speed testing across both SIMs and WiFi)

Written 2026-08-02. Everything below is **verified on 白い熊's Mate XT unless marked otherwise**.
Read this before touching the 接続 project; several of the findings cost multiple wrong attempts.

---

## 1. What 白い熊 asked for

A `接続` project, launched from a home-screen shortcut, that measures real download/upload throughput
per SIM because speed varies by location.

- **SIM1** — colour `#880000`, name `T-Mobile`. **SIM2** — colour `#0000FF`, name `O₂` (subscript two).
  Use these colours and names in every dialog, scene and report.
- Main task: ensure WiFi off → switch to SIM1 → download + upload test → live visual feedback showing
  progress, current speed, average speed → repeat for SIM2 → final report with statistics, nicely laid
  out.
- Wrappers: per-SIM (SIM parameter), **current-SIM** (whatever is active, no switching), **triple**
  (both SIMs + WiFi), and a **blind** wrapper (WiFi on → triple, WiFi off → dual).
- WiFi state saved before and restored after. Testing *without* toggling WiFi was preferred — **not
  possible, see §3**.
- Cancel button: stop immediately, restore WiFi state **and** the data SIM active before the test.
- Rich visuals: big SIM icon in the SIM's colour, SIM name, SIM number if obtainable, yellow graph,
  Ookla-style speedometer, black background, yellow text and border, much larger than the first attempt.
- Data volume is **not** a concern — 白い熊 is on unlimited and wants precision, Ookla-like (~1 GB/run).
- History (decided later): per-run statistics for the current run, plus a **reverse-chronological,
  scrollable** history list with GPS coordinates, each row tappable to open in **白い熊 地図**
  (`shiroikuma.chizu`), and a place name reverse-geocoded from the web after the test.

---

## 2. Hard-won device facts — do not re-derive these

| Fact | Detail |
| --- | --- |
| SIM slots | slot 0 = **T-Mobile CZ**, slot 1 = **O2.CZ** |
| Address SIMs by **slot, never subId** | The phone carries **five** subIds for two physical SIMs (stale entries from earlier insertions). A hardcoded subId breaks on re-insertion. |
| Data-SIM switch works root-free | shell holds `MODIFY_PHONE_STATE: granted=true`; Shizuku runs as shell → `ISub.setDefaultDataSubId` via a UserService. Verified both directions with restore. |
| `cmd phone` has **no** data-sub subcommand | Only ims/uce/cc/gba/src. Don't look again. |
| `settings put global multi_sim_data_call` | Mirrors the choice; does **not** drive the switch. |
| **SIM phone number is unavailable** | `number=` is empty in the subscription info for **both** SIMs. Show slot + carrier; there is no number to display. |
| Shizuku UserServices need R8 keep rules | Shizuku instantiates them **by name**. Without a keep rule `bindUserService` returns a null binder and the only symptom is "could not start the privileged telephony bridge". Rules exist in `app/proguard-rules.pro` for both `KeyGrabberService` and `TelephonyBridgeService`. |

---

## 3. Per-network binding is impossible on this device — WiFi must be toggled

`ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)` **succeeds** (a cellular network comes up
while WiFi stays connected), but using it fails:

```
Binding socket to network 132 failed: EPERM (Operation not permitted)
```

Confirmed **two independent ways** — per-connection `network.openConnection()` *and* process-wide
`bindProcessToNetwork()`. EMUI refuses a non-default network to an ordinary app. Both code paths are
retained in `SpeedTestAction` (process-binding preferred, per-connection fallback) so the code works
unchanged on a device that permits either — but **on this phone a SIM can only be measured while it is
the default route**, i.e. with WiFi off.

**Consequence for development:** any WiFi-off task **kills wireless adb mid-run**. You cannot observe
these flows over `adb connect`. Either use USB adb, or run the task and wait for the reconnect — the
reconnect itself proves the WiFi restore worked.

---

## 4. Endpoints — three dead ends, one that works

| Endpoint | Verdict |
| --- | --- |
| `speed.cloudflare.com/__down` | **HTTP 403** to a non-browser client. It backs their web speed test, is not a public API. Faking browser headers was rejected as fragile — do not "fix" it that way without asking. |
| `fra.proof.ovh.net` | **Does not exist.** Was a fabricated fallback; removed. |
| `proof.ovh.net` | Reachable but **throttled** (~30–80 Mb/s) and ~300 ms away. Silently carried three runs and made them look like 白い熊's link. Fallback only. |
| **Ookla server list** | ✅ `https://www.speedtest.net/api/js/servers?engine=js&limit=5`, ordered by distance from the client IP. Take `[0].url` (`…/upload.php`); the download companion is the same path with `upload.php` → `random4000x4000.jpg`. Picked `speedtest5.t-mobile.cz`. |

**Ookla servers 307-redirect `http` → `https`,** and `HttpURLConnection` silently refuses
cross-protocol redirects. Download follows hops manually; **upload pre-resolves** the final URL with a
zero-length POST probe first, because a body already streaming cannot be replayed at a new location.

`%SPD_DownFallback` / `%SPD_UpFallback` are **empty when the nearest server was used**. Non-empty means
the figure came from a fallback and is **not comparable** with other runs. Surface this in the report.

---

## 5. Measurement design (matches Ookla's shape)

- **The clock is the limiter**, not bytes: `seconds` (default 10). `max_mb` default 4000 is a runaway
  guard only. A byte cap that binds first ends the leg inside TCP slow-start — a 5 MB cap finished in
  0.28 s and produced a single sample.
- **8 parallel streams** (1–16). One TCP stream is bounded by `window / RTT` and under-reports.
- **`ramp_ms` (default 2000) is excluded from the headline average**; the un-excluded figure is kept in
  `%SPD_<Dir>Raw`. On download this raised 790 → 881 Mb/s.
- Byte counts verified against `/proc/net/dev` to ~1 %. That is also how to measure any other app's
  usage from Termux without root: `awk -v i='wlan0:' '$1==i{print $2, $10}' /proc/net/dev` before/after.

### Accuracy against Ookla, SIM1 (T-Mobile)

| | Ookla | 接続 | Note |
| --- | --- | --- | --- |
| Download | 168 Mb/s | 195.35 | +16 %, credible |
| Upload | 39.1 Mb/s | 23.87 | **−39 %, under-reads — BACKLOG** |
| Ping / latency | 27 ms | 325 ms | **Not comparable.** Ours is time-to-first-byte (DNS+TCP+TLS+HTTP), not ICMP. Relabelled 初byte; consider a real ping. |

WiFi reference run: 881 Mb/s vs 白い熊's Ookla 950 — within 7 %.

**Upload instantaneous figures are untrustworthy**: the counter increments when `write()` returns, i.e.
when data enters the kernel buffer, not when it reaches the wire. Peak read 1081 Mb/s, and the ramp
exclusion *lowered* the average (367 → 325) where on download it raised it. Totals are sound (confirmed
by the interface counter); **peak should probably not be reported for upload** until sampled from
interface counters instead.

---

## 5b. UNITS — 白い熊's convention (2026-08-02)

**Speeds are shown in `MB/s`, 1024-based (MiB/s), as the headline figure**, with the megabit value
close by in **smaller grey text**. Numbers are **bold**; labels are not — the number must read bolder
than its label.

The engine publishes both for every figure:

| Megabits (decimal, 10^6) | 1024-based MB/s |
| --- | --- |
| `%SPD_Cur` `%SPD_Avg` `%SPD_Peak` | `%SPD_CurMB` `%SPD_AvgMB` `%SPD_PeakMB` |
| `%SPD_DownAvg` `%SPD_DownPeak` | `%SPD_DownAvgMB` `%SPD_DownPeakMB` |
| `%SPD_UpAvg` `%SPD_UpPeak` | `%SPD_UpAvgMB` `%SPD_UpPeakMB` |

Conversion: `MiB/s = Mb/s * 1_000_000 / 8 / 1_048_576`.

**Still owed:** 白い熊 wants the primary unit **settable in the UI**. Not yet implemented — a scene
cannot branch on a variable, so it needs either two scene variants or a native conditional/format
element. Both units are always published, so only the presentation choice is missing.

---

## 5c. Scene renderer — the config keys are NOT what you would guess

Unknown config keys are **silently ignored**, so a wrong name renders nothing and looks like a layout
bug. Read `SceneActivity.kt` before inventing a key.

| Want | Correct key | What I wrongly used |
| --- | --- | --- |
| fill colour | `bgColor` | `fillColor` (only PROGRESS uses that) |
| border | `borderColor`, `borderWidth` | `strokeColor`, `strokeWidth` |
| button caption | `label` | `text` |
| panel background + border | **scene-level** `bgColor` / `borderColor` / `borderWidth` | a full-size RECTANGLE element |

TEXT supports only `text`, `textSize`, `bold`, `textColor`, `align`. **There is no shadow key** — a
"white shadow" is faked by drawing a white copy offset 2 dp behind the coloured copy.
There is no arc/gauge element either (`TEXT/BUTTON/OVAL/RECTANGLE/PROGRESS/IMAGE/SLIDER/...`), so the
SIM icon is composed from rounded RECTANGLEs and the speedometer is a big number over a bar.

`scene.show` takes `dismissOnOutside=false` and `keepScreenOn=true` — without the first, a stray touch
kills a running test, which happened.

---

## 5d. Control path when adb is down

adb's TCP listener dies (reboot/timeout) and then `adb connect` gives "no route to host". **ssh to
`skhw` drives the bridge fine** and is the better fallback:

```bash
scp file.json skhw:~/tmp/          # ~/tmp on skhw IS /sdcard/tmp
ssh skhw "am broadcast -a shiroikuma.jiyusagyoban.action.RUN_TASK \
  --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1 \
  --es shiroikuma.jiyusagyoban.extra.TASK '...' \
  -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver"
```

**But `screencap` over ssh returns 0 bytes** (Termux UID lacks the permission) and ssh cannot install
an APK. So over ssh a chat can show a scene but not see it, and 白い熊 must install builds by hand.
**Restore adb first** (`adb tcpip 5555` over USB) — without it the visual iteration loop cannot close.

**Scene preview without running a test** — verified, and the way to iterate on layout:
`接続 試験 -- 画面下見` (live) / `接続 試験 -- 報告下見` (report) / `接続 試験 -- 画面下見閉` (close).

---

## 6. Workspace conventions that cost real time

- **Variable NAME arguments are bare — no `%`.** Live workspace: **619 bare vs 3 with `%`**. The
  `var.set` field hint says "%var name" and is **wrong**. Arguments are expanded *before* the action
  runs, so `%Foo` resolves to nothing and the write is **silently dropped** — the settings task reported
  `success` in 3 ms while storing nothing. Values keep their `%`; those *are* references.
  - This same bug left 白い熊's **WiFi switched off** with no way for me to reach the phone.
- **Scenes cannot see task-local variables.** A scene renders outside the running task's local scope.
  Anything a scene displays must be a **global** (leading uppercase, e.g. `Setsuzoku_CurName`).
  `%simname` rendered blank for exactly this reason.
- **Restore guards must be fail-safe**: use `%Setsuzoku_WifiWas != false`, so an unknown/unreadable
  state **restores** WiFi rather than leaving it off.
- Bundle format: `schemaVersion: 5`, id-free/name-based; scene elements use `xDp/yDp/widthDp/heightDp`;
  the wait action is `flow.wait` with `millis`.
- Bridge: every broadcast needs `--ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1` and an explicit `-n`
  component, and must be ordered (`am broadcast` is). `RUN_TASK` now returns the failure reason.

---

## 7. What is built

**Engine** (`custom`, committed):
- `net.speedtest` — `SpeedTestAction.kt`. Transport pin, Ookla discovery, redirects, parallel streams,
  ramp exclusion, live `%SPD_*` publishing every 250 ms.
- `net.speedtest.cancel` — volatile flag checked by every stream on every buffer.
- `sim.data.set` / `sim.list` — slot-addressed, via `ITelephonyBridge` Shizuku UserService.
- Manifest: `CHANGE_NETWORK_STATE`, `READ_PHONE_STATE`, `<queries>` for `shiroikuma.chizu`.

**Workspace** — `.scratch/setsuzoku.json` (10 tasks, 2 scenes): 接続の設定, 一枚測定 (core, param `slot`),
両SIM, SIM1のみ, SIM2のみ, 現在のSIM, 三重, 自動判定, 報告閉, 中止.

**Verified:** settings persist; the guard evaluates `WOULD restore`; `接続 -- 現在のSIM` ran end-to-end
(195 Mb/s T-Mobile, WiFi dropped and restored, SIM untouched); SIM switch + restore.

**NOT verified:** the rebuilt scenes (never seen — phone went unreachable before delivery), 両SIM /
三重 / 自動判定 / per-SIM wrappers, and the cancel button.

---

## 8. Backlog for the next chat

1. **Install `+018` and import `setsuzoku_2026-08-02_16-58-55.json`** (both already on the phone at
   `/sdcard/tmp/`), then look at both scenes. They are a rewrite (380×760 dp, black/yellow, SIM badge,
   cancel button) and have **never been seen**. Expect iteration.
2. **Test the cancel button** — mid-run, then confirm WiFi and the data SIM both came back.
3. **Upload under-read** (−39 % vs Ookla) and upload peak sampled from interface counters.
4. **Real ping** instead of / alongside time-to-first-byte.
5. **Native `GAUGE` scene element** — there is no arc/needle in the element vocabulary
   (`TEXT/BUTTON/OVAL/RECTANGLE/PROGRESS/IMAGE/SLIDER…`), so the current "speedometer" is a big number
   over a bar. A real Ookla-style dial needs a new element type.
6. **Phase 4 — history**: persist each run; GPS fix during the test; reverse-geocode to a place name
   after it; a scrollable reverse-chronological screen whose rows open
   `VIEW geo:<lat>,<lon>?z=17` in `shiroikuma.chizu`
   (`net.osmand.plus.activities.search.GeoIntentActivity`, exported — already in `<queries>`).
7. **Trio grouping** (71/01/37) was not applied to 接続 — see the `trio-group-convention` memory.
8. **Workspace mirror not synced** — 白い熊 has not yet confirmed the bundle works, so per the
   `confirm-bundle-then-sync-mirror` rule the mirror is deliberately untouched.

Delete this file once the project is finished.
