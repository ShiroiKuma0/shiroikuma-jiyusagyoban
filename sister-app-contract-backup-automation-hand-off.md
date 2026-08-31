# Sister-app contract — the 保存復元 state-export automation, to be implemented in a SISTER app

> ## ⚠ NOT a to-do for this repo
>
> **自由作業盤 is the *caller* here, and already implements its own side**
> (`core/transfer/StateExportReceiver.kt` + `AutomationAuth.kt`). Nothing in this file is outstanding
> work for `shiroikuma-jiyusagyoban`. It lives here because this is the app that defines the contract,
> and it is **tracked on purpose** — unlike an `hand-off-*.md`, it is permanent and never self-destructs.
>
> **Who implements it:** a **sister app**, typically once it has just been built or is being brought
> onto the 保存復元 batch for the first time.
>
> **Canonical location:** `~/git/shiroikuma-jiyusagyoban/sister-app-contract-backup-automation-hand-off.md`
> **How it is invoked:** 白い熊 says *«Process jiyusagyoban's backup-automation hand-off»* in **a sister
> app's chat** — that chat reads this file from the path above (it is never copied into the target repo)
> and implements it *there*. Any sister app can be brought onto the 保存復元 batch this way.

**Read this whole file, then implement it in the repo this chat is working on** — which, if you are
reading this correctly, is a sister app and **not** `shiroikuma-jiyusagyoban`. It is self-contained:
nothing else needs to be consulted, and no part of it needs editing per app. Every app-specific value
(action strings, category ids, labels) is *derived* from the app itself, as described below.

## Why

白い熊's automation app **白い熊 自由作業盤** (`shiroikuma.jiyusagyoban`, repo
`shiroikuma-jiyusagyoban`) now has a **保存復元** project that backs up every sister app in one run:
it fires a token-gated intent at each app, the app exports itself headlessly, reports progress with
real counts, and replies with the written path and size. 自由作業盤 collects the replies into one
summary (✓/✗ per app, individual sizes, total).

Your app is one of those targets. This document is the wire contract you implement. When you are
done, 白い熊 turns on your app's automation switch, copies its token into 自由作業盤's
「保存復元の設定 -- [979][01]」, and your app joins the batch.

**The reference implementations already in-tree** (read them if anything here is ambiguous):
- `shiroikuma-renrakusaki` — `app/src/main/kotlin/org/fossify/contacts/receivers/BackupContactsReceiver.kt`
  (the EMUI-proven reply round-trip) and `helpers/Config.kt` (token infra).
- `shiroikuma-jiyusagyoban` — `app/src/main/java/com/opentasker/core/transfer/StateExportReceiver.kt`
  (this exact contract, implemented over a Kōjiki-style category ZIP) and `AutomationAuth.kt`.

---

## 1. Wire contract

Let `<pkg>` be **your app's `applicationId`** (e.g. `shiroikuma.memo`). You expose two exported
broadcast receivers' actions:

### `<pkg>.action.EXPORT_STATE`

Run your existing Export (the category-ZIP backup the app already writes from its Export/Import
page) **headlessly** — no Activity, no user interaction.

Incoming extras (**all String**, exactly these names):

| extra | required | meaning |
| --- | --- | --- |
| `token` | yes | must match your stored automation token (§2) |
| `path` | no | absolute **directory**. When present it **overrides** the app's own configured export directory. Create it if missing. |
| `items` | no | comma-separated list of **category ids** to export. Absent/empty = **everything**. |
| `progress_action` | no | broadcast action to send progress on (§3) |
| `reply_action` | yes | action of the reply broadcast |
| `reply_package` | yes | package to `setPackage()` the reply to |
| `reply_id` | yes | correlation id — echo it back verbatim, never interpret it |

Directory precedence: **`path` extra → the app's configured export directory → `ERROR:no-directory`**.

**File name — mandatory family convention (白い熊, 2026-07-25).** Every backup this app writes,
from the automation path **and from its own Export/Import page**, is named:

```
<english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip
```

