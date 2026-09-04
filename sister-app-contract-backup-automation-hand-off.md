# Sister-app contract v2 — automation, and app data that survives a clean phone

> **v2, 2026-09-04.** Two things changed and one thing is new. **The token is now opt-in** — every
> app answers automation out of the box, and 「Use authorization token?」 is an extra a caller may
> be asked for, not the gate. **A data door was added**: a `ContentProvider` that identifies its
> caller and moves the app's own backup through a **file descriptor**, so 白い熊 応用管理 can back
> an app up *with its data* and put it back on a wiped phone. Everything the family learned about
> EMUI — the reply broadcast, the single-fire guard, the foreground service, real progress counts —
> is unchanged, because it was measured rather than guessed.
>
> **If you are implementing this in an app that already has v1**, the delta is §2, §2a and §4.
> Nothing else moves.

---

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

Your app is one of those targets. This document is the wire contract you implement.

**In v2 there is nothing for 白い熊 to turn on and nothing to paste.** The switch ships ON and the
token is off by default, so when you are done your app is already on the batch. That is the point:
the same code has to work on a phone that has just been wiped, where nobody has configured anything
and 応用管理 is restoring your app from an APK and a descriptor.

**And your app gains a second capability**: 応用管理 can back it up *with its data* and put that data
back. That is the §2a half — the reason the token had to become optional in the first place.

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
| `token` | **no** | checked only when 「Use authorization token?」 is on. Sent to an app that does not want one it is **ignored, never refused** (§2) |
| `path` | no | absolute **directory**. When present it **overrides** the app's own configured export directory. Create it if missing. |
| `items` | no | comma-separated list of **category ids** to export. Absent/empty = **your DEFAULT set** — the ones you report as `on` from `LIST_CATEGORIES`, which is not the same as everything. (v1 said "everything" here and "the default set" under `LIST_CATEGORIES`; the two coincided only in apps with nothing opt-out.) |
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
| `token` | **no** | checked only when 「Use authorization token?」 is on. Sent to an app that does not want one it is **ignored, never refused** (§2) |
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

**Audit this while you are in the file — the v2 rollout found repos still on `goAsync()` that plainly
cannot be.** `shiroikuma-kuchusen`'s §1 export held the `PendingResult` across a Realm
`writeCopyTo` its own code documents as *"minutes for a big library"*, so 自由作業盤's batch would
have ANR'd and been killed mid-export: a half-written `.part` left behind, no terminal reply, and the
caller waiting forever. It has been moved to a foreground service. `shiroikuma-yotehyo` reported the
same shape — a calendar with thousands of events on a plain background thread — and flagged it for
the Fossify forks generally. **Neither was found by testing**; both were found by reading the export
core and asking how long its slowest step can take. If your app holds a database snapshot, a media
corpus or an unbounded row count, the answer is "longer than the window", whatever your current
archive happens to weigh.

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

## 2. The gate — a switch that is ON, and a token that is OFF

### What changed, and why it had to

v1 shipped every app **closed**: `automation_enabled` defaulted to false, and a caller also had to
present a 48-character secret 白い熊 had pasted from the app's settings into the caller's.

That is wrong for where this is going. **A pasted secret cannot survive a wipe**, and the case this
whole family now exists to serve is 応用管理 restoring apps *and their data* onto a clean phone,
where nothing has been configured yet and nobody has pasted anything. A gate that only works once
the phone is already set up is no gate for setting the phone up.

So:

| pref key | v1 | v2 |
| --- | --- | --- |
| `automation_enabled` | Boolean, default **false** | Boolean, default **true** |
| `automation_require_token` | — | Boolean, **new**, default **false** |
| `automation_token` | as before | unchanged — still 24 `SecureRandom` bytes hex-encoded, still generated lazily on first read, still **never in your export** |

### The check, in one place

Put it in one function. Two checks written out at each entry point is how "disabled" and "bad token"
drift apart across forty-two apps.

```kotlin
/** null = proceed. Otherwise the exact ERROR: string to answer with. */
fun refuse(context: Context, candidate: String?): String? = when {
    !enabled(context) -> "ERROR:automation disabled"
    requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
    else -> null
}
```

### Write all three flags with `commit()` — because this gate fails OPEN

v2 flipped `automation_enabled`'s default from false to **true**, so a write that never reaches disk
does not fall back to "off": **it falls back to ON.** And 応用管理 force-stops an app the instant it
replies to an import, with `Process.killProcess` — a `SIGKILL`, which leaves an in-flight `apply()`
nowhere to land. Turning an app off is the one action 白い熊 has for shutting a sister app out, and
it is the action most likely to be running near a force-stop. A lost `setEnabled(false)` therefore
silently reopens the door.

The other two are the same shape. A lost `automation_require_token = true` leaves the door not asking
for the token that was just switched on. A lost lazily-generated token is worse still: 白い熊 may
already have pasted the value into a caller, and nothing surfaces the mismatch — the caller simply
begins failing `ERROR:bad token`.

All three are tiny, infrequent writes, so synchronous costs nothing anyone waits on. **This exists in
every repo that took the v2 default without touching its write path.** (`shiroikuma-jisho`, which saw
that the un-flushed write that matters most is not on the import path at all, but in the gate.)

### Idempotent about the token — this is required, not a nicety

**A token handed to an app that does not require one is IGNORED. It is never an error.**

Tokens live in task arguments and workspace variables that outlive the setting they were pasted for.
A caller still sending one — because it was configured last year, or because another app on the
batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off" into "half
the batch mysteriously fails", which is precisely the friction the switch exists to remove.

