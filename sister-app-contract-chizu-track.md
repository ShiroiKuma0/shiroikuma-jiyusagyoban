# Contract — handing a walk to 白い熊 地図

**Between:** `shiroikuma.jiyusagyoban` (白い熊 自由作業盤) and `shiroikuma.chizu` (白い熊 地図).
**Agreed:** 2026-08-23, between the two repos' chats, with 白い熊 directing.
**Status:** both halves are built. 自由作業盤's is shipped and installed; 地図's landed in
`5.4.0+022`; `+023` added the `track_id` canonicalisation and `+024` the `OPEN_TRACK` Activity and
the pending map target — **`+024` is the one to install**, and it waits at `/sdcard/tmp/` for 白い熊.
Nothing has made a real round trip yet — the automation switch and the token live in 地図's own
settings, so the first genuine `IMPORT_TRACK` is 白い熊's to trigger. Until then Send to 地図 answers
「地図 did not answer」, which is correct for a receiver that is not yet listening.

## Why this exists

The HUAWEI Band 11 Pro records a walk with GPS: a summary and, when it saw satellites, a track file.
自由作業盤 decodes that file into a route it can name and store, and it can draw nothing with it.
地図 is an OsmAnd fork that already stores tracks, analyses them and rasterizes maps offscreen.

So the split follows the competence: **自由作業盤 owns the band and the walk; 地図 owns the map.**
自由作業盤 never renders a map and never asks for a tile. 地図 never talks to the band and never
parses a `.bin`. Each keeps a copy of what the other produced, so neither has to be running for the
other's screen to draw — 白い熊 freezes apps, and a frozen app answers nothing.

## The channel

Identical to the 天気 and 保存復元 contracts, and for identical reasons — see
`sister-app-contract-weather-query.md`. Briefly:

- **Out:** an *ordered* broadcast with `FLAG_INCLUDE_STOPPED_PACKAGES` and an explicit
  `setPackage("shiroikuma.chizu")`.
- **Back:** a *fresh plain broadcast* from 地図 to `reply_package` / `reply_action`, also with
  `FLAG_INCLUDE_STOPPED_PACKAGES`, carrying string extras only and echoing `reply_id`.
- **Never a binder.** This EMUI drops a broadcast carrying a `ResultReceiver` and silently never
  fires a `PendingIntent` delivered to a manifest receiver. Both apps reached this conclusion
  independently, from different bruises.
- 地図 also sets the ordered result. Neither side relies on it.

## The receiver, and the switch in front of it

Requests go to `net.osmand.plus.chizu.ChizuTrackReceiver`, behind the **same** automation switch
(**off by default**, in 地図 → Export / Import) and the **same** 24-byte token as 保存復元. There is
no second gate and no second token; 自由作業盤 holds it in `%Huawei_ChizuToken` and stores it
device-locally under `HuaweiSettings`.

A separate class from 保存復元's `ChizuStateExportReceiver`, sharing one `ChizuReplier` so the reply
dialect stays byte-identical across both. Invisible from this side — 自由作業盤 broadcasts an action,
never a class — and recorded only so the document does not say something false.

A blank token is refused **before sending**. 地図 would only answer `ERROR:bad token`, and a refusal
that costs no broadcast is a better error message.

## `shiroikuma.chizu.action.IMPORT_TRACK`

Take a GPX, store it as a track, draw two pictures of it.

**Sent by 自由作業盤:**

| extra | | |
|---|---|---|
| `token` | required | the 地図 automation token |
| `reply_id`, `reply_package`, `reply_action` | required | the round-trip keys |
| `gpx_data` | one of these two | the GPX document itself, as a string extra. Wins when both are sent |
| `gpx_path` | one of these two | absolute path, **ordinary shared storage** |
| `name` | required | human name, e.g. `walk 2026-08-23 18:28` |
| `out_dir` | optional | where to write the files — **ordinary shared storage**. 自由作業盤 always sends it; see the default below |
| `track_id` | optional | a previous reply's id — makes this a re-share, overwriting in place |
| `thumb_w`, `thumb_h`, `map_w`, `map_h` | optional | pixel sizes, clamped 64–4096; 自由作業盤 sends 480×360 and 1440×1080 |
| `thumb_px`, `map_px` | optional | square-edge fallback, read only when the w/h pair is absent |
| `night` | optional | `auto` \| `day` \| `night`; default and 自由作業盤's pin are **`day`** |
| `folder` | optional | sub-folder inside 地図's tracks dir |
| `show` | optional | `true` puts the track on 地図's map; default is saved-but-hidden |
| `density` | optional | cartographic detail per pixel, clamped 1–4; default is the display density |

