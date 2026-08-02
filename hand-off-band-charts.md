# Hand-off — 「健康」: zoomable, honest graphs of the band's health data

Run this from `~/git/shiroikuma-jiyusagyoban` in a fresh chat. Read `CLAUDE.md` and
`.claude/skills/build-apk/SKILL.md` first.

**Prerequisite: `hand-off-band-sync.md` must be done and merged, and 白い熊 must have been syncing for
several days.** This hand-off draws data; it does not fetch any. If `band_samples` has only a day or
two in it, stop and say so — the whole point of the delay was to have something worth zooming into.

**From:** the band reverse-engineering session, 2026-08-02.

---

## Why

The sync hand-off gave 白い熊 his data and a table of numbers. This one gives him the thing he actually
wanted: **24-hour views in clear, easy-to-read graphs, zoomable in and out.**

His requirement, in his own words, and it is unusually precise about what *not* to do:

> I don't think it's good to average the data: only to remove outlier peaks and troughs, and it should
> probably be smoothed out between the points if there are extreme outlier readings. The graphs we show
> should then be smooth — but these must be as close to the actual measurements as possible.

That is the whole design brief. Smooth to look at, but every drawn value traceable to a real
measurement. No bucket averages, no invented points, no cosmetic curve that overshoots into readings
that never happened.

And on zoom (白い熊, 2026-08-02):

> Free panning, start with 24h, but both zoom in and out, marking visually the hour boundaries, when
> zoom smaller the day boundaries, theoretically with enough data showing only week boundaries etc.

So: **no 24-hour ceiling.** 24 h is the starting span, not the maximum. The boundary markers adapt to
the span — hours at day scale, days when zoomed out, weeks beyond that.

---

## The task

Add a 「健康」 tab rendering `band_samples`, `band_daily` and `band_sleep` as line charts, step bars,
blood-pressure dumbbells and a sleep hypnogram, sharing one pinch-zoomable time viewport, with an
outlier-rejection and smoothing pipeline that is honest about what it dropped.

**Write the chart engine. Do not add a charting library.** `verifyResolvedDependencyPolicy` requires a
version-catalog entry plus a SHA-256 in `gradle/verification-metadata.xml` (which does not exist in
this checkout), and there is an F-Droid readiness gate and a rebase-onto-every-upstream-commit
workflow on top. Roughly 900 lines of pure Kotlin we own is genuinely the cheaper side of that trade
**in this repo specifically**.

---

## 1. What already exists (already mapped — but locate it yourself, don't assume)

There is **no chart of any kind** in this project today, and **no pinch-zoom**. Both are new. What
exists to build on:

| File | What it gives you |
|---|---|
| `app/src/main/java/com/opentasker/scenes/SceneActivity.kt:1162-1330` | The most advanced drawing in the repo — the charging-fire `Canvas`. Shows the house pattern: stay in `DrawScope`, drop to `drawIntoCanvas { canvas.nativeCanvas }` only for gradients/mask filters, and scope the `withFrameNanos` loop so it stops off-screen. **One wart not to copy:** `drawDenchiComet` allocates a fresh `Paint` every frame. |
| `app/src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt:354-374` | Plain `DrawScope` line/circle drawing. |
| `app/src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt:~320` | **`rememberTransformableState` is already used once here**, behind `@Suppress("DEPRECATION")`, zooming via `Modifier.graphicsLayer`. Read it first — as a *counter-example* (`graphicsLayer` scales stroke widths and text with the content, which is wrong for a chart), but also because that deprecation suggests a newer overload taking a **centroid**, which is exactly what focal-point anchoring needs. **Verify that signature before building the gesture layer.** |
| `app/src/main/java/com/opentasker/ui/screens/ContextInspectorScreen.kt` | The pattern for a screen owning its ViewModel + Factory in-file. |
| `app/src/main/java/com/opentasker/ui/theme/DesignSystem.kt` | Spacing / Radii / Elevation tokens. |
| `app/src/main/java/com/opentasker/ui/theme/ThemeStore.kt` | `ThemePrefs` — ~100 user-settable visual knobs, SharedPreferences + `MutableStateFlow`. |
| `app/src/main/java/com/opentasker/ui/screens/UiCustomizationScreen.kt` | 1759 lines exposing every knob. **This fork's convention is that everything visual is settable**, so a new chart is expected to add its own fields and a section here. `FlashPreview` at ~:317 is the live-preview pattern to mirror. |
| `app/src/main/java/com/opentasker/core/storage/RunLogPaging.kt` | Keyset pagination — the right shape for windowed time-range queries. |

