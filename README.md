<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 自由作業盤"/>

# 白い熊 自由作業盤
## ShiroiKuma Jiyūsagyōban

**A FOSS, Tasker-style Android automation app** — a fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) with major additions.

**📥 Latest release: [`0.2.75+31`](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases)

</div>

“自由作業盤” *Jiyūsagyōban* means: **“FREE (as in Freedom) task launcher”**!

It is a native **Kotlin + Jetpack Compose** automation engine — profiles bind **triggers** to **tasks**, tasks run **actions**, all persisted in Room, no Hilt, no native code. Built on OpenTasker and extended into a markedly more capable tool than OpenTasker — and, in everyday use, than Tasker itself. It installs **side-by-side** with upstream (application id `shiroikuma.jiyusagyoban`), so both can coexist.

> A fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) with major additions: a generic **Send Intent** action, a screen-off **notification wakedance**, **app freeze/unfreeze + launcher-task generation**, a fully app-driven **kanji clock**, **projects + foldable groups + scoped variables**, a **capability-aware action editor**, **home-screen task shortcuts with custom icons**, **Desktop re-freeze bubbles**, home-screen **widgets + a template library**, sub-minute triggers, and a black-and-yellow theme.

---

## What it adds over OpenTasker

### 🎯 Send Intent — fire any Android intent
The reason this fork exists. A generic action that fires arbitrary **explicit or implicit intents** (action, data URI, MIME type, string/int/bool extras, target component, flags) — including the token-gated automation intents exposed by the sister apps (e.g. `白い熊 GNU Jami` / `shiroikuma.jami`: send-message, place-call, open-conversation). `<queries>` manifest entries make the explicit targets resolve on Android 11+.

### 🔔 通知明滅 — the notification wakedance
A per-app coloured **edge-light** for incoming notifications. Screen-on, a frame blinks in the app's colour. **Screen-off, it wakes the device *over the lockscreen* and rotates through every unread app** (colour + sender + preview), then sleeps — repeating on a sub-minute timer. It survives EMUI's aggressive service-reaping with a `SCREEN_BRIGHT` wakelock, draw-before-wake, an opaque show-when-locked Activity, and a clean self-sleep. Powered by new engine primitives: a **`sec_tick`** sub-minute trigger, `state.get screen`, and `wake` / `screen.off`.

### ❄️ Freeze / Unfreeze + the launcher-task generator
**Freeze App** and **Unfreeze App** disable/enable any app through Shizuku (`pm disable-user` / `pm enable`). The **Make Launcher Tasks** action pops a **multi-select grid of app-icon tiles** (all installed user apps, *including frozen ones*, searchable) and, on OK, writes one **unfreeze-then-launch** task per chosen app into a project group — re-sorted alphabetically on every run, with no duplicates. Each generated task takes the app's own icon and is set to show a re-freeze bubble.

### 🫧 Freeze bubbles — re-freeze from the Desktop
A native port of the Tasker 凍結 融解 idea. Any task flagged **Freeze bubble** (toggleable inline on the task card; on by default for the generated launch tasks) drops a small **draggable bubble** when it runs. The bubbles appear **only while your home launcher (the Desktop) is in the foreground** — nowhere else, so nothing intrudes while you work — each showing the app's icon. **Tap a bubble to freeze that app** and remove it; **long-tap to just dismiss**. Bubbles persist across reboots, keep their position relative to the top-right edge across rotation/fold, and are fully styleable (icon size, roundness, label size/weight/font) with a live preview.

### 🔗 Home-screen task shortcuts + custom icons
Drop any task onto the home screen as a **one-tap shortcut**. Long-press the launcher → *Shortcuts* → **白い熊 自由作業盤** opens a **foldable projects → tasks picker** (all projects folded by default); pick a task and it lands as a shortcut that runs it directly — or use the in-task **Pin to home screen**. Give each task its own **icon** from an **installed app**, a **picture**, or an **emoji** — each *snapshotted to a PNG* the moment you pick it, so it keeps showing even if the source picture is deleted or its app is frozen. The icon appears on the task card (folded and unfolded) and is **baked into the shortcut**, with a global **size slider** (live preview) under *UI customization*; tasks with no icon fall back to the app icon. **Tap a task's icon in the list** to change it without opening the editor. Icons even **travel inside JSON exports**, re-materializing on another device.

### 🕐 The kanji clock (時間と日付)
A fully app-driven port of 白い熊's Tasker spoken-kanji clock: calc tasks compose the time and date into variables, **勘亭流-font** home-screen widgets render them, a **per-minute trigger** refreshes them, and live WiFi / Airplane / Battery widgets read device state (with Shizuku toggles).

