<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 自由作業盤 app icon" />

# 白い熊 自由作業盤

**“自由作業盤” reads JIYŪ-SAGYŌBAN — a “free workbench”: an open, do-anything automation board.**

**A FOSS, Tasker-style Android automation app — privacy-respecting, no accounts, no cloud.**

A fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) with **major additions**:
**Scenes** (a floating-overlay UI builder with system-wide edge bars & gesture strips),
**notification edge-lights** that frame the screen in an app's colour, an **always-on engine** with a
live **Monitor tab** and self-healing, a **home-screen widget engine** with a template library and a
fully app-driven **kanji clock**, a generic **Send Intent** action to drive other apps, a
**~100-action catalogue** (variables & arrays, date/time, dialogs, accessibility gestures, and a
**Shizuku-powered elevated tier**), **named task parameters & returns**, new **notification /
broadcast / orientation / app-foreground triggers**, **Projects** with **persistent project-scoped
variables**, name search, grouping & multi-select on every list, and a fully-customisable
**black-and-yellow theme**.

Installs **side-by-side** with upstream OpenTasker — package `shiroikuma.jiyusagyoban`, label
**白い熊 自由作業盤**.

**📥 Latest release: [`0.2.68+107`](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban/releases)

</div>

---

## 🎯 Why this fork exists — a generic Send Intent action

The fork was born to **fire arbitrary Android intents** from a task: action string, category, data
URI, MIME type, up to three `key:value` extras, an explicit target package/class, and a
broadcast / activity / service target. That makes 白い熊 自由作業盤 a controller for the sister
apps' token-gated automation intents (e.g. `白い熊 GNU Jami`'s send-message / place-call /
open-conversation intents) — and for anything else on the device that listens for an intent.

---

## 🪟 Scenes — build a floating UI

**Scenes** are interactive overlays you design in-app and pop up from any task — as a foreground
panel or, with “display over other apps”, a **system-wide overlay** that floats over other apps and
fires from background triggers. Build them from **twelve element types** — Text, Button, Edit Text,
Slider (horizontal & vertical), Number picker, Checkbox, Toggle, Spinner, Image, **Progress bar**,
Rectangle and Oval. Input elements **write a variable and run a task**, and a shown scene
**re-renders live** when a bound variable changes — so a scene is a real, reactive control panel, not a
static popup. Style each element (colour, size, weight, alignment, border) and the panel itself
(background, corner radius, modal scrim, border), choose how it shows (`scene.show`: top/centre/bottom,
modal vs tap-through HUD, auto-dismiss timeout, dismiss-on-outside — or per-scene defaults), and arrange
it on a **live canvas** with drag-to-move, **drag-to-resize**, a live styling preview, and
**duplicate / z-order**. A scene can also show as a **full-width bar over the status bar** — the basis
for the **battery line** (電池線): a thin top-edge bar whose length tracks the battery, colours by
level, and shows a red sweeping glow (back and forth) while charging. Scenes also live at the screen
**edges as gesture strips** — per-third edge bars, short/long swipe detection, invisible swipe-only
sliders, and a bottom bar that captures the flush gesture-nav area via an accessibility overlay — and as
a **music edge-light** (音楽端灯) that pulses the screen edge from a WebView element.

---

## 🧩 Home-screen widgets & a template library

A styled **bitmap-widget engine** draws rich layouts (text, rows/columns, fonts, colours, padding)
onto the home screen via the **Set Widget** action, designed in a visual **layout editor** — RGBA
colour pickers, ± number steppers, per-field sliders, a resizable preview, and **Tasker Widget V2
import**. Named layouts live in a **Template Library** (the “Widgets” tab): edit a design once and
every widget using it updates. Widgets follow a **pull / placeholder model** — bind a widget to a
template, pick its tap task from a list (**bound by name**, so it survives bundle re-imports), and
**Refresh Widgets** re-renders them all from the current variables. Templates ride inside the JSON
bundles, and the **`serif`** keyword renders CJK in **Minchō** without importing a font.