There is **no `NavHost`** despite the dependency; `ActiveAutomationUi` switches on an enum.

---

## 2. The viewport — span in milliseconds, not a scale factor

```kotlin
// ui/charts/ChartViewport.kt
@Stable
class ChartViewport(initialEndMs: Long, initialSpanMs: Long = 24 * 3_600_000L) {
    var endMs by mutableLongStateOf(initialEndMs)     // right edge
    var spanMs by mutableLongStateOf(initialSpanMs)   // visible duration
    var plotWidthPx by mutableFloatStateOf(0f)
    val startMs: Long get() = endMs - spanMs

    fun xOf(tMs: Long): Float = (tMs - startMs).toFloat() / spanMs * plotWidthPx
    fun tOf(x: Float): Long = startMs + (x / plotWidthPx * spanMs).toLong()
    fun zoomAround(focalX: Float, zoomChange: Float, bounds: LongRange)
    fun panBy(dxPx: Float, bounds: LongRange)
}
```

Expressing zoom as **visible span** rather than a unitless factor is what makes everything else fall
out cleanly: the tick ladder, the Room query window and the decimation target all derive from it
directly, and the value is legible to a human ("6 hours" beats "scale 4.31").

- **Start span 24 h.** Floor **10 minutes** — at a 120 s cadence that is five real samples across the
  plot, the point at which individual measurements become countable. Below that you are inspecting
  interpolation, not data. **No ceiling** beyond the available data range.
- Pan clamped to `[oldestSample, newestSample + spanMs/4]` — a quarter-window of overscroll so "now"
  isn't pinned to the edge.
- Pinch anchors on the **finger centroid**: the timestamp under the centroid is invariant across the
  zoom.

**One shared viewport across every chart.** The entire value of a stacked column of health charts is
cross-reading — "HR spiked at 03:12, was I in REM?" — and independent per-chart zoom destroys that and
triples the query load. Expose `chartSyncZoomAcrossMetrics` in `ThemePrefs` for the everything-settable
convention; un-synced mode just gives each chart its own instance.

### Adaptive boundary markers — what 白い熊 asked for specifically

The tick ladder is chosen from the span, and the label format with it:

| span | major boundaries | minor | label |
|---|---|---|---|
| < 30 min | 5 min | 1 min | `15:20` |
| 30 min – 3 h | 1 h | 15 min | `15:00` |
| 3 h – 36 h | **hour** | — | `15:00`, with the date at midnight |
| 36 h – 10 d | **day** (midnight) | 6 h | `Sat 2` |
| 10 d – 10 wk | **week** (Monday) | day | `Aug 3` |
| > 10 wk | month | week | `Aug` |

Draw the major boundary line more strongly than the minor. **Step the ticks with
`java.time.ZonedDateTime`, never by adding fixed millis** — a fixed-millis ladder drifts by an hour
across a DST transition and the labels stop landing on the hour.

### Gesture interop with the vertical list — the one risky part

The failure mode to avoid: `detectTransformGestures` consumes every change past touch slop, so a
vertical drag starting on a chart never reaches the `LazyColumn` and the tab feels broken.

```kotlin
Modifier.transformable(
    state = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        if (zoomChange != 1f) viewport.zoomAround(centroid.x, zoomChange, bounds)
        if (panChange.x != 0f) viewport.panBy(panChange.x, bounds)
    },
    canPan = { pan -> abs(pan.x) > abs(pan.y) * 1.4f },   // vertical-dominant drags fall through
    lockRotationOnZoomPan = true,
)
```

`canPan` returning false leaves the pan unconsumed so the parent list picks it up; multi-touch zoom is
claimed regardless, so a pinch always zooms even if the fingers drift vertically. The `1.4f` bias means
an ambiguous diagonal scrolls rather than pans — the safer default, since scrolling is the more
frequent intent.