Constant-time compare (`MessageDigest.isEqual`) stays for the case where the token *is* required.

### Where the UI goes — **inside the Export/Import section** (not a section of its own)

The automation controls belong **in the existing Export/Import section of your app's 白い熊 UI /
settings page** — the very section that already holds the export-directory row and the
「Export / Import…」 entry — appended directly **below** those existing rows. Do **not** create a
separate "Automation" section, page, or dialog: this is a backup feature, so 白い熊 finds it where
backup lives, and every sister app must look the same.

Three rows now, in this order:

1. **The master switch** — 「Automation export」 or your app's wording. **Default ON.** Its
   description should say that sister apps may trigger this app's export *and back its data up*.
   It stays a switch rather than being removed because it is the only way to close one app off, and
   a feature that can be turned on but never off is one 白い熊 cannot retreat from.
2. **「Use authorization token?」** — **default OFF.** One line saying that off means any sister app
   may drive the automation, on means a caller must also present the token below, and that the data
   door checks the caller's identity and signature either way.
3. **The token row — shown only when row 2 is ON.** Abbreviated (`80922d8c…4c49a87c`), **copies the
   full token on tap** with a confirmation, and carries a **Regenerate** action that warns pasted
   copies must be updated. Hidden when the token is not being asked for: a 48-character secret
   sitting under an off switch invites 白い熊 to paste it somewhere it will do nothing.

Reference implementation: `shiroikuma-jiyusagyoban` →
`app/src/main/java/com/opentasker/ui/screens/UiCustomizationScreen.kt` (the rows after
「Export / Import…」) backed by `core/transfer/AutomationAuth.kt`.

---

## 2a. The data door — a provider, a verified caller, and a file descriptor

**This is the new half of v2, and it is what makes a clean-phone restore possible.** It sits
*alongside* the receiver in §1; it does not replace it.

### Why a `ContentProvider` and not another broadcast action

**A broadcast cannot tell you who sent it.** v1's answer to that was the shared secret. Take the
secret away and a broadcast receiver has no idea who is asking — and the caller supplies the
destination an export is written into, so "no idea who is asking" means any app on the phone can
harvest every sister app's data. A provider gets the caller's identity from the framework.

**And a list needs a synchronous answer.** 応用管理 draws a row per installed app before any export
exists. A broadcast round trip per app to fill a list is the wrong shape.

```xml
<provider
    android:name=".automation.AutomationProvider"
    android:authorities="${applicationId}.automation"
    android:exported="true"
    tools:ignore="ExportedContentProvider" />
```

No `android:permission`, and this time for a real reason rather than v1's: a custom permission would
have to be `normal` (any app may hold it) or `signature` (impossible — **every app in this family
has its own keystore**, 42 of them). Neither buys anything the caller check does not.

### Who may call — exact names and a pinned certificate

```kotlin
private val CALLERS = mapOf(
    "shiroikuma.oyokanri"     to "9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc",
    "shiroikuma.jiyusagyoban" to "efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602",
)
```

Three things are checked, in order, and each exists because the one before it is not enough:

1. **An exact name** from that map. **Never a prefix.** What makes `getCallingPackage()` worth
   anything is that a name cannot be taken *while the real package is installed* — package names are
   not a namespace anyone owns, so any sideloaded app may call itself `shiroikuma.evil` and pass a
   `shiroikuma.*` test. Since the caller supplies the descriptor, a prefix check would hand such an
   app every sister app's complete data in turn: **strictly weaker than the token it replaces.**
2. **The uid agrees.** `getCallingPackage()` reflects the caller's *declared* attribution, and
   packages sharing a uid are not distinguished by it. Confirm against
   `getPackagesForUid(Binder.getCallingUid())` — the kernel's answer cannot be borrowed.
3. **The signing certificate matches the pin.** This closes the real gap, which is not
   restore-specific: *whichever caller package is absent from the device is a name anyone can take*,
   and a clean phone is precisely a device where not everything is installed yet. The moment the
   assumption is weakest is the moment it is most needed.

Derive a pin with `apksigner verify --print-certs <that app's signed release APK>`. If a caller's
key is ever rotated its calls stop working and the fix is these constants — that is the intended
failure, because a signing key changing unnoticed is exactly what a pin is for.

Copy `AutomationCallers.kt` from `shiroikuma-jiyusagyoban`
(`app/src/main/java/com/opentasker/core/automation/AutomationCallers.kt`) — it is deliberately
self-contained and app-independent.

### The four methods

All synchronous, all short, **none carries the payload**. Each answers a `Bundle` with a `result`
string in the same `OK:` / `ERROR:` grammar as §1, so a caller has one vocabulary, not two.

| method | does | answers |
| --- | --- | --- |
| `describe` | reads state; exports nothing | the header JSON, below |
| `export` | validates, starts a foreground service, returns | `OK:<job_id>` |
| `import` | same | `OK:<job_id>` |
| `cancel` | signals the running job (`job_id` extra) | `OK:cancelled` |

Extras in: `fd` (`ParcelFileDescriptor`, required for export/import), `token` (optional — see §2),
`items`, `job_id`, `reply_action`, `reply_package`, `progress_action`.

**Never degrade to an implicit broadcast. `setPackage(replyPackage.ifEmpty { null })` is the bug.**
Three repos shipped exactly that line — `shiroikuma-denwa`, `shiroikuma-rindenwa`,
`shiroikuma-yotehyo` — and it reads as defensive when it is the opposite: passing `null` to
`setPackage` does not send a broadcast more widely, it sends one that **no manifest-declared
receiver has been able to hear since API 26**. If `reply_package` is absent there is nobody to
answer, so **do not send at all**; send only when both it and the action are present, and make
`setPackage` unconditional on that path.

