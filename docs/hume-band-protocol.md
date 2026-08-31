# Hume Band V2 — protocol, records, and the chart pipeline

The authoritative reference for the 「健康」 feature: how the band is spoken to, what its records mean,
and why the charts filter the way they do. Written from a reverse-engineering session (2026-08-02) and
from measurements against 白い熊's own device (2026-08-03).

> Source of truth for the shapes: `app/src/main/java/com/opentasker/core/band/*.kt` (protocol, records,
> stream machine, census) and `app/src/main/java/com/opentasker/ui/charts/*.kt` (the render pipeline).
> Keep this file in sync when those change.

Two things this document exists for. First, **the band is not documented anywhere** — if this file and
the code comments go, the next session starts from a decompiled vendor SDK again. Second, and more
important, most of what follows is **choices with reasons**. A choice whose reason is lost gets
"improved" back to the wrong answer by someone acting in good faith, which is exactly how a chart ends
up averaging data it was specifically built not to average.

---

## 1. The link

No pairing, no bonding, no authentication. A MAC address is enough to connect and read a person's
entire stored health history. That is the band's design, not ours; it is worth knowing.

| | |
|---|---|
| Service | `fff0` |
| Write characteristic | `fff6` (write-without-response) |
| Notify characteristic | `fff7` |
| CCCD | `2902` |
| Frame size | 16 bytes, plaintext |
| Checksum | additive over the payload |
| Dates | BCD |

**`requestMtu(247)` is mandatory.** Sleep frames are 130 bytes; the default ATT MTU of 23 gives a
20-byte payload and the frames arrive truncated with no error to tell you so.

`autoConnect = false` deliberately: `true` leaves the radio scanning indefinitely for a band that may
be off the wrist and charging. `close()` runs in a `finally` on every path — a leaked `BluetoothGatt`
produces status 133 on the next connection, which looks like a hardware fault and is not one.

### Paging

Frame-counted, not length-prefixed: after every **50 frames** the host sends CONTINUE, and the stream
ends with a `0xFF` terminator. **CONTINUE carries a ZERO date**, not the original start date — sending
the start date again restarts the stream from the beginning, forever.

Records are sliced out of a frame *before* the terminator check, because the terminating frame still
carries data.

### One notification is one frame — measured, not assumed

This was carried as an open assumption for months, and the frame-counted rule above is meaningless
without it. It is now settled against ten syncs (2026-08-06).

A frame is packed with **whole records up to the granted payload**. Once `maxFrameBytes` was
instrumented the rule could be read straight off the wire, and every stream's longest notification is
exactly `floor(244 / stride) × stride`:

| stream | stride | records/frame | longest frame | measured |
|---|---|---|---|---|
| `hr` | 10 | 24 | 240 | **240** |
| `spo2` | 10 | 24 | 240 | **240** |
| `hrv` | 15 | 16 | 240 | **240** |
| `temp` | 11 | 22 | 242 | **242** |
| `detail` | 25 | 9 | 225 | **225** |
| `sleep` | whole frame | 1 | 130 | **130** — and 255 records in 255 frames, exactly 1.0000 |

Five independent strides landing on their own arithmetic simultaneously is not a coincidence, and
nothing ever exceeds the 244-byte payload. Sleep is the decisive case anyway: its 130-byte frames are
the largest and the only ones that could plausibly fragment, and they arrive one record per
notification. Fragmentation is in any case impossible at the granted MTU — 247 on every sync — and
every stream in every working sync has ended on `TERMINATOR`, never `IDLE_TIMEOUT`, which
frame-counted paging could not manage if our notification count diverged from the band's.

`BandStreamStat.maxFrameBytes` / `minFrameBytes` record the extremes on every stream of every sync, so
the rule stays self-monitoring rather than merely once-checked: a max above `MTU − 3` means
fragmentation has appeared, and a max collapsing toward 20 means the MTU request silently failed and
frames are arriving truncated. `BandRecordsTest` asserts the ceiling.

### The mode byte, and the one that is absent

`BandReadMode` has exactly two members: `START (0x00)` and `CONTINUE (0x02)`.

The firmware defines a third that **erases the band's stored history**. It is not implemented here, and
its absence is enforced rather than remembered: `BandSafetyGuardTest` reads the sources as text and
fails the build if the opcode, the words "erase"/"factoryReset"/"clearBand", or an `Int`-taking frame
builder appear anywhere in `core/band`. A caller cannot name a raw mode byte, so the destructive
command is unrepresentable rather than merely unused.

---

## 2. Records

`BandStream.SYNC_ORDER`: heart rate, HRV, SpO₂, temperature, sleep, daily, detail — then five slots
that are expected to be empty. Those five are still requested every sync: one round trip each, and if a
firmware update lights one up, the census notices.

### Dedupe key

**The band's own wall clock**, `yyyyMMddHHmmss`, straight off the BCD bytes. Never epoch millis. At a
DST fall-back the same wall-clock hour occurs twice with different epochs, and an epoch key would store
every record in that hour a second time. `epochMs` is stored alongside for plotting and is disposable.

### Detail activity (`0x52`) — 25 bytes per record

| offset | meaning |
|---|---|
| 3..8 | BCD datetime |
| 9..10 | steps, LE16 |
| 11..12 | calories, LE16 ÷ 100 |
| 13..14 | distance, LE16 ÷ 100 **km** |
| 15..24 | ten per-minute step counts |

**The ten per-minute counts run FORWARD** from the record's timestamp (`t+0 … t+9`). This was the one
field the sync hand-off could not confirm — backward would shift every step sample by nine minutes —
and it is settled against 87 real records:

- Slot 0 is non-zero in **87 of 87**. Twenty records have exactly one non-zero slot, and in all twenty
  it is slot 0. Backward would put a lone sample at a uniformly random index, so that is a 1-in-10²⁰
  coincidence.
- Forward explains it structurally: the band opens the bucket when the bucket's *first step* lands.
  That is also why the timestamps carry arbitrary seconds (`:31`, `:26`, `:22`) instead of sitting on a
  ten-minute grid — they are step arrivals, not timer ticks.
- The ten counts sum to the record's own step total in all 87, and the per-minute total for a day
  matches the `0x51` daily total exactly on two of three reference days.
- No two consecutive records are less than ten minutes apart, so forward buckets never overlap.