**The w/h pair is canonical** — 自由作業盤's cells are 4:3, not square. It sends `*_px` as well, which
is harmless because the pair wins. Every numeric extra is parsed from a string *or* a number, so
自由作業盤's string-only extras are fine.

**Answered by 地図** as named string extras: `track_id`, `name`, `stored_path`, `gpx_path`,
`thumb_path`, `map_path`, `map_detail`, `zoom`, `distance_m`, `duration_s`, `moving_time_s`,
`points`, `start_time`, `end_time`, `elevation_up`, `elevation_down`, `avg_speed`, `max_speed`.
Times are epoch milliseconds, distances metres, speeds m/s.

**And packed into `result`**, in this order:

```
OK:<track_id>|<gpx_path>|<thumb_path>|<map_path>|<distance_m>|<duration_s>
```

That redundancy is deliberate: an older 自由作業盤 bridge dropped every extra but `result`. It has
since been fixed and reads the named extras first, but a packed summary costs one line and survives
a regression. **This order has already changed once**, which is why 自由作業盤's fallback splitter
names its slots rather than indexing them by hand — a fallback that silently returns the wrong field
is worse than no fallback at all.

### `map_detail` — the field that says whether the picture is worth keeping

`map` means real streets were rendered under the track. `basemap` means only 地図's bundled world map
was there, which at walking zoom draws nothing. `none` means the plain fallback.

**Nothing on 自由作業盤's side can tell those apart by looking** — all three are a route on a pale
ground — so the value is stored with the walk and drives what the screen says. A `basemap` walk is
labelled as a missing region rather than left to read as a failed render.

### Decisions worth keeping

- **The track travels inline when it is small enough, and by path when it is not.** 白い熊 specified
  inline delivery and estimated a track at about 30 kB. Measured, it is roughly **89 bytes per
  point**: the first real walk is 1763 points and **153 kB**, and an hour on foot would be nearer
  370 kB. A broadcast rides a Binder transaction with about a megabyte for everything in flight, so
  inlining unconditionally would work for months and then throw `TransactionTooLargeException` on a
  long walk — arriving as a lost walk rather than as a warning. 自由作業盤 therefore sends `gpx_path`
  always and adds `gpx_data` only under a **128 kB** ceiling. Under it the track travels in the
  broadcast as asked; over it, by path. On the path branch 地図 reads the file with ordinary file
  access at the moment it handles the broadcast, so it must be readable *then* — which the walks
  folder is, being shared storage. 自由作業盤 being frozen at that moment changes nothing: 地図 is
  reading a file, not asking an app.
- **Both sides use plain paths in shared storage.** 地図 holds `MANAGE_EXTERNAL_STORAGE`, but *no*
  permission opens another app's `Android/data/`, so a private path breaks the hand-off silently in
  one direction and invisibly in the other. Walks therefore live under
  `/sdcard/〇/[666] 私資料/[666][147] tracks`, one folder per walk. No `content://` grants — not on this EMUI.
- **自由作業盤 copies the PNGs into the walk's own folder** rather than referencing 地図's. A path
  into another app's storage breaks when it reorganises, and cannot be read at all while that app is
  frozen.
- **Two renders, not one downscale.** Vector map labels turn to mush when an image is shrunk, so
  地図 rasterizes at each requested size.
- **The track is stroked twice: a casing, then the colour.** The casing is picked by luminance, not
  taste — Rec. 709 luma ≥ 110 gets black, below it white. Yellow `#FFFF00` is luma 226 and therefore
  always carries a black casing, which is what keeps it legible on the palest day-style ground. This
  matters here beyond legibility: **白い熊 is red-green colour-blind**, and yellow's separation from
  everything around it is carried by *luminance*, with the casing supplying the edge contrast that
  hue cannot. 地図 also draws the ends — a filled dot in the track colour at the start, the same dot
  inverted at the finish — so direction is readable in a 480 px cell.