**`progress_action` is useless without `reply_package`, and the failure is total.** Every broadcast
you send back — the terminal reply and **every progress line** — must carry `setPackage`. Since
API 26 an implicit broadcast is not delivered to a manifest-declared receiver **at all**, so a
progress broadcast without `setPackage` is not a weak progress broadcast; it is no progress
broadcast. The export runs, finishes, and reports its terminal reply correctly while every progress
line is dropped in silence — a panel row that never moves and then jumps to done, with nothing in
any log to say why. §3's snippet has always carried `setPackage`; the table above listed the two
extras separately without saying the first depends on the second, which is how `shiroikuma-denwa`
shipped a door whose replies set the package and whose progress did not.

**Return a refusal, never throw.** An exception across a binder reaches the caller as a
`RuntimeException` carrying your stack trace, which tells 白い熊 nothing and tells a misbehaving
caller rather more than it should.

**Flush synchronously BEFORE you reply `OK` to an import — `apply()` loses the restore.**
応用管理 force-stops you the instant you answer success (§7), with `Process.killProcess`, which is a
`SIGKILL`. A `SharedPreferences.apply()` still in flight at that moment has only an asynchronous disk
write pending and no orderly shutdown left to flush it, so **the kill that protects the import can
also truncate it** — the restore reports success and the data is gone. It is invisible in testing,
because a hand-run import is followed by a normal lifecycle that flushes properly; only the automated
path kills the process cold. Use `commit()` (or your store's synchronous equivalent — a Hive `flush`,
a Room checkpoint) on every store the import touched, before the reply. It costs nothing: the import
already runs off the main thread. Found independently by `shiroikuma-shoruikanri` and
`shiroikuma-mpvkakucho`; `shiroikuma-jisho` hit the same shape with an unflushed Hive box, and
`shiroikuma-hogu` with a first-run flag it had to write with `commit()` so the force-stop could not
drop it. **Assume your repo has this** — `apply()` is the family's default pattern.

**If your settings layer DEBOUNCES, "flush before you reply" is not enough — wait out the debounce
first.** `shiroikuma-shutokukanri` does not use `SharedPreferences` at all: its settings are a
`MutableStateFlow` persisted through `.debounce(500.milliseconds).onEach { saveData(it) }`, so at the
moment the import finishes **the store has not been asked to write yet**, and a flush sized for an
`apply()` would have been comfortably too short. A restore would have reported success over nothing.
A debounced write-back is an ordinary shape for a settings flow and it is invisible unless you go
looking, so **find your own interval and wait longer than it** — theirs waits 2 s against a 500 ms
debounce, with the figure and its source line recorded in the code so the next reader can check it
rather than trust it. Ask your repo the question explicitly: *does the settings write-back debounce,
and by how much?*

**Commit your own edits AND flush what your framework owes you.** `shiroikuma-kojiki` fixed the
`apply()` in its shared import core and then found two more write paths it did not own — a
Krate-backed `PersistentState`, and the store where rules for not-yet-installed apps park, which is
the clean-phone path specifically. Both use `apply()` internally and neither is reachable by editing
your own `edit()` call. Their answer generalises: **an empty `commit()` per preferences file before
replying**, which blocks on that file's write lock until any queued `apply()` has landed. A repo that
fixes only its own edit still loses the framework's.

**Check every caller before switching `apply()` to `commit()`.** The import core is usually shared
with the hand-run Export/Import panel. `shiroikuma-gauguin` verified both — its data service runs on
an IO scope and its panel wraps the call in `withContext(Dispatchers.IO)` — so the synchronous write
is free on both paths. **An app whose panel imports on the main thread would trade a truncated
restore for an ANR**, which is not an improvement. Look before you swap.

**Reach the editor if you can; use an empty `commit()` only when you cannot.** Where the restore path
owns its `SharedPreferences.Editor`, change that `apply()` to `commit()` — `shiroikuma-renketsujoka`
makes the point that this is the stronger fix, because the write is synchronous by the time anything
is answered. The trailing empty `commit()` is for the other case: a framework store writing with
`apply()` behind your back, which your own `edit()` cannot reach. The mechanism behind both is the
same, and it means **you never have to track which keys were pending**: `SharedPreferences` keeps one
in-memory map per file, an earlier `apply()` has already published into it, and `commit()` writes that
whole map on the calling thread. Flush **every file the restore spans** — `shiroikuma-mise` restores
across two. (Established independently by `shiroikuma-mise`, `shiroikuma-simplex`, `shiroikuma-kojiki`,
`shiroikuma-yotehyo` and `shiroikuma-renketsujoka`.)

**Grep for what the import path starts ASYNCHRONOUSLY — not for `apply()`, and not for the storage
engine.** A repo that searches for `apply()` and finds none can still lose a whole category:

- `androidx.core.content.edit { }` defaults to `commit = false`, so a Room- or DataStore-backed app
  reads as durable at the call site and is not (`shiroikuma-jinsoningen`, whose entire house look
  persists through exactly that). **Grep the restore path for `.edit {`.**
- Worse, and with no `apply()` anywhere to find: `shiroikuma-ongakuots`'s `ui` category restores
  through a state holder doing `_ui.value = sane; scope.launch { dataStore.putString(…) }` —
  fire-and-forget, so the import returns while the write is still in flight. Reply `OK`, caller
  `SIGKILL`s, and the theme silently comes back unrestored with every other category correct and
  nothing reporting a failure.

