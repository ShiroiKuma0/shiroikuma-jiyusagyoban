# Changelog — 白い熊 自由作業盤

Fork-specific changes layered on top of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker).
This lists what the fork adds; upstream's own history lives in the OpenTasker repository.

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