**Prototype this on device before building on it.** If `canPan` interop misbehaves, the fallback is
`transformable(canPan = { false })` for pinch-only plus
`draggable(state = …, orientation = Orientation.Horizontal)` for one-finger pan — the same machinery a
`HorizontalPager` inside a `LazyColumn` uses, and known-good. Order `transformable` first.

Also give it a chip row — **24h / 6h / 1h / Now** — which is worth more than fine pinch control on a
phone.

---

## 3. The pipeline — and why the order is not negotiable

```
Room rows (indexed on localTs)
 ├─ S1 QUALIFY   sentinel drop, plausible-range gate, slew gate, split interleaved HR
 ├─ S2 HAMPEL    rolling median + MAD; FLAG only, never replace
 ├─ S3 SEGMENT   split into contiguous runs at gaps
 │     ^^^ viewport-INDEPENDENT — computed once per (metric, day), cached
 ├─ S4 LTTB      per segment, only when n > threshold, buckets anchored to absolute time
 ├─ S5 PCHIP     Fritsch–Carlson slopes → cubic Bézier control points, in data space
 └─ S6 MAP       data→pixel affine + Path build            [main thread, per frame]
```

**Filter BEFORE decimate, always.** LTTB's selection criterion is maximum triangle area — it
*deliberately* prefers extremes. Decimate first and it picks out precisely the outlier spikes; the
surviving series is then outlier-enriched and non-uniformly spaced, so a subsequent rolling median
computes over a sample that is mostly extremes and not contiguous in time. The MAD inflates, the
threshold widens, and the filter rejects nothing at all.

The corollary is architectural: **the filter chain is cadence-bound work, not viewport-bound work.** It
runs once per (metric, day) and is cached; only S6 is per-frame.

### Caches

| Cache | Key | Value |
|---|---|---|
| L1 qualified | `(metric, dayStart, filterParamsVersion)` | `QualifiedSeries` — `LruCache`, 14 entries |
| L2 decimated | `(seriesKey, lodBand, targetPoints)` | `RenderModel`, data-space positions + Bézier controls |
| L3 path | — | a `Path` + scratch `FloatArray` in `remember`, `reset()` per frame |

Day-sized chunks make panning across a boundary a cheap union of two immutable chunks. **Sleep uses
noon-to-noon chunks** — a night spanning midnight is one session, not two halves.

Budget check: ~6,700 values/day across all metrics ≈ 80 KB/day. Seven hot days is under 600 KB. Nothing
here needs cleverness.

### Reactivity — the trap

**Do not back the viewport with a Room `Flow` over the range.** Room invalidation is table-granular, so
every single insert during a sync re-emits the whole window and re-runs the entire chain. Use one-shot
`suspend fun rangeAsc(metric, from, to)` for historical chunks, which are immutable once the day is
past, and invalidate only today's chunk from an explicit `MutableSharedFlow` that the sync engine
emits **once per completed sync**, not per row.

During a gesture set `isInteracting = true`, suppress the LOD recompute and draw the existing model
under the new affine transform — visually correct, since the samples are real either way and only the
point density is briefly stale. Recompute 120 ms after the last gesture event.

---

## 4. Honouring "no averaging"

At day scale the honest answer is that **every retained sample is drawn**: 720 samples across a
1080 px plot needs no decimation at all, and LTTB will sit idle. That is a good result, not a wasted
component — it means at 白い熊's primary viewing scale the chart *is* the measurements.

Where it earns its keep is the zoomed-out end he asked for, where a month is ~200k points:

- **Never `AVG()`, `GROUP BY` bucket means, or any other averaging.** Decimate with **LTTB
  (Largest-Triangle-Three-Buckets)**, which *selects actual samples* and preserves the visual envelope.
- Target `ceil(plotWidthPx / 2)` points, clamped `[64, 2048]`. Skip entirely when `n <= threshold`.
- Run **per contiguous segment**, never across a gap.
- **Anchor buckets to absolute time**, `floor((t - EPOCH_ANCHOR) / bucketSpan)`, not to array index.
  Index-partitioned LTTB re-partitions every bucket when the window shifts by one sample, and the
  selected set visibly crawls while panning. Non-obvious, worth the extra dozen lines.
