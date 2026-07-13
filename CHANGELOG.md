# Changelog — 白い熊 自由作業盤

Fork-specific changes layered on top of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker).
This lists what the fork adds; upstream's own history lives in the OpenTasker repository.

## 0.2.75+189 — 2026-07-12

The **cool-running release**: the 音楽端灯 meteors move from a WebView canvas to a **native METEOR
scene element** — same dance, roughly **a third of the CPU** during music playback (~195 % → ~60 %,
measured) — plus a headless **adb workspace-transfer bridge** (broadcast-driven export/import) that
powers a fully automated build-test cycle, physically **rounded screen corners** with their own knob,
and a 電池線 fix that stops the charging flame at 100 % and turns the line blue.

### 音楽端灯 — native METEOR element (the heat fix)
- **Why:** the phone heated up badly while playing music. Systematic measurement (live `top`
  sampling per variant, thread-level breakdown) traced the load to **WebView's per-frame canvas
  machinery, not the meteor math**:
  - the real meteor page cost **~185–195 % CPU** sustained (app process + WebView renderer);
  - a bare full-screen canvas drawing ONE line at 60 fps already cost **~60 %**;
  - four edge-strip canvases cost **more** (~110 %) — per-layer commit overhead, not pixels;
  - an rAF loop with **no** canvas drawing cost ~0 % — the commit path itself was the furnace.
- **New scene element `METEOR`** (`scenes/EdgeMeteors.kt` + a `SceneActivity` renderer branch):
  a 1:1 native port of the meteor page — perimeter ribbons in a rounded-rect band, layered glow,
  comet-taper core, white-hot head star, per-ribbon twinkle, hue drift, and the full **音楽反応 v3
  tempo-locked** behaviour (beat-grid pump, auto-gain-normalised dynamics, onset fallback) — drawn
  by the app's **own RenderThread** like the 電池線 charging fire, reading
  **`MusicPulseSource.Bridge` natively** (no JS bridge, no WebView renderer process at all).
- **Three rendering pathologies found and eliminated on the way** (each measured, each ~190 %+):
  - `BlurMaskFilter` Gaussian glow — CPU-rasterized per blurred path on a hardware canvas (~225 %);
  - wide anti-aliased **stroked paths** (even 3-point ones) — HWUI software-rasterizes every stroke
    into a mask texture on its `hwuiTask` threads each frame;
  - the band's **even-odd ring `clipPath`** — a full-window coverage mask rasterized every frame;
    this was the invariant cost across ALL variants, the original WebView page included.
- **Final architecture — GPU-native primitives only**: every ribbon is split at the screen corners
  it crosses into 1–3 **axis-aligned capsules** (`drawRoundRect`); the glow is concentric widening
  capsules with a Gaussian-ish alpha falloff; the core taper is a per-run axis-aligned
  `LinearGradient`; the band's inner hole is punched by **one `PorterDuff.CLEAR` round-rect**
  instead of a clip. Result: `hwuiTask` raster threads at **0.0 %**, RenderThread ~21 %,
  **~60 % total at 60 fps / ~50 % at 45 fps** (vs ~195 %), dance visually intact.