**A DataStore-only settings layer owes none of this, and can verify and stop.** Preferences DataStore
writes temp-file-then-rename and its `suspend` does not resume until that is durable, so there is no
debounce to wait out and no `commit()` owed (`shiroikuma-universal-installer`). **The trap is a repo
that is part DataStore and part SharedPreferences** — that one still needs the empty `commit()` per
prefs file. And DataStore reached through a fire-and-forget `launch { }` is not DataStore's problem
but the caller's, which is the case below.

Their fix is the shape to copy: the awaitable path belongs on **the class that owns the write**, as an
*additional* `suspend fun persistNow()` that the existing setter delegates to — not as a change to
the fire-and-forget itself, which is deliberate and is what makes a slider repaint on the next frame
rather than after a round trip.

**And swapping one `apply()` does not finish the job.** The bug is "anything the import leaves
un-flushed when the `SIGKILL` lands", so audit **every** write on the restore path, not just the
preferences one. `shiroikuma-nekokan` was already safe on its other two for reasons that do not
generalise — fonts written with a direct `File.writeBytes`, cards through a SQLite transaction
committed before it returns — and makes the point that an app caching anything else in memory and
flushing it lazily has a third instance the one-line fix will not catch.

**An import can weaken a surface that is not on the automation path at all — and that is the shape
to look for.** "Does this app expose an acting operation through automation?" is the wrong question,
because the answer can be **no** while the app is still reachable: the import writes a preference and
something else entirely does the acting.

- `shiroikuma-universal-installer`: `import` writes `auto_confirm_external_install` and the
  root-engine flags, which turn its **already-exported** install activity into a silent installer —
  and step two needs no automation privilege at all, because any app can fire a plain `VIEW` intent
  at an APK. It also silently defeats "always show the install preview", which 白い熊 asked for.
- `shiroikuma-mise`: `import` rewrites `PREFERENCE_INSTALLER_ID`, changing which installer the app
  uses. Milder, same shape.