- Where the span is wide enough that even LTTB thins visible extremes, underlay a **min/max envelope
  band computed from real samples** — measured extremes, not means. Nothing drawn is ever a value that
  was not measured.

---

## 5. Outlier rejection

Three gates, softest last.

**1. Range gate (non-statistical).** Sentinels and physiological impossibilities, dropped before any
statistics touch them:

| metric | valid | note |
|---|---|---|
| heart rate | 25–250 bpm | |
| HRV | 3–400 ms | |
| stress | 0–100 | confirm whether 0 is legitimate or a sentinel |
| systolic | 60–260 | **drop 0**; require `sys > dia` |
| diastolic | 30–160 | **drop 0** |
| SpO₂ | 70–100 % | |
| temperature | 30.0–45.0 °C | |
| steps/min | 0–250 | **0 is a real value** |

**Blood-pressure `0` means "no reading" and must never reach the statistics.** In our real data 153 of
600 HRV records had `hr=0, sbp=0, dbp=0`. Zeros break a rolling median three ways: they drag it down,
they inflate the MAD, and with four or more zeros in a seven-window the median *becomes* zero and the
genuine readings get flagged as the outliers.

**2. Slew gate.** A single 120 s step of >40 bpm, >3 % SpO₂ or >0.5 °C is a sensor artefact by
physiology, not by statistics. Catches the classic dropout spike with an explicit justification and no
degenerate cases.

**3. Hampel** — rolling median + MAD. Flag `i` when
`|v[i] − median(window)| > t · max(1.4826 · MAD, minScale)`.

| metric | half-window k | t | **minScale** |
|---|---|---|---|
| heart rate | 3 (14 min) | 3.5 | 2.0 bpm |
| HRV | 3 | 3.0 | 3.0 ms |
| stress | 3 | 3.0 | 3.0 |
| SpO₂ | 2 | 3.0 | 1.0 % |
| temperature | 2 | 3.0 | 0.15 °C |
| BP | 2 | 3.0 | 3.0 mmHg |
| steps | **off** | | |
| sleep | **off** | | |

**Hard-won constraints — do not "improve" these:**

- **The `minScale` floor is mandatory.** When a window is quiet — seven consecutive SpO₂ readings of
  97, resting HR pinned at 58 overnight — MAD goes to 0, the threshold goes to 0, and *every* deviation
  including a real 1-unit change gets flagged. The filter goes berserk in precisely the calmest, most
  trustworthy stretches of the night. The floor is what stops that.
- **Flag, never replace.** Textbook Hampel substitutes the window median for the outlier. That would
  draw a value that never occurred, violating the brief. The output is a `BooleanArray rejected`;
  nothing ever writes to the value array. Encode it in the type so it cannot be done by accident.
- **Never filter steps.** Per-minute step counts are zero-inflated and heavy-tailed; over a mostly-zero
  window MAD is exactly 0, so with the floor everything nonzero clears the bar and without it
  everything nonzero is flagged. Neither is useful. Range gate only, drawn as bars.
- **Never filter or smooth sleep.** The stage codes are categorical. A median over {deep, light, REM,
  awake} is meaningless, and interpolating between them would draw stages that never occurred.

---

## 6. Smoothing — PCHIP, and why not anything else

Use **Fritsch–Carlson monotone cubic (PCHIP)**. Natural cubic splines and Catmull-Rom/Bézier both
overshoot near a local extremum, and here an overshoot means the chart *draws* an SpO₂ of 101 %, or an
HR trough below every surrounding sample. PCHIP guarantees no overshoot at extrema, so:

> **min/max of the drawn curve == min/max of the retained samples.**

That is exactly 白い熊's requirement, and it is a testable invariant — make it a unit test.

`d_k = (v[k+1]-v[k]) / h_k`; if `d_{k-1} · d_k <= 0` then `m_k = 0`; else the weighted harmonic mean
with `w1 = 2h_k + h_{k-1}`, `w2 = h_k + 2h_{k-1}`; endpoints by the clamped three-point formula.