- **`night` is pinned, not `auto`**, and pinned properly. These pictures are kept for years and shown
  in one grid; a grid whose cells were drawn under whichever theme happened to be current reads as
  broken. 地図's legacy rasterizer took day/night from the live theme with no way to ask otherwise,
  so it gained an override set for the duration of one render and cleared in a `finally`. A walk
  shared at midnight and one shared at noon come out the same.
- **`track_id` round-trips** so a re-share overwrites. Without it a walk sent twice becomes two
  tracks, and the second one is not a new walk.
- **地図's figures are stored beside the band's own and never merged with them.** 自由作業盤 keeps
  distance, duration, moving time, climb and descent, and shows them on their own card. Two independent measurements of one route are how a decoder that misreads the format
  gets caught — this one already did once, a 33-byte header where every published description says
  32 — so a disagreement here is a finding, not a number to reconcile.

## Opening a walk in 地図 — an Activity, not a broadcast

```
action    shiroikuma.chizu.action.OPEN_TRACK
class     net.osmand.plus.chizu.ChizuShowTrackActivity   (exported, category DEFAULT)
extra     track_id   — the canonical id, 自由作業盤/<base>.gpx
```

`startActivity`, and nothing else. No broadcast, no reply, no wait, no ordering. 地図's side is a
translucent trampoline with no UI: it takes the foreground it is handed, holds it invisibly while it
finds the track — which on a cold start means waiting out initialization — then starts the map as an
ordinary foreground start. Excluded from recents, finishes itself.

**No token, deliberately.** This does nothing a launcher icon cannot: it opens the app and chooses
which track is on screen. Gating it would buy no safety and would break the button silently every
time the token drifted. The token still guards `ChizuTrackReceiver`, where the headless work is and
where it means something.

**An unknown id still opens the map**, with a toast saying the walk is not in 地図 and to share it
again. A button that does nothing is the failure this action exists to end, so it does not silently
do nothing even when it fails.

### Why this is an Activity at all — the bug that produced it

`SHOW_TRACK` was first built as the button's path, and the button did nothing: no error, no screen
change, in either place it appears.

**A broadcast receiver cannot bring its app to the front.** Since Android 10 an app with no visible
window may not start an activity, and 地図 has none at the moment a broadcast arrives — the sender
is the app in front. Its `startActivity` was refused silently. 地図's handler was not incomplete but
*defeated*: the track was selected and stayed selected, while the camera move was written onto a map
view that did not yet exist and was then overwritten by the map's own restored centre.

Neither side reached for a delay between two calls. The fix was to notice that **the app with the
foreground is the one that may start an activity**, and that 自由作業盤 has it.

## `shiroikuma.chizu.action.SHOW_TRACK` — the headless path

`token`, the round-trip keys, and `track_id`. Kept for tasks and for a 自由作業盤 talking to a 地図
older than `5.4.0+024`; **the button uses `OPEN_TRACK`.**

Since `+024` it records a **pending map target** via OsmAnd's `setMapLocationToShow`, at a zoom
fitted to the track's bounding box rather than a bare centre. That is the sanctioned mechanism —
upstream's own source warns against setting the map view directly for this purpose, which is exactly
what the first version did. The consequence is that the map lands on the walk **whichever way it is
next raised**, so every ordering works, cold or warm, and nothing is timed by either side.

The receiver still attempts a launch and the system still refuses it. That no longer matters: the
target is already pending.

## What lands where

Per import, three files in `out_dir`: `<base>.gpx` — 地図's normalized copy, written by OsmAnd's own
writer — plus `<base>.png` and `<base>_thumb.png`.

地図 keeps its own copy separately under its tracks folder, and **the `track_id` is the path relative
to that folder** — `自由作業盤/<base>.gpx`, with **no `tracks/` prefix**. Sent back, the same file is
corrected in place rather than duplicated.

