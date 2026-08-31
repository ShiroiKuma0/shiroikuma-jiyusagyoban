# The on-device build's progress contract

What `huawei.pgnss` publishes while it runs, and what the 衛星 panel binds to. Written down because
the two are built separately and a variable renamed on one side is a blank box on the other.

Every name is `MixedCase` after the prefix so it lands in the project bucket and a scene can read it
(`VariableStore` routes by the first letter's case — an all-lowercase name never reaches a scene).
Prefix defaults to `HUAWEI_`.

## The four steps

The panel is a linear list. Each step is `wait`, `run`, `done` or `fail`, and the list only ever moves
forwards.

| # | step | ends when |
| --- | --- | --- |
| 1 | Download | all sources are on disk |
| 2 | Build | the six files are written into the app's store |
| 3 | On the band | the band has been told; waits for 白い熊 to press Update |
| 4 | Transferred | the band has taken the files; Bluetooth closed |

**Two actions, one panel.** Steps 1–2 are `huawei.pgnss`; steps 3–4 are `huawei.gnss`, which
publishes into these same variables only when given `panel=true`. That flag exists because the gnss
action is also used on its own, where a four-step list would be noise. Without it the flow stopped
dead at "Building — done" and the panel, which by then could spot a run that had stopped writing,
blanked itself mid-transfer (白い熊, 2026-08-30: "it skipped showing #3").

## Variables

| variable | meaning |
| --- | --- |
| `<prefix>PgnssSteps` | the four states, comma-joined, e.g. `done,run,wait,wait` |
| `<prefix>PgnssPhase` | one line naming what is happening now |
| `<prefix>PgnssDetail` | what is being worked on — a file name, a satellite, a constellation |
| `<prefix>PgnssCount` | `3/12` — units finished over units planned, within the current step |
| `<prefix>PgnssPct` | 0–100 across the whole run, for a bar |
| `<prefix>PgnssElapsed` | `2m 14s` since Generate was pressed |
| `<prefix>PgnssEta` | best estimate of time left, blank until it can be honest |
| `<prefix>PgnssLog` | the last few lines, newest at the bottom |
| `<prefix>PgnssResult` | filled only at the end: what was built and served |
| `<prefix>PgnssFailed` | non-empty if a step failed, naming which and why |
| `<prefix>PgnssHeartbeat` | wall clock, ms, rewritten on every publish — see below |
| `<prefix>PgnssStartedAt` | wall clock, ms, when Generate was pressed; the gnss half reads it |

The serving step additionally reuses what `huawei.gnss` already publishes — `GnssServed`,
`GnssBytes`, `GnssLog` — rather than duplicating them.

## Rules the UI depends on

- **Throttle to about 2 s.** Every variable write bumps a revision *and* queues a Room upsert, and a
  scene `vars` change reloads the whole WebView. A 25 MB download reporting every chunk would spend
  more time redrawing than fetching.
- **`PgnssElapsed` must keep moving** even when nothing else changes, or a long download looks like a
  hang. It is the one value that ticks on a clock rather than on progress.
- **A step never goes backwards.** If the band asks for a second round after step 4, that is logged,
  not a return to `run`.
- **`PgnssCount` counts real units**, not percentages dressed up: files for the download, element sets
  for the fit. `3/12` must mean three of twelve things are actually finished.
- **ONE denominator per step, pinned before the step starts.** Each sub-phase of the build used to
  count against its own size, so the panel read `n/36`, then `n/720`, then `n/6` — every number true
  and the sequence useless (白い熊, 2026-08-30: "we don't know at all how many steps in total"). The
  build's total is now fixed the moment the orbit products are read, from `plan` alone, and the
  counter only moves forwards. BeiDou element sets are counted as **planned**; a satellite dropped as
  untrustworthy has its epochs credited in one lump, because deciding not to fit something is
  finished work and otherwise the counter could never reach its total.
- **The bar stops at 90 % when the build ends.** Steps 3 and 4 have not happened yet, and a full bar
  while the phone waits for 白い熊 to press Update on the band is simply a lie.
- **A stopped run must not look like a running one.** These variables outlive the process that wrote
  them, so a panel opened after a kill showed a download that had ended hours before. `PgnssHeartbeat`
  is rewritten on every publish and the panel shows progress only while it keeps moving — except for
  a run that ENDED (all four `done`, or any `fail`), which keeps what it ended with. The staleness
  window is 45 s: the transfer reports per file rather than on a clock, and blanking a live transfer
  would be worse than showing a dead one for another half minute.

## Timings measured on 2026-08-30, for the ETA's first guess

| stage | measured |
| --- | --- |
| downloads, total, 2026-08-30 | 368 s for 21.3 MB |
| — the BeiDou orbits from Wuhan's own FTP, 3 files | 299 s of that, at ~18 KB/s |
| — **the same 3 files from IGN's mirror, 2026-08-31** | **~2 s** |
| — everything else | under 33 s |
| Kepler fits | 23–31 ms each on a desktop; 2196 of them ≈ 57 s on one core |
| BeiDou integration | the largest remaining unknown on-device |

**The download no longer dominates; the solve does.** Until 2026-08-31 the three Wuhan orbit files
were 26 % of the bytes and 81 % of the wall clock. They are the same files at `igs.ign.fr`, which
serves them byte for byte — verified by md5 on the same issue, 0.65 s against 50.0 s — so the first
ETA should now be built from the solve rather than from bytes.

**What was wrong before, and why it stood for days.** This document used to end with "Wuhan is the
floor": that 45 KB/s was a hard ceiling, that parallel streams bought nothing, and that no mirror
carried the product. The first two are true and still are — the entire spread is `igs.gnsswhu.cn`
varying by a factor of three within a quarter of an hour, so **any two download timings taken at
different times are not comparable**, and three parallel streams sum to the same ceiling.

The third was never true. It was a search that stopped early, recorded as a fact about the world.
IGN's copy is not under `mgex/` — that legacy directory stops around week 2044 — and IGN's FTP
rejects curl's DEFAULT anonymous password with a 530 that reads exactly like a dead host, which is
how a note in `Fetch.kt` came to say it "does not answer at all from here". It answers fine to
`anonymous` / `anonymous@`, which our own FTP client was already sending.