e.g. `shiroikuma-memo_2026-07-25_18-58-23.zip`, `shiroikuma-jiyusagyoban_2026-07-25_18-58-23.zip`.
The name part is the app's English identifier — the repo / APK basename (`shiroikuma-<app>`), never
the Japanese display name. **No version, no `-export` infix, no `-ui`/`-settings`/`-data` suffix, no
other decoration**: 白い熊 keeps all apps' backups in one directory, so they must sort and read
uniformly. Update your existing `EXPORT_PREFIX` to `"<english-dash-separated-app-name>_"` and drop
the version from the name; if your "latest export" query matches that prefix, let it also accept your
old prefix so previously written backups stay recognised.

**Write it atomically — never leave a half-backup behind.** Write to a temporary name in the target
directory (`<final-name>.part`) and **rename to the final name only after the archive is closed and
complete**. If anything fails, times out, or kills you, delete the partial file on the way out. A
killed export otherwise leaves a file that is indistinguishable from a real backup until someone
tries to restore it — and 白い熊 keeps every app's backups in one directory, sorted by date, so a
truncated one silently becomes "the latest backup" of that app. (自由作業盤 sweeps unreadable archives
before a retry, but that is a safety net, not a substitute: only your app knows the file is doomed at
the moment it fails.) A complete ZIP ends with the end-of-central-directory signature `50 4b 05 06`;
a truncated one does not, which is exactly how the caller tells them apart.

**ONE ZIP per app — always.** However many components your app exports (settings, appearance, a
database, media, learned dictionaries, per-account archives…), a single `EXPORT_STATE` request
produces **exactly one** `.zip` at that one path, with the components as entries inside it. Never
write a second file, never split by category, never emit a companion `.json`/`.db` next to the ZIP,
and never expose separate "UI export" and "data export" files — if your app currently has more than
one export flow, this contract's export is the **union** of them in one archive, and each part is
selectable through `items` (§`LIST_CATEGORIES`, sub-options included). The single path you report
back in the reply must be the whole backup: 白い熊 restores an app by picking that one file.

Writing to an arbitrary absolute path needs All-Files-Access (`MANAGE_EXTERNAL_STORAGE`), which
renrakusaki already declares for exactly this reason. If your app holds it, write with plain
`java.io.File`. If it does not and you should not add it, ignore `path` **only** when you have a
configured SAF directory, and otherwise reply `ERROR:no-storage-access`.

**Check the grant, don't discover it by failing.** Declaring `MANAGE_EXTERNAL_STORAGE` in the manifest
is not holding it — on Android 11+ it is granted from a Settings page, per app, and 白い熊 may well
have declined it. So test `Environment.isExternalStorageManager()` **before** touching `path`, and
reply exactly `ERROR:no-storage-access` when it is false. That precise string is what 自由作業盤 keys
on to offer a **「全ファイルアクセスを許可」** button on the failed row, which opens your app's
All-files-access page directly, so the whole repair is grant → retry, from the report. A raw
`…/foo.zip: open failed: EACCES (Permission denied)` or a generic `export failed: …` is shown
verbatim instead and can only be read, not acted on.

**Never persist an "export in progress" flag, and never let one outlive its export.** Guard against
concurrent exports with a process-local `AtomicBoolean` released in a `finally`. Persist it and a
single crash wedges the app for good; leave it set on a path that hangs rather than returns, and the
`finally` never runs — either way every later request answers `ERROR:export already running` and no
backup is possible until the process is killed. Whatever can hang inside the export must therefore be
bounded (see the heartbeat rule in §3): a guard is only as good as the work it guards terminating.

### `<pkg>.action.CANCEL_EXPORT` — required for anything that can run for minutes

白い熊 must be able to stop a long export from where he started it. The panel's 中止 button used to
only stop 自由作業盤 *listening*: the app carried on to the end, renamed its part-file into place and
delivered a backup that had been cancelled, while its reply arrived with nobody waiting. So an export
that can outlast a few seconds **must** be stoppable from outside.

Declare this action on the **same exported receiver** as the other two. Incoming extras:

| extra | required | meaning |
| --- | --- | --- |
| `token` | yes | must match your stored automation token (§2) |
| `reply_id` | no | the run to stop. Absent = **the export you are running**, which is unambiguous because §1 forbids two at once |