An earlier draft of this document wrote the id as `tracks/自由作業盤/<base>.gpx`, which is the on-disk
path. That costs nothing while 自由作業盤 only ever echoes the string it was given, and costs a
silently duplicated walk the first time anyone *constructs* an id from this page: 地図 resolves a
relative id under its tracks dir, so the extra prefix lands the file at `tracks/tracks/…` as a second
track — the exact failure the field exists to prevent.

Since `5.4.0+023`, 地図 also reduces an id to its canonical form before filing, so
`tracks/自由作業盤/x.gpx` and a full `stored_path` both resolve to the same track as
`自由作業盤/x.gpx`. That is a belt under the document, not a licence to write the id loosely: the
form above is the one 地図 returns and the one to send back.

**`<base>` is not always `name`.** 地図 sanitizes it: `/ \ : * ? " < > |` and control characters
become spaces, whitespace runs collapse, leading dots go, and the result is capped at 96 characters.
Japanese survives intact. So `walk 2026-08-23 18:28` files as `walk 2026-08-23 18 28.gpx`. Match on
the paths 地図 returns, never on a name you sent.

`out_dir` is honoured as given, and 自由作業盤 always sends its own — the walk's folder — so 地図
writes straight into it.

**Both sides now root at the same place.** 白い熊 directed on 2026-08-23 that walks live at
`/sdcard/〇/[666] 私資料/[666][147] tracks`, which is already 地図's default `out_dir` — the folder
白い熊 named to it before either half of this existed. So the explicit `out_dir` and the default now
agree, and `out_dir` has stopped being a place where the two halves of this document can drift apart.
自由作業盤 keeps sending it explicitly anyway: agreeing with a default is not the same as depending
on one.

Walks are deliberately **not** under `[979] バックアップ/…白い熊 自由作業盤 Huawei Band 11 Pro/`.
That directory is the **watch-face archive** — 45 captured faces that cannot be re-captured, since a
face can only be pulled out of a Huawei Health session that has already downloaded it. A `walks/`
subfolder of it was the original destination and was never created; the archive itself stays exactly
where it is.

## Errors

地図's own vocabulary, shown verbatim by 自由作業盤 and therefore worth reading as the messages
白い熊 will actually see. The gate first:

```
ERROR:automation disabled
ERROR:bad token
ERROR:unknown action
```

Then `IMPORT_TRACK`'s own:

```
ERROR:no gpx: pass gpx_data or gpx_path
ERROR:cannot read <path>
ERROR:gpx unreadable: <reason>
ERROR:no track points
ERROR:no-storage-access
ERROR:cannot create directory <path>
ERROR:not a directory: <path>
ERROR:cannot write <path>: <reason>
ERROR:cannot write the picture: <reason>
ERROR:<ExceptionName>            ← catch-all; a bug on 地図's side, worth sending to it verbatim
```

and `SHOW_TRACK`'s: `ERROR:unknown track: <track_id>`, `ERROR:gpx unreadable: <name>`. `OPEN_TRACK`
has no error vocabulary — it is an Activity, and it reports to the person looking at the screen.

### When the token stops working

`ERROR:bad token` is the only signal 自由作業盤 can ever get that the token has changed, and there is
no way to detect it earlier — so it is worth knowing what can change it.

**A settings restore in 地図 cannot.** The token and the automation switch live in the prefs file
`chizu_exim`; everything 保存復元 reads or writes lives in `chizu_ui`, and the export/import code
opens only the latter. 地図 read both call sites rather than trusting the comment that says so.

**Two things can:** clearing 地図's app data, and the deliberate **Regenerate** button in its
settings, which confirms first. After either, `%Huawei_ChizuToken` must be re-pasted. Nothing
automatic will notice; the next Send to 地図 simply answers `ERROR:bad token`, which is at least an
unambiguous string rather than a silence.

**`ERROR:cancelled` cannot occur here** — it belongs to `CANCEL_EXPORT`, and there is no cancel for
an import. An earlier draft copied the export contract's list wholesale.

自由作業盤 shows whatever comes back verbatim. Silence — a missing app, a receiver that does not
exist yet, a render that never returned — reads as 「地図 did not answer」.

