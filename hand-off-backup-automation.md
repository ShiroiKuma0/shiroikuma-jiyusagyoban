# Hand-off — implement the 保存復元 state-export automation contract in THIS app

> **Canonical location:** `~/git/shiroikuma-jiyusagyoban/hand-off-backup-automation.md`
> **How it is invoked:** 白い熊 says *«Process jiyusagyoban's backup-automation hand-off»* in a sister
> app's chat — read this file from that path (it is not copied into the target repo) and implement it
> there. Any sister app can be brought onto the 保存復元 batch this way.

**Read this whole file, then implement it in the repo this chat is working on.** It is self-contained:
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

### `<pkg>.action.LIST_CATEGORIES`

Token-gated, instant. Reply `OK:` followed by one line per exportable category:

```
OK:settings\tSettings (theme · behaviour)
appearance\tAppearance
books\tBook library (3 shelves)
books.covers\tCovers\tbooks
books.notes\tReading notes\tbooks
```

That is `id<TAB>label` per line, with an **optional third field `parent-id`** for sub-options —
a category that itself has selectable parts lists each part as its own line whose third field is
the parent's id (parents must appear on their own line too, before their children). The ids are
exactly the ones accepted in `items` (sub-option ids included, each independently selectable), and
top-level ids should be the **same stable ids your ZIP already uses as entry names** (your
`Cat`/`Category` enum's `id`). 自由作業盤 renders the list as a checkbox picker with a 全選択
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
- Hold the broadcast open with `goAsync()` and do the work on a background dispatcher. If your export
  can exceed ~10 minutes, hand off to a foreground service and reply from there.

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
    putExtra("text", "書籍 1234/8942")   // numbers-first display line
    putExtra("current", 1234L)            // structured, for logic/bars later
    putExtra("total", 8942L)
    putExtra("unit", "書籍")              // what is being counted
})
```

- Throttle to **at most one every 500 ms** (and always send a final one at completion).
- `text` is what 白い熊 reads in the notification; make it specific and countable.
  Good: `書籍 1234/8942`, `512 MB / 4.2 GB`, `区分 3/7 — 設定`. Bad: `Exporting…`, `47%`.
- `current`/`total` are `long` extras and `unit` a String — send them alongside `text`, not instead.
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
    </intent-filter>
</receiver>
```

No `android:permission` (the caller cannot hold one) — the token is the gate.

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
`(categories, OutputStream, onProgress)` — with the UI panel and this receiver as two thin callers.
Do not duplicate export logic in the receiver.

---

## 6. Acceptance checklist

Verify all of these before telling 白い熊 you are done. Substitute your `<pkg>` and token.

1. **Build + install** the app the normal way for this repo, then confirm the two automation rows sit
   **inside the Export/Import section, directly below the existing export rows** (§2), flip the
   switch **ON**, and tap the token row to copy it.

2. **Gate works (token empty/wrong):** should reply an error, not hang, not export.
   ```bash
   adb shell "am broadcast -a <pkg>.action.LIST_CATEGORIES -p <pkg> \
     --es token wrong --es reply_action shiroikuma.jiyusagyoban.action.INTENT_REPLY \
     --es reply_package shiroikuma.jiyusagyoban --es reply_id test-1"
   ```
   Watch your own log for `ERROR:bad token`; with the switch off, `ERROR:automation disabled`.

3. **Categories list:** same command with the **real** token → the reply carries `OK:` + one
   `id<TAB>label` line per category, ids matching your ZIP entry names.

4. **Real export, directory override:**
   ```bash
   adb shell "am broadcast -a <pkg>.action.EXPORT_STATE -p <pkg> \
     --es token <REAL_TOKEN> --es path '/sdcard/tmp' \
     --es progress_action shiroikuma.jiyusagyoban.action.BR_PROGRESS \
     --es reply_action shiroikuma.jiyusagyoban.action.INTENT_REPLY \
     --es reply_package shiroikuma.jiyusagyoban --es reply_id test-2"
   adb shell ls -la /sdcard/tmp/
   ```
   The ZIP must appear in `/sdcard/tmp` (proving `path` overrides the configured directory), and the
   reply's byte count must equal the real file size. Then **delete the test ZIP from `/sdcard/tmp`** —
   nothing of ours stays there.

5. **Items subset:** repeat with `--es items '<one id>'` → the ZIP contains only that category and
   the reply says `1 categories`.

6. **Unknown id:** `--es items 'bogus'` → `ERROR:unknown category in items: bogus`, no file written.

7. **No directory:** with no `path` extra and no configured export directory →
   `ERROR:no-directory`, no crash.

8. **Progress:** during a large export, confirm progress broadcasts fire with real counts, ≥500 ms
   apart, and that `current`/`total`/`unit` are populated.

9. **Import still works** from your Export/Import page for a ZIP produced headlessly — the automation
   path must produce a normal, restorable backup, not a special format.

10. **Token not in the ZIP:** unzip a headless export and grep for the token value — it must be absent.

**Then tell 白い熊:** the app is ready, and the automation token is at
*UI page → Export / Import → Automation token → tap to copy* — to be pasted into 自由作業盤's
「保存復元の設定 -- [979][01]」 in the `%BR_Token_<App>` line for this app, followed by running
「保存復元 ⇨ 起動 -- [979][71]」. If your app is not in 自由作業盤's roster yet, say so — a wrapper
task and the two settings lines need adding there (自由作業盤 can auto-generate the wrapper from its
「保存作成」 task).

---

## 7. Repo hygiene (unchanged, per-repo rules still apply)

Follow this repo's own `CLAUDE.md` for building, delivery, and versioning. Standing rules that always
apply: no `Co-Authored-By: Claude` or Anthropic attribution in commits; never commit/push unprompted
(build and deliver, wait for 白い熊's «Push»); every `adb push` goes only to `/sdcard/tmp/` with a
full `yyyy-MM-dd_HH-mm-ss` stamp in the filename, superseded copies pruned; run `adb` unsandboxed and
`adb disconnect` at the end of each delivery batch.