So when you audit your own import, ask not "can this act?" but **"can this write a preference that
weakens a different, already-exported surface?"** If it can, report it rather than deciding it. A
blanket "keep the token on install surfaces" catches neither of the two above. (Framing by
`shiroikuma-mise`, sharpening its own finding and `shiroikuma-universal-installer`'s.)

**`import` exists ONLY here.** It never gets a broadcast action. An import overwrites an app's data,
and the §1 receiver is `exported="true"` with no permission — an import there would let any app on
the phone wipe any sister app.

### The payload is a descriptor, not a path

**The caller opens the destination and passes a `ParcelFileDescriptor`. You write bytes into it and
nothing else.** Not a path, not a `content://` URI. Three separate things break otherwise, all of
them in 応用管理's backup code:

- **A backup is not a stable directory while it is being written.** 応用管理 writes into a temporary
  path and renames it into place on commit. A directory handed to you is about to be renamed out
  from under you.
- **Encryption is per file.** It encrypts the files it knows about, one by one. A file you dropped
  in yourself is never passed to that step and would sit **in plaintext inside an otherwise
  encrypted backup** — a silent leak of exactly the data the encryption was for.
- **Integrity is the same story.** `checksums.txt` is built per known file. A foreign file is not a
  verification failure; it is simply unverified, so a corrupted archive would be handed back to you
  at restore with no complaint.

A descriptor is also **a capability that expires when it is closed** — the property a URI grant
failed to give us on the 地図 contract, where an explicit `grantUriPermission` was load-bearing and
the revoke needed a five-minute floor.

**Consequence worth having: you no longer need `MANAGE_EXTERNAL_STORAGE`** for the automation path.
That permission was only ever required because v1 handed you an absolute path — and under scoped
storage you probably could not honour one anyway.

Four mechanical rules:

- **`dup()` the descriptor before it leaves the provider call.** The one in the `Bundle` belongs to
  the binder transaction and is closed the moment `call()` returns; a service reading it afterwards
  finds it shut. That is a bug you only see under load.
- **Close your copy in a `finally`.** A leaked descriptor holds the caller's file open, and a
  caller cannot checksum or encrypt a file that is still open.
- **If the service fails to start, close the dup and drop the job before you answer.** The window is
  real: `startForegroundService` from a binder call is a background start, and on API 31+ it can be
  refused outright with `ForegroundServiceStartNotAllowedException` unless the app is exempt from
  battery optimisation. Catch it, close the descriptor, remove the handover entry, and answer
  `ERROR:<reason>` — otherwise the caller's open file is stranded in a map nothing will ever read,
  and the exception crosses the binder as a stack trace instead of a refusal. (`shiroikuma-denwa`
  and `shiroikuma-jinsoningen` both hit this independently; the reference implementation leaked on
  that path until they did.) When 応用管理 reports a failed row whose reason names a foreground
  service start, the fix is the battery-optimisation exemption on that app, not the code.
- **Every exit from `onStartCommand` must go foreground first — including the ones with nothing to
  do.** Once a caller has invoked `startForegroundService`, the platform requires `startForeground`
  within the window whatever the service then decides, and enforces it by killing the process with
  `ForegroundServiceDidNotStartInTimeException`. So the early returns — no intent, unknown job, an
  entry already drained — are the dangerous ones: **a caller retrying with a stale job id crashes
  the target app** instead of being quietly ignored. Guard the call, since by then the start may be
  refused anyway. (`shiroikuma-nekokan`, which found it in this reference as well as its own port.)
- **Go foreground as the FIRST statement, not "before each early return".** The second phrasing
  invites a `startForeground` call per return and one always gets missed — `shiroikuma-ongakuots`
  made the point and `shiroikuma-jinsoningen` supplied the proof: it had covered the stale-job-id
  branch and still had the crash on a null intent, because reading the job id first is the natural
  way to write it (the notification wants `importing` from the same intent). Read the extras
  defensively, go foreground, then validate. And **`stop()` must then `stopForeground(…)`** — once
  every return runs after the foreground call, a bail-out otherwise leaves a live notification and a
  foreground service behind.
- **On a stale or already-claimed job id, stop SILENTLY.** The instinct is to answer
  `ERROR:unknown job`; do not. That id's request has already had its one terminal reply, and a
  second one breaks the single-reply rule the whole contract rests on. (`shiroikuma-universal-installer`,
  which flagged that anyone applying the fix has to choose this deliberately.)
- **These two rules pull in OPPOSITE directions and only one ordering satisfies both.** The handover
  rule wants the descriptor owned before anything that can throw; this one wants `startForeground`
  before anything that can return. `shiroikuma-renketsujoka` applied the first alone and landed
  exactly on the crash. State them as one recipe rather than two rules: **read the extras
  defensively → go foreground inside a `try` → drain `HANDOVER` → then the early returns**, which may
  now safely `stopForeground`/`stopSelf`. Someone will otherwise satisfy each separately and still
  crash.
- **One `handedOff` flag beats one guard per failure.** The window is between draining the map and
  the coroutine taking ownership via `fd.use`, and `startForeground` throwing is only one way to
  leave it — `shiroikuma-ongakuots` leaked on its busy-guard early return as well. Set a flag when
  the coroutine owns the descriptor and close it on every other path out.
- **Drain the handover map inside the same `try`/`finally` that calls `startForeground`.** The rule
  above closes the provider side; this closes its mirror on the service side. If `startForeground`
  throws in `onStartCommand` — a foreground type disagreeing with the manifest, or the same
  not-allowed exception — the throw lands **before** the descriptor is taken out of the map, so it
  stays there held open. Give the descriptor an owner from the moment it arrives and the window
  disappears. (`shiroikuma-hogu`, which had already fixed the provider half and went looking for the
  other one.) It is bounded by process lifetime rather than unbounded, and it cannot fire at all
  when the manifest type and the permission agree — but an app that gets the type wrong leaks the
  caller's descriptor on **every** attempt, which is exactly the app least likely to notice.
- **Spool a large import to disk rather than into memory.** Reading the whole archive into a byte
  array to sniff it is fine for a settings ZIP and fatal for an app whose archive carries a corpus —
  `shiroikuma-handyrss` ships roughly 3,700 images. Stream the descriptor to a cache file, validate
  there, then apply. The guarantee is unchanged (nothing is written until the whole archive has
  arrived and been checked); only the bound moves from RAM to disk.

### `describe` runs before your app exists — keep it off the DI graph

A `ContentProvider.onCreate` runs **before `Application.onCreate`**, and the provider is published
before it too. So a `call()` can be dispatched on a binder thread while the main thread is still
inside `startKoin`, Hilt initialisation or whatever your app does at startup — and this is not an
edge case, it is **the clean-phone case**, where a provider call is what starts the process at all.

`describe` must therefore answer from things that exist at that instant: the manifest, a plain enum,
`SharedPreferences` read directly. **Not from a DI-resolved backup engine.** `shiroikuma-shutokukanri`
found its gate was a Koin `object` doing `by inject<Context>()` and had to split it so `refuse` reads
preferences with no DI at all; `shiroikuma-ongakuots` kept `describe` off the graph deliberately for
the same reason. Everything heavier belongs in the service, whose `onStartCommand` is queued behind
`Application.onCreate` and is therefore safe.

If your app builds its global state in a `ContentProvider` of its own, declare the automation
provider **after** it — providers are created in manifest order (`shiroikuma-shoruikanri`).

### Progress on the data door — §3 applies here too

**An export driven through `export` reports progress exactly as §3 specifies**, whenever the caller
passed a `progress_action`. This was left implicit in the first draft of v2 and it was wrong to:
§3's own rule is that **an app silent for two minutes is presumed dead**, and an archive that takes
minutes to write is precisely the case §2a exists for. Three repos added it independently during the
rollout rather than let the caller time out, which is the sign of a gap in the contract, not of
three chats over-reaching.

Use the **`job_id` as the correlation id**, and set it in both the `job_id` and `reply_id` extras so
one progress reader serves both doors. Everything else is §3 unchanged: real counts and never a
percentage, at most one message per 500 ms, a mandatory final message, and the heartbeat. A caller
that passes no `progress_action` gets nothing, so this is purely additive.

**A throttle is not a heartbeat, and confusing them fails the two-minute rule.** They solve opposite
problems: the throttle caps a chatty engine at one message per 500 ms; the heartbeat covers an engine
that is not chatty at all. `shiroikuma-ongakuots`'s export calls back **once per category**, so its
`downloads` category ticks once and then says nothing for however long several gigabytes take to zip
— silent well past two minutes, with a correctly-implemented throttle in place. If your engine
reports per category rather than per item, add a separate timer that **re-sends the last true line**
every 10–30 s. Do not invent a moving number to look busy: a fabricated count is worse than a
repeated one, because it cannot be distinguished from progress. Several repos judged their own export
too short to need one and said so — that is the right way to decide it, per app rather than by rule.

**But "my export is fast" is not sufficient reasoning on the data door.** `shiroikuma-shutokukanri`
made the point that settles it: §2a writes into **a descriptor the caller supplied**, which may be a
pipe — so a category blocks for exactly as long as 応用管理 is slow to drain it, and that stall has
no relation to how much data you have. A heartbeat on the data door is therefore justified even for
an export that could never stall on its own. The §1 path, writing to a local file, keeps whatever it
already does.

If your app already has a §1 progress sender, **parameterise that one on the correlation-id extra
rather than writing a second**. Two implementations of the same watchdog drift, and the one that
drifts is always the one nobody is looking at.

### The header — returned from `describe`, and NOT put inside the archive

```json
{"app_id":"shiroikuma.memo","version_code":14,"version_name":"1.7.0+14",
 "format":1,"min_format_readable":1,"requires_launch_first":false,
 "requires_permissions":[],
 "contains":["Notes","Settings","Widgets"]}
```

Outside the archive deliberately: 応用管理 must draw a list row **before any export exists**, and at
restore must judge compatibility **before** streaming tens of megabytes into an app that would
reject them — which it cannot do if the header is buried inside an encrypted archive.

`contains` is short human strings rendered verbatim, so each app describes itself.

**`requires_permissions` — a hole in the restore path, found during the rollout.** The order in §7 is
**install → do NOT launch → import → force-stop**, and a freshly installed app has **no runtime
permissions granted**. So an app whose data lives behind a permission-guarded system provider —
contacts, calendar, SMS, call log — cannot write it: `shiroikuma-renrakusaki`'s `contacts` category
fails with a `SecurityException` for want of `WRITE_CONTACTS`, while its settings categories restore
perfectly. It fails honestly, never as a false success, but the failure arrives **after** the archive
has been streamed.

`requires_launch_first` does not describe this, and saying it does would be wrong: launching the app
is not sufficient either, because what is missing is a **grant**, which only 白い熊 can give. So list
the permissions your import needs as an array of Android permission names, empty when it needs none.
応用管理 can then ask for them **before** streaming rather than reporting a failure afterwards. Raised
by `shiroikuma-renrakusaki`, which flagged it rather than inventing the field unilaterally — the
right call, and the reason it is specified here once instead of four incompatible ways.

**Do not assume it from the app's category.** I first wrote that four repos were affected — contacts,
calendar, messages, phone — and `shiroikuma-yotehyo` checked rather than accepting it: its events and
tasks are **the app's own Room rows**, restored through the ICS importer with `SOURCE_IMPORTED_ICS`,
and `insertEvents` skips the `CalendarContract` write for that source and again unless CalDAV sync is
on, which is false on a fresh install and never exported. Its manifest's calendar permissions serve
CalDAV, which the restore path never enters. So the answer there is `[]`, and 応用管理 should not
prompt. **The question is which provider your import actually writes to, not what kind of app it
is** — an app that owns its data in Room needs nothing, and only one writing through a
permission-guarded system provider does.

**Version skew has a direction.** Old data into a newer app is normally fine, because an app migrates
its own storage. Newer data into an older app is not. `min_format_readable` is what lets a restore be
refused at discovery time rather than halfway through.

### Capability discovery — manifest `<meta-data>`, readable without waking you

```xml
<meta-data android:name="shiroikuma.automation.contract"   android:value="2" />
<meta-data android:name="shiroikuma.automation.format"     android:value="1" />
<meta-data android:name="shiroikuma.automation.min_format" android:value="1" />
```

Not a query intent, because **a frozen app cannot be asked anything** — and 白い熊 freezes
aggressively. 応用管理 builds this list across every installed package at once and must be able to
answer "can this app be backed up" for an app that is currently disabled. It reads these with
`MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS`; without those flags frozen apps vanish
from the list entirely.

An app **without** these entries is simply not offered for data backup or restore. That is the
opt-out: implement nothing and nothing is claimed on your behalf.

### One device fact that will mislead you

白い熊's phone is a **Mate XT running EMUI 14.2 — based on Android 13** — and it reports
**`SDK_INT = 31`**.

Version checks are fine for *API availability*. Do not gate **behaviour** on them: EMUI is Huawei's
own platform rather than a stock Android of any one level, so a `SDK_INT >= 33` branch will not run
here, and code assuming a wholesale Android 13 platform would be wrong in the other direction. Ask
the platform the real question instead of the version one, and measure on the device.

### `<queries>` — or your reply is never heard

```xml
<queries>
    <package android:name="shiroikuma.oyokanri" />
    <package android:name="shiroikuma.jiyusagyoban" />
</queries>
```

Without it your reply broadcast's `setPackage` **fails silently** on Android 11+. The export runs,
writes correctly, and is never heard of.

**The audit is `grep` for BOTH package names. Nothing weaker works.**

Five repos failed this five different ways during the v2 rollout, and the last two are the reason a
presence check and a contents check are both useless:

| repo | what it had | why it looked fine |
| --- | --- | --- |
| `shiroikuma-gauguin`, `shiroikuma-sokki`, `shiroikuma-rindenwa` | no `<queries>` element at all | nothing to see |
| `shiroikuma-handyrss`, `shiroikuma-jisho` | `<package>` for 応用管理 only | the element is there and looks deliberate |
| `shiroikuma-kxkb`, `shiroikuma-simplex` | an element holding only `<intent>` filters, no package | passes a presence check |
| `shiroikuma-raikidoban` | an element naming neither caller | passes a presence check |
| `shiroikuma-yotehyo` | **two** elements naming `org.fossify.*` packages only | well-formed, non-empty, deliberate-looking |
| `shiroikuma-shosekietsuran` | **two** elements (TTS and dictionary), neither naming a caller | looks the healthiest of all |

And **a missing entry can be invisible because something unrelated is covering for it**:

- A `<queries><intent>` carrying a bare `MAIN` action with no category **matches every app with a
  launcher activity**, so both callers are visible by accident. `shiroikuma-tenki` was saved by an
  upstream *icon-pack* query line; its §1 weather replies have demonstrably been arriving, because
  the band's forecast push was measured and refined against what came back.
- A repo declaring `QUERY_ALL_PACKAGES` for its own purposes sees everyone (`shiroikuma-kakutoku`,
  which needs it to track installed versions).
- **Interacting with you grants visibility implicitly**, so any caller that has reached you once
  stays visible afterwards.

In all three the defect is **latent rather than live** — and the way it bites is that someone later
deletes an unrelated `<intent>`, or tightens a permission, and a path nobody knew was load-bearing
goes silent. Declare the packages so it rests on nothing. Conversely, **do not assume a repo with a
`<queries>` gap has a broken path**: if you were attributing a symptom to this, check whether a
bare-`MAIN` intent was covering it, because the symptom then has another cause still unfound.

**BOTH callers, always — this is a v2 correction.** Until 2026-09-04 this snippet named only
応用管理, which is wrong and was wrong in v1 too: §1's batch is driven by **自由作業盤**, so an app
listing only 応用管理 answers the restore caller and is inaudible to the one that has been calling it
all along. Found during the v2 rollout by `shiroikuma-gauguin`, whose fork carried no `<queries>`
element at all — so its §1 replies had been silently discarded for as long as it has existed.
**Audit your own manifest for the element's presence, not just its contents** — an absent
`<queries>` fails exactly like a wrong one, and produces a receiver that works perfectly in every
test that does not check whether the answer arrived.

A third caller is a one-line addition here, so add the package rather than reaching for
`QUERY_ALL_PACKAGES`, which is a Play-policy-restricted permission and buys nothing this needs.

**Two reasons a missing entry can look harmless, and why neither is a defence.** Interacting with
you grants visibility implicitly, so a caller that has already reached you once is visible
afterwards — and a repo that declares `QUERY_ALL_PACKAGES` for its own purposes (`shiroikuma-kakutoku`
tracks installed versions) sees everyone regardless. In both cases the defect is **latent rather
than live**: the §1 batch works, and nothing indicates that the identity check is resting on a grant
this contract does not control. Declare the packages so it rests on nothing.

### Restore rules — mostly 応用管理's job, but know them

You do not implement these; they are stated so you do not fight them.

- **応用管理 force-stops you the instant you reply success to an import.** This is not rudeness: a
  running process writes its cached `SharedPreferences` back out at orderly shutdown and **silently
  undoes the import that just happened**. 応用管理 already had to solve this for itself with
  `Process.killProcess(myPid)` — explicitly not `Runtime.exit`. The guarantee lives on its side so
  that forty-two apps do not each have to remember it.
- **The order is: install → do NOT launch → import → force-stop.** Many apps write defaults on first
  run and would then merge badly against them. If your app genuinely cannot accept an import before
  it has been launched once, say so with `"requires_launch_first":true` in your header — it should
  be the exception and it has to declare itself.
- **A freshly installed app is in the stopped state.** Only `FLAG_INCLUDE_STOPPED_PACKAGES` reaches
  it by broadcast; a provider `call()` starts it. This is the single assumption the whole restore
  path rests on.
- **Frozen and suspended.** 応用管理 unfreezes, exports, and refreezes exactly what it thawed. This
  is not hypothetical: **270 packages are frozen on 白い熊's phone** (`pm disable-user`,
  `enabled=3`), so "Could not find provider" is the ordinary case for a callee, not a defect — thaw,
  test, and re-freeze exactly what you thawed (`shiroikuma-mise` did this to smoke-test its door).
  **The suspension caveat that stood here was wrong**, and 雫 corrected it: 応用管理 already holds
  `DELEGATION_PACKAGE_ACCESS`, which is the scope carrying `setPackagesSuspended`, and a delegate's
  call is attributed to the *delegating admin* — so it acts **as** the admin the suspension is
  recorded under rather than racing it. The unsuspend → export → re-suspend cycle therefore belongs
  on 応用管理's side, mirroring what it already does for freeze/thaw; it needs no round trip to 雫
  and keeps working with 雫 force-stopped, because `system_server` holds the delegation. Two
  caveats: `setPackagesSuspended` returns only what it could **not** change and there is no
  per-suspender readback, so 応用管理 must **record what it lifted before lifting it**; and this is
  read from the code rather than measured, because the Mate XT — the phone the batch actually runs
  on — has no Device Owner at all, so the caveat is inert there and could only be verified on the
  razr.
