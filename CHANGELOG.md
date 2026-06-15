# Changelog — 白い熊 自由作業盤

Fork-specific changes layered on top of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker).
This lists what the fork adds; upstream's own history lives in the OpenTasker repository.

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