**The two sides' clocks are not the same, and 自由作業盤's is the wider one on purpose.** 地図 allows
up to 180 s for a cold app to finish initializing and only then up to 120 s for the render; both are
far under a second in practice. 自由作業盤 waits **300 s**, which covers the sum. A reply landing
after its window would arrive as an unmatched `reply_id` — discarded silently, and indistinguishable
from a failure — so waiting is the cheaper mistake of the two.

## The GPX

GPX 1.1, `<trkpt lat lon>` with `<time>` per point and `<ele>` where the band recorded it. **The
timestamps stay**: they are what makes 地図's analysis work.

**地図 does not store a byte copy.** What it keeps and what it exports is OsmAnd's re-serialization
of the parsed track — that is what "normalized copy" means above, and it is also how the track colour
gets stamped in. 自由作業盤's own file is of course untouched; 地図's is rewritten. `<metadata><desc>`
survives that round trip (地図 checked its writer rather than assuming), so the band's own summary is
safe there if it is ever wanted inside the file. It is not sent as extras today.

## What is under the track — and a correction worth keeping

**白い熊 has the maps.** The phone's `/sdcard/〇/[60] 地図/` holds thirteen regional maps, including
`Czech-republic_praha_europe.obf` (102 MB, 2026-07-09), alongside `stredni-cechy`, `jihozapad`,
`jihovychod`, `severovychod`, `severozapad`, `stredni-morava`, `moravskoslezsko`, Austria lower,
Croatia, Moscow, Vladimir and Hawaii. So the first walk in this archive — recorded in Prague — should
come back `map_detail=map`, with real streets under it. **If it answers `basemap`, that is a genuine
finding**, not the expected outcome, and the reply string is worth sending to 地図.

### The correction, recorded because the failure mode is the interesting part

Two earlier drafts of this document said otherwise, and both were wrong for the same reason. The
first said 地図 had no regional maps at all — read from the app's private directory, which holds only
the bundled world basemap. 白い熊 corrected that and named the shared folder. The second then said
Prague specifically was missing — read from `~/〇/[60] 地図/` **on the PC**, which is a partial mirror
holding three of the thirteen, and mistook it for the whole.

Two different wrong directories, one after the other, each time with a genuine "I checked" behind it.
**"I checked" and "I checked the right thing" are not the same claim**, and only the phone counts.
The version above was verified on the phone by both sides independently rather than by one side
reporting to the other.

### The hand-off is entirely offline — with one exception

Not one step of `IMPORT_TRACK` touches the network: 地図 reads the inline copy or the file, parses,
writes, rasterizes from the installed `.obf` maps and writes PNGs. So **a 地図 that cannot reach the
internet still completes the round trip and still answers `map_detail=map`.** If an import ever
fails, a network problem is not the explanation — the reply string is, and it should go to 地図.

This is worth stating because 地図 is currently unable to download maps at all: 白い熊 hit an
"I/O error" on a map update, and the suspect is `shiroikuma.kojiki`'s VPN firewall rather than
storage — OsmAnd reports every `IOException` as `I/O error: …`, a refused connection included, so a
block reads exactly like a disk fault. That is a separate matter and does not touch this contract.

**The one place it does reach in:** the day a walk falls outside the installed regions, **Draw the
map again** cannot help until 地図 can actually fetch the missing region. The button would then be a
no-op behind a card that looks perfectly correct. Nothing here can detect that, so it is written down
instead.

### The mechanism still stands, and is still the point

None of this makes `map_detail` decorative. A walk outside the installed regions still answers
`basemap`, 自由作業盤 still labels that on the card rather than letting a route on a pale ground read
as a failed render, and **Draw the map again** is still the instrument for re-rendering a walk once a
region lands — the walk itself never needs re-sending. The limitation is real in general. It simply
does not apply to the walks 白い熊 has taken so far.

## Reliability notes 地図 owns

- The shared renderer is a single instance, so requests serialize.
- Upstream's `initAndDraw()` calls back only when it decides an update is needed, so a repeated
  request with an identical tile box can silently never answer. 地図 bypasses that guard and puts a
  timeout under every request, so a reply always lands — `OK:` or `ERROR:`.