### 🗂️ Projects, scoped variables & foldable groups
Case-based **variable scoping that survives reboots** — `%ALLCAPS` super-global, `%MixedCase` project-global, `%lowercase` task-local — plus **projects** that file profiles, tasks, scenes and widgets, and **foldable, nestable groups** with drag-and-drop on every tab, per-tab search, and per-tab sorting.

### ⚙️ A capability-aware action editor
Every action carries a **live status pill**: red, with a one-tap **deep-link to the exact Settings screen**, when its required permission/service isn't set up; yellow (FYI) when it is — checked against the *same* state the Setup tab uses (accessibility, Shizuku, modify-settings, overlay, Do-Not-Disturb, notifications). An **app-package** field type lets you type a package / `%variable` *or* pick from an installed-apps list. Build action lists fast: **long-press to multi-select** actions, then **clone / copy / cut / delete** them — with **paste before / after** and an **app-wide clipboard** that moves actions **between** tasks.

### 🔗 Robust by-name linking & imports
Everything links by **name**, not by fragile ids: `scene.show`/`scene.hide` resolve a scene by **`(project, name)`**, and a scene's button/slider/gesture actions resolve their task **by name** too — so re-importing a bundle or recreating a task never silently breaks a link. Imports **overwrite in place** (a re-import keeps each item's id, group and notes), and item names are **unique within a project** (enforced in the editors and at the DB level).

### 📊 Monitor, widgets & theme
A **Monitor** tab aggregates engine task-activity and widget pulls. A styled-bitmap **home-screen widget engine** with a visual layout editor (Tasker Widget V2 import) and a **named-template library**. A black-and-yellow **AMOLED theme** + a UI-customization page, unified JSON import/export, multi-select, and an in-app Help/Docs tab.

---

## Triggers (contexts)

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

## Actions — **115 built-in** (＋ engine flow control)

> Bold = added or materially extended in this fork.

**App (16)** — **Send Intent** · **Launch Intent** · Launch App · **Freeze App** · **Unfreeze App** · **Make Launcher Tasks** · Kill App · Go Home · Next App · Previous App · Open URL · Send SMS · Call · Compose Email · List Apps · Take Screenshot

**System (18)** — **Turn Screen Off** · **Wake Device** · **Run Shell** (Shizuku) · **Show Scene** · **Hide Scene** · **Set Widget** · **Refresh Widgets** · Flash · Vibrate · Reboot Device · Lock Device · Set / Get Clipboard · Set Wallpaper · Set / Pick Keyboard · Profile Status · Log Message

**Settings (18)** — Toggle WiFi · Toggle Bluetooth · Toggle Mobile Data · Toggle Airplane Mode · Toggle Torch · Set / Auto Brightness · Set Volume · Get Volume · Set Ringer Mode · Set Do Not Disturb · Set Screen Timeout · Location Mode · Set Tile State · **Get Device State** (battery / charging-plugged / WiFi / airplane → vars) · Get / Put Setting · WiFi Settings

**Variable (15)** — Set Variable · **Persist Variable** · Variable Clear · **Variable Split** · Variable Join · Variable Add · Variable Convert · Variable Search Replace · Parse/Format DateTime · Array Set / Push / Pop / Clear / Process · Arrays Merge

**Flow (11)** — If · Else · End If · For Each · End For · Run Task · Return Values · Stop · Fail · Wait · Comment

**File (8)** — Read · Write · Append · Move · Delete · List Files · Create Directory · Open File

**Interface (7)** — Back · Recents · Lock Screen · Notifications Panel · Quick Settings · Power Dialog · Take Screenshot *(accessibility global actions)*

**Media (6)** — Play / Stop / Pause Sound · Next / Previous Track · Mute

**Network (5)** — HTTP GET · HTTP POST · Download File · Ping Host · Wake-on-LAN

**Notification (4)** — Show Notification *(tap-task + 3 action buttons)* · Cancel Notification · **Dismiss App Notifications** *(by package)* · Say (Text-to-Speech)

**Alert (3)** — Input Dialog · List Dialog · Text Dialog

**Plugin (2)** — Locale Plugin Setting · Locale Plugin Condition

**Script (1)** — Run Termux Script &nbsp;·&nbsp; **Import (1)** — Unsupported Tasker Action

A **Shizuku-powered elevated tier** unlocks shell, airplane mode, screenshot, location mode, app freeze/unfreeze and more; **Termux** runs hash-pinned scripts.

---

## Also inherited from OpenTasker

So the full surface this builds on is visible: a floating-overlay **Scene** system (11 element types, input→variable binding, a system-wide overlay), a **visual flow editor** (pinch-zoom, edge routing, branch markers), **encrypted DB backup** (AES-256-GCM), **Locale/Tasker plugin** interop (both setting *and* condition), a **Run-Log expression debugger**, dotted/bracketed `var.set` JSON paths, RE2/J linear-time regex, and the full seven-context trigger engine — all retained and carried forward onto upstream **0.2.75**.

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
