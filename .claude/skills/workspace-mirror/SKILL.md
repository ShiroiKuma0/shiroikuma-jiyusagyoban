# workspace-mirror — version-controlled mirror of the 白い熊 自由作業盤 app workspace

A git repo holds an **exploded** copy of the entire app workspace — one JSON file per
task / profile / scene / widget-template, grouped by project — so Claude can consult, diff, and modify
the current content and keep an ongoing record. It's the OpenTasker analogue of the Tasker mirror at
`~/〇/[666] 私資料/[666][1074] tasker/`.

## The directory

**`~/〇/[666] 私資料/[666][60792] 白い熊 自由作業盤`** — quote it (spaces + brackets). Use `git -C "$DIR"`
rather than `cd` (the spaces/brackets + sandbox make `cd` painful). Full layout + conventions live in that
repo's own `README.md`. In short:

- `<project>/_project.json`, `<project>/{tasks,profiles,scenes}/<name>.json`, `<project>/variables.json`
- `_widgets/<name>.json` — **global** widget templates (the app keys them by name, not by project)
- `_orphans/{tasks,profiles,scenes}/…` — Unfiled items (no projectId)
- `_globals/{bundle,variables,groups,item-meta}.json` — top-level meta + UI metadata
- `scripts/explode.py` — the exploder (full-export JSON → this tree)

Filenames are only handles (`/`→`／`, empty→`_anon-<id>`); the authoritative name is the JSON `"name"`.
Each per-item file is the bundle's object **verbatim**.

## The broadcast bridge — headless export / import over adb (2026-07-12, +182)

`WorkspaceTransferReceiver` (`core/transfer/`) lets adb drive export and import without the UI. Ordered
broadcasts; `result=-1` (RESULT_OK) = success, `result=1` = failure with the message in `data=`. All adb
calls UNSANDBOXED.

```bash
# EXPORT the whole workspace → /sdcard/tmp/白い熊 自由作業盤.<stamp>.json (path echoed in data=)
adb shell "am broadcast -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver \
  -a shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1"

# IMPORT a bundle JSON (bare filename resolves against /sdcard/tmp; overwrite-in-place, merge projects)
adb shell "am broadcast -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver \
  -a shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1 \
  --es shiroikuma.jiyusagyoban.extra.PATH '<file>.json'"

# RUN a task by NAME (PROJECT optional — required when the name is not unique). This is how the 71
# reload task gets run after a settings import, and how a scene is put on screen to be inspected.
adb shell "am broadcast -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver \
  -a shiroikuma.jiyusagyoban.action.RUN_TASK --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1 \
  --es shiroikuma.jiyusagyoban.extra.TASK '<task name>' \
  --es shiroikuma.jiyusagyoban.extra.PROJECT '<project name>'"
```

**Quote the whole `am` line for the DEVICE shell, not just for bash.** `adb shell a b c` joins its
arguments with spaces and hands the result to the phone's shell, so one level of quoting is lost —
and every task name in this workspace ends in ` -- [NNN]`, which `am` then reads as its own `--`
separator followed by a package name. The reply is `result=1, data="no such task: <name without the
suffix>"` and the intent dump shows `pkg=[727]`, which reads as a renamed task rather than as
mangled quoting (2026-09-03). Pass the whole command as ONE double-quoted argument with the task
name single-quoted inside it:

```bash
adb shell "am broadcast -a shiroikuma.jiyusagyoban.action.RUN_TASK \
  -n shiroikuma.jiyusagyoban/com.opentasker.core.transfer.WorkspaceTransferReceiver \
  --ei shiroikuma.jiyusagyoban.extra.PROTOCOL 1 \
  --es shiroikuma.jiyusagyoban.extra.TASK '運動（Huawei） -- [727]'"
```

**Every extra is namespaced and the PROTOCOL one is mandatory.** `--es task …` (the bare name) is not
read, and a call without `--ei …extra.PROTOCOL 1` is dropped by the receiver's first line — in both
cases `am` prints a bare `result=0` with no `data=`, which looks exactly like a dead bridge rather than
a malformed call (cost half an hour, 2026-09-02). A reply that reaches the receiver always carries a
`data=` message, success or failure.