The vendor SDK (`ResolveUtil.getDetailData`) confirms every offset above but emits the ten counts as an
opaque space-separated string, so it could not answer the direction question.

### Daily totals (`0x51`) — 27 bytes, and a trap

The date sits at **offset 2, not 3**: this stream's prefix is two bytes, not three.

`[9..12]` is labelled `ExerciseMinutes` by the vendor SDK but reads ~0.4× steps against real frames, so
the label is wrong; `[21..22]` is claimed to be the step goal but reads `0x0000` while the `0x4B` query
reports 10000. Both are stored raw under neutral names and nothing is built on them.

Distance in this record is **hundredths of a kilometre** (607 → 6.07 km), stored as metres.

### Sleep

One record is one **segment**, not one night. Stage codes are stored **raw**: `1` = deep, `2` = light,
`3` = REM, `5` = awake. Do not use the Hume plugin's re-coded scheme. Sleep chunks are noon-to-noon, so
a night spanning midnight is one session rather than two halves.

### Three of the HRV record's six fields are not measurements

Established 2026-08-06 by forensic analysis of 2 131 records, each claim independently re-verified.
This section overrides anything earlier in this document that treats `hrv`, `stress`/`vascular`,
`sbp` or `dbp` as physiological readings.

#### The record comes in two kinds

| | n | `hrv` range | median | asleep |
|---|---|---|---|---|
| **A** — HR/BP triple present | 1 644 | 15–94 | 31 | 53 % |
| **B** — that triple absent | 487 | 50–99 | 75 | 0.4 % |

**Nothing is ever partially missing** — `hrv_hr`, `sbp` and `dbp` vanish together or not at all, so it
is a whole-record success or failure, not per-field sentinels. `P(B | hrv < 50) = 0.000`;
`P(B | hrv ≥ 50) = 0.638`. **The apparent 15–99 range of `hrv` is these two populations pooled.**

#### `hrv` is not HRV

It is a device state index. Two binary firmware flags — sleep mode, and whether the optical read
succeeded — explain **74 % of its variance**; adding real step data adds 0.2 points. The evidence:

- **Wrong-sign coupling to heart rate.** Same-record pairing gives Pearson **+0.383** pooled and
  **+0.179** asleep. Every genuine variability metric is *negative*, both physiologically and by the
  `RR = 60000/HR` non-linearity.
- **No sleep-stage information at all.** Deep 21.0, light 21.0, REM 20.5 — effect size ≈ 0 between
  all three. Real HRV separates these strongly (SDNN roughly halves from REM to deep).
- **It is near-random within a state.** Lag-1 autocorrelation +0.196 asleep, where the *same
  record's* heart rate holds +0.595.
- **It switches like a flag, not a signal.** At sleep onset it locks into 15–21 within one or two
  2-minute records; heart rate ramps over 40 minutes across the same boundary.
- **It is not motion artefact either.** Awake-but-stationary (no steps ±15 min, median 48) resembles
  awake-and-moving (54), not asleep (21); with record type controlled, 46 vs 44, p = 0.29. The
  reference channel `hrv_hr` *does* respond correctly across the same split, so the test had power.
- The band never transmits RR intervals, so no real HRV could be computed from what it sends.

It is charted as **バンド状態指数 / Band State Index**, split by record type, with no band ladder and
no unit, and it is **not** a 健康指数 component.

#### `sbp` / `dbp` are generated inside a ±10 mmHg clamp

Across 1 644 records systolic occupies **every integer 110–129 and never leaves it**; diastolic every
integer 60–79 and never leaves it. That is **120 ± 10 and 70 ± 10** — the calibration window this
class of SDK clamps its output to. Six days of ordinary life does not keep real systolic pressure
inside a twenty-point box.

They also carry no memory: lag-1 autocorrelation −0.015 and −0.020, mean|Δ|/σ of 1.14 and 1.16
against 1.13 for independent draws, while heart rate on the same records holds +0.59.

The chart is kept at 白い熊's request, chipped "not a measurement", with the above in its info sheet.
The FDA's September 2025 safety communication against unauthorised cuffless blood-pressure devices is
cited there too.

### `vascular` and `stress` are the same byte, and both are a lookup on `hrv`

The HRV record's offset `[10]` (stored as `vascular`) and offset `[12]` (stored as `stress`) are
**identical in 2038 of 2038 samples**, and in both golden frames. One number is being stored under two
names, and at least one of those labels is wrong; the data cannot say which.

Nor does it behave like a stress index. Asleep it is pinned at ~45 — **850 of 872 samples in 40–49** —
while awake it scatters across 10–99. A real stress measure falls during sleep; this does the
opposite, which is what a placeholder written when nothing was measured would look like.