- **v1 of this is user 0 only.** A `ContentResolver.call()` goes to the calling user.

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

No `android:permission` on the receiver. In v1 the token was the gate; in v2 the receiver is the
**unauthenticated** half of the surface, and that is deliberate — it only ever *writes where you were
told to* and reports what it did. Everything that moves data through a caller-supplied descriptor
lives behind the provider in §2a, which knows who is calling.

**And the v2 components** (see §2a for what each is for):

```xml
<provider
    android:name=".automation.AutomationProvider"
    android:authorities="${applicationId}.automation"
    android:exported="true"
    tools:ignore="ExportedContentProvider" />

<service
    android:name=".automation.AutomationDataService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Automation data export/import" />
</service>

<meta-data android:name="shiroikuma.automation.contract"   android:value="2" />
<meta-data android:name="shiroikuma.automation.format"     android:value="1" />
<meta-data android:name="shiroikuma.automation.min_format" android:value="1" />
```

**Those three values are INTEGERS, not strings, and the reader must use `getInt`.** `aapt2` parses a
bare numeric `android:value` and stores it as an int, so `metaData.getString("shiroikuma.automation.contract")`
returns **null** — not "2", and not an exception. Every app that pastes the snippet as written
produces int-typed meta-data, so this is settled for the whole family: **応用管理 reads them with
`getInt(name, -1)`**, and a `-1` means absent rather than malformed. (Raised by
`shiroikuma-raikidoban`, which is right that it is a question about the reader rather than about any
callee — but it would have silently emptied the entire capability-discovery mechanism, so it belongs
here where both sides can see it.)