On receipt, and in this order:

1. **Stop the export promptly** — set a `@Volatile` flag the write loop checks between entries/files,
   so it unwinds at the next boundary rather than being torn down mid-write. Do not `System.exit`, do
   not kill your own process, do not interrupt a thread mid-`write()`.
2. **Delete the partial file.** This is the point of the whole action. Whatever you were writing —
   `<final-name>.part` per §1 — is removed on the way out, in the same `finally` that handles any
   other failure. A cancelled export must leave the backup directory **exactly as it found it**: no
   short archive, no stray `.part`, nothing for a later restore to find.
3. **Send the terminal reply for the original request** — `ERROR:cancelled` — through the normal reply
   channel, guarded by the same `AtomicBoolean` so it cannot double-fire with a success. Send it even
   though nobody may still be listening: 自由作業盤 stops waiting the moment it presses 中止, and the
   reply is what proves the run really ended rather than continuing unseen.
4. **Stop the foreground service and release the wakelock**, exactly as on the success path.

The cancel action itself **sends no reply of its own** — it is fire-and-forget. Do not answer it with
`OK:`; the one terminal reply belongs to the export request it stopped.

**It must be safe to send at any time.** A cancel that arrives when nothing is running, or after the
export already finished, is a **silent no-op** — not an error, not a reply, not a crash. 自由作業盤
fires it whenever 白い熊 presses 中止, without knowing how far you got.

```kotlin
// In the receiver — instant, no work, no service handoff needed beyond the signal.
"${'$'}{app.packageName}.action.CANCEL_EXPORT" -> {
    if (!AutomationAuth.enabled(app)) return          // silent: nothing to report to
    if (!AutomationAuth.isTokenValid(app, token)) return
    StateExportService.requestCancel(app)             // sets the volatile flag on the running export
}
```

If your app's own UI already has a stop button (a notification action, a panel button), route both
through the same cancel path so there is exactly one way to unwind — and make sure that path deletes
the partial too. A stop that leaves a `.part` behind is the same bug wearing different clothes.

### `<pkg>.action.LIST_CATEGORIES`

Token-gated, instant. Reply `OK:` followed by one line per exportable category:

```
OK:settings\tSettings (theme · behaviour)
appearance\tAppearance
books\tBook library (3 shelves)
books.covers\tCovers\tbooks
books.notes\tReading notes\tbooks
```