---

## 🕐 A kanji clock & live status widgets (時間と日付)

A fully **app-driven** port of a 勘亭流-font kanji clock/date: calc tasks compose the spoken-kanji
**time and date into variables**, an **every-minute trigger** refreshes the widgets, and the whole
set ships as an importable bundle. Alongside it, **live status widgets** read real device state — a
no-permission **Get Device State** action exposes battery / charging / WiFi / airplane, so **WiFi**,
**Airplane** and **Battery** (the percent rendered as 八割三分 / 全, with a charging line) show as
kanji — and tapping the WiFi or Airplane widget **toggles it through Shizuku**, flipping the kanji
instantly with a haptic tap and a black-and-yellow confirmation.

---

## 🔔 Notification edge-lights (通知明滅)

When a notification arrives from an app you care about, 白い熊 自由作業盤 **frames the whole screen in
that app's colour** — a permanent edge light that stays lit until you deal with it. When several apps
are lit at once, one frame **cycles through their colours and titles**. Three ways to clear a light,
all built from tasks: tap a persistent **“all-off”** notification to clear every light at once; or
**open an app** (from its notification or the launcher) to drop just its light and dismiss its
notification, while the others keep glowing. It's a full port of a Tasker edge-light project, powered by
new **notification triggers** — the posting app, title, body and ongoing flag land in `%NOTIF_*`, each
queued notification keeps **its own snapshot** (so a burst from different apps never mixes up colours),
a tap on a notification can **run a task**, and a new **Dismiss Notifications** action clears another
app's notifications by package.

---

## 🩺 An always-on engine — Monitor & self-healing

The automation engine is built to **stay alive** under aggressive OEM battery management. It holds a
wakelock, **resurrects itself** from a Doze-proof minute alarm, and — after the discovery that a single
failed coroutine could silently freeze everything — isolates failures so one dying trigger can't take
the rest down, with a **heartbeat that re-arms** the engine within ~2.5 minutes if its tick ever stalls.
A new left-most **Monitor tab** shows it all at a glance: engine status and uptime, what's actually
drawn on screen, each profile's real activity (a trigger *firing* isn't the same as its overlay being
*up*), and a history of every restart / re-arm. From the same tab, pick tasks to **run automatically on
every start** — set your master “start everything” task and your overlays come back on their own after
an app update or reboot. The trigger set grew too: **notifications**, **broadcasts** (Intent Received,
with typed extras), **device orientation** and **app-to-foreground** now drive profiles, and profiles
**reload live** as you edit them.

---

## 🧰 A ~100-action catalogue

The built-in library was expanded from a few dozen actions to **~100**, grouped into clear
categories in the picker:

- **Variables & Arrays** — Variable Clear, Split, Join, Search-Replace (regex), Convert
  (case / trim / length / reverse / URL / Base64 / MD5 / SHA-1 / SHA-256), Add (numeric, with
  wrap & round); **Parse/Format DateTime**; Array Set, Push, Pop, Clear, Process
  (sort / reverse / shuffle / unique / squash) and Merge.
- **Dialogs** — Input, List and Text dialogs that **pause the task and wait for the user's answer**
  (a transparent host activity bridges the result back), with an optional close-after timeout.
- **Interface gestures** (via an opt-in accessibility service) — Back, Recents, the
  Notifications and Quick-Settings panels, the Power dialog, and Lock Screen.
- **Platform** — Flash (a styled overlay, see below), Comment, Set/Get Clipboard, Compose Email,
  Set Wallpaper, Open File, List Apps, Keyboard Picker, Wi-Fi Settings, Place Call, Profile Status,
  per-stream Set Volume, Get/Set Setting, Auto Brightness, Move File and Create Directory.
- **Elevated tier** (via **Shizuku**) — Run Shell with privileged output capture, plus real
  Wi-Fi / Airplane / Mobile-data toggles, Take Screenshot, Location Mode, Set Keyboard, and
  Secure/Global setting writes. Each degrades to a clear “needs Shizuku” message when it isn't set up.