**`specialUse` is an API 34 value, so it is a build-time question, not a runtime one.** Two things
follow, and both bit real repos in the rollout:

- **Below `compileSdk 34`, `aapt2` rejects the literal outright** and the build fails — the value
  does not exist in the platform you are compiling against. Declare **`dataSync`** instead, which is
  accurate for streaming an archive, needs no subtype property, and needs no extra permission at a
  `targetSdk` below 34. (`shiroikuma-handyrss`, `compileSdk 31`.)
- **At `targetSdk 34+` the `FOREGROUND_SERVICE_SPECIAL_USE` permission is required as well** — the
  `foregroundServiceType` alone is not enough and the service throws at `startForeground`.

**But the RUNTIME half is settled, and the answer is that `specialUse` works.** `shiroikuma-kojiki`
reasoned that the Mate XT reporting `SDK_INT = 31` would reject the type on the device even though
the build passed, and switched to `dataSync`. That switch is harmless, but the premise is wrong and
it should not spread: 自由作業盤 has shipped **two** `specialUse` services on that exact phone for
months — `com.opentasker.core.engine.AutomationService`, the profile engine that runs continuously,
and `SceneOverlayService`, which draws the scenes 白い熊 uses every day. Both work. So do not
downgrade a working `specialUse` on this reasoning; downgrade only when `aapt2` will not compile it.

