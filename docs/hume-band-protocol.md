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

**Split them for the line; pool them for envelopes and summaries.** Hume's own day range (55–103 on
2026-08-03) matches the *pooled* population, not the periodic series alone. And the pooled series has
essentially no gaps: over 15:00–18:43 the periodic series has one 14-minute hole and the pooled series
has none.

---

## 3. The census

The band's buffers are small and overwrite silently. Nothing on our side deletes anything — the Hume
app never sends the destructive mode either, and a factory reset is the only thing that empties the
band — so what `band_syncs` measures is the band's own ring buffer.

Its depth **cannot be looked up; it can only be measured**, over days of real use with varied gaps:

- a sync whose gap came back with **no** loss says the buffer is **at least** that deep — a lower bound;
- a sync that **did** lose records says it is **at most** that deep — an upper bound;
- a stream that errored or timed out says nothing at all and is excluded, because counting it as
  "no loss" would inflate the lower bound with a sync that never read anything.

`band_syncs` is never pruned. It is a few rows a day and it is the entire measurement series.

Nominal cadences, in seconds: `hr` 120, `hrv` 120, `spo2` 600, `temp` 1800, `detail` 60. Daily and sleep
are event-shaped rather than sampled, so "expected records" is meaningless for them and inventing a
number would manufacture fake loss.

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

## 5. Where 「健康」 lives

**Its own fullscreen window**, `BandChartsActivity` — not a tab (白い熊, 2026-08-03). It was briefly a
bottom-bar destination and that was wrong twice over: it buried the charts behind a horizontally
scrolling bar they fell off the right-hand end of, and it made looking at your own health data require
opening an app about automation.

A task reaches it through the `band.charts` action, and a launcher shortcut reaches the task through the
existing `CREATE_SHORTCUT` picker, so a home-screen icon opens straight onto the data. The window owns
nothing: a sync started in it survives the window closing.

The 「健康」 project ships four tasks — `健康の設定 -- [727][01]`, `同期 -- [727]`, `同期状態 -- [727]`
and `グラフ -- [727]`.

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

## 7. Known and open

Two assumptions carried over from the sync hand-off's on-device checklist, still unverified:

- **One BLE notification equals one frame.** Everything downstream assumes it, and the frame-counted
  paging rule loses its meaning if notifications are ever fragmented. The granted MTU is logged into
  every census row, so the evidence is being collected. If fragmentation ever shows up, this needs a
  reassembly layer — say so rather than working around it.
- **Sleep stage `4` has never been observed.** Codes 1/2/3/5 are deep/light/REM/awake. Stage 4 is
  counted as unknown; log it if it ever appears.


- **Hume's own views** are the model for the eventual "power views". Its `H` tab draws an **hourly
  min/max envelope** — one capsule per hour, not one measurement — with `D`/`W`/`M` above it. Its day
  range matches our *pooled* heart-rate population.
- Hume drops single-sample dips that we keep: on 2026-08-03 our minimum was 52 bpm (one sample at
  11:39:30, neighbours 85 and 72) against Hume's 55. Our decode is faithful; the difference is their
  display filtering.
- The current renderer is **scaffolding**. Pinch-zoom, the special renderers (steps bars, blood-pressure
  dumbbells, sleep hypnogram, sleep ribbon), the crosshair and the theme knobs are all unbuilt. The
  pipeline in §4 is view-independent and survives any redesign of the drawing.
- `transformable(canPan = …)` interop with a scrolling list is **unprototyped**. It needs answering
  before the power views are designed, because the documented fallback (pinch-only `transformable` plus
  a horizontal `draggable`) changes the layout.