Real HRV-derived stress indices do exist and are validated — Baevsky's Stress Index has a normal range
of 80–150, rising 1.5–2× under mild and 5–10× under severe stress
([AJP-Regu 2025](https://journals.physiology.org/doi/full/10.1152/ajpregu.00243.2024)) — but every one
of them needs **beat-to-beat RR intervals, which this band never sends.** So the number is very
unlikely to be one.

Worse than duplicated — **derived**. The vendor's own chain reconstructs exactly:

- **Type B: `stress` = ⌊hrv/2⌋ or ⌈hrv/2⌉, plus either 20 or 45 — 487 of 487, 100.00 %.**
- **Type A: `stress` ∈ 43…47 when `hrv` ≤ 45, and ∈ 10…14 when `hrv` ≥ 46 — 1 632 of 1 644, 99.27 %.**

The residual entropy is 1.94 bits ≈ log₂5: the spread *is* a five-value uniform dither. The field
carries **zero** independent information, so it has no chart. Its `MetricSpec` is kept so the decoder
and the archive stay unchanged and the finding stays checkable.

One irony worth recording: the chain maps *high* `hrv` → *low* stress, so the firmware treats byte [9]
as HRV in the conventional "higher = more relaxed" sense — while the data show it is highest when
awake and active. The vendor's own derivation is inverted against the physiology it invokes.

All fields keep being decoded and stored, so history stays continuous. Do not relabel any of them as
physiology without evidence that does not currently exist.

### Heart rate carries two populations

`0x55` interleaves a 120-second periodic series with an extra reading taken at each SpO₂ measurement,
under a different measurement mode and with a different bias.

**The rule for telling them apart is a timestamp join, not the seconds field.** The charts hand-off
proposed `:14`/`:34` against the periodic `:30`; on real data only 322 of 433 interleaved samples sit on
those seconds, so 111 would be misclassified. **All 433 share their exact timestamp with an SpO₂
sample.** That join is exact, needs no schema change and no tag at write time.

Measured separation on three days of 白い熊's data:

| | n | mean | median |
|---|---|---|---|
| periodic | 849 | 65.08 | 65 |
| SpO₂-coincident | 433 | 72.54 | 70 |

A **+7.46 bpm** bias, and a median step of 5.0 bpm across a series boundary against 2.0 bpm within one.
Merged, that is a systematic sawtooth every fifth slot which consumes the outlier filter's entire
rejection budget.

**Split them for the line; pool them for envelopes and for summaries.** Hume's own day range matches
the *pooled* population, not the periodic series alone — confirmed twice over: 55–103 on 2026-08-03,
and on 2026-08-04 Hume's H-tab headline of **58–91 bpm** against our pooled 08:00–15:00 range of
**58–91 bpm**, exact, with our hourly min/max matching all seven of its capsules.

#### It is not a bias. They are not measuring the same thing.

Corrected 2026-08-09, when 白い熊 asked why the two differ "on a massive scale" and the answer turned
out to overturn the framing above. Each spot reading was compared against the periodic readings
either side of it (within 5 min), over ten days:

| when | median gap | > +15 bpm |
|---|---|---|
| **asleep and still** (n=69) | **+1.0 bpm** | **0 %** |
| awake, no steps within ±5 min (n=374) | +3.5 bpm | 7.8 % |
| 1–20 steps (n=46) | +8.5 bpm | 26.1 % |
| 21–100 steps (n=102) | +10.5 bpm | 27.5 % |
| **over 100 steps** (n=40) | **+22.0 bpm** | 72.5 % |

**A calibration offset would survive sleep. This one vanishes.** What is left is exertion — and the
striking half is which series fails to show it:

- Walking at **130 steps/min the periodic series reads a median of 58 bpm**, *below* its own resting
  median of 66. Its 95th percentile is ~76 whether 白い熊 is still or walking. It is not tracking
  activity at all.
- The spot reading at the same cadence reads **89**, with an interquartile range of 85–94 while
  walking — ordinary walking heart rates.
- It is **not** the classic cadence-lock artefact either: an artefact locks HR onto the step rate,
  and at 130 steps/min this sits **42 bpm below** it.
- It owns the day's maximum on **9 of 10 days** (98–119 bpm; the periodic series' own maxima are
  76–87 on those days).

#### Both are real measurements. One of them fails under motion.

Sharpened the same evening, because "a slowly-updated resting baseline" was still the wrong picture.
Against each reading's OWN quiet baseline — the median of the still periodic readings in the previous
30 minutes — for readings taken with ≥60 steps in the surrounding minute:

| | n | median value | vs own quiet baseline |
|---|---|---|---|
| periodic, walking | 41 | 64 bpm (50–77) | **−4.0 bpm, 59 % BELOW resting** |
| spot, walking | 29 | 86 bpm (59–112) | **+18.0 bpm, 97 % above** |

So the periodic series does not *hold* a baseline while you walk — it drifts slightly **down**, and a
heart rate cannot fall during brisk walking. Nor is it a frozen or derived number: consecutive
periodic samples repeat exactly only 6 % of the time while walking (13 % at rest), so it is live and
varying. It is a genuine low-power measurement that loses the pulse under wrist motion, and its
failure mode is to read a little low rather than to report nothing.

That distinction matters for what may be built on it: the periodic series is trustworthy at rest —
where it agrees with the spot reading to 1 bpm, and where resting-HR and the health index read it —
and untrustworthy during activity. It is not a separate "resting HR" metric to be used as such.

The pooled figures above still hold, and the reason Hume's day range matches the *pooled* population
is now obvious: the top of that range is the spot readings, which are the only ones that see
exertion.

The old "+7.46 bpm bias" is the same number seen through the wrong frame: it is the mean of a
movement-dependent gap, not a constant offset. Do not re-derive a "correction" from it.

### The heart-rate gaps are the band, not us

The periodic series has ~47 gaps over 12 min across six days (11.2 % of its span). They were suspected
of being a pipeline defect. They are not, and the question is closed three independent ways:

1. **Every one of the 47 has other sensor streams alive inside it** — SpO₂ in 47 of 47, plus
   temperature, HRV and steps. Not one gap has every stream silent, so the band was worn and
   recording; it simply did not write a periodic HR sample in those slots.
2. **Hume has no data we lack.** Across the 60-minute hole at 2026-08-04 12:10–13:10, Hume's 12 PM
   capsule reads ~62–84 against our six readings' 60–85. Its numbers come from the same handful of
   samples ours do.
3. We read the entire ring buffer on every sync, so there is no mechanism by which a record the band
   still holds could fail to reach us.

The overnight stretch that was flagged as unchecked is checked: the periodic series fills 51–77 % of
its nominal slots on every one of six nights, with no night anomalous. Overnight is not different from
daytime — the series is sparse everywhere. **A gap where the band did not measure is the correct and
honest rendering**, so nothing here needs fixing.

---

## 3. The census, the archive, and the buffer-drop detector

### The archive is written on every path, and repairs itself

Fixed 2026-08-09. **The JSONL archive is only worth having if it is complete, and it was silently
not.**

`persist()` commits each stream's rows to the database as that stream lands, and banks the matching
JSONL lines; the flush that turned banked lines into a file write sat *after* the stream loop, inside
the same `try`, on the success path alone. So any sync that landed rows and then threw left the
database holding rows the file had never heard of — and the banked lines were dropped on the floor,
and leaked, since only the success path ever cleared the map.

Measured on 白い熊's own archive: syncs **28** (2026-08-08) and **41** (2026-08-09) are absent
entirely — no header, no records, no census — while their **27 heart-rate rows** of 2026-08-08 sit in
the database and show up in the day table (452 there against 425 in the file). Nothing warned. The
only reason it was ever noticed is that the two were counted against each other by hand.

Two changes, because one is not enough:

1. **The flush is unconditional.** Success, timeout, exception, failed connect — every exit path
   writes, and a `finally` clears the bank so nothing can outlive the sync that banked it. A failed
   sync now writes its header and a census with `ok:false`, which is worth having anyway: a hole in
   the id sequence was the only trace such a sync used to leave.
2. **Every sync repairs the archive** (`BandArchiveRepair`). A sync is known to be archived by its
   census line — written LAST, so its presence means every record line before it landed. Any sync id
   in `band_syncs` with no census in the monthly files gets its rows re-emitted from the database,
   closed by a `{"t":"repair","ids":[…],"n":…}` marker, itself written last for the same reason. The
   scan is a `startsWith` pass for the few dozen bracket lines, so it costs nothing in the normal
   case, where it finds nothing to do.

The window is bounded by the files themselves: only syncs at or after the **oldest monthly file
present** are candidates. Anything older has its census in a file that is not here — pruned, moved to
the backup archive — and with no monthly file at all the repair does nothing, because an empty
directory is not evidence that thousands of rows need writing.

### The band's own buffer

The band's buffers are ring buffers and overwrite silently. Nothing on our side deletes anything — the
Hume app never sends the destructive mode either, and a factory reset is the only thing that empties
the band — so what `band_syncs` measures is the band's own eviction. It is never pruned; it is a few
rows a day and it is the entire measurement series.

### The band ignores the requested start date

The fact everything else rests on, measured over ten syncs (2026-08-06): **the band returns its whole
buffer on every stream of every sync, regardless of the `from` date on the wire.** Sync 8 asked for
records from 2026-08-05 07:41 and was handed heart rate from 2026-08-01 18:59.

So `oldestLocalTs` is a direct reading of the buffer floor, free, on every sync. Watch the floor:
while it stands still nothing was evicted, and when it moves forward the band discarded everything it
passed over. `BandFrom`/`overlapMinutes` still exist and are harmless, but they buy nothing for these
streams — the overlap they request is arriving anyway.

### The three numbers

| field | meaning |
|---|---|
| `bufferDepthSec` | `newest − oldest` — the **headroom**, how long a sync may be missed |
| `floorAdvancedSec` | how far the floor moved since the last read of *this* stream — eviction |
| `lostWindowSec` | `oldest(now) − newest(previous read)` — what was evicted **unread**. The loss |

Eviction is not loss. HR's floor advanced 25 h between syncs 7 and 8 and cost nothing, because the new
floor was still far behind what the previous sync had banked. `lostWindowSec` is the only honest
number, and it is exact: on 白い熊's archive it reproduces the two real HRV holes — 13.4 h and 2.1 h —
and reads zero everywhere else. Both are asserted in `BandCensusTest` from the archive's own stamps.

The previous read is resolved **per stream**, over a window of recent syncs. `同期状態 -- [727]` probes
with `streams=hr` alone, and taking the last successful sync wholesale would give every other stream a
null previous-read and report a spurious loss on the next full sync.

### Measured capacities

From the last full sync of 2026-08-06. A buffer that has been seen to roll has its capacity pinned by
saturation; one that has not is only known to hold *at least* what it has been watched holding.

| stream | records held | depth | evicting? |
|---|---|---|---|
| `hr` | **2048** — saturated, a power of two | ~111 h (4.6 d) | yes, since 2026-08-05 |
| `hrv` (+`vascular`/`stress`/`sbp`/`dbp`) | ~600 | **~21.5 h** | yes, continuously |
| `spo2` | 837, growing | >141 h | never |
| `temp` | 273, growing | >140 h | never |
| `detail` | 1630, growing | >141 h | never |
| `sleep` | 36 segments, growing | >127 h | never |

**HRV is the binding constraint at about 21 hours**, against four and a half days for heart rate.
`自動同期 -- [727]` syncs every four hours against it — a 5.4× margin, which still holds if the band
ever quadruples its HRV rate.

### The estimator that was here before, and why it is gone

Loss used to be `expectedRecords − inserted`, with `expectedRecords` derived from a nominal cadence.
Every number it produced was wrong. `hr` is documented at 120 s and really runs at a 240 s median, so
it ran 2× high; `detail` was listed at 60 s, but one detail record is a **ten-minute** bucket, so it
ran 10× high — one sync reported "detail lost 1913" having lost precisely nothing.

Fixing the constants would not have saved it. The band skips slots constantly: the periodic heart-rate
series fills only 51–77 % of its own nominal slots overnight while demonstrably on the wrist. *Any*
cadence-based expectation manufactures loss out of a band that simply did not measure. **The floor is
an observation; an expectation is a guess.**

`BAND_CADENCE_SEC` survives as the nominal reference for the chart gap thresholds. It no longer drives
anything in the census.

> **Where "the buffer is about three days deep" came from.** It was never a measurement. The first
> sync requested `from = 2026-07-31 00:00` because `BandSyncArgs.FALLBACK_DAYS = 3`, and got three days
> back. We asked for three days and got three days. It bounded the request, never the buffer.

---

## 4. The chart pipeline

白い熊's brief, in their own words (2026-08-02), from which everything below descends:

> I don't think it's good to average the data: only to remove outlier peaks and troughs, and it should
> probably be smoothed out between the points if there are extreme outlier readings. The graphs we show
> should then be smooth — but **these must be as close to the actual measurements as possible**.

```
Room rows
 ├─ S1 QUALIFY   sentinel drop, range gate, slew gate, split interleaved HR
 ├─ S2 HAMPEL    rolling median + MAD; FLAG only, never replace
 ├─ S3 SEGMENT   split into contiguous runs at gaps
 │     ^^^ viewport-INDEPENDENT — computed once per (metric, day), cached
 ├─ S4 LTTB      per segment, only when n > threshold, buckets anchored to absolute time
 ├─ S5 PCHIP     Fritsch–Carlson slopes → cubic Bézier control points, in data space
 └─ S6 MAP       data→pixel affine + Path build            [per frame]
```

### The gap threshold must match the series it is drawn over

`ChartPipeline.gapThresholdMs` takes the *median* interval, which is right for one series and wrong
for a mixture. Pooled heart rate interleaves a 120 s and a 600 s cadence, so its median lands at 176 s
and the threshold at 528 s — just under the 600 s spacing that is perfectly ordinary wherever only the
SpO₂-coincident reading exists. That manufactured **67 spurious ~10-minute gaps out of 70**, and on
the real dashboard it tinted the heart-rate card with **52 gaps** where there are 4.

`mixedCadence = true` switches to the 90th percentile, which sits above the slower mode instead of
between the two. Only the pooled series sets it.

### Heart rate is a curve over the periodic series, with the spot readings on top

Changed 2026-08-09, at 白い熊's instruction, on both the dashboard card and the full-screen detail.

It was an hourly capsule: one mark per clock hour spanning that hour's real min and max. The mark was
honest, but it answered a question nobody was asking. An hour of heart rate is **12 to 30 readings**
(6 SpO₂-coincident on the ten-minute grid, plus whatever the periodic series manages of its 120 s
slots — it fills 37–48 % of them). A capsule showed two of those readings and hid the rest, and the
chart therefore read as though the band measures once an hour. It does not.

It went through three marks in one day, and the last one is the point. First a scatter of every
reading. Then, once §2's measurement showed the two populations are not the same measurement,
`RenderKind.LINE_WITH_SPOTS` — a PCHIP curve with a gradient fill, exactly as body temperature is,
with the other population as **hollow dots** on top. The curve went to the periodic series, on the
reasoning that a curve suits a slow-moving quantity sampled often.

**That was the wrong way round, and 白い熊 had it swapped the same evening.** The curve belongs to
the series that can be believed, and during any activity that is the ten-minutely spot reading. So:
the **spot readings carry the curve**; the periodic series is drawn as hollow dots around and under
it. At rest the dots sit on the line, because there both are right; when the dots fall away below it,
that is the periodic series losing the pulse to wrist motion, not the line being wrong.

The cost is a sparser curve — six samples an hour rather than twenty-four, breaking wherever the spot
series pauses for more than half an hour. The benefit is that the shape on screen is the shape of the
heart rate. Every reading is still drawn; nothing is aggregated, averaged or decimated.

#### Two chunks, because a tint and a break mean different things

The pooled chunk stays authoritative for the footer counts, the rejections and above all the **gap
tint** — a tint has to mean "the band recorded nothing here", which is only true of the pooled
series. The curve gets its own chunk (`ChartQualify.curveSeries`), built from the pooled chunk's
*retained* points so a rejected reading cannot return as a knot in the curve, and re-segmented at the
spot cadence with `mixedCadence` **off** — it is no longer a mixture, and `gapThresholdMs` takes the
larger of the nominal cadence and the observed median, so a ten-minutely series gets a ten-minutely
threshold without being told. So:

- the **line breaks** where the spot series really stops;
- the **shading** appears only where nothing at all was recorded;
- and a stretch carrying only periodic samples gets a break in the line and no shading, which is
  exactly what happened there.

Hampel stays off, as it was for the capsule: the chunk is pooled, and pointed at a pooled series the
filter flags the interleaving itself (102 real readings on 白い熊's data, against 2 with it off).
What the curve is fitted through is a single population, which was never the noisy part.

What did NOT change: the pooled gap threshold and its tint, the slew gate, the ✕ marks, the footer
counts, the day table, and the `53–105 bpm` headline. That last one was previously inferred from the
mark — `CAPSULE` meant a range — so it moved to its own `headlineIsRange` flag rather than silently
becoming a median when the mark changed.

### The Hampel filter does not belong on a capsule or a pooled chunk

It is an instrument for smoothing a *line*, and it assumes one population. Pointed at pooled heart
rate it flags the interleaving itself — the +7.46 bpm second population reads as a sawtooth of
outliers and burns the entire rejection budget on real readings: **102 of them** on 白い熊's data,
against 2 once it is switched off. Neither an hourly envelope nor a scatter needs such a filter
anyway: a capsule's ends are readings, and heart rate's curve is fitted through ONE population,
which was never the noisy part. Both keep the range and sentinel gates and skip Hampel; only a plain
`LINE` gets it.

### Filter before decimate — the order is not negotiable

LTTB's selection criterion is maximum triangle area, so it *deliberately prefers extremes*. Decimate
first and it picks out precisely the outlier spikes. The surviving series is then outlier-enriched and
non-uniformly spaced; a rolling median computes over a sample that is mostly extremes and not
contiguous in time, the MAD inflates, the threshold widens, and the filter rejects nothing at all.

The corollary is architectural: the filter chain is **cadence-bound work, not viewport-bound work**.

### Filters flag; they never delete or replace

The textbook Hampel filter substitutes the window median for an outlier. That draws a value that never
occurred. Here the signature is the guarantee: values in, booleans out, no path by which a value could
be written.

The **slew gate flags too**, and that is a correction to the original design. Deleting a sample files it
under "no-reading" in the footer, which is a lie — it *was* read. It is still withheld from the Hampel
statistics, since a spike left in the window inflates the MAD and lets the next spike through.

### The `minScale` MAD floor is mandatory

In a quiet window — seven consecutive SpO₂ readings of 97, resting heart rate pinned at 58 overnight —
the MAD is exactly 0, so the threshold is 0, and *every* deviation including a real one-unit change gets
flagged. The filter goes berserk in precisely the calmest, most trustworthy stretches of the night.
`Hampel.flag` refuses a `minScale` of zero outright rather than trusting the caller.

### The slew gate only applies where the cadence is short enough to bound physiology

The hand-off specified ">3 % SpO₂" and ">0.5 °C" alongside ">40 bpm" as limits on "a single 120 s step".
But SpO₂ is sampled every **ten** minutes and temperature every **thirty**, and over those intervals
such swings are ordinary physiology. On real data a 3-point SpO₂ limit condemned **53 of 430** adjacent
pairs, in a series whose values never left 91–100 and which had nothing out of range at all.

So the gate is on for heart rate only (40 bpm / 120 s, where it drops exactly one sample in three days)
and off for SpO₂ and temperature, where Hampel is the right instrument.

### The gap threshold is measured, not assumed

Nominal cadences are what the band is *documented* to do. The periodic heart-rate series is nominally
120 s; its real median interval is **240 s** — the band skips slots, and 149 of its intervals are
exactly 600 s. Taking the nominal figure at face value put the threshold at 360 s, declared 231 of 848
intervals to be gaps, shredded the line into 235 fragments and tinted half the chart red.

The threshold is therefore `max(nominal cadence, observed median interval) × multiplier`. Taking the
larger means a burst of closely-spaced samples can never make the threshold *tighter* than the metric is
documented to be, while a band that samples more slowly than advertised is believed.

### Gaps and rejections

| situation | behaviour |
|---|---|
| retained samples closer than the threshold | curve runs through |
| farther apart | **break the path**, tint the extent. **Never a dashed connector** — that reads as data |
| **one** sample rejected | interpolate across it; optional hollow ✕ at its real value |
| **three or more** consecutive rejected | treat as a gap and break the path |
| sentinel / out of range | dropped at qualify — a non-measurement, neither outlier nor gap |

One bad sample is noise worth bridging; a run of them means the sensor was wrong and there is nothing
to bridge.

### LTTB buckets are anchored to absolute time

`floor((t − EPOCH_ANCHOR) / bucketSpan)`, never to array index. Index-partitioned LTTB re-partitions
every bucket when the window shifts by a single sample, so the selected set visibly crawls while
panning — points pop in and out under the finger. Anchoring costs a dozen lines.

The only mean anywhere in the pipeline is LTTB's third triangle vertex, and it is never drawn: it only
decides which *real* sample to keep.

### PCHIP, for a guarantee rather than for looks

Natural cubic splines and Catmull-Rom both overshoot near a local extremum, and an overshoot here means
the chart *draws* an SpO₂ of 101 %, or a heart-rate trough below every sample around it. Fritsch–Carlson
monotone cubic cannot overshoot, which gives:

> **min/max of the drawn curve == min/max of the retained samples.**

That is a unit test (`PchipTest`), not a comment. Endpoint slopes use the clamped three-point formula —
an unclamped endpoint is the other classic way a monotone spline overshoots.

A Hermite cubic *is* a cubic Bézier, so the curve is emitted directly as control points and drawn with
one `cubicTo` per sample: exact, and far cheaper than evaluating per pixel.

PCHIP flattens at every local extremum, so a genuine single-sample peak renders as a slight plateau.
That is the right trade against overshoot, and it is mitigated by drawing the real sample dots on top —
which serves the brief directly, since those dots *are* the measurements.

### Never filter steps or sleep

Per-minute step counts are zero-inflated and heavy-tailed. Over a mostly-zero window the MAD is exactly
0, so with the floor everything non-zero clears the bar and without it everything non-zero is flagged.
Neither is useful: range gate only, and `0` is a **real** step count, not a sentinel.

Sleep stages are categorical. A median over {deep, light, REM, awake} is meaningless and interpolating
between them would draw stages that never occurred.

### The footer is a feature, not debug output

`718 samples · 3 rejected · 2 gaps · 41 no-reading`, tappable to reveal ✕ marks at the rejected
samples' real values. 白い熊 cares that the chart stays close to the measurements, so the app has to be
able to **prove** what it dropped. This is what makes the filtering acceptable at all; it does not
belong behind a debug flag.

### Stress is the band's raw byte

Hume displays a filtered mean — their 37.2 against our raw 42.1 is best explained by dropping records
outside roughly HRV 19–75, but that was fitted against two targets and is a lead, not a fact. Draw the
raw value. Reproducing Hume's number is separate work needing a second reference day.

---

### 健康指数 — our own composite, and why it is not Hume's

Hume's Health Score is 0–900 from resting heart rate, HRV, heart-rate stability, SpO₂ and sleep
quality — every input is something we measure — but **the weights are withheld** and part of it comes
from Body Pod data that does not exist here. Its neighbours on that screen are worse: Metabolic
Momentum, "Life Added 1.9 days" and "Pace of Aging 0.5×" come from undisclosed algorithms, and the
vendor's own consumer report calls the life-added figure a *model-based estimate, not the result of a
controlled clinical study*, noting there is *no universal clinical standard for wearable biological
age estimation*. None of it is reproduced.

`HealthIndex` is ours instead: 0–100, five components, every breakpoint and weight a constant in the
file and printed in the info panel beside each component's actual contribution. It is falsifiable,
which is the entire difference.

The five are **resting heart rate 26 %, heart-rate stability 11 %, blood oxygen 17 %, sleep 26 %,
steps 20 %**. Steps joined on 2026-08-07 at 白い熊's instruction; the 20 % came out of the other four
in proportion. Two consequences are load-bearing rather than incidental:

- **Steps are a behaviour; the other four are physiological state.** A long walk lifts the index with
  nothing in the body's own numbers moving. That is what was asked for, it is said plainly in the
  info panel, and a test asserts it so it is never "fixed" as a bug.
- **Zero steps is a measurement, not an absence** — the one place in this index where 0 and null are
  genuinely different. An empty series is missing; a series of zeroes scores zero.

Every component's breakpoints ARE its card's band-ladder edges — steps take 3 000 and 7 500 from the
steps ladder rather than the 2 500 / 8 000 the mortality literature suggests on its own, because a
card reading "Standard" beside a component scoring 15 is the failure that invariant exists to stop,
and it is worth more than 500 steps of precision. `HealthIndexTest` asserts the tie for every metric.

A day earns a number in the day table only once **half the index's weight** is present. Steps score
on their own, so without that gate a day where the band recorded nothing but a short walk would land
a renormalised 0 in a column beside days scored from all five: correct arithmetic, unreadable table.

**It refuses rather than guesses.** A component with no data is reported missing and named, and the
index is labelled partial — never imputed, never defaulted. Scoring a night the band did not record
as though sleep were bad is the failure this design exists to prevent, and `HealthIndexTest` asserts
it directly.

### The rendering is settable, and the colours are checked on device

Everything the plots used to hard-code — heights, mark weights, opacities, what gets drawn, the
opening span, the line shape, and eleven series colours — lives in `ThemePrefs` and arrives at the
renderers as one immutable `ChartStyle` carried on `PlotFrame`. The mark functions are `DrawScope`
extensions and so cannot read a CompositionLocal; the plot composable reads it once and hands it down
with the geometry.

Two things this fixed or forced, worth not rediscovering:

- **Mark weights are dp, not raw pixels.** `STROKE = 2f` was two *device* pixels, so on a 3× screen
  the line was a third of the weight the method calls for and got thinner the better the display.
- **A settable palette needs the validator on the phone.** `PaletteCheck` is a faithful Kotlin port of
  the data-viz skill's script — same OKLab conversion, same Machado–Oliveira–Fernandes severity-1.0
  CVD transforms, same thresholds — run live on whatever is picked, reported beside a one-tap restore.
  It is advisory by design: a check that blocked is a check someone learns to work around. The
  documented rejection is now executable — Hume's violet REM beside a blue stage measures ΔE 1.9 under
  protanopia, and `PaletteCheckTest` fails if the port ever stops noticing.

The preview on the customization page runs **the real renderers** over synthetic data. A second,
simplified painter would eventually disagree with the thing it previews, and the disagreement would
surface on the real screen — the one place a preview exists to avoid.

### The crosshair is shared, and it refuses across a gap

One long-press drag drives **every** card: each reads out its own value at the same instant, which is
the only way to answer "heart rate spiked at 03:12 — was I in REM, and did my oxygen dip?" without
opening three cards and lining up their axes by eye. That requires every chart on one `ChartViewport`,
including 睡眠, which gave up its night-spanning viewport for it.

`nearestSample` takes a tolerance and returns null beyond it. With the line parked inside a four-hour
hole the honest answer is nothing — printing the value from either edge would state a reading for a
time the band was not measuring, which is the exact claim the gap tint exists to deny.

**Two ways in, because the two screens have different spare gestures.** On the dashboard a tap
already means "open this metric", so the crosshair is long-press-and-drag and vanishes on lift. On the
full-screen detail nothing else wants a tap, so one tap plants the line and it **stays** until tapped
away — you put it on the spike, then pinch and pan around it. Requiring a long press there hid the
feature behind a gesture nobody thinks to try, which is how `+018` shipped it and why 白い熊 found
tapping a step bar did nothing.

`ChartGestureInteropTest` drives both, in the same modifier order the screen uses: a tap plants it, a
second tap in the same place clears it, a tap elsewhere moves it, and neither pan nor pinch is lost.

### One date format, and a history under every chart

Dates are **`2026-08-07`** everywhere (白い熊, 2026-08-07) — `BandDates` is the only place they are
formatted. Three formats had grown up (`MM-dd`, `MM-dd HH:mm`, `M/d`), which is three chances to
misread which day is on screen, and the year stops being optional the moment the archive crosses one.
A full ISO date is wide for an axis, so `ChartTicks.labelled` offers at most five labels now and
`drawTimeLabels` still drops any that would collide.

Every full-screen chart carries a day-by-day table underneath (`MetricHistory`), because a chart shows
a shape and does not let you read Tuesday's number. Sleep lists **every recorded session** with its
extent (`22:41 → 08:33`) as well as its duration — not just the latest, and not just the longest of
each day, which is [DailySummary]'s rule and belongs to a summary rather than a history. Other metrics
get median, low–high and the **sample count**, which is part of the reading: a median from four
readings is a different claim from one built from four hundred.

Histories come from the **raw** series, before the outlier filter — the table is what the band
recorded, and the chart's ✕ marks are where the two disagree.

### The day table is a table

`DailySummary` answers a different question from the plots — "was Tuesday better than Monday" rather
than "what happened today" — and comparison between days wants numbers side by side, not points to
measure against an axis. A night is attributed to **the day it started**, matching the band's own
noon-to-noon chunking; the longest session of a day wins, so a nap never displaces the night; and a
day that would be all dashes gets no row.

## 5. Where 「健康」 lives

**Its own fullscreen window**, `BandChartsActivity` — not a tab (白い熊, 2026-08-03). It was briefly a
bottom-bar destination and that was wrong twice over: it buried the charts behind a horizontally
scrolling bar they fell off the right-hand end of, and it made looking at your own health data require
opening an app about automation.

A task reaches it through the `band.charts` action, and a launcher shortcut reaches the task through the
existing `CREATE_SHORTCUT` picker, so a home-screen icon opens straight onto the data. The window owns
nothing: a sync started in it survives the window closing.

The 「健康」 project ships seven tasks and one profile. The standard 71/01/37 trio leads the list in an
`起動無効` group, exactly as every other project:

| pos | task | |
|---|---|---|
| 0 | `健康 ⇨ 起動 -- [727][71]` | run the 01, then enable the profile |
| 1 | `健康の設定 -- [727][01]` | every setting, including `Band_WarnAtPct` |
| 2 | `健康 ⇨ 無効 -- [727][37]` | disable the profile |
| 3–6 | `同期`, `自動同期`, `同期状態`, `グラフ` | the working tasks, ungrouped |

`起動完了 ⇨ 起動 -- [717][71]` and `⇨ 無効 -- [717][37]` run the trio's ends as `r7_`, so 健康 starts
and stops with everything else.

**Group membership lives in `itemMeta.groupName`, not on the task row** — and an `itemMeta` note
replaces the whole row on import, so a bundle that ships a grouped task without its meta silently
drops it out of its group.

**`自動同期` is what keeps the buffer question closed.** It fires on `clock_tick` with
`everyMinutes = 240`, so at 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00, against the ~21 h HRV
constraint of §3. It syncs silently and warns only when something is actually wrong: a sync that
could not go through *and* enough time gone that the shallowest buffer is within reach, or a
`lostWindowSec` above zero — which should never happen, and if it does means the cadence itself is
too slow. There is deliberately no routine nudge; a warning that shows every day is not a warning.

> **A profile always imports DISABLED, whatever the bundle says.** `自動同期` sat inert from the
> moment it was imported until `[727][71]` was run. This is exactly what the 71 task is for, and it is
> why shipping a profile without its 71 ships something that never runs.

---

## 6. Golden frames — real bytes, and where they are asserted

Captured off 白い熊's band on **2026-08-02 at 15:23 local**. Every decode below was verified against
the device. These were the ground truth the sync hand-off carried; that file is gone, so they live
here and — more importantly — as executable assertions in `BandProtocolTest` and `BandRecordsTest`.

```
0x55 heart rate      550000260802152034 49    -> 2026-08-02 15:20:34, 73 bpm
                     550100260802151530 4a    -> 2026-08-02 15:15:30, 74 bpm
0x56 HRV             560000260802151930 45 4f 00 4f 00 00
                       -> 15:19:30 · HRV 69 · vascular 79 · HR 0 · stress 79 · SBP 0 · DBP 0
                     560200260802151530 2c 2f 4a 2f 72 40
                       -> 15:15:30 · HRV 44 · vascular 47 · HR 74 · stress 47 · SBP 114 · DBP 64
0x66 SpO2            660000260802152034 60    -> 15:20:34, 96 %
0x65 temperature     650000260802145900 6c01  -> 14:59:00, 0x016c = 364 -> 36.4 °C
0x52 detail          5200002608021519314500e30105002400210000000000000000
                       -> 15:19:31 · 69 steps · 4.83 kcal · 0.05 km
                       -> per-minute [36,0,33,0,0,0,0,0,0,0], summing to the record's own 69
0x51 daily           51 00 260802 5b180000 660a0000 5f020000 94b20000 0000 18000000
                       -> 2026-08-02 · 6235 steps · 6.07 km · 457.16 kcal
                     51 01 260801 3c270000 f9110000 64030000 210b0100 0000 24000000
                       -> 2026-08-01 · 10044 steps · 8.68 km · 683.85 kcal
0x53 sleep           530000 260802052857 16 020202050505050202020202020202020202020202020000…
                       -> start 05:28:57 · 22 minutes · light×3, awake×4, light×15

info replies         13 4c            -> battery 76 %
                     27 00000205      -> firmware "0.0.2.5"
                     22 d5a706dca13a  -> D5:A7:06:DC:A1:3A
                     41 260802152305  -> band clock 2026-08-02 15:23:05
                     4b 1027          -> step goal 10000 (LE16 0x2710)
                     57 ff            -> no alarms (a bare terminator)
```

Command frames, likewise asserted byte-for-byte:

```
GET TIME             41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41
HR from 2026-07-28   55 00 00 00 26 07 28 00 00 00 00 00 00 00 00 AA
HR CONTINUE          55 02 00 00 00 00 00 00 00 00 00 00 00 00 00 57   <- note the ZERO date
```

---

## 7. Settled, and still open

### Settled on 2026-08-06

Both of the sync hand-off's unverified assumptions, and the gap question, are now measured. They are
written up where they belong — one notification per frame in §1, the gaps in §2, the buffer detector
in §3 — and each carries a regression test. Do not reopen them from the older prose.

**Sleep stage `4` does not exist.** Over 2 970 stage-minutes in 36 segments across six nights the raw
codes are `{1: 630, 2: 1527, 3: 590, 5: 223}` — **zero** occurrences of 4, or of anything else. Hume's
own sleep screen shows exactly four stages (Awake / REM / Light / Deep), matching the four codes. Our
proportions — light 51 %, deep 21 %, REM 20 %, awake 8 % — are physiologically ordinary, whereas the
vendor plugin's re-coding would yield REM 8 % and awake 20 %, which is not. The `unknown` bucket in
`BandSleepSegment` stays: it costs nothing and it is how we would find out if a firmware update
started emitting one.

### Settled on 2026-08-09

**Heart rate is a curve over the periodic series with the spot readings hollow on top**, on both the
dashboard card and the detail screen — no capsule anywhere. Written up in §4, with
`HeartRateScatterTest` asserting the split, the break-over-a-hole rule and that Hampel stays off.

**The two heart-rate populations are not one measurement with an offset.** They agree to 1 bpm asleep
and still, and diverge by 22 bpm with movement, because only the spot reading tracks exertion. §2.

**The archive is written on every exit path, and repairs itself.** Syncs 28 and 41 had gone missing
with 27 heart-rate rows; the flush is now unconditional and `BandArchiveRepair` re-emits anything the
file is missing on every sync. Written up in §3, with `BandArchiveRepairTest`.

### Still open

- **Hume's own views** were the model for the power views, shipped in `+009`. Its `H` tab draws an **hourly
  min/max envelope** — one capsule per hour, not one measurement — with `D`/`W`/`M` above it. Its day
  range matches our *pooled* heart-rate population. Ours no longer imitates that: since 2026-08-09 our
  heart rate draws every reading (§4), and the capsule remains only where the data is genuinely
  ten-minutely, on SpO₂. Their envelope also pools two populations that measure different things,
  which is worth remembering before treating their number as a reference.
- Hume drops single-sample dips that we keep: on 2026-08-03 our minimum was 52 bpm (one sample at
  11:39:30, neighbours 85 and 72) against Hume's 55. Our decode is faithful; the difference is their
  display filtering. **Do not "fix" our number to match theirs.**
- **A sleep stage `4`** would show as 不明 rather than being folded into a neighbour. It has never
  appeared; if it does, that is the signal to revisit §7.
### The gesture question, answered 2026-08-06

`transformable(canPan = …)` **works**, and the documented fallback — pinch-only `transformable` plus a
separate horizontal `draggable` — is not needed. The power views can be laid out on the assumption
that one modifier handles pinch and horizontal pan while the enclosing `LazyColumn` keeps its scroll.

`rememberChartGestureModifier` in `ui/charts/ChartGestureBox.kt` is the whole of it:

```kotlin
canPan = { abs(it.x) > abs(it.y) * 1.4f }   // ~35° either side of the horizontal
```

Compose consults `canPan` *before* the transformable claims a pan, so refusing the vertical ones lets
them fall through to the list. Zoom never goes through the predicate at all — a two-finger spread is
not a pan — which a vertical pinch confirms.

Six instrumented tests drive real touch streams through it on the Mate XT
(`ChartGestureInteropTest`), and one of them is the **control**: the same harness with a bare
`transformable` swallows the vertical drag and the list stays frozen. That is the bug `canPan`
prevents, reproduced on demand. A green suite proves nothing without it — `swipe()` could have been
producing touch streams too gentle to trigger anything and every other assertion would still pass. If
the control ever starts passing, the positive test has lost its teeth.

Running instrumented tests at all needed two build changes, both debug-only and neither touching the
release build: the debug variant takes `applicationIdSuffix = ".debug"` so it installs **alongside**
the release APK rather than failing on a signature mismatch (the only way through would have been
`adb uninstall`, which destroys the workspace database), and `app/src/debug/AndroidManifest.xml`
drops the `com.opentasker.permission.AUTOMATION` declaration, since two packages cannot both own a
custom permission.