In code, ask for the typed `startForeground` only on API 34+, use the plain overload below it, and
catch the failure rather than trusting either branch. This is the one place where **EMUI's
`SDK_INT = 31` on a platform based on Android 13 is not a curiosity but a live hazard** — a
version-derived guess is wrong in both directions here (see §2a's device-fact note).

**Three things the build will tell you about, in the order you will meet them.**

- **The reference `AutomationDataService.kt` used to crash AGP lint, and the trigger is
  co-location.** A local `fun reply(...)` and an anonymous `object : OutputStream()` capturing a
  local `var` **in the same method** kill `lintVitalAnalyzeRelease` with *"FirDeclaration was not
  found for class KtProperty, fir is null"* — after Kotlin, Java and dex have all succeeded, so it
  arrives ten minutes into a release build and does not read as a source problem
  (`shiroikuma-shutokukanri`). The file here is fixed: `reply` is a `val` lambda and the counter a
  named private class. **Triage by inspection rather than by a ten-minute build**: neither construct
  alone is enough — `shiroikuma-nekokan`, `shiroikuma-gauguin` and `shiroikuma-mpvkakucho` all have
  both in *different* methods and lint clean, and `shiroikuma-shosekietsuran`'s §1 receiver has
  carried a local `fun reply` since it was written. **It is also toolchain-dependent**:
  `shiroikuma-kojiki` has both shapes co-located and lints clean anyway, so a repo where it does not
  reproduce should still adopt the robust shape rather than conclude the correction does not apply.
- **Lint wants `tools:ignore="ExportedContentProvider"` on the provider.** An exported provider with
  no `android:permission` is a lint error, exactly as the §1 receivers need `ExportedReceiver`. The
  snippet above omits it; a fork that runs `lintDebug` in its build fails on it as written.
  (`shiroikuma-renrakusaki`.)
- **`AutomationCallers.HOW_TO_DERIVE_PINS` trips `UnusedPrivateProperty`** in any repo whose detekt
  runs at `maxIssues 0`. It is documentation, not code: inline it into the KDoc above, or carry
  `@Suppress("UnusedPrivateProperty")`. Either is fine, and the family file now carries the
  suppression so a verbatim copy lints clean.
- **Declare the automation provider AFTER any provider that builds your global state.** Android
  creates providers in manifest order, so an app that initialises itself in a `ContentProvider` — a
  common pattern — must not have the automation provider created first. Publication happens only
  after the whole install loop, so a `call()` cannot in fact land early; the ordering is stated so it
  is not rediscovered. (`shiroikuma-shoruikanri`.)

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
full `yyyy-MM-dd_HH-mm-ss` stamp in the filename; run `adb` unsandboxed and `adb disconnect` at the
end of each delivery batch.

**NEVER delete a build — this line used to say the opposite and it was wrong.** Until 2026-09-04 the
paragraph above ended "superseded copies pruned", which contradicts a **hard rule in 白い熊's global
`CLAUDE.md`**: never remove, overwrite or tidy away a build artefact — an APK, an XPI, a ZIP — from
`~/tmp`, from `/sdcard/tmp/`, from a release page, or anywhere else. Not when a newer build
supersedes it, not when it was superseded before 白い熊 installed it, and not because two filenames
look confusable. Every build is a record of what shipped and the only way back to it, and a signed
artefact in particular can never be reproduced once its version number is spent. Push the new one
**beside** the old one. Deleting one is 白い熊's to decide, never a chat's. (Caught by
`shiroikuma-mise`, which followed the global rule over this document — correctly.)

**Your repo's own rules outrank this file where they conflict.** Many repos' `CLAUDE.md` forbid
`adb install` outright and have 白い熊 install by hand from `/sdcard/tmp/`. When that is so, the push
is the last step a chat may take: honour the repo rule, stop there, and say which step you stopped
at. A hand-off cannot lift a rule 白い熊 wrote in the repo it is handed to.