**Render it as cubic Béziers, not by evaluating per pixel.** A Hermite cubic *is* a cubic Bézier with
control points at `(t_k + h/3, v_k + m_k·h/3)` and `(t_{k+1} − h/3, v_{k+1} − m_{k+1}·h/3)`. So the
whole curve is one `Path` with one `cubicTo` per retained sample — exact, and far cheaper than
sampling. This is the single biggest accuracy-and-performance win in the design.

PCHIP flattens at every local extremum, so a genuine single-sample peak renders as a slight plateau —
preferable to overshoot, and mitigated by drawing the **real sample dots on top** whenever spacing
exceeds `chartSampleDotMinSpacingDp` (default 6 dp). Those dots also serve the brief directly: they are
the actual measurements, visible.

Offer `chartCurveMode` = `pchip` (default) / `linear` / `step`. A linear polyline is the maximally
honest rendering and costs one `when`.

---

## 7. Gaps, and proving what was dropped

`gapThreshold = chartGapMultiplier × nominal cadence` (default ×3): 6 min for the 120 s metrics,
30 min for SpO₂, 90 min for temperature.

| situation | behaviour |
|---|---|
| retained samples closer than threshold | PCHIP through — normal line |
| farther than threshold | **break the path**, tint the gap's extent. **Never a dashed connector** — that reads as data |
| **one** sample rejected | interpolate across it; optional hollow ✕ at its real value |
| **≥3 consecutive** rejected | treat as a gap and break the path |
| sentinel / out-of-range | dropped at qualify — a non-measurement, neither outlier nor gap |

The "three consecutive rejections becomes a gap" rule is what keeps this honest: one bad sample is
noise worth bridging, a run of them means the sensor was wrong and there is nothing to bridge.

**Every chart carries a footer:** `718 samples · 3 rejected · 2 gaps · 41 no-reading`, tappable to
toggle the rejected marks. 白い熊 explicitly cares that the chart stays close to the measurements, so
the app must be able to **prove** what it dropped. This is not a debug affordance; it is the feature
that makes the filtering acceptable at all.

---

## 8. The individual charts

**Line charts** — heart rate, HRV, stress, SpO₂, temperature. Parameterise one `MetricLineChart` by a
`MetricSpec` carrying cadence, range gate, Hampel params, colour key, unit, formatter and y-range;
adding a metric should be a table row, not new code.

**Y-axis: fixed clinical bands with auto-expand**, not per-window auto-ranging — a scale that jumps as
you pan destroys the ability to judge magnitude by eye. HR 40–180 · HRV 0–150 · stress 0–100 ·
temperature 34–38 · BP 40–200.

**SpO₂ axis: 88–100 with a visible axis-break marker at the baseline** (白い熊, 2026-08-02). A full
70–100 axis makes real desaturations invisible; the break marker is what keeps the truncation honest
rather than hidden.

**Blood pressure gets no line.** Draw each valid reading as a **dumbbell** — a vertical capsule from
diastolic to systolic with round caps. Readings taken hours apart are then never visually joined, which
a line would imply.

**Steps** — bars, from `steps_min`, plus the `band_daily` total as a header figure.

**Sleep hypnogram** — its own renderer, `ui/charts/SleepHypnogram.kt`. Merge equal consecutive
per-minute stages into runs at load time (1440 minutes typically collapses under 200 runs);
**never decimate** — the exact staircase is the entire point. Four lanes top-to-bottom: Awake, REM,
Light, Deep, with a thin riser between adjacent runs at differing lanes so it reads as a staircase
rather than floating bars.

**Enforce a 1 px minimum rect width.** At 24 h across 1080 px one minute is 0.75 px; rounding
sub-pixel rects to zero silently deletes brief awakenings, which are the most interesting events on the
chart.

Remember the stage codes are stored **raw**: `1`=deep, `2`=light, `3`=REM, `5`=awake. Do not use the
Hume plugin's re-coded scheme.

**The sleep ribbon** — render the same runs as a 12 dp single-lane colour strip under *every other*
chart (`chartSleepRibbon`, default on). One extra draw loop, and it answers "was I asleep when this
happened?" with no interaction at all. This is the cheap feature that makes the tab feel designed
rather than assembled.

---

## 9. Rendering and the performance budget

