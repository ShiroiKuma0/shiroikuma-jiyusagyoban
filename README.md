<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 自由作業盤"/>

# 白い熊 自由作業盤
## ShiroiKuma Jiyūsagyōban

**A FOSS, Tasker-style Android automation app** — a fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) with major additions.

**📥 Latest release: [`0.2.82.2026-08-08.gbd01eebb+001`](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases)

[![version](https://img.shields.io/badge/version-0.2.82-blue.svg)](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases/latest)
[![license](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

> The version names the upstream commit the fork is rebased on:
> `<upstream version>.<base commit date>.g<8-char upstream sha>+<build>`. Upstream's own version
> string stands still for months between releases, so the sha is what says how current this fork is.

</div>

“自由作業盤” *Jiyūsagyōban* means: **“FREE (as in Freedom) task launcher”**!

It is a native **Kotlin + Jetpack Compose** automation engine — profiles bind **triggers** to **tasks**, tasks run **actions**, all persisted in Room, no Hilt, no native code. Built on OpenTasker and extended into a markedly more capable tool than OpenTasker — and, in everyday use, than Tasker itself. It installs **side-by-side** with upstream (application id `shiroikuma.jiyusagyoban`), so both can coexist.

> A fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) with major additions: a generic **Send Intent** action, cross-app **protected contacts** (content-free notifications for private senders), a screen-off **notification wakedance**, **app freeze/unfreeze + launcher-task generation**, a fully app-driven **kanji clock**, **projects + foldable groups + scoped variables**, full **drag-to-reorder** (tasks, projects, project-tabs), a **Review Import** workflow, a **capability-aware action editor**, **home-screen task shortcuts with custom icons**, **Desktop re-freeze bubbles**, home-screen **widgets + a template library**, **living scene overlays** (a charging fire on the battery line, a **natively-rendered, music-reactive, tempo-locked** music edge-light — all screen-off gated), **per-app share-sheet tiles** (each generated as its own signed relay APK, on-device), a **backup-guarded system language switch**, a full **Hume Band V2 health decoder + 「健康」 charts**, **「文字認識」 offline OCR** (share a screenshot, get its text — PP-OCRv5 on-device, six languages, no network, models supplied rather than shipped), **live-wallpaper switching without the picker** (Shizuku sets the component outright), a **full app-state Export/Import** (everything settable as one category ZIP), **one-tap backup of every sister app** (a plan → run → report window: pick apps and items, watch two live panes, repair what failed without leaving it), a headless **adb automation bridge** (workspace export/import broadcasts), **per-SIM speed testing** (real throughput and a real round-trip ping on each SIM and over WiFi, with root-free data-SIM switching), sub-minute triggers, and a black-and-yellow theme.

---

## What it adds over OpenTasker

### 🎯 Send Intent — fire any Android intent
The reason this fork exists. A generic action that fires arbitrary **explicit or implicit intents** (action, data URI, MIME type, string/int/bool extras, target component, flags) — including the token-gated automation intents exposed by the sister apps (e.g. `白い熊 GNU Jami` / `shiroikuma.jami`: send-message, place-call, open-conversation; `白い熊 音楽` / `shiroikuma.ongaku`: favorite-track, delete-track, play-playlist). `<queries>` manifest entries make the explicit targets resolve on Android 11+.

### 🖼️ Wallpapers, without the picker
Switching to a **live wallpaper** is guarded by a `signature|privileged` permission, so the only route an app has is the system preview screen and a confirming tap — which is why the Tasker original drove that screen with AutoInput across six taps, complete with an `If`/`Else` on the system locale because the button's label follows the system language. Shell holds that permission, so with Shizuku the `wallpaper.live` action sets the component outright: under half a second, no picker, no locale branch. Without Shizuku it opens the preview and says so rather than claiming success. `wallpaper.set` covers the still side, including the **lock screen** — which the API leaves alone unless you ask for it by name.

---

### 🔍 「文字認識」 — share a screenshot, get its text

Cut out any part of the screen, share it, and the text comes back — **entirely on the device, with no network**. PP-OCRv5 detection and recognition run on ONNX Runtime against models baked into the APK: **Japanese and English in one model** (so the everyday case never picks a language), plus German, Czech and Polish on a Latin recogniser and Russian on an East Slavic one, each a chip that re-reads the same image without re-detecting it.

The window is built for checking, not just reading. The screenshot on top with every detected line boxed, a three-line field under it holding the text, and one button that copies and closes. **Tap a box on the image and the caret jumps to that line** — the loop between "that character looks wrong" and fixing it is a single gesture. Vertical Japanese is read as vertical: tall crops are turned upright and the columns are emitted right-to-left, because the recogniser was flawless on a vertical sample and still scored 67 % character error purely from the order.

Accuracy is measured, not asserted: **0.00 % character error on Japanese, English, German and Russian**, 5.6 % Czech and 6.9 % Polish, on a corpus with hand-written ground truth. Detection runs at a 1600 px long side instead of PP-OCR's 960 default, which halves the error on a full-width phone screenshot — and the errors it removes are exactly the small text. Both recognition tiers ship: the accurate 81 MB model by default, a 16 MB one that is ~2.5× faster behind a switch in settings, and a per-action override for tasks. The same engine is available to automation as `ocr.recognize`.

---

### 🩺 「健康」 — a Hume Band V2 the vendor app cannot match
The band's whole history, decoded from its own BLE protocol and kept in a database you own. A `band.sync` action pulls heart rate, HRV, blood oxygen, temperature, sleep, steps, calories and distance over GATT; an auto-sync profile runs every four hours; and an append-only JSONL archive means nothing is ever silently overwritten. The buffer depth is **measured, not assumed** — the band ignores the date you ask for and returns its whole ring buffer, so the oldest record it hands back *is* the floor, and the app warns only when a sync could not go through and the headroom is genuinely running out.

「健康」 opens as its own fullscreen window from a task. A sync header with live per-stream progress and **the band's own charge** (printed with the age of the reading, because the band only answers while a sync is connected), a **健康指数** whose every breakpoint and weight is printed on screen, then full-width cards — 歩数, 睡眠, 体温, 心拍, 血圧, 血中酸素, バンド状態指数 — each tapping through to a full-screen view that opens on 24 hours and pinch-zooms across the entire archive. **Long-press any card and one crosshair reads every chart at the same instant** ("heart rate spiked at 03:12 — was I in REM, and did my oxygen dip?"), and it refuses to answer inside a gap rather than inventing a reading. **Long-press and drag across a chart to mark a stretch of time and total it** — steps are summed, because "how many did that walk come to" is the question a step chart provokes and the fixed 1h/6h/24h chips cannot answer it; every other metric reports mean and range instead, since the sum of a heart rate is not a quantity. Underneath each chart, a day-by-day table; under sleep, every recorded night with its extent, duration and four stages.

Four renderers do the drawing — hourly **capsules** spanning each hour's real min and max, blood-pressure **dumbbells** on one shared mmHg axis, a **hypnogram** stepped between raw stage codes with nothing interpolated, and step **bars** where a zero-height bar is a real measurement. The pipeline never averages: outliers are **flagged, never replaced**, and the ✕ marks stay reachable so the chart can prove what it dropped. **Thirty-five chart knobs** (heights, mark weights, opacities, line shape, eleven series colours) live beside the rest of the appearance settings with a live preview that runs the real renderers — and an on-device port of the colour-blindness and contrast validator that says so when a palette stops being readable.

And it is honest about the hardware. Forensic analysis of 2 131 records established that **four of the band's fields are not measurements**: the byte labelled HRV is a device state index (74 % of its variance fixed by two firmware flags, positively correlated with heart rate, carrying no sleep-stage information), blood pressure is generated inside a ±10 mmHg clamp, and "stress"/"vascular age" are a lookup on the same byte. They are drawn where they are still interesting, labelled for what they are, and kept out of the index — with the evidence in the info panel rather than a shrug.

### 🔔 通知明滅 — the notification wakedance
A per-app coloured **edge-light** for incoming notifications. Screen-on, a frame blinks in the app's colour. **Screen-off, it wakes the device *over the lockscreen* and rotates through every unread app** (colour + sender + preview), then sleeps — repeating on a sub-minute timer. It survives EMUI's aggressive service-reaping with a `SCREEN_BRIGHT` wakelock, draw-before-wake, an opaque show-when-locked Activity, and a clean self-sleep. Powered by new engine primitives: a **`sec_tick`** sub-minute trigger, `state.get screen`, and `wake` / `screen.off`.

### ❄️ Freeze / Unfreeze + the launcher-task generator
**Freeze App** and **Unfreeze App** disable/enable any app through Shizuku (`pm disable-user` / `pm enable`). The **Make Launcher Tasks** action pops a **near-fullscreen multi-select grid of app-icon tiles** (all installed user apps, *including frozen ones*) — each tile shows the **package id under a bold label**, search matches **name or id**, and a ⚙ panel makes icon size, both text sizes, bold, and grid padding **settable and persistent** — and, on OK, writes one **unfreeze-then-launch** task per chosen app into a project group — re-sorted alphabetically on every run, with no duplicates. Each generated task takes the app's own icon and is set to show a re-freeze bubble.

### 🫧 Freeze bubbles — re-freeze from the Desktop
A native port of the Tasker 凍結 融解 idea. Any task flagged **Freeze bubble** (toggleable inline on the task card; on by default for the generated launch tasks) drops a small **draggable bubble** when it runs. The bubbles appear **only while your home launcher (the Desktop) is in the foreground** — nowhere else, so nothing intrudes while you work — each showing the app's icon. **Tap a bubble to freeze that app** and remove it; **long-tap to just dismiss**. Bubbles persist across reboots, keep their position relative to the top-right edge across rotation/fold, and are fully styleable (icon size, roundness, label size/weight/font) with a live preview.

### ⚡ Flash bubbles — the flashing apps, one tap away
The 通知明滅 layer's Desktop face. While an app's notification is edge-flashing, its **icon appears down the LEFT edge of your home launcher** (the freeze bubbles' mirror — the two stacks never mix), with a single **全消灯 kill-all icon** pinned below the stack; each newly flashing app slots in above it and pushes it back to the bottom. **Tap** an icon to open the app and kill its flash; **long-tap** to kill the flash — and dismiss the app's notification — without opening it; both gestures are **settable** (open + kill / kill only / open only / dismiss only), as are the kill tasks themselves, so the layer stays generic. The kill-all icon's tap does exactly what tapping the flash-ongoing notification does — and deliberately leaves the per-app icons standing, so you still see what demanded attention. Driven from tasks via five `bubble.*` actions; the per-app kill runs the workspace's own 消灯 task with the bubble's package injected per-invocation.

### 🔗 Home-screen task shortcuts + custom icons
Drop any task onto the home screen as a **one-tap shortcut**. Long-press the launcher → *Shortcuts* → **白い熊 自由作業盤** opens a **floating picker dialog** (a tall, yellow-framed card over a dimmed home screen): each project unfolds into its **groups as bordered folder-boxes** — so a folded group reads as a closed box, never as a parent of the loose tasks below — then its ungrouped tasks, with order preserved and a *Cancel* button at the bottom. Pick a task and it lands as a shortcut that runs it directly — or use the in-task **Pin to home screen**. Give each task its own **icon** from an **installed app**, a **picture**, an **emoji**, or a **song's embedded album art** (pick an mp3/ogg/flac/m4a) — each *snapshotted to a PNG* the moment you pick it, so it keeps showing even if the source is deleted or its app is frozen. The icon appears on the task card (folded and unfolded) and is **baked into the shortcut**; tasks with no icon fall back to the app icon. **Tap a task's icon in the list** to change it without opening the editor. Icons even **travel inside JSON exports**, re-materializing on another device. The picker's font, spacing, indent, and folder-box look are all tunable under *UI customization → Shortcut picker*.

### 🕐 The kanji clock (時間と日付) + 相撲字時計
A fully app-driven port of 白い熊's Tasker spoken-kanji clock: calc tasks compose the time and date into variables, **勘亭流-font** home-screen widgets render them, a **per-minute trigger** refreshes them, and live WiFi / Airplane / Battery widgets read device state (with Shizuku toggles). Its companion **相撲字時計** is a **fold-aware over-lockscreen overlay clock** in **相撲字 (sumo-script)** style: **folded / semi-folded / unfolded** layouts swap automatically with the device's fold state (published as **`%FOLD`** by a HALL-sensor `fold` context), the time is centre-anchored on the wide layouts, and the overlay is **touch-through**. An **app-multiselect picker** chooses which apps hide it.

### 🗂️ Projects, scoped variables & foldable groups
Case-based **variable scoping that survives reboots** — `%ALLCAPS` super-global, `%MixedCase` project-global, `%lowercase` task-local — plus **projects** that file profiles, tasks, scenes and widgets, and **foldable, nestable groups** with drag-and-drop on every tab, per-tab search, and per-tab sorting. The **Variables tab** styles each var like the action editor (blue name, bold value, one line, all sizes/colours settable), folds into **per-scope sections** with live counts and **project-filter pills**, and carries a **"Clean up dead globals"** analyzer that finds and sweeps shadow-copies, orphans, and **dangling** project-globals (rows left behind by a deleted project) — with hard guards that keep a project-scoped name from ever leaking into the global bucket.

### ⚙️ A capability-aware action editor + workspace health marks
Every action carries a **live status pill**: red, with a one-tap **deep-link to the exact Settings screen**, when its required permission/service isn't set up; hidden when it is — checked against the *same* state the Setup tab uses (accessibility, Shizuku — verified **binder-up and granted**, not merely installed — modify-settings, overlay, Do-Not-Disturb, notifications), re-evaluated every time the app resumes. Any task that **cannot run right now** (an unsupported action, or a blocking permission that's really missing) gets a **red ❗ that propagates all the way up**: task row → project filter chips → the Tasks icon in the nav bar — and profiles inherit the mark from the task they run, up to the Profiles nav icon. The **Setup tab opens with a Task-health card** listing every blocked task and exactly what it's missing (one-tap jump to Tasks), so startup breakage is visible from the top level without opening anything. An **app-package** field type lets you type a package / `%variable` *or* pick from an installed-apps list. Build action lists fast: **long-press to multi-select** actions, then **clone / copy / cut / delete** them — with **paste before / after** and an **app-wide clipboard** that moves actions **between** tasks.

### 🛑 An exit that actually exits — and a live inventory of what's running
An automation app is the hardest kind to switch off: stopping the engine achieves nothing when a
Doze-exempt per-minute alarm resurrects it, and the accessibility and notification-listener services are
bound by the *system*, so the process returns within seconds however it is killed. **Exit app fully**
(top-bar ⋮) does it properly. It runs your own teardown tasks — a **Run on exit** list mirroring **Run on
start**, so the app never has to know a project name — then shows you **everything still live** and only
tears down once you confirm. The report comes *before* the shutdown on purpose: a dialog raised after the
app is gone can't be read, and its whole value is naming what should already have stopped. It lands in
the run log too.

Then a **persisted stop flag** holds the line: the resurrect alarm, boot, the quick-settings tile, widget
and shortcut taps, notification buttons, sister-app token intents and the adb bridge all *decline* while
it's set — each writing a `停止中 — refused …` row, so a refused trigger stays debuggable instead of
silently missing. The accessibility service goes **dormant rather than disabled** (disabling would drop
the grant and cost a trip through system settings) and the notification listener unbinds while *keeping*
its grant, rebinding silently on next open. Widgets keep rendering from persisted globals throughout — a
stopped app can be woken to **draw**, never to **run**. Opening the app lifts it; so does a reboot, unless
**Start engine on boot** is off.

The same inventory powers a **Live now** section on the Monitor tab — in-flight tasks, scene overlays,
bubbles, the progress panel, the engine itself, each row stoppable on the spot. Because the moment you
want to look at something that shouldn't be running is rarely the moment you want to quit.

### 🔗 Robust by-name linking & imports
Everything links by **name**, not by fragile ids: `scene.show`/`scene.hide` resolve a scene by **`(project, name)`**, and a scene's button/slider/gesture actions resolve their task **by name** too — so re-importing a bundle or recreating a task never silently breaks a link. Imports **overwrite in place** (a re-import keeps each item's id, group and notes), and item names are **unique within a project** (enforced in the editors and at the DB level).

### 🔥 Living overlays — the charging fire
Scene overlays that are genuinely alive, and free when you can't see them. The **battery line** bursts into a **charging fire**: two red comets glide in from the ends, collide mid-line in a bloom, and breathe back out — red star-glints twinkling at the tips, embers spraying and raining below the line, and a lingering "residual fire" trail where a comet just passed. Everything is strictly **screen-off gated**: overlays stop computing the instant the display goes dark (opt-in per scene, so over-lockscreen effects keep working).

### 🎵 白い熊 音楽 — the music-player pairing
The `音楽端灯` project pairs the workspace with the **白い熊 音楽** player (`shiroikuma.ongaku`, the Felicity fork) through a clean two-way contract: the player broadcasts **play-state and track changes** (title, artist, path, favorite) into `%INTENT_*` variables, and overlay **良 / 削除 buttons** — shown only while the player is foreground and playing — fire its **token-gated automation intents**: toggle-favorite, and a **confirmed delete** (song title + artist in the dialog) that lets the player itself skip, SAF-delete the file, and clean its library. Named **play-playlist tasks** (start playlist X, optionally at track Y) work from home-screen shortcuts. The audio-reactive **edge meteors that used to live here now render natively inside 白い熊 音楽** — beat-locked from the player's own decoder tap (sample-accurate, works under Android Auto and Bluetooth offload) — so this app no longer holds an audio `Visualizer` at all, and playback audio offload is never blocked.

### 🧩 共有アプリ工房 — per-app share-sheet tiles
EMUI's share sheet renders **one tile per package**, so every share entry point this app exposed collapsed under its single tile. The workaround: give each share target **its own installed package** — a tiny **relay APK generated, signed, and installed entirely on the phone**, no PC rebuild. A fixed relay stub (dex + `resources.arsc` + a binary-manifest template in assets) is specialised per target by rebuilding the manifest string pool, swapping the icon, and assembling a hand-rolled aligned zip, then **apksig-signed with an on-device software key** and installed over a Shizuku streaming session. The relay forwards the share to the app, which **unfreezes the target**, hands off the content, and drops a re-freeze bubble. Manage targets in **共有アプリ工房** (add a frozen-inclusive app, set its tile name + icon, Generate / Reinstall / Remove); icons can come from another app's activity icons, an **installed icon pack**, or curated framework drawables.

### 🌐 System language switch — backup-guarded
A one-tap **ja ⇄ en system-locale toggle** (root-less, via `CHANGE_CONFIGURATION` + hidden `updatePersistentConfiguration`) that **reorders** the locale list so every other installed language survives — a naive replace once dropped English. Because a locale change on EMUI once **recreated `contacts2.db` empty and wiped every contact**, the switch is **guarded**: it first backs up all contacts via the sister app `白い熊 連絡先` and proceeds **only** if it gets an `OK:` back — otherwise it buzzes, shows a modal, and refuses to switch. The verify round-trip uses a new **`reply_via=receiver`** Send Intent mode: a **binder-free cross-app reply channel** (plain string extras out, a correlated reply broadcast back to an exported receiver) that works where EMUI drops broadcasts carrying a live `ResultReceiver`/`PendingIntent`. On-tap it shows an immediate flash and a persistent "backing up…" notification through the wait.

### 💾 Full app-state Export/Import — one ZIP, everything settable
The UI page opens with a Kōjiki-style **Export/Import** section: a settable **export directory** (red until set, then yellow, with a live "Last export:" line queried on page open) and a category panel — **Workspace programming** (projects · tasks · profiles · scenes · variables, as the standard full JSON), UI theme + font files, widgets, bubbles, app settings, share tiles, and task icons — each an independent **plain-JSON entry in one ZIP** that merges on import (never wipes). ArcaneChat-style pill buttons, black-yellow result dialogs that **close the whole chain** on success, a **Restart now / Later** choice after import — and the ordinary "+" → Import JSON… flow **accepts the ZIP itself**, importing its workspace entry like the plain export.

### 🗄️ One-tap backup of **every** sister app (保存復元)
The same export runs **headlessly, for every 白い熊 app at once** — and the whole job now lives in one
window that carries it from choosing to reporting.

**It opens as a plan, not a launch.** Every app in the roster is a ticked row; unfold one and its own
items are listed with the app's labels and sub-option indentation, ticked as that app's saved
selection has them. Deselect a whole app, drop a single item, or add one the saved selection leaves
out — with select/deselect-all at both levels. The choice applies to *this run only*; the saved
per-app selections are never touched.

**「個別保存」 opens that same plan with nothing chosen** — for the times you want one or two apps and
not the roster. Tick them, press 保存開始; their items come already ticked from what each app has saved.
A pill above the list shows where the run will write, and tapping it turns the panel into a folder
browser: whatever you pick holds **for that run only**, leaving the configured export directory alone.

**Then the same window becomes the run.** Two auto-following panes — the apps on top, the current
app's items below — with the running row parked five lines down so finished work stays in view,
finished rows ticked and dimmed, and real counts throughout (「アプリ 7/33」, and the app's own
「書籍 1234/8942」 or 「512 MB / 4.2 GB」 — never a percentage), relayed by broadcast while it works.
Which row lights up comes from the app itself — the category id it names as it writes it — so the
marker and the counter under it are always the same thing, and every row the run has passed is ticked.
**中止** stops it within a second — it abandons the reply it was waiting on *and* tells the app to stop,
so the export deletes its half-written archive instead of finishing a backup you cancelled. The whole
window is an ordinary Activity: Home puts it aside while the run continues, and recents brings it back.

**And then the report — in that same window.** Rows stay browsable; open one for its items and the
path it wrote. Open a failed one for the **whole** error and the repair that fits it: grant the app
All-files access, **stop an app the OEM is starving and jump to アプリ起動管理**, or **re-run just
that app** — which first sweeps the half-written archive a killed export leaves behind. Repairs
update the row and the tally in place, so a run that half-failed is finished from the report instead
of started over.

Robustness the batch learned the hard way: a **frozen** app is thawed, exported, and re-frozen
(a `pm disable-user` app cannot receive broadcasts at all, and used to cost the full 600 s timeout in
silence); a `LIST_CATEGORIES` pre-flight fails a dead app in 20 s; and the watchdog judges **progress,
not noise** — an app whose reports stop *changing* is given up on, which catches one that heartbeats
while hung. Every request is gated by a per-app automation token (24 random bytes, constant-time
compared, never included in any backup), off by default.

**「保存項目一括選択」 — deciding once what each app backs up.** A sweep asks every app in the roster
what it can export right now (frozen ones thawed and re-frozen, 中止 available, one progress row each),
so new items and changed labels appear; the same window then becomes an editor with every app's saved
selection ticked. 保存 writes each ticked app's choice back into both the variable and the `[979][01]`
config task, making it the default every later backup starts from — and unticking an app leaves its
saved selection alone, so one app is edited without disturbing the rest.

**保存整理 — the housekeeping half.** The backup directory as a tick-list: one row per app with its
archives newest-first, everything but the newest pre-ticked, per-app and grand totals of both count
and size, and single files toggled by hand. Nothing is deleted until the button, and the same window
then reports what went.

**Adding an app to the roster is one tap.** Ticking it in the app picker is the entire setup: the
workspace writes that app's two settings rows into its own `[979][01]` config task, generates the
per-app wrapper task, and files both alphabetically — because a task can now **edit and extend other
tasks**. `task.addaction` inserts an action into another task only if it isn't already there
(identity = action type + `name` arg) and, in `sorted` mode, re-sorts the whole matched block by a
regex-captured key; `task.exists` lets a task check for a sub-task before calling it; `tasks.sort`
alphabetises a group that grows a generated task per app. All that's left for you is pasting the
app's token.

### 📶 接続 — what each SIM is actually doing
Speed varies by where you are, and this phone carries two SIMs. **Speed Test** measures real download
and upload throughput over **one chosen transport** — it pins its own network, so the leg runs on the SIM
you asked for. Shaped like Ookla's: the **clock** is the limiter rather than a byte count (a size cap that
binds first ends the leg inside TCP slow-start and under-reports a fast link), **eight parallel streams**
because a single TCP stream is bounded by `window / RTT`, the first two seconds excluded from the headline
as ramp, and the nearest server **discovered at run time** from Ookla's own distance-ordered list. Live
figures publish into `%SPD_*` as it runs — instantaneous rate, average, peak, percent and a direction
glyph — so a scene animates without polling.

Throughput is read from the **kernel's per-UID counters**, which advance only when bytes leave the
interface: counting a `write()` as sent measures the socket buffer, not the network, and overstated every
upload. The measurement window closes at the last counted byte rather than when the workers return, and
the leg stops at its deadline instead of draining afterwards — a leg used to run 12 s past its 10 s with
nothing moving, quietly dragging the average down. **Cancel Speed Test** stops a run in under 200 ms.

Latency is a **real round trip** — the TCP handshake to the server the leg will use, taken before the
transfer while the link is idle, since measuring under load reports bufferbloat instead. **Set Data SIM**
and **List SIMs** move mobile data between slots **without root** (through Shizuku), addressing SIMs by
**slot rather than subscription id**, because a phone accumulates stale subIds and a hardcoded one breaks
the day a SIM is re-seated.

Every run is **kept**: where it happened, what it measured, and the fix's age — because indoors a phone
often cannot get a fresh position, and a coordinate worth keeping is not automatically one worth trusting.
The history is a **scrollable HTML page inside a scene** whose rows act: the coordinates open that spot in
**白い熊 地図**, and a pill beside the place name lets a place be **named once and read back on every run
there**. It is written to your own tree as append-only JSON lines, so renaming costs a line and clearing
the name costs a line, and nothing already recorded is ever rewritten.

### 📊 Monitor, widgets & theme
A **Monitor** tab aggregates engine task-activity and widget pulls. A styled-bitmap **home-screen widget engine** with a visual layout editor (Tasker Widget V2 import) and a **named-template library**. A black-and-yellow **AMOLED theme** + a kxkb-styled UI-customization page (text-wide underlined headings), unified JSON import/export, multi-select, and an in-app Help/Docs tab.

---

## Triggers (contexts)

- **7 context families** — application, time, day, location, state, event and plugin
  (`core/model/ContextSpec.kt`). Counted from the enum by `verifyReleaseTruth`, not by hand.

A profile is active while **all** its contexts match. Seven families:

| Family | Fires on |
| --- | --- |
| **Application** | a watched app comes to the foreground (via the accessibility service) |
| **Time** | a clock window (from–to) |
| **Day** | a weekly schedule |
| **Location** | a FOSS geofence (enter / dwell) |
| **State** | device state — battery level, **charging (plugged-in)**, **screen on/off**, WiFi, airplane, … |
| **Event** | one-shot triggers (below) |
| **Plugin** | a Locale/Tasker **condition plugin**'s satisfied/unsatisfied state (polled) |

**Event triggers:** boot · notification posted · NFC tag · calendar event · **app changed** (foreground) · **device orientation / fold** · shake · **sunrise / sunset** · **per-minute tick** · **per-second (sub-minute) tick** · system broadcast · camera / microphone in use · Bluetooth · package added/removed · Quick-Settings tile.

---

## Actions

### Actions (170 registered + 10 engine-handled)

**170 built-in actions** in the registry, plus 10 the engine handles itself (the flow-control
constructs — `flow.if`, `flow.foreach`, `flow.try` and friends — which the runner interprets rather
than dispatching). Counted from `core/RuntimeRegistries.kt`, not by hand: `verifyReleaseTruth`
recomputes both figures from source and fails the build if this line drifts.

> Bold = added or materially extended in this fork.

**App (27)** — **Send Intent** *(＋ `reply_via=receiver` binder-free reply channel, waits up to 600 s)* · **Launch Intent** · Launch App · **Freeze App** · **Unfreeze App** · **Make Launcher Tasks** · **Generate Share Relays** · **Pick Apps → Variable** *(icon-tile grid, pre-ticked)* · **Pick One App → Variable** *(one-tap, restrictable)* · Kill App · Go Home · Next App · Previous App · Open URL · Send SMS · Call · Compose Email · List Apps · Take Screenshot · Archive App · Unarchive App · Publish Shortcut

**System (32)** — **Get Location** *(framework LocationManager, no Play Services; publishes the fix's age)* · **Set Data SIM** *(root-free, by slot, via Shizuku)* · **List SIMs** · **Turn Screen Off** · **Wake Device** · **Run Shell** (Shizuku) · **Show Scene** · **Hide Scene** · **Set Widget** · **Refresh Widgets** · **Flash Bubble Add / Remove / Clear** · **Flash Kill Icon Show / Hide** · Flash · Vibrate · Reboot Device · Lock Device · Set / Get Clipboard · Set Wallpaper · Set / Pick Keyboard · Profile Status · Log Message

**Settings (24)** — Toggle WiFi · Toggle Bluetooth · Toggle Mobile Data · Toggle Airplane Mode · Toggle Torch · Set / Auto Brightness · Set Volume · Get Volume · Set Ringer Mode · Set Do Not Disturb · Set Screen Timeout · Location Mode · Set Tile State · **Get Locale** · **Set Locale** *(reorders the list — keeps other languages)* · **Get Device State** (battery / charging-plugged / WiFi / airplane → vars) · Get / Put Setting · WiFi Settings · Set Zen Rule · Clear Zen Rule · Temporary State *(applies a setting and restores it after a duration, surviving process death)* · Get Keyboard Info

**Variable (25)** — Set Variable · **Persist Variable** · Variable Clear · **Variable Split** · Variable Join · Variable Add · Variable Convert · Variable Search Replace · Parse/Format DateTime · Read Data *(JSON/CSV/XML → vars, path selectors)* · Format / Parse / Add Date-Time · Match / Replace / Split / Join / Substring Text *(linear-time RE2 regex)* · Array Set / Push / Pop / Clear / Process · Arrays Merge · Lookup Contact

**Flow (14)** — If · Else · End If · For Each · End For · Run Task · Return Values · Stop · Fail · Wait · Comment · Try · Catch · End Try *(bounded exponential retry, `FLOW_ERROR_*` handler variables)*

**File (9)** *(＋ `shared` — resolve in your own storage instead of the app sandbox)* — Read · Write · Append · Move · Delete · List Files · Create Directory · Open File

**Interface (8)** — Back · Recents · Lock Screen · Notifications Panel · Quick Settings · Power Dialog · Take Screenshot *(accessibility global actions)*

**Media (9)** — Play / Stop / Pause Sound · Next / Previous Track · Mute

**Network (10)** — **Speed Test** *(per-transport throughput, wire-measured, with a real TCP-handshake ping)* · **Cancel Speed Test** · HTTP Request *(any method, headers, body, timeouts, response capture)* · HTTP GET · HTTP POST · Download File · Ping Host · Wake-on-LAN · Home Assistant Webhook · MQTT Publish

**Notification (5)** — Show Notification *(tap-task + 3 action buttons)* · Cancel Notification · **Dismiss App Notifications** *(by package)* · Say (Text-to-Speech) · Progress Notification

**Alert (4)** — Input Dialog · List Dialog · Text Dialog · **Pick From List → Variable** *(checkbox multi-select with a 全選択 master toggle and indented sub-options)*

**Tasks (4)** — **Add Action to Task** *(idempotent, optionally re-sorting the matched block)* · **Task Exists** · **Sort Tasks** · **Edit Action**

**Plugin (2)** — Locale Plugin Setting · Locale Plugin Condition

**Script (1)** — Run Termux Script &nbsp;·&nbsp; **Import (1)** — Unsupported Tasker Action

A **Shizuku-powered elevated tier** unlocks shell, airplane mode, screenshot, location mode, app freeze/unfreeze and more; **Termux** runs hash-pinned scripts.

---

## Also inherited from OpenTasker

So the full surface this builds on is visible: a floating-overlay **Scene** system (11 element types, input→variable binding, a system-wide overlay), a **visual flow editor** (pinch-zoom, edge routing, branch markers), **encrypted DB backup** (AES-256-GCM), **Locale/Tasker plugin** interop (both setting *and* condition), a **Run-Log expression debugger**, dotted/bracketed `var.set` JSON paths, RE2/J linear-time regex, and the full seven-context trigger engine — all retained and carried forward onto upstream **0.2.76**.

---

## Build & install

Installs alongside upstream (id `shiroikuma.jiyusagyoban`, label 白い熊 自由作業盤). Pure Kotlin/Compose, JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleRelease
```

`minSdk 26`, `targetSdk 37`. Grab a signed APK from the [releases page](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases).

## Built on OpenTasker

A fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) by SysAdminDoc. `master` mirrors upstream (fast-forward only); `custom` carries this fork's work, rebased onto each new upstream release.

## License

MIT — inherited from OpenTasker.