Every action carries a **capability badge** (Supported / Requires setup / Unsupported) so you know
up front what a task needs to run.

---

## 🔁 Named task parameters, returns & a Fail action

**Perform Task** is no longer limited to shared globals. A caller passes **named parameters**; the
sub-task reads them as `{{ param.name }}` (or the terse `%@name`), returns **named values** via a
**Return Values** action, and signals errors via a **Fail** action. The caller gets back
`%prefix_name`, plus `%prefix_ok` / `%prefix_error` — all properly **call-scoped** (shared globals
and arrays, fresh locals).

---

## 🗂 Projects & persistent variables

Tasker-style **Projects** group your profiles, tasks and scenes; a top-bar switcher (on **every**
tab) filters to the active project, with full management (create, rename, recolour, reorder, move
items between projects) and an **Unfiled** catch-all. **Variables are persistent and project-scoped
by case** — `%ALLCAPS` is an app-wide super-global, `%MixedCase` a project-global, `%lowercase` a
task-local — and globals now **survive across runs and reboots** (so a widget can read them outside
any task). Every list tab also has **multi-select** (batch delete / move-to-project) and a pinned,
case-insensitive **name search**.

---

## ↕ Sorting, grouping & notes

Each list tab has its own sort toggle — **Alphabetical** by name, or **Manual** to
**long-press-drag** a card into any position — and the choice and order **round-trip through
export/import**. Items on all five tabs can be organised into **groups and nested subgroups** (drag a
row in, or out to an *Ungrouped* zone), and each item carries a **foldable note**. Cards **fold to a
name**, and a collapsed card can be **run with one tap**.

---

## 🎨 Black-and-yellow, themed to the pixel

A signature **black background with pure-`#FFFF00` text and borders**, set as the default. The
**白い熊 自由作業盤 UI** page lets you recolour background / text / accent / surface / borders, pick
fonts (including imported `.ttf`/`.otf`), and tune border widths and text scale — all live. The
**Flash** action is a fully-styled overlay: per-flash **text / background / border colours** (via a
4-slider RGBA picker), **nine anchor positions** with X/Y offsets, and an **HTML** toggle — with the
defaults set once in the UI.

---

## ✍️ Editor & workflow touches

- **Advanced full-screen action picker** — searchable, folded by category, each action expandable to
  its description and fields.
- **Foldable task list** — tasks collapse to a name; actions reveal their detail on demand.
- **Full-screen action editor** so you see as many fields at once as possible, with a swatch-based
  **RGBA colour picker** for colour fields.
- **Drag-to-reorder actions** within a task, a **task-name picker** for Run Task, and a
  **continue-on-error** toggle per action.

---

## 📦 Export / Import

Workspaces and individual items export to versioned **JSON bundles** (schema v4 — projects, tasks,
profiles, variables, scenes and **widget templates**): export everything, a whole project, or
hand-picked items; imports prompt on name conflicts, uniquify names, and restore each item's
**manual position** and the tab's **sort method**. A **Help tab** documents the bundle schema and an
auto-generated reference for every action.

---

## Built on OpenTasker

This project is a fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) — a FOSS,
Tasker-style automation app for Android (native Kotlin + Jetpack Compose, Room, WorkManager; no
native code). It's renamed to `shiroikuma.jiyusagyoban` so it coexists with the official build.

All upstream work belongs to the OpenTasker project — see the
[upstream repository](https://github.com/SysAdminDoc/OpenTasker) for issues, contributing and the
canonical source. Released under the [MIT License](LICENSE), as upstream.

## Building

Needs JDK 21 and the Android SDK (`compileSdk 36`, `minSdk 26`).

```
git clone https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban.git
cd shiroikuma-jiyusagyoban
./gradlew assembleRelease
```

A signed release build (for installing side-by-side) uses the gitignored `keystore.properties`; the
unsigned `assembleRelease` / `assembleDebug` outputs build without it. See [`CHANGELOG.md`](CHANGELOG.md)
for the full list of fork additions.