**Compose `DrawScope` by default.** The house rule visible in `SceneActivity.kt` is not "use
nativeCanvas for hard drawing" — it is "stay in `DrawScope` until the platform API is genuinely
absent". Compose has `Brush.verticalGradient`, `PathEffect` and `drawPath`, so a chart needs none of
the escapes that the charging fire needed. Drop to `nativeCanvas.drawPoints(FloatArray, Paint)` for one
thing only: batching the sample dots and hypnogram ticks, where `DrawScope` has no batch primitive and
500 individual `drawCircle` calls means 500 dispatches.

Text via `rememberTextMeasurer()` + `drawText(textLayoutResult, topLeft)`. This will be the repo's
first `TextMeasurer` usage. **Measure inside a `remember` keyed on the tick list, never inside the draw
lambda** — that is the most common Compose chart performance bug.

Three layers per chart, cheapest first:

1. **Static-in-y** (`Modifier.drawWithCache`, keyed on y-range + size + theme) — y gridlines, y labels,
   border, background. Survives pan entirely.
2. **Viewport** (`Modifier.drawBehind`) — x ticks and labels, gap bands, sleep ribbon, the path, sample
   dots.
3. **Overlay** — crosshair and readout, only while scrubbing.

**Target ≤ 4 ms of chart work per frame while pinching**, out of 8.3 ms at 120 Hz on the Mate XT, with
only ~3 charts on screen.

**The single most important structural rule: read the viewport *inside* the draw lambda**
(`Modifier.drawBehind { … viewport.startMs … }`), never in the composable body. Reading it in draw
scope registers a draw-phase dependency only, so a pinch invalidates *draw*, not composition. Read it
in the body and every frame recomposes the whole subtree — the difference between 2 ms and 20 ms.

**Zero allocation inside the draw lambda.** Hoist the `Path`, the scratch `FloatArray` and any
`android.graphics.Paint` into `remember`. Do not copy `drawDenchiComet`'s per-frame `Paint`.

Verify with `adb shell dumpsys gfxinfo shiroikuma.jiyusagyoban framestats` after a scripted pinch.

---

## 10. Theming

Per this fork's everything-settable convention, add ~35 fields to `ThemePrefs` in `ui/theme/ThemeStore.kt`
with matching `K_CHART_*` keys, `load()` / `persist()` / `normalized()` entries, and a "健康 charts"
section in `ui/screens/UiCustomizationScreen.kt` with new `ColorTarget` entries (~:1344).

`ThemePrefs` holds Int/String/Boolean only, so express the Hampel threshold as tenths
(`chartHampelSigmas = 35` → 3.5), matching the existing `fontScalePct` idiom.

Split chrome from data: axes, gridlines, border and labels follow the fork's black-yellow identity;
**the series get their own distinguishable palette**, because a multi-series chart in one hue is
unreadable. Say so in the section subtitle so it doesn't read as an oversight.

Include a **live `ChartPreview`** at the top of the section drawn with synthetic data, mirroring
`FlashPreview` at `UiCustomizationScreen.kt:317`. With ~35 knobs this is not optional — it is what makes
the section usable. `SettingsBackup` iterates SharedPreferences generically, so the new keys
export/import for free.

Filter parameters (`chartFilterEnabled`, `chartHampelWindow`, `chartHampelSigmas`, `chartGapMultiplier`)
belong here too — they change what is *drawn*, which by this fork's convention makes them user-settable.

---

## 11. Wiring

`ui/screens/HealthScreen.kt`, VM + Factory + Composable in one file per `ContextInspectorScreen.kt`.
Thread `OpenTaskerScreen` through the enum (~229), `icon()` (~265), `headerDetail` (~703), the
tab-actions `when` (~1040) and the dispatch `when` (~1330).

**Tab label: 「健康」** (白い熊, 2026-08-02). The 「接続」 tab from the sync hand-off stays as it is —
that one is the plumbing, this one is the data.

A `Canvas` chart is completely invisible to TalkBack. Add `Modifier.semantics` descriptions per chart
and a table-view fallback; this repo has an `AccessibilitySourceTest` and takes that seriously.

---

## 12. Build order

Do not build this in one pass. Each phase is independently testable.