So the standard dev cycle is fully hands-off:
1. **Cycle start:** EXPORT_WORKSPACE → `adb pull` into `.scratch/` → explode + commit (baseline below).
2. **During development:** push each bundle to `/sdcard/tmp/` (timestamped filename) → IMPORT_BUNDLE →
   tell 白い熊 what to test. Imported intermediates may be deleted from `/sdcard/tmp/` afterwards —
   only the latest matters (2026-07-12 rule).
3. **On acceptance:** EXPORT_WORKSPACE → pull → explode + commit the mirror; then archive on-phone —
   `adb shell mv` (MOVE, never cp — nothing stays behind in `/sdcard/tmp/`, 白い熊 2026-07-12) the
   final export to `/sdcard/〇/[979] バックアップ/[979][60792] 白い熊 自由作業盤/` and the final APK to
   `/sdcard/〇/[979] バックアップ/`; clean the cycle's remaining leftovers out of `/sdcard/tmp/`
   (quote those paths — spaces + brackets). After a completed cycle `/sdcard/tmp/` holds NONE of our
   files.

## When to use

### Fresh full export (broadcast bridge, or 白い熊 hands one over)
Get the export (EXPORT_WORKSPACE + `adb pull` as above, or 白い熊's manual `Setup → Export` into
`~/tmp/`). Re-explode + commit:
```bash
D="$HOME/〇/[666] 私資料/[666][60792] 白い熊 自由作業盤"
python3 "$D/scripts/explode.py" <export.json>   # or no arg: newest ~/tmp export
git -C "$D" add -A
git -C "$D" -c user.name="白い熊" commit -m "Sync export <appVersion> (<YYYY-MM-DD>)"
```
`explode.py` **wipes & rewrites** the tree (everything but `.git/`, `scripts/`, and the dotfiles/docs),
so items deleted in the app show up as removed files in `git diff`.

### Claude shipped a bundle → 白い熊 OKs it → sync the mirror (REQUIRED workflow)
Whenever you hand 白い熊 a JSON bundle, you **explicitly request confirmation** that it imported / works
(don't move on without asking). **The moment 白い熊 OKs it**, update the mirror to match and commit —
write the bundle's changed objects **verbatim** into the matching per-item files
(`<project>/{tasks,profiles,scenes}/<name>.json`, plus any new profile / scene / variable / `_globals`
entry), then commit. This keeps the mirror current between full exports — e.g. after shipping
`batt-power.v2.json`, update `時間と日付/tasks/時間と日付 ⇨ 起動 ….json` and add the new
`電池ウィジェット・電源` profile + `dt.batt.refresh` task files, then commit. (A periodic full
`Setup → Export` + `explode.py` reconciles the whole snapshot — ids, item-meta, groups, orphans, the
`tapTaskName` backfill — so prefer that when many items moved.)

### Consulting / modifying current content
Read the per-item JSON directly (e.g. `通知明滅/tasks/通知明滅点灯.json`) to see exactly what's
configured — no need to ask 白い熊 to screenshot. To ship an edit back to the phone, wrap the changed
objects in an `OpenTaskerBundle` (`schemaVersion` + `appVersion` + **`exportedAtEpochMs`** + the arrays —
see the `bundle-required-fields` memory) and push the bundle to `/sdcard/tmp/`.

## Rules
- The phone's Room DB is the source of truth; the mirror reflects the **last export**. After 白い熊 edits
  things in the app, get a fresh export before trusting the mirror for those items.
- **This mirror is LOCAL-only — it has NO github/remote, and never will.** It is *our* private version
  control; it just lives on disk. So "pushing" the mirror only ever means a local `git commit`.
- **Sync the mirror the moment 白い熊 OKs a shipped bundle** (the workflow above) and commit it then.
- **On 白い熊's “Push”, commit + push BOTH repos:** the code repo (`custom` →
  `git push --force-with-lease origin custom`; `master` fast-forwards) AND this mirror (a local
  `commit` only — no push). Don't push the mirror anywhere; don't skip it either.
- **No Claude attribution** in commits (global rule).
- `git`/`scp` run **unsandboxed** (`dangerouslyDisableSandbox: true`).