- **Physically rounded screen corners** (白い熊's design): four **opaque-black corner masks** drawn
  over the band — ribbons run into the corner squarely underneath and emerge from behind the curve.
  The mask path is static and HWUI-cached (rebuilt only on size/radius change), so it is free.
  Its radius is an independent live knob **`%Ongaku_Corner`** (default 18; `0` = square corners,
  `32` = the band's own rounding) in `音楽端灯の設定 [01]`.
- **All knobs are now %var-live**: the METEOR config maps every `%Ongaku_*` variable through the
  scene engine's live expansion, so palette, speeds, glow, fps, reactive tuning — everything —
  applies **instantly without re-showing the scene** (the WebView read them once at page load).
- **The FPS cap became a true linear heat dial**: a capped-out frame is skipped before the sim
  step — no state write, no recompose, no draw, no commit — so `%Ongaku_Maxfps` now scales cost
  almost proportionally. Default retuned **60 → 45** (visually smooth, measurably cooler);
  the dead interim `Ongaku_Glowres` knob was removed from 設定.
- Invisible ribbons (fade-in/fade-out ends of life) skip all drawing; `METEOR` is editable in the
  scene editor (element-type list, defaults, size).
- Screen-off gating as before: the element leaves composition when the display sleeps — the frame
  loop stops dead, nothing computes in the dark.

### Workspace-transfer bridge — headless export/import over adb
- **New `WorkspaceTransferReceiver`** (`core/transfer/`), an exported ordered-broadcast bridge
  gated by the shared protocol extra (same convention as the widget bridges):
  - **`shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE`** — writes a full workspace export
    (tasks, profiles, scenes, variables, templates, projects, groups, item metadata) as an
    OpenTaskerBundle JSON to `/sdcard/tmp/白い熊 自由作業盤.<yyyy-MM-dd_HH-mm-ss>.json` (or an
    explicit `extra.PATH`), answering with the written path and item counts;
  - **`shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE`** — imports the bundle JSON at `extra.PATH`
    (a bare filename resolves against `/sdcard/tmp`) with the standard strategies (merge projects,
    overwrite same-name items in place), answering with a human-readable import summary and any
    validation warnings; failures return the error message in the broadcast result.
- Powers the new **fully automated dev cycle**: baseline export → mirror sync → build →
  `adb install -r` → push bundles → headless import → test → final export → archive — no manual
  file picking anywhere.

### 電池線 — stop the charging flame at 100 %, full-charge line goes blue (workspace-side)
- `denchi.update` gained a gate: at **100 % battery `%Charging` is forced to `false`**, so the
  charging fire stops while the plug stays in (it previously burned forever on a full battery,
  since EXTRA_PLUGGED-based charging detection stays true).
- **`%Denchi_Full`** (the 100 % line colour) changed green → **blue `#0000FF`**; label updated.
- Both tasks re-shipped fully literate (labels on every action + task notes).

### Packaging & docs
- Dev-workflow overhaul recorded in `CLAUDE.md` and the repo skills (`build-apk`,
  `workspace-mirror`): wireless adb (direct or via the `skhw` ssh tunnel), automatic
  `adb install -r`, `/sdcard/tmp` keeps only the current APK, end-of-cycle archives the final
  export and APK to the on-phone backup tree.
- Version tail: builds `+182` – `+189`; `versionCode = 770189`.

## 0.2.75+181 — 2026-07-11

The **music-reactive release**: the 音楽端灯 edge-light meteors now **dance to the actual music** —
tempo-locked to a beat grid the engine derives live from the device's output mix — plus a
narrow-screen (folded-cover) layout mode, a fix for the 物理鍵 grabber silently dead since the
variable-demotion campaign, and a Visualizer feasibility diagnostic.

### 音楽端灯 — 音楽反応: tempo-locked, audio-reactive meteors
- New engine component **`MusicPulseSource`** (`core/media`): taps the device **output mix** via
  `Visualizer` on audio session 0 with the **push capture listener** at the device max rate
  (~20 Hz) and distills it into small live signals:
  - **level** — smoothed loudness 0..1 (fast attack, slow release);
  - **beat** — a decaying 0..1 impulse on bass onsets (low-FFT-bin flux over a ~2 s running
    average, with a **180 ms refractory** so one drum hit is one onset);
  - a **beat grid** — tempo + phase + confidence, from inter-onset-interval clustering **folded
    into the 60–180 BPM band** (half/double-time hits agree), modal cluster ±8 %, gentle period
    tracking, and **PLL-style anchor nudging** toward on-beat onsets; confidence fades out ~2 s
    after onsets stop (quiet outros, pauses).
- **Nothing is recorded** — the Visualizer yields transient 8-bit visualization snapshots only.
- The bridge is injected into WEB scene elements as **`window.OngakuPulse`**
  (`level()/beat()/bpm()/beatPhase()/tempoConf()`), polled from the page's rAF loop (R8 keep rule
  for the `@JavascriptInterface` methods).
- **Ref-counted lifecycle**: the Visualizer exists only while a reactive scene element is visible
  AND the screen is on AND the knob is on — the scene's `musicPulse` config is live-%var-expanded
  (`%Ongaku_Reactive`), so flipping the knob to `0` releases the capture completely.
- The meteor canvas gained a reactive branch (scene bundle):
  - with a confident tempo, the whole flow **pumps precisely ON each grid beat** —
    `exp(-beatPhase × sharpness)`: sharp attack at the beat instant, decaying to the next
    (metronomic, immune to missed/extra onsets); ribbons **flash** and head stars **swell** on
    the same grid;
  - the **baseline speed follows the music's dynamics, auto-gain normalised** against the track's
    own rolling min/max (~8 s window) — mastering compression holds raw RMS nearly constant, which
    froze speed in the first iteration; normalisation restores the full slow↔fast swing;
  - low tempo confidence falls back to per-onset surges with a gentle drift; `Ongaku_Reactive=0`
    reverts to the original random walk exactly.
- **Five knobs, recipe-documented** in `音楽端灯の設定 [01]`: `%Ongaku_Reactive` (master),
  `%Ongaku_Reactgain` (dynamics→speed sensitivity), `%Ongaku_Reactpulse` (beat flash),
  `%Ongaku_Reactkick` (pump depth), `%Ongaku_Reactsharp` (pump shape: 2 = swell … 10 = jab). All
  nine tuning labels (incl. `Speedmin/Speedmax/Twinkle/Headglow`) were rewritten as **cross-linked
  recipes** — every label states its reactive-mode role and names partner knobs with concrete
  setting ideas (EDM jab, ballad breathing, flash-only, no-stall floor …).

### `music.viz.test` — Visualizer feasibility diagnostic
- New Media action: taps `Visualizer(0)` for N seconds (play music!) and reports frames received,
  live-frame share, peak RMS and peak bass energy, ending in a clear ✅/🔴 verdict — this decided
  the reactive pipeline was buildable on the Mate XT before any of it was written.
- Handles two verified EMUI quirks: a fresh `Visualizer(0)` can arrive **already enabled** (resize
  then throws "wrong state 2" — disable → resize best-effort → re-enable), and **polled**
  `getWaveForm()` is throttled to ~4 Hz (hence the push listener in the real pipeline).
- Wired into the capability pre-flight (Microphone, blocking); manifest gains
  `MODIFY_AUDIO_SETTINGS`; ships with a `視覚化試験` task in the 音楽端灯 project.

### 物理鍵 — grabber dead since the variable demotion (fix)
- The 2026-07-05 demotion campaign renamed the `%PKEY_*` super-globals to project-scoped
  `%Pkey_*` and rewrote every task — but the engine's `ShizukuKeyEventListener` still read the
  deleted ALL-CAPS names from the super-global bucket, so `enabled()` was permanently false and
  the volume-key grabber (double-press → camera etc.) had been silently dead since that day.
  It now resolves the MixedCase project-globals via `snapshotAll()` (the listener runs outside any
  task, so it can't know the owning project's id), keeping the legacy ALL-CAPS names as fallback.
- Same class of fix for the edge-bar long-swipe threshold: `SceneActivity` read the demoted
  `%LONGSWIPE_DP` and silently fell back to the default — now `%Longswipe_Dp` first.
- A full audit of all 40 demoted names found no other live code readers; stale prose mentions in
  18 item notes were refreshed on-device via a minimal notes bundle.

### Narrow screen — compact layout on the folded cover panel
- Under **480 dp** window width (the folded Mate XT cover, ~336 dp; semi/unfolded stay regular),
  the list layouts switch to a compact mode:
  - the per-level **group indent shrinks 56 → 14 dp** (the old indent ate ~17 % of the panel);
  - list side gutters 16 → 6 dp; task-card inner padding 16 → 10 dp;
  - action rows **reflow: each argument on its own full-width line** — the key pill plus a value
    that takes the whole rest of the row and **wraps to 2 lines** before ellipsising, so
    `%Ongaku_*`-length names read in full instead of "Ong…".

### Workspace content (bundles, not APK)
- **Battery 割 display**: the short battery form (`%ST_BattShort` — the `batt-fold` widget and the
  相撲字時計 overlays) now renders round tens as 九割/八割/…/一割 instead of 九〇/八〇 (non-round
  values like 八五 and 100 = 全 unchanged; same character width, so no layout shifts).
- The 18 pre-demotion variable names still mentioned in task/scene **notes** were freshened to the
  current `%MixedCase` names (and the 画面操作 01 note's "super-global" claim corrected to
  project-global).

## 0.2.75+175 — 2026-07-09

The **living-overlay release**: a native **charging-fire animation** on the battery line, a rewritten
**buttery-smooth music edge-light** canvas, strict **screen-off gating** so no overlay ever computes
behind a dark screen, a full set of **documented tuning variables** in the projects' `[01]` settings
tasks, a **task-target bridge** for sister launchers, and an action-row fix so variable names always
render in full.

### 電池線 — charging fire (native scene renderer)
- While charging (and only with the screen on), **two fire-comets glide in from both ends of the
  battery line, meet in the middle** with a red-orange collision bloom, and slide back out — a
  seamless, breathing loop (cosine-eased turnarounds, so it never jumps or disappears).
- Each comet has a **blood-red → hot orange-red gradient body** (soft-blurred capsule) and a red head
  glow — no white anywhere in the flame.
- **Red star-cross glints** twinkle at each flame tip: tiny `+`-shaped strokes flashing in and out on
  fast per-glint cycles — a genuine red sparkle instead of a solid dot.
- An **ember burst** sprays red sparks in all directions from each tip (bright red-orange at birth,
  cooling to deep crimson), arcing down under gravity into the scene's below-line head-room.
- A decaying **heat field** tints the bar deep red where a comet just passed — a lingering
  "residual fire" trail (~1.3 s time-constant) that cools back to the line's own colour.
- The visible bar itself stays a thin strip (`barThickness` config, default 3 dp) at the top of the
  now-taller scene; the line keeps its state colours (base / low-battery red / full green) at all
  times, and the whole overlay stays fully tap-through.
- **Fully variable-tunable** via the scene config → `電池線の設定 [01]`: `%Denchi_Cycle` (seconds per
  converge-and-return breath), `%Denchi_Hibana` (ember count), `%Denchi_Kirameki` (glint count),
  `%Denchi_Nokoribi` (heat-trail linger seconds; `0` disables the trail loop entirely).
- The effect exists in composition **only while `charging && screen-on`** — unplugging or blanking
  the screen stops the animation clock dead (zero off-screen CPU).

### 音楽端灯 — smooth, rich, heat-controlled edge-light (scene canvas)
- The WebView canvas hot loop was rewritten **allocation-free**: no per-frame arrays (the perimeter
  mapper writes globals), per-ribbon colours resolved once at spawn, in-place particle compaction,
  and the ribbon core drawn as **one gradient stroke** instead of 24 per-segment strokes — roughly
  **9× fewer draw calls per frame**. This eliminated the GC-pause stutter ("choppy, interrupted").
- Runs at the **full display refresh rate** with a settable cap: `%Ongaku_Maxfps`
  (`0` = uncapped, `60` = default, `30` = power-saver) — the overheating control.
- **New eye-candy knobs**, all injected as canvas variables: `%Ongaku_Headglow` (a near-white head
  star melting into the ribbon colour — shooting-star tips), `%Ongaku_Twinkle` (per-ribbon shimmer
  depth), `%Ongaku_Huedrift` (deg/s — a ribbon's colour slowly walks the colour wheel as it orbits).
- Palette reworked: the original bright multicolour set **plus blood-reds** (`#ff0000`, `#c00000`,
  `#ff2a00`) interleaved, so ~1 in 4 meteors runs red among the bright ones.

### Screen-off gating (engine)
- New opt-in WEB-element config **`pauseWhenScreenOff`**: when the display turns off, the native
  renderer calls `WebView.onPause()` *and* `window.__scenePlay(false)` (a JS hook the page defines),
  freezing both the compositor and the rAF loop; both resume on screen-on. **Opt-in by design** so
  通知明滅's over-lockscreen wakedance scenes — which must draw while the screen is off — are
  untouched.
- The battery-line comet effect leaves composition entirely on screen-off (same guarantee, native).

### Settings-task workflow
- Both projects' `[01]` settings tasks now carry the **complete knob set with a documentation label
  on every action** — the task doubles as the manual. Re-running the project's `⇨ 起動 [71]` task
  (which runs `[01]`) idempotently applies any settings change; a live scene reloads with the new
  values automatically.

### Task-target bridge (sister-launcher integration)
- New **`GET_TASK_TARGET_PACKAGE`** ordered-broadcast receiver: a sister launcher holding one of our
  run-task shortcuts can ask *which app the task ultimately opens* (by task name or id) and point its
  "app info" / "uninstall" menu entries at that app instead of at us. Newer shortcuts additionally
  bake the target package into the shortcut intent's extras.

### UI fixes
- **Action rows: variable names always render in full.** The arg renderer's hard 160 dp cap on
  non-last values (the `var.set` *name*) truncated most real-world names on a wide screen; names now
  take their natural width, with a 3:1 weight backstop so the value keeps ≥~25% of the row and a
  pathological name ellipsises at ~75% instead of pushing the value off.

## 0.2.75+164 — 2026-07-05

A large feature release over 0.2.75+127: the **相撲字時計** fold-aware over-lockscreen clock, a **task & action UI overhaul**, a full **Variables-tab redesign** with an in-app **dead-globals analyzer** and hard guards against scope leaks, a new **Edit Action** action, tap-through **permission deep-links**, and a switch to **event-local** notification/broadcast variables.

### 相撲字時計 — fold-aware overlay clock
- A new over-lockscreen overlay clock rendered in **相撲字 (sumo-script)** style, ported from the Tasker 時間と日付 project and driven entirely by the app. Three layouts — **folded / semi-folded / unfolded** — swap automatically with the device's fold state.
- **`%FOLD` via the HALL sensor**: a `fold` event context reads the hinge/HALL sensor and publishes the fold state, so the clock (and any task) can branch on `%FOLD`.
- The wide (semi/unfolded) layout **centres the time itself** on screen, with 午前/午後 and the weekday placed relative to it (center-anchored scene positioning, `xc`).
- Scenes are **touch-through** — the overlay passes taps to whatever is beneath it.
- An **app-multiselect picker** dialog chooses which apps hide the clock; the selection is committed to the blacklist variable *and* written back into the 設定 task so it survives the next startup.
- Bundles are **id-free / name-based** (zero ids; everything referenced by unique name), so a re-import can't dangle a profile→task link.

### Task & action UI overhaul
- Redesigned **action rows** with UI-settable styling.
- **Per-task and per-action menus** with **clipboards** — copy/cut/paste actions between tasks and duplicate tasks.
- Removed the olive accent throughout (**olive-free theme**).

### Variables tab — redesign
- Variable **name in blue, value bold**, both at action-view sizes — colours and sizes independently **UI-settable** (defaulting to parity with the action editor). Name and value share **one line**; the folded view is a single line.
- **Row padding** defaults to 2px and is settable.
- A yellow **magnifying-glass** icon sits to the right of the search bar.
- **Foldable scope sections** (Global, Project-global, …) with larger underlined headings, a fold triangle to the right of each heading, slight indentation, and a **live count** in parentheses.
- **Project-filter pills** in the top bar scope the list (and the project-global counts) to the selected project; a pill expands with a wrap-around rounded border around its members when unfolded.

### Dead-globals analyzer + scope guards
- A **"Clean up dead globals"** analyzer folds into the Variables tab. It classifies every persisted global and lists — per category, expandable — exactly what will be deleted and where:
  - **Shadow-copies** — a super-global duplicating a live project-global (with the twin project named).
  - **Orphans** — globals referenced by no task, profile, scene, or widget template.
  - **Dangling project-globals** — rows whose `projectId` points to a deleted/re-created project (previously invisible to both the analyzer and the Var tab).
- Deletion is **cache-consistent** (the in-memory global cache updates in lockstep with the DB).
- **Hard guards against the root cause** of scope leaks:
  - Deleting a project now **cascades to its variables** (they can't re-home to a dead project id).
  - Startup **sweeps dangling** project-globals before warming the cache.
  - `set()` redirects a MixedCase name written at projectId 0 to **task-local**, and import **skips** MixedCase-at-0 — so a project-scoped name can never land in the super-global bucket.

### New action
- **Edit Action** (`task.editaction`) — programmatically edit another task's action: locate it by index or by `matchType`/`matchName`, set one arg, and persist. Used by the clock's blacklist picker to keep its 設定 task in sync.

### Engine & permissions
- **Tap-through permission deep-links**: the permission block/warning dialog shows an **"Open &lt;permission&gt; settings →"** pill per missing permission, deep-linking straight to the correct System settings page (via `CapabilityState.settingsIntent`), on the shared dialog host and the task pre-flight block.
- **Event-local notification/broadcast variables**: `%NOTIF_*` (notification listener) and `%INTENT_*` (broadcast receiver — e.g. Poweramp's ~30 `TRACK_CHANGED` extras) are **no longer persisted as super-globals**. They're threaded per-invocation to the triggered enter task via the event's `vars`, and a startup sweep clears any stale copies — keeping the global namespace to genuine app/engine state.

## 0.2.75+127 — 2026-07-03

A point release over 0.2.75+122 — one targeted fix.

### Fixes
- **Huawei Mate XT foldable**: restored the folded-portrait wakedance fold-compensation the upstream reconcile had dropped. On the folded cover panel held in portrait, EMUI reserves a 105px top system-bar strip and confines the over-lockscreen wakedance Activity below it, clipping the 通知明滅 black mask + edge-light. The fix pulls that window up 105px so the blink covers the full panel again — applied **only** in that one state (folded landscape, semi-folded, unfolded, and the screen-on overlay blink are all untouched).

## 0.2.75+122 — 2026-07-03

A large update over 0.2.75+36: cross-app **protected contacts**, a **drag-reorder** pass across the app, a **Review Import** overhaul, **critical data-loss hardening**, task-card & group-header **styling controls**, a **Termux keyboard** trick, and a resync onto the latest OpenTasker.

### Protected contacts (cross-app privacy)
- Companion feature with the sister apps (白い熊 GNU Jami, 白い熊 Arcane Chat): for a marked contact, the messenger posts a **content-free notification** (a fixed “着信あり：新着伝言。” body) instead of the sender name / message text, so lock-screen and Android-Auto read-outs stay private. A per-package marker keeps it opt-in per messenger.
- 自由作業盤 pushes the protected-contact list to each messenger over an ordered broadcast, and can **read it back** (a `GET_PROTECTED_CONTACTS` query channel) to verify state.
- A **保護試験** test group (per-messenger checks + a cumulative check) confirms each messenger's stored list with a 🟢/🔴 dialog.

### Review Import
- Importing a JSON bundle now opens a near-full-screen **Review import**: per-category counts (“Tasks: N”, with “N exists” flagged), a **folder tree** (project → type → group → items) showing exactly where each item lands (and marking projects/groups that will be created), a **global conflict strategy** with per-item overrides (Overwrite / Overwrite + backup / Keep both), and Cancel/Import.
- Review typography (per-text sizes, a readable sky-blue conflict colour, row padding) is UI-settable.
- **An import never downgrades a Manual-sorted tab** — a partial import no longer silently re-sorts untouched groups.

### Drag-reorder & projects
- **Tasks, the Projects tab, and the top project-tabs** all support drag-to-reorder (the Projects tab's up/down arrows are gone; a long-press on a project tab gives a grab haptic).
- **Projects tab**: tap a project to make it active; the current project is clearly highlighted (accent border + tint).

### Task list & group-header styling
- Icon-less task cards stay **compact** and honour the padding settings; the empty “add icon” placeholder is small and visually subdued.
- Shrunk the ⋮ menu buttons so they no longer inflate rows/headers — **“Padding between cards”** and **“Padding inside group headers”** are now actually respected.
- **Group-header background** is a settable ARGB, with a settable **header border** (thin yellow by default).
- The **task-icon-size** slider now works; the **advanced full-screen, category-foldable action picker** is restored and default-on; an **app-picker** (choose from installed apps) is on app.kill / app.launch / intent.launch / intent.send / notify.dismiss.

### Reliability & engine
- **Critical data-loss fix**: hardened task-action persistence against a strict-decode landmine that could blank a task's whole action list after an app update (tolerant storage codec + an overwrite guard + write logging).
- **Battery**: removed the engine's permanent partial wake-lock, gated the shake / orientation / app-usage sensors to run only when a profile needs them, and stopped eager high-accuracy GPS.
- Generic **Send Intent** gained ordered-broadcast **result capture** (`result_var`).

### More
- **Termux keyboard**: an edge-bar up-swipe re-focuses Termux to bring its IME back (for mosh/emacs sessions where a screen tap won't).
- Themed black/yellow **snackbar & flash** everywhere; **oval-bar borders** (settable width + colour) on the volume/brightness panels; the **Variables** tab's folded rows now show each variable's value.
- The open tab + active project **persist across app exit**.

### Upstream
- Resynced onto **OpenTasker 0.2.75 / code 77**, including upstream's hardened database-backup publishing (WAL-checkpointed, schema-validated backups).

## 0.2.75+36 — 2026-06-27

**Pure-black, yellow-framed popups everywhere**, a task **icon from a song's album art**, and a **redesigned launcher shortcut picker** — now a tall floating dialog whose tasks are organised into bordered folder-boxes, with its own UI-customization controls.

### Menus & dialogs
- **All popup menus and dialogs are now true black with a yellow border**, matching the cards and search box — no more the lifted, brownish Material surface. The theme's `surfaceContainer*` roles are pinned to pure black, and a shared `ThemedDropdownMenu` (black container + 1.5 dp yellow border) backs **every** dropdown: the `+` FAB menu, the project switcher, the scene / widget / sort / font menus, and the action-clipboard menu. Standalone dialogs (task icon, etc.) carry the same yellow rounded frame.

### Task icons
- **New "Audio" icon source** in the task icon picker (App / Picture / Emoji / **Audio** / Clear): pick an **mp3 / ogg / flac / m4a** and the task takes its **embedded album art** as the icon — extracted with `MediaMetadataRetriever`, centre-cropped and snapshotted to a PNG like the other sources. A toast tells you if the file has no embedded artwork.

### Launcher shortcut picker (add-to-home-screen → 白い熊 自由作業盤)
- **Now a floating dialog**, not a fullscreen page: a tall + wide (94 % × 90 %) card with a **yellow rounded frame** over a dimmed scrim. Dismiss by tapping outside or the bottom **Cancel** button (black background, yellow rounded border).
- **Tasks are organised by group.** Each project, unfolded, shows its groups as **bordered rounded folder-boxes** — a folded group is visibly a closed box, so its siblings below can no longer be mistaken for its contents — then the project's ungrouped tasks. Groups **nest**, order is preserved, expanded group contents are indented deeper, and the old misleading play-arrow on task rows is gone.
- **New UI-customization → Shortcut picker section:** font size, row spacing, indent per level, group-box roundness, group-box border, and font — all applied live.

## 0.2.75+31 — 2026-06-26

**Move actions around freely** — long-press to multi-select and **clone / copy / cut / delete / paste** actions within and **between** tasks — plus a workspace-wide shift to **name-based linking**: scenes, scene-element task links, and `task.run` all resolve by **name** (not fragile ids), imports **overwrite in place**, and item names are now **unique within a project**. And `sound.play` can read tones anywhere in shared storage.

### Task editor — action multi-select & clipboard
- **Long-press an action** in a task to select it (multi-select by long-pressing / tapping more rows; selected rows are highlighted with a ✓). The long-press menu acts on the **whole selection**: **Clone** (duplicate in place), **Copy**, **Cut**, **Delete**.
- **Paste before / Paste after** the long-pressed action (shown once something has been copied/cut). An app-wide **`ActionClipboard`** holds the copied/cut actions, so you can **move actions between tasks**, not just within one.
- The drag handle keeps its own long-press reorder; a plain tap still expands a row (or toggles its selection while selecting).

### Link everything by name (no more broken links on re-import)
- **`scene.show` / `scene.hide`** resolve a scene by **`(project, name)`** — the scene in the calling task's project wins, then any-project by name (deterministic by position then id), then a numeric id only as a legacy fallback. `VariableStore.projectId` is exposed so the action knows the caller's project.
- **Scene element task links** — tap, long-press, and the edge-gesture handlers — now carry a **task name** (`tapTaskName` / `longPressTaskName`, JSON-only, no migration) and resolve **name-first** at run time *and* on import. A re-imported or recreated task no longer silently drops a slider/button's action: the editor stores the name on pick, **export back-fills** names from ids, and **import re-binds** by name → the bundle id map → the raw id.
- **`task.run`** resolves **name-first** (exact, then case-insensitive), with the numeric id only as a legacy fallback — matching the scene/profile resolvers.

### Import — overwrite in place
- **Profiles and scenes now overwrite *in place*** on import (reuse the existing row id, matched by name), just like tasks already did — so a re-import **keeps each item's id, group membership, and notes** instead of deleting + re-inserting and orphaning them. A profile overwrite preserves its enabled state.
- The **default conflict strategy is now Overwrite** (in place), and the conflict dialog leads with it. The "missing tap task" import warning no longer misfires when a scene element carries a name to re-bind against.

### Name uniqueness
- The **task / profile / scene editors block a duplicate name within the same project**, and the **project editor blocks a duplicate project name** — Save is disabled with an inline error (widget templates already did this).
- Enforced at the DB level by **UNIQUE indices** on `(projectId, name)` for tasks/profiles/scenes and `(name)` for projects (**DB schema v16 → v17**, `MIGRATION_16_17`). The migration **self-heals** first — any pre-existing collision is renamed `"<name> (<id>)"` so the index build can never fail; SQLite treats Unfiled (null-project) rows as distinct, so the editor's UI check covers those.

### Sound
- **`sound.play` — all-files access.** A new **All-files capability** (`Environment.isExternalStorageManager()` / `MANAGE_EXTERNAL_STORAGE` on API 30+, `READ_EXTERNAL_STORAGE` below) lets `sound.play` read custom tones **anywhere in shared storage** (e.g. the 通知明滅 Jami notification tone), surfaced as a capability pill on the action and a new **"All files access"** row on the Setup tab with a deep-link to grant it.

## 0.2.75+25 — 2026-06-25

**Freeze bubbles** — a native port of the Tasker 凍結 融解 re-freeze workflow — plus a tiled app picker, app-icon launcher tasks, an inline freeze toggle + tappable task icon, fully styleable bubbles, and every numeric UI-customization setting converted to a slider.

### Freeze bubbles
- **Per-task "Freeze bubble" flag** (DB **v16**, `MIGRATION_15_16`). Running a flagged task queues a re-freeze bubble for the app it launches/unfreezes (package read from its `app.launch` / `app.unfreeze` action). Toggle it in the task editor **or inline on the expanded task card**; **Make Launcher Tasks enables it by default**.
- **Desktop-gated overlay** — bubbles render as draggable `TYPE_APPLICATION_OVERLAY` windows shown **only while the default home launcher is foreground** (auto-detected), hidden everywhere else. Each shows the app's icon + a ❄ badge + label.
- **Tap a bubble → freeze the app** (`app.freeze` via Shizuku) and remove it; **long-tap → dismiss only**. Bubbles are **draggable**, **persist across reboots** (`FreezeBubbleStore`), de-dupe per app, and **re-clamp on rotation / fold** keeping their position relative to the top-right edge.
- **Fully styleable** under *UI customization → Freeze bubbles*: icon size, icon roundness, label size, label weight, and label font — with a **live preview**.
- The Setup tab's **Overlay access** row now notes freeze bubbles.

### Launcher tasks & icons
- The **Make Launcher Tasks** app picker is now a **yellow-bordered grid of app-icon tiles** (icon + name, multi-select with a check badge), replacing the plain text list — shared with every app-package field.
- Generated unfreeze-then-launch tasks now **default their icon to the selected app's icon**.
- A task's **icon is tappable in the list** — opens the icon picker (App / Picture / Emoji / Clear) without opening the editor; tasks with no icon show an "add icon" affordance when expanded. The picker is a shared component (`TaskIconEditorRow` / `TaskIconPickerDialog`) used by both the card and the editor.

### UI customization
- **Every numeric setting is now a slider** (was +/− steppers): Borders → Border width; Typography → Text size; Flash / toast → Border width, Corner radius, Text size; plus the freeze-bubble sizes. The flash and bubble sections keep their live previews.

### Infrastructure
- New `FreezeBubbleStore` (SharedPreferences) + `FreezeBubbleOverlayManager`, started from `AutomationService`; bubble enqueue hooked into `executeAndLogTask` (covers every run path). `TaskIconStore` gains a context-free `saveFromApp(pkg)` for non-UI callers.

## 0.2.75+18 — 2026-06-23

**Home-screen shortcuts that run a task directly**, each with a **persisted custom icon** (from an app, a picture, or an emoji), a **launcher shortcut picker**, a global **icon-size** control, and **cross-device icon transfer** in exports.

### Task shortcuts
- **Launcher "create shortcut" picker** — a new `CreateTaskShortcutActivity` registers for the system `CREATE_SHORTCUT` flow, so long-pressing the home screen → *Shortcuts* → **白い熊 自由作業盤** opens a **foldable projects → tasks picker** (all projects folded by default); choosing a task drops a home-screen shortcut that runs it.
- The in-app **Pin to home screen** path now uses the task's custom icon (previously always the app icon).
- **`TaskRunActivity` is now exported**, so a third-party launcher (e.g. 白い熊 雷起動盤 / raikidoban) that fires the raw shortcut intent itself can start the task — fixes the launcher's "this shortcut isn't associated with a valid app". Shortcuts carry the task **id + name**, so they survive a task re-import.

### Per-task icon
- Assign a task's icon in its editor from three sources: an **installed app's icon**, a **picture** (photo picker), or an **emoji / glyph** (new `EmojiPickerDialog` — type or tap a quick-pick, with a live preview). The chosen icon is **snapshotted to a PNG** in app storage (`TaskIconStore`) at pick time, so it keeps displaying even if the source picture is deleted or the source app is frozen, and is **baked as a bitmap** into shortcuts so it survives in the launcher regardless of the app's state.
- The icon shows in the **editor preview**, next to the task in the **task list** (folded and unfolded), and on its **shortcut**; no icon set → the app's launcher icon.
- **Global task-icon size** — a new **slider with a live preview** under *UI customization → Task list* (`ThemePrefs.taskIconSizeDp`, 16–96 dp) sizes every task's icon on its card.
- The **app picker** dialog was rebuilt as a **yellow-bordered grid of app-icon tiles** (icon + name), replacing the plain text list — used here and by every app-package field.

### Import / export
- Task icons now **travel across devices**: an export embeds each icon as base64 (`Task.iconData`); import **re-materializes** it into local storage (reusing the existing local file on a same-device re-import). The bundle schema stays **v4** — the field is additive, so older builds ignore it — and `iconData` is never written to the database.

### Schema
- **DB v15** — new `tasks.iconPath` column (the saved icon's path) with a `14 → 15` migration; existing data is untouched on update.

## 0.2.75+11 — 2026-06-23

Re-based the **entire fork onto upstream OpenTasker 0.2.75**, and added **Turn Screen Off**, **Freeze / Unfreeze App** + a **multi-select launcher-task generator**, a **capability-aware action editor**, and the screen-off **通知明滅 wakedance**.

### Re-synced onto upstream 0.2.75
- Rebased the whole fork delta from upstream **0.2.68 → 0.2.75**, keeping every customization (~31 file overlaps resolved by hand). The full pre-resync history is preserved in `backup/custom-0.2.68-pre-resync`.
- Inherited upstream's intervening work: **Locale plugin** interop (both *condition* contexts and a *setting* target), **encrypted DB backup** (AES-256-GCM), a real **Shizuku** elevated backend + **Termux** script dispatch, scene **multi-select / alignment guides / resize**, a **visual flow editor** (zoom, edge routing, branch & subflow markers), `var.persist`, dotted/bracketed `var.set` JSON paths, **RE2/J** linear-time regex, a **Run-Log expression debugger**, and a large batch of concurrency / teardown / schema-drift hardening, plus i18n scaffolding.
- Our **DB schema stays at v14** — no migration when you update; existing data is untouched. Upstream's redundant per-item `group` tag is dropped in favour of our richer project grouping. Gradle dependency-verification is disabled (it only fights local builds).

### New actions
- **Turn Screen Off** (`screen.off`) — accessibility `GLOBAL_ACTION_LOCK_SCREEN` first (no Shizuku needed), Shizuku `KEYCODE_SLEEP` fallback; no longer greyed out.
- **Freeze App** (`app.freeze`) / **Unfreeze App** (`app.unfreeze`) — disable/enable any app through Shizuku (`pm disable-user` / `pm enable`).
- **Make Launcher Tasks** (`tasks.launchers`) — a **multi-select app picker** (all installed user apps, *including frozen ones*, searchable) that writes one **unfreeze-then-launch** task per chosen app into a named project group, **re-sorted alphabetically on every run**, skipping duplicates; the group is auto-created beneath the generator task.

### The 通知明滅 screen-off wakedance
- When a notification arrives **screen-off**, the device now **wakes over the lockscreen** and rotates through every unread app (colour + sender + preview) before sleeping, repeating on a sub-minute timer. Beats EMUI's ~2 s teardown via a `SCREEN_BRIGHT` wakelock, draw-before-wake, an **opaque show-when-locked `WakedanceActivity`**, and a clean self-sleep — no lockscreen or wallpaper flash.
- New engine primitives: a **`sec_tick`** sub-minute event trigger; `state.get screen=on/off`; `wake` / `screen.off` via Shizuku key events.

### Capability-aware action editor
- Each action now shows a **live status pill** — **red** with a one-tap **deep-link to the exact Settings screen** when its permission/service isn't set up, **yellow** (FYI) when it is — evaluated against the **same checks the Setup tab uses** (accessibility, Shizuku, modify-settings, overlay, Do-Not-Disturb, notifications). Consistent across the action picker, the in-task list, and the config dialog.
- New **`APP_PACKAGE`** field type — type a package name / `%variable`, or pick from an installed-apps list.

### Battery, scenes & recomposition
- **Charging detection = `EXTRA_PLUGGED` only** — Huawei lingers `isCharging`/status after unplug, so charging state now follows the plug (covers wireless, drops instantly). The **電池線** battery line turns **solid red while charging**.
- **Per-variable scene recomposition** (`derivedStateOf`) — a scene element re-renders only when *its own* expanded values change, not on every global write, cutting idle overlay CPU.

### Profiles & engine
- Profiles now link their **enter/exit task by NAME** (DB **v14** + migration) with the id as fallback, so re-importing a task — which re-ids it — no longer orphans the profile (“Missing task #N”).
- The **Monitor** tab aggregates engine task-activity and a widget-pull log.

### Docs
- **README** fully rewritten: a two-line title (白い熊 自由作業盤 / ShiroiKuma Jiyūsagyōban), the *Jiyūsagyōban* gloss, the full **Triggers** + **115-action** tables, and fork-vs-upstream feature sections.

## 0.2.68+107 — 2026-06-21

The **通知明滅 notification edge-light** port, a **self-healing always-on engine** with a live **Monitor tab**, new **notification / broadcast / orientation / app-foreground triggers**, the **music edge-light** & a full **edge-gesture** system, **item grouping** across every tab, and reliability fixes for OEM battery management.

### 通知明滅 — notification edge-lights (new project port)
- A notification from a configured app **frames the whole screen in that app's colour** as a permanent edge light; several lit apps share **one frame that cycles** through their colours and titles (~2 s each). Built entirely from tasks + a full-screen tap-through WebView scene, with per-app colours and an `%TSUCHI_*` slot model rebuilt into the cycle list.
- **Three off-paths:** a persistent **“all-off”** control notification (tap → clear every light, keep the apps' own notifications); and **entering an app** (via its notification or the launcher) → that app's light off and its notification dismissed, while the others keep glowing. Ongoing/persistent notifications never light; a notification arriving while you're already in the app doesn't light it; per-app gates (e.g. blink only on missed calls).

### Triggers (new)
- **Notification trigger** — an `EVENT` context (`event=notification`, optional `package` allowlist) fires a profile when a matching app posts a notification.
- **Broadcast (Intent Received) trigger** (`7c7c343`) — fire on any system/app broadcast action, with **typed intent extras** parsed into variables; profiles now **reload live** as you edit them.
- **Device-orientation trigger** (`8060ddc`) — an `EVENT` source for portrait / landscape / reverse changes, exposed as `%DEVICE_ORIENTATION`; orientation is named by the **on-screen** orientation, not the device-natural angle (`4e17243`), fixing foldables.
- **App-to-foreground trigger** (`8060ddc`) — fires when an app comes to the foreground (`%APP_PACKAGE`), fed from the accessibility service so it works where OEM UsageStats is dead (`3bcec99`). Powers the **Previous/Next App** switcher (`bd45920`, `9037117`).

### Notifications
- **`%NOTIF_*` super-globals** (`8bc12c7`) — a posting notification's package, title and body, plus its **ongoing flag** (`%NOTIF_ONGOING`, `4001fb3`), exposed for tasks to read.
- **Per-invocation event vars** (`3a3715e`) — each event now carries its own `%NOTIF_*` snapshot, threaded through the matcher and the **task queue** to the fired task and injected as locals that shadow the shared globals, so a **burst from different apps never mixes up** colours/titles under a QUEUED profile.
- **`notify.show tap_task`** (`c7737de`) — run a task when a notification body is tapped (works while collapsed).
- **Dismiss Notifications** (`notify.dismiss`, `7e3ead6`) — cancel another app's clearable notifications **by package**, via the notification-listener service.
- **`scene.show` expands `%vars`** in element configs at show time (`23f4611`) — so an overlay reflects live globals (the edge-light colour/title).

### Engine & reliability
- **Survives OEM battery management** (`d695587`) — the foreground service holds a partial **wakelock** and a Doze-proof **minute alarm resurrects** it if the process is reaped.
- **Survives coroutine death** (`d181881`) — the engine scope uses a `SupervisorJob` so one failed trigger can't cascade and freeze the rest; a **heartbeat** stamps the per-minute tick and **re-arms** the engine within ~2.5 min if it ever stalls.
- **Auto-run on start** (`d181881`) — pick tasks (e.g. a master “start everything”) to run automatically on every fresh engine start, so overlays/state return after an app update or reboot without manual intervention.

### Monitor tab (new)
- A left-most **Monitor** tab (`fff63ed`) showing engine status / uptime / seconds-since-last-tick, the **overlays actually on screen**, each enabled profile's **real activity** (a trigger firing ≠ its overlay being drawn), and a **history** of every start / re-arm / resurrect — refreshing every second.
- The **“Run on start”** picker (`fff63ed`, `5921666`) — a bordered dialog that groups tasks by project, folds per project, and keeps each project's **manual task order**; every monitor section folds.

### Scenes & edge overlays
- **Music edge-light** (`ec036a2`) — a WebView scene element, a full-screen overlay mode, edge HUDs and custom fonts.
- **Edge-gesture system** — fraction-height edge strips and invisible swipe-only sliders (`291f8f4`); a full edge-bar gesture set with **per-third placement** (`a01678d`); **short/long swipe** + a bottom edge bar, headings honouring fonts (`21959fd`); a **bottom edge bar via an accessibility overlay** that captures the flush gesture-nav area (`ebde11f`); edge-swipe **direction detection** + task-id remap on import (`eb4d91b`).
- **Live sliders, edge-centred panels, tap-outside-close, drag-to-keep-alive** (`45512c4`); a **font picker** in element style (`956db2b`).
- **Battery line charge sweep** now **ping-pongs** (left↔right) instead of snapping back (`d0831ae`).

### Actions
- **Take Screenshot** (`nav.screenshot`, `a2a489d`) — system screenshot via accessibility.
- **Previous App / Next App** (`bd45920`) — switch using the accessibility foreground history.
- **Percent volume / brightness** + **editable dropdown** fields (`446a5da`).
- **Hybrid Back / Recents** (`1380db3`) — accessibility first, Shizuku fallback, with an accessibility-setup row.

### Items, grouping & navigation
- **Grouping on all five tabs** (`c2fcbaa`) with direct **New group / New subgroup**, **nested subgroups** and **foldable per-item notes** (`a5431af`); **drag** rows into groups (`acce065`) or **out** to an *Ungrouped* zone (`e1257d9`); a new group **inherits the item's project** (`326e873`).
- **Project sort toggle, group-delete cascade, collapsed-task quick-run** (`901ee01`); fixed folded-nav covering the screen, **last-tab memory**, and **swipe between tabs** (`e30a42a`); a **Settings link stays** on already-granted permissions (`a68f269`).

### Import / export
- **Overwrite-in-place** (`b0d69cb`) — re-importing a task keeps its id, so profiles and scenes stay linked (no more “Missing task”).
- **Export everything** from the project menu + **timestamped** default filenames (`1340cde`).

## 0.2.68+16 — 2026-06-17

A **battery line** (電池線): a thin bar over the status bar showing charge, built from a new scene element and a full-width overlay mode.

### Scenes
- **Progress-bar element** (`PROGRESS`) — a new scene element type: a horizontal fill bar whose `value` (0–100), `fillColor` and `trackColor` are variable-bound and **re-render live**. A truthy `charging` flag draws a **red sweeping glow** along the fill — advanced by a delay-driven state loop so it animates inside the system-overlay window (where an infinite-transition frame clock doesn't reliably tick) — over a static red tint. It's a first-class element in the editor (palette entry, default size 220 × 12).
- **Full-width overlay** — `scene.show fullWidth=true` shows a non-modal overlay that spans the whole screen width and lays out **over the status bar**, flush to the top edge (`FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`). Scene-card elements sized `widthDp`/`heightDp ≤ 0` fill the card, so a single element can span the entire bar.

### Actions
- **Get Device State** (`state.get`) — charging detection hardened: it now also consults the live `BatteryManager.isCharging`, not only the sticky battery intent's `plugged` / `status`, so charge state is reliable across OEMs.

### 電池線 (battery line)
- These compose into a battery line: a full-width **3 dp** bar at the very top whose length tracks battery %, coloured **amber** normally, **red** ≤ 20 %, **green** at 100 %, with the red charging glow while plugged in — refreshed every minute and updated **instantly** on plug-in/out via a `charging=true` state trigger.

### Docs
- Recorded 白い熊's version-controlled Tasker reference projects directory in `CLAUDE.md` (the porting source for the kanji clock, battery line, etc.).

## 0.2.68+10 — 2026-06-17

Live home-screen widgets and widget UX, on top of the 0.2.68+3 widget/clock system.

### Actions
- **Get Device State** (`state.get`) — read battery % (zero-padded), charging, WiFi-enabled and airplane-mode into variables; no permissions needed. Drives the live status widgets.
- **Toggle Airplane Mode** (`airplane.toggle`) — fixed: the `AIRPLANE_MODE` broadcast is system-only and fails from the Shizuku shell, which previously failed the whole action even though the setting applied. The broadcast is now best-effort, so success tracks the setting write (and dependent widgets update).

### Home-screen widgets & the kanji clock
- **Live status widgets** — WiFi (無線 / 無線無し), Airplane (機内 / 機内無し) and Battery (八割三分 / 全, with a charging line) read real device state every minute; tapping the WiFi or Airplane widget toggles it through Shizuku and the kanji flips instantly.
- **Tap task bound by name** — a widget's tap task is stored by **name** and resolved at tap time, so it survives bundle re-imports (no re-pointing). The widget config now offers a **task picker** dropdown instead of a typed task name.
- **Themed tap feedback** — widget taps give an immediate **vibration** plus a black-and-yellow **Flash** confirmation anchored at the bottom of the screen (a system toast can't be recoloured on a modern targetSdk); failures still surface.
- **Legible preview thumbnails** — template thumbnails render at a canvas scaled to the template's largest font, with `%vars` expanded against the live globals, then scaled down — so big-screen clock templates read as mini widgets instead of a narrow wrapped strip.
- **Wider one-line widgets** — the rendered-bitmap cap was raised 1440 → 2880 px and the one-line time templates set `maxLines = 1`, so a wide one-line widget renders at full width instead of wrapping.

### Theme
- **Serif / Minchō font** — `font: "serif"` (also `明朝` / `mincho`) renders CJK in the built-in serif family (= 明朝 / Minchō), so widgets can use Minchō without importing a font; `"sans"` / `"gothic"` fall back to sans-serif.

### Infrastructure
- Declared the **VIBRATE** permission (widget-tap haptics).

## 0.2.68+3 — 2026-06-16

Rebased onto **OpenTasker 0.2.68** (up from 0.2.60), with a large round of new fork features on top.

### Scenes — a floating-overlay UI builder
- Build interactive overlays from elements and show them with **Show Scene** (`scene.show`) / **Hide Scene** (`scene.hide`) — as a foreground panel or, with “display over other apps”, a **system-wide overlay** that floats over any app and works from background triggers.
- **Element types**: Text, Button, Edit Text, Slider (horizontal & vertical), **Number picker** (− / + stepper), Checkbox, Toggle, Spinner, Image, Rectangle, Oval.
- **Inputs write variables**: sliders, steppers, checkboxes, toggles, spinners and text fields set a `%var` (case-scoped) and run a per-element tap task; shown scenes **re-render live** when a bound variable changes.
- **Styling**: per-element text/label colour, size, bold, alignment, background and border; **panel styling** (background, corner radius, modal scrim, border) with black/yellow theme defaults.
- **`scene.show` options**: position (top/center/bottom), modal vs tap-through HUD, auto-dismiss timeout, dismiss-on-outside — plus **per-scene defaults** so a scene remembers how it likes to show.
- **Editor**: drag-to-move and **drag-to-resize** on a live canvas, a **live styling preview**, element **duplicate** and **z-order** (bring forward / send back), and a project-aware library.

### Home-screen widgets & a template library
- **Set Widget** (`widget.set`) renders a styled bitmap widget from a layout (text / columns / rows, fonts, colours, padding) with a visual **layout editor** — RGBA colour pickers, ± number steppers, per-field sliders, a resizable preview, and **Tasker Widget V2 import**.
- A **Template Library** (new “Widgets” tab) of named layouts referenced by name — edit once, every widget using it updates. File & clipboard import/export; templates also travel inside JSON bundles.
- **Pull / placeholder model**: bind a placed widget to a template, and **Refresh Widgets** (`widget.refresh`) re-renders them all from current variables — no per-widget wiring. Plus a `SET_WIDGET_NAME` broadcast receiver.
- **Fonts**: import `.ttf` / `.otf`, delete from the picker, UTF-8 names preserved.

### Kanji clock (時間と日付)
- A modular, fully app-driven port of the Tasker 勘亭流 kanji clock/date: calc tasks compose spoken-kanji time and date into `%DT_*` variables, two widget templates (clock & date), and a per-minute refresh.
- New **every-minute “clock tick”** EVENT trigger (`event=minute`) to drive it.

### Variables
- **Persistent, project-scoped globals** with case-based scoping: `%ALLCAPS` → app-wide super-global, `%MixedCase` → project-global, `%lowercase` → task-local. Globals now **survive across runs and reboots**. The Vars tab shows super-globals plus the selected project’s globals.

### UI & navigation
- **Uniform top bar** — the project selector now appears on every tab.
- **Name search** on every list tab (Profiles, Tasks, Scenes, Vars, Widgets): a pinned, case-insensitive filter.
- **Multi-select** with batch delete / move-to-project across Profiles, Tasks, Scenes, Vars and Widgets.
- **Help tab** — concepts, variable scoping, the bundle schema and an auto-generated action reference, in collapsible sections.
- **Unified import/export** — one JSON-bundle engine with a per-tab “+” menu (New / Import / Export), a persistent import-result dialog and clearer conflict prompts.
- Foldable cards on every list tab; a horizontally-scrollable bottom navigation.

### Infrastructure
- **Room DB v10**; export **bundle schema v4** (widget templates; per-scene styling & defaults; project-scoped variables).
- CI build workflows disabled (local builds only).

## 0.2.60+35 — 2026-06-15

The action catalogue grew from a few dozen built-ins to **~100**. New built-in actions, grouped:

### Actions — Variables & Arrays (pure logic, no permissions)
- **Variable Clear** (`var.clear`) — unset a variable (and any array of the same name).
- **Variable Split** (`var.split`) — split a value into an array (custom delimiter; empty = per character).
- **Variable Join** (`var.join`) — join an array back into a single value.
- **Variable Search Replace** (`var.replace`) — regex replace; optionally capture matches to an array; ignore-case / multi-line.
- **Variable Convert** (`var.convert`) — upper / lower / trim / length / reverse / capitalize / URL-encode/decode / Base64-encode/decode / MD5 / SHA-1 / SHA-256.
- **Variable Add** (`var.add`) — add a number to a numeric variable, with wrap-around and round.
- **Parse/Format DateTime** (`datetime`) — now / epoch-seconds / epoch-millis / formatted input → a formatted output string.
- **Array Set / Push / Pop / Clear** (`array.set` / `array.push` / `array.pop` / `array.clear`).
- **Array Process** (`array.process`) — sort / sort-desc / numeric / reverse / shuffle / unique / squash.
- **Arrays Merge** (`array.merge`) — concatenate several arrays into one.

### Actions — Dialogs
- **Input Dialog** (`dialog.input`), **List Dialog** (`dialog.list`), **Text Dialog** (`dialog.text`).
- Backed by a transparent host activity + a `CompletableDeferred` result bridge, so the task **suspends until the user answers**; themed black-and-yellow, with an optional close-after timeout, and they cancel cleanly (the task never hangs).

### Actions — Interface gestures (new opt-in `AccessibilityService`)
- **Back** (`nav.back`), **Recents** (`nav.recents`), **Notifications Panel** (`panel.notifications`), **Quick Settings** (`panel.quicksettings`), **Power Dialog** (`nav.power`), **Lock Screen** (`screen.lock`, Android 9+). Global-action only — no screen content is read.

### Actions — Platform
- **Flash** (`flash`) — reworked into a styled overlay window: per-flash text / background / border colours, nine anchor positions with X/Y dp offsets, an **HTML** toggle, and defaults from the UI; falls back to a plain toast without overlay permission.
- **Comment** (`flow.comment`) — a labelled no-op for documenting a task.
- **Set Clipboard** (`clipboard.set`), **Get Clipboard** (`clipboard.get`).
- **Compose Email** (`email.compose`), **Set Wallpaper** (`wallpaper.set`).
- **Open File** (`file.open`) — via a new `FileProvider` over the app sandbox.
- **Move File** (`file.move`), **Create Directory** (`file.mkdir`).
- **List Apps** (`apps.list`), **Keyboard Picker** (`ime.pick`), **Wi-Fi Settings** (`wifi.settings`).
- **Place Call** (`call.place`) — `CALL_PHONE`, else opens the dialer.
- **Profile Status** (`profile.toggle`) — enable / disable / toggle a profile by name.
- **Get Setting** (`setting.get`), **Set Setting** (`setting.put`) — read any of System/Secure/Global; write System via Write-Settings (Secure/Global via Shizuku).
- **Auto Brightness** (`brightness.auto`).
- **Set Volume** (`volume.set`) — per-stream (music / ring / alarm / notification / call / system).

### Actions — Elevated tier (via Shizuku)
- **Run Shell** (`shell.run`) — runs `sh -c <cmd>` with ADB/root privileges; stores `%stdout` / `%stderr` / `%exit`; optional ignore-exit-code.
- Rerouted through Shizuku (now functional, previously stubbed): **Toggle Wi-Fi** (Android 10+), **Toggle Airplane Mode**, **Toggle Mobile Data**, **Take Screenshot** (`screencap`), **Secure/Global Set Setting**.
- New: **Location Mode** (`location.mode`), **Set Keyboard** (`ime.set`).

### Actions — Flagship & flow
- **Send Intent** (`intent.send`) — the reason the fork exists: fire an arbitrary intent (action, category, MIME, data, three `key:value` extras, target package/class, broadcast / activity / service).
- **Named task parameters** — Run Task passes `param:<name>`; sub-task reads `{{ param.name }}` / `%@name`.
- **Return Values** (`task.return`) — return named values to the caller (`%prefix_name`, `%prefix_ok`, `%prefix_error`).
- **Fail** (`flow.fail`) — signal a task error.

### Features
- **Projects** — Tasker-style grouping of profiles/tasks/scenes: data model, DB migration, top-bar switcher, management UI, move-between-projects, per-project filtering, Unfiled catch-all.
- **Per-tab sorting** — Alphabetical or Manual per tab (Profiles / Tasks / Scenes), with long-press **drag-and-drop** reorder; persisted and round-tripped through export/import.
- **白い熊 自由作業盤 UI customization page** — colours (background / text / accent / surface / border), border width, font (incl. imported `.ttf`/`.otf`) and text scale, live; plus a configurable **Flash / toast** section (colours, border width, corner radius, text size, weight) with a live preview.
- **JSON export/import** — versioned bundles (**schema v3**): export all / a project / hand-picked items; per-item, per-project and selective export; conflict prompt (overwrite vs keep-both) and unique-name handling on import; preserves each item's manual **position** and the tab's **sort method**.
- **Editor & workflow** — advanced full-screen, searchable, category-folded **action picker**; **foldable task list**; **full-screen action editor**; **RGBA colour-picker** fields (4 sliders + preview); **drag-to-reorder actions**; task-name picker for Run Task; continue-on-error toggle.

### Changed
- Renamed all user-facing **“OpenTasker” → “白い熊 自由作業盤”** (top bar, export/import dialogs, the home-screen widget, permission prompts, OEM battery-guidance, notifications/channels, NFC/file/backup messages, Shizuku/Termux setup text). Code identifiers, logcat tags, notification-channel IDs and the upstream URL were left intact.
- **Black-and-yellow** as the default theme; pure `#FFFF00` throughout; a yellow border on every dialog; fixed the Material3 1.4 button-label colour so labels render full-strength.
- Capability badges updated: the elevated actions moved from **Unsupported → Requires setup (Shizuku)**.

### Infrastructure
- `applicationId` `shiroikuma.jiyusagyoban`; black-yellow launcher icon; signed release; **side-by-side** install with upstream.
- Added: an `AccessibilityService`, a `FileProvider`, and **Shizuku** (`dev.rikka.shizuku:api` + `:provider`).
- **Room DB v6** — `position` columns on profiles/tasks/scenes; **export bundle schema v3**.