1. **The heart-rate chart alone**, fixed 24 h window, **no zoom**: qualify + slew + Hampel, PCHIP as
   Béziers, gap breaks, sample dots, axis labels, footer counts. This exercises every layer — windowed
   query, filter chain, path building, `TextMeasurer`, theme wiring, frame time. If this is right, the
   rest is mechanical.
2. **The viewport** — shared `ChartViewport`, the gesture modifier and its `LazyColumn` interop, the
   adaptive tick ladder, LOD bands, debounce, prefetch. **Isolate this phase**; the gesture interop is
   the one genuinely uncertain item and should not be entangled with rendering changes.
3. **The remaining line metrics** via `MetricSpec`. Should be a data table.
4. **The special renderers** — steps bars, BP dumbbells, hypnogram, sleep ribbon.
5. **Interaction polish** — shared crosshair with synchronised readout across charts, daily summary
   cards, accessibility.
6. **The full `UiCustomizationScreen` section**, live preview, rejected-sample overlay, CSV export.

**Tests, alongside phases 1–2** — all pure Kotlin, no Android, which the 522-test floor rewards:

- LTTB retains only real samples; retains the global min and max; is the identity when `n ≤ threshold`;
  anchored buckets are stable under a one-sample window shift.
- Hampel rejects an injected spike; **does not** reject inside a constant run (the `minScale` floor);
  never sees a sentinel.
- **PCHIP no-overshoot: for every evaluated t, `min(bracketing y) ≤ f(t) ≤ max(bracketing y)`.** This
  is the test that encodes 白い熊's actual requirement — write it first.
- Segmentation: gap threshold splits correctly; ≥3 consecutive rejections become a gap.
- Qualify: BP zeros dropped before Hampel; `sys > dia` enforced; step zeros retained.

Bump `minimumTests.set(...)` in `app/build.gradle.kts` **and** the asserted literal in
`app/src/test/java/com/opentasker/docs/LocalReleaseGateContractTest.kt:25` together, last, after
`./gradlew testDebugUnitTest` prints the real total — or the gate fails on itself.

---

## Done =

白い熊 opens 「健康」, sees his last 24 hours as a stacked column of smooth charts with the sleep
ribbon running under them, and can pinch from 24 hours down to ten minutes and out past a week —
watching the boundary markers change from hours to days to weeks — with the line staying smooth, the
gaps staying visibly broken, and the footer telling him exactly how many samples were rejected.

Build with the **build-apk** skill and deliver; that skill's standing authorization applies.
**Build-only until 白い熊 says "Push".**

---

## Where you should come back and ask rather than guess

- **`transformable(canPan = …)` interop with `LazyColumn`.** Prototype it. If it misbehaves, use the
  documented fallback rather than inventing a third approach.
- **Sleep day boundary is noon-to-noon**, not midnight, for chunking and daily summaries. If that reads
  oddly in the summary cards, say so.
- **Interleaved heart rate.** `0x55` carries both the 120 s periodic series (seconds always `:30`) and
  an extra sample at each SpO₂ measurement (seconds `:14`/`:34`), taken under a different measurement
  mode with a different bias. Merged, they create a systematic sawtooth every fifth slot that Hampel
  will waste its rejection budget on. If the sync hand-off tagged the source at write time, use the
  tag; the timestamp heuristic is a fallback, not a plan.
- **Stress interpretation.** We store the band's raw stress byte. The Hume app displays a filtered mean
  — its 37.2 against our raw 42.1 is best explained by dropping records outside roughly HRV 19–75, but
  that was fitted against two targets and is a lead, not a fact. **Draw the raw value**; do not
  reimplement Hume's filter. If 白い熊 later wants their number reproduced, that is a separate piece of
  work needing a second reference day.

---

## Repo hygiene

Follow this repo's own `CLAUDE.md`. No `Co-Authored-By: Claude` trailer and no Anthropic attribution
line. Never commit or push unprompted — build-only until 白い熊 says "Push". Every `adb push` goes to
`/sdcard/tmp/` with a full `yyyy-MM-dd_HH-mm-ss` stamp, and **never delete an APK from the phone**.
Run `adb` with `dangerouslyDisableSandbox: true`, and `adb disconnect` at the end of the batch.

This hand-off is transient — delete it once the work is accepted.