That is `id<TAB>label` per line, with an **optional third field `parent-id`** for sub-options and an
**optional fourth field `default`** (`on` / `off`; absent = `on`) —
a category that itself has selectable parts lists each part as its own line whose third field is
the parent's id (parents must appear on their own line too, before their children). The ids are
exactly the ones accepted in `items` (sub-option ids included, each independently selectable), and
top-level ids should be the **same stable ids your ZIP already uses as entry names** (your
`Cat`/`Category` enum's `id`). **The fourth field is how you say "not by default".** The picker is drawn fresh from *your* answer
every time 白い熊 opens it, so what you mark `off` is what starts unticked — that is the only way an
opt-out part of your app can be opt-out. Mark `off` anything that is **large, derived, and
re-creatable**: cover caches, generated thumbnails, downloaded media, anything rebuilt from data that
is itself in the backup. Everything authored — settings, accounts, notes, annotations, anything that
cannot be made again — stays `on`. When the field is absent the item is `on`, so existing apps need
no change.

Because the default lives in your reply, **`items` absent means "your default set", not "everything"**.
An app 白い熊 has never picked items for exports what you recommend, not your entire footprint.

```
settings	Settings (theme · behaviour)
books	Book library (3 shelves)
books.notes	Reading notes	books
books.covers	Cover images	books	off
bulk	Downloaded media		off
```

(The fourth field is positional: a top-level item that is `off` still needs the empty third field, as
`bulk` shows.)

自由作業盤 renders the list as a checkbox picker with a 全選択
master toggle; sub-options appear indented under their parent and follow its toggle. Flat lists
simply omit the third field. In `items`, treat a parent id WITHOUT its children as "that category's
own data only", and each child id as exactly that part.

### The reply — the ONLY channel that works on this device

Send a **fresh broadcast**, never a binder:

```kotlin
context.sendBroadcast(Intent(replyAction).apply {
    setPackage(replyPackage)
    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    putExtra("reply_id", replyId)
    putExtra("result", result)
})
```

**Hard-won constraints — do not "improve" these:**
- **No `ResultReceiver`, no `PendingIntent`, no `Messenger`** in the reply. EMUI will not reliably
  carry a live Binder into another app's manifest receiver; a broadcast carrying one may be dropped
  outright.
- **Do not rely on the ordered-broadcast result** (`setResultData`). EMUI severs the result channel
  between third-party apps. Setting it too is harmless and correct AOSP behaviour (renrakusaki does),
  but it must never be your only reply.
- Verified on 白い熊's Mate XT, 2026-07-23.
- `FLAG_INCLUDE_STOPPED_PACKAGES` matters: without it a backgrounded/stopped caller never hears you.
- **If your app is frozen** (`pm disable-user`, which 白い熊 uses a lot), it cannot receive the
  request at all — a disabled package's receivers stop resolving. 自由作業盤 handles that itself:
  it reads the freeze state, thaws the app, runs the export, and re-freezes exactly what it thawed.
  Nothing is required of you, except not to assume your process outlives the reply — finish writing
  the ZIP and report the real byte count **before** you reply, never after.

`result` is one line:

| case | `result` |
| --- | --- |
| export OK | `OK:<absolute path>|<bytes>|<human size>|<n> categories` |
| categories OK | `OK:` + the `id<TAB>label` lines |
| anything else | `ERROR:<short reason>` |

Examples:
```
OK:/storage/emulated/0/〇/[979] バックアップ/shiroikuma-memo_2026-07-25_14-02-11.zip|4823711|4.6 MB|3 categories
ERROR:automation disabled
ERROR:bad token
ERROR:no-directory
ERROR:unknown category in items: settings,bogus
```

Rules for the reply:
- **Exactly one** terminal reply per request, guarded by an `AtomicBoolean` so an async success and
  a synchronous error can never both fire.
- Report **`automation disabled`** and **`bad token`** as distinct errors (they debug differently).
- `<bytes>` is the real byte length of the written file; `<human size>` is for display
  (`4.6 MB`, `1.20 GB`); **your app computes both** — the caller cannot stat the file.

### Where the export RUNS — a receiver cannot hold it (this kills apps)

**`goAsync()` does not extend the broadcast timeout.** A manifest receiver — `PendingResult` held or
not — must reach `finish()` within Android's broadcast window: **~10 s when the app is in the
foreground, ~60 s when it is not**. Overrun it and the system raises an ANR against *your* app and
kills the process **mid-export**. Nothing replies, the file is left half-written, and 自由作業盤 waits
for a reply that can never come.

This is not hypothetical — it has happened in this family, to an app exporting several thousand
images: the receiver overran the window, the system ANR'd it, and the batch was left waiting on a
dead process with a half-written archive on disk.

**So: `goAsync()` alone is only ever acceptable for an export that CANNOT exceed a few seconds.** If
your app holds media, covers, thumbnails, attachments, audio, a large database, or simply thousands
of rows — assume it can't. The receiver then does **nothing but**: check the switch + token, validate
`items`, and **start a foreground service** with the request's extras, then return immediately. The
service does the whole export, sends the progress broadcasts, sends the one terminal reply, and stops
itself.

```kotlin
// StateExportReceiver.onReceive — after the token gate, hand off and get out.
val svc = Intent(context, StateExportService::class.java).apply {
    putExtra("path", pathOverride); putExtra("items", items)
    putExtra("progress_action", progressAction)
    putExtra("reply_action", replyAction); putExtra("reply_package", replyPackage)
    putExtra("reply_id", replyId)
}
ContextCompat.startForegroundService(context, svc)   // returns at once; no ANR window left open
```

```kotlin
class StateExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())   // MUST be within 5 s of starting
        val i = intent ?: run { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            val replied = AtomicBoolean(false)
            fun reply(result: String) {
                if (!replied.compareAndSet(false, true)) return
                sendBroadcast(Intent(i.getStringExtra("reply_action")!!).apply {
                    setPackage(i.getStringExtra("reply_package"))
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("reply_id", i.getStringExtra("reply_id"))
                    putExtra("result", result)
                })
            }
            try { /* the export, reporting progress as it goes */ reply("OK:$path|$bytes|$human|$n categories") }
            catch (e: Exception) { reply("ERROR:${e.message ?: e.javaClass.simpleName}") }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
}
```

**An export that runs for minutes needs the OEM to allow it — and will not tell you when it does not.**
On EMUI a foreground service is *not* enough: the system force-releases the app's partial wakelock
seconds after it starts and then starves the process, so the export simply stops part-way, at no
consistent point, with no crash, no ANR and no log. `dumpsys power` shows the giveaway — the wakelock
in the **"Force Released WakeLocks"** list — and `dumpsys deviceidle whitelist` shows the app absent
while apps that survive are present. Two consequences:

- **Check whether you are exempt, and ask if you are not**: `PowerManager.isIgnoringBatteryOptimizations(packageName)`,
  and if false send the user to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` **before** starting a long
  export rather than failing halfway. On Huawei the decisive setting is additionally
  「アプリ起動管理」 → 手動管理 with バックグラウンドで実行 ON, which no app can set for itself — say so
  in the prompt.
- **Prefer not needing it.** An export that finishes in a couple of seconds is never touched. Everything
  that finishes fast in this family does so because it serialises settings and rows; if yours must walk
  thousands of files, that is what puts it in this territory.

Manifest: `<service android:name=".StateExportService" android:exported="false"
android:foregroundServiceType="dataSync" />` plus `FOREGROUND_SERVICE` and (API 34+)
`FOREGROUND_SERVICE_DATA_SYNC`. Call `startForeground()` **inside 5 s** of the service starting or the
system kills it for the same class of reason. Take a **partial wakelock** around the export if it can
run for minutes — EMUI will otherwise doze the CPU with the screen off. Keep exactly one terminal
reply, guarded by the `AtomicBoolean`, wherever it now lives.

---

## 2. Automation token infra (required)

If your app already has an automation token (jami, ongaku, renrakusaki), **reuse it** — do not add a
second one. Otherwise add the renrakusaki pattern:

- SharedPreferences: `automation_enabled` (Boolean, **default false**) + `automation_token` (String).
- Token = 24 random bytes from `SecureRandom`, hex-encoded, **generated lazily on first read** so the
  settings row always shows a value.
- Compare **constant-time**: `MessageDigest.isEqual(candidate.toByteArray(), stored.toByteArray())`.
- Keep the prefs file **out of your own export** (renrakusaki has a `PREFS_EXCLUDE` list; 自由作業盤
  simply keeps it in a prefs file that is not in the export map). The token must never travel in a
  backup ZIP.
Check the switch **and** the token on every request, before doing any work.

### Where the UI goes — **inside the Export/Import section** (not a section of its own)

The automation controls belong **in the existing Export/Import section of your app's 白い熊 UI /
settings page** — the very section that already holds the export-directory row and the
「Export / Import…」 entry — appended directly **below** those existing rows. Do **not** create a
separate "Automation" section, page, or dialog, and do not scatter the controls elsewhere in
settings: this is a backup feature, so 白い熊 finds it where backup lives, and every sister app must
look the same.

Two rows, in this order:

1. **A master switch** — label it for what it does (e.g. 「Automation export」), with a one-line
   description saying that sister-app tasks may trigger this app's export via the token-gated
   intent. **Default OFF.**
2. **A token row** — shows the token abbreviated (e.g. `80922d8c…4c49a87c`), **copies the full token
   to the clipboard on tap** (with a confirmation snackbar/toast), and carries a **Regenerate**
   action on the right that warns pasted copies must be updated.

Nothing is reachable until 白い熊 turns the switch on. Reference implementation (this exact layout):
`shiroikuma-jiyusagyoban` → `app/src/main/java/com/opentasker/ui/screens/UiCustomizationScreen.kt`,
the rows following 「Export / Import…」 in the `SectionHeader("Export / Import")` block, backed by
`core/transfer/AutomationAuth.kt`.

---

## 3. Progress broadcasts (required when `progress_action` is present)

白い熊's explicit requirement: **real numbers, never a percentage.** An app with thousands of books
must report `書籍 1234/8942`; a gigabyte-scale map export must report `512 MB / 4.2 GB`.

While exporting, send plain broadcasts (no reply/no ordering):

```kotlin
context.sendBroadcast(Intent(progressAction).apply {
    setPackage(replyPackage)
    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    putExtra("reply_id", replyId)
    putExtra("app", "<your app's display label>")
    putExtra("item", "books")             // WHICH category id you are writing right now
    putExtra("text", "書籍 1234/8942")   // numbers-first display line
    putExtra("current", 1234L)            // how far through whatever you are counting
    putExtra("total", 8942L)
    putExtra("unit", "書籍")              // what is being counted
    putExtra("bytes", 536_870_912L)       // the second counter: bytes written so far
    putExtra("bytes_total", 4_509_715_660L)
})
```

**`item` is how the panel knows which row is running — send it.** 自由作業盤 draws your categories as a
list and highlights the one in progress. It cannot work that out from `current`, because `current` is
whatever *you* are counting at that moment: categories while you walk them, files or messages while you
write one of them. An app that reported `1234/8942` files while the panel had nine category rows put a
four-digit count against row 1235 and ticked nothing (Jami's chat corpus, 白い熊 2026-07-28). So:

- **`item`** — the **category id** (an `id` from your `LIST_CATEGORIES` reply, sub-option ids included)
  you are writing right now. Send it on every progress broadcast. When the panel sees it, it highlights
  that row and ticks off everything above it.
- **`current`/`total`** are display numbers, **never an index**. Count whatever is honest for the step
  you are on.
- Without `item`, the panel falls back to reading `current` as the **1-based position of the category
  being written** — but only when `total` happens to equal the number of categories it is showing,
  which is exactly the case where your number really is a walk through them. Any other shape is shown
  as text and moves no highlight.

**`bytes`/`bytes_total` are the second counter** — send them whenever you know them (a file corpus, a
media export, anything measured in megabytes). 自由作業盤 renders both pairs on one line —
「ファイル 1234/8942 · 512 MB / 4.2 GB」 — matching what your own export screen shows, so 白い熊 sees
the same two numbers whether the export was started by hand or by the batch. Omit them for a settings
export where the byte count means nothing.

All three are **optional and additive**: an app that sends none behaves exactly as it did before.

- Throttle to **at most one every 500 ms** (and always send a final one at completion).
- `text` is what 白い熊 reads in the notification; make it specific and countable.
  Good: `書籍 1234/8942`, `512 MB / 4.2 GB`, `区分 3/7 — 設定`. Bad: `Exporting…`, `47%`.
- `current`/`total` are `long` extras and `unit` a String — send them alongside `text`, not instead.
- **A progress broadcast is also your HEARTBEAT.** 自由作業盤 treats every one as proof you are still
  alive and gives up on an app that goes quiet — so send one **at least every 30 s even when the
  numbers have not moved** (a single long step: zipping a 2 GB library, one huge attachment). An app
  that reports nothing for two minutes is presumed dead and its slot is failed.
- **A heartbeat is a promise, not a shield.** Because it keeps the caller waiting, an export that
  hangs while still ticking is worse than one that dies: it holds its slot until the full timeout.
  So bound every step that could block — a per-file / per-step timeout, skip-and-continue over what
  will not read (count the skips in your reply) — and reply `ERROR:…` rather than hang.
- **When you count categories, `current` is the POSITION of the one you are writing** — `1` while the
  first is being written, and a final one with `current == total`. It must agree with the label beside
  it in `text`: 「区分 4/9 — Downloaded images」 means image writing is category 4, not that four are
  done. (This line used to say the opposite — that `current` counted what was FINISHED — and the relay
  added 1 to it accordingly, so every app that sends numbers only had its highlight drawn one row too
  far down. No app in the family ever implemented it that way: Handy RSS, 白い熊 音楽, 空中線 and
  応用管理 all report the position. Corrected 2026-07-28.)
- **When you count anything else** — files, messages, rows — count what is FINISHED, and say so in
  `unit`. Those numbers are display only; they never move the highlight.
- **When you do count categories**, `total` must be the number **actually being exported** (after
  `items` filtering), not your full catalogue — that is the number the panel's item list is built
  from, and matching it is what lets the fallback recognise the count as a walk through the list.
- **Name sub-options in `item` too.** The panel now lists selected sub-options indented under their
  group, so an app writing `books.covers` should say so rather than reporting its parent — the row
  that lights up is the part actually being written.
- Your app **may** also show its own progress UI; the broadcasts are what 自由作業盤 displays and
  are mandatory either way.

---

## 4. Manifest

```xml
<receiver
    android:name=".<your package path>.StateExportReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="<pkg>.action.EXPORT_STATE" />
        <action android:name="<pkg>.action.LIST_CATEGORIES" />
        <action android:name="<pkg>.action.CANCEL_EXPORT" />
    </intent-filter>
</receiver>
```

No `android:permission` (the caller cannot hold one) — the token is the gate.

**The cancel must be reachable from outside.** If your stop path lives on a service, that service is
almost certainly `android:exported="false"` — correctly so — and a third-party app cannot start it.
Jami's did, which is why its three local stop buttons could not be triggered by the batch that started
the export. Route the cancel through the **exported receiver** above and have it signal the service
internally.

---

## 5. If your app has no category-ZIP export yet

Build (or finish) it first — it is the thing this contract triggers. The family pattern, already in
17 sister apps: `FORMAT`/`VERSION`/`EXPORT_PREFIX` constants, an `enum class Cat(id, label)`, a ZIP
containing `manifest.json` (format/version/app/appVersion/createdTs/categories) plus one
`<id>.json` per category (type-tagged prefs dump and/or DB rows), a SAF tree URI stored in a
device-local prefs file that is itself never exported, and an import that **merges** per key and
skips absent categories. Nearest references: `shiroikuma-kojiki` (`KojikiExport.kt`),
`shiroikuma-jiyusagyoban` (`core/transfer/SettingsBackup.kt`), `shiroikuma-memo`
(`helpers/SettingsExport.kt`).

Refactor so the export core is callable **headlessly** — a function taking
`(categories, OutputStream, onProgress)` — with the UI panel, this receiver, and (for anything that
can run longer than a few seconds) the foreground service of §1 as thin callers. Do not duplicate
export logic in the receiver, and do not run it there.

---

## 6. How it gets verified — in 自由作業盤, not here

**Do not build a self-test for this.** The contract is exercised where it is actually used: 白い熊
runs 保存復元's 「保存」, which drives every app in the roster and reports each one in a panel — ✓ with
size and path, or ✗ with your exact `ERROR:` line. A failed row opens to the full error and offers
the repair (grant storage access, stop a wedged app, re-run just that app), so a fault in your export
is seen, diagnosed and retried there.

What that leaves for you, once the code is written: make sure the two rows are in the Export/Import
section (§2) and the switch is ON, then tell 白い熊 the token is ready to copy. 白い熊 runs the batch.

If you want a smoke test while developing, the one worth doing is the realistic one — trigger the
export with the app **in the background** against your largest real data set, and confirm it neither
ANRs (`adb logcat | grep -i "ANR in <pkg>"`) nor stops reporting; a small export proves nothing about
the paths that break.

---

## 7. Repo hygiene (unchanged, per-repo rules still apply)

Follow this repo's own `CLAUDE.md` for building, delivery, and versioning. Standing rules that always
apply: no `Co-Authored-By: Claude` or Anthropic attribution in commits; never commit/push unprompted
(build and deliver, wait for 白い熊's «Push»); every `adb push` goes only to `/sdcard/tmp/` with a
full `yyyy-MM-dd_HH-mm-ss` stamp in the filename, superseded copies pruned; run `adb` unsandboxed and
`adb disconnect` at the end of each delivery batch.
