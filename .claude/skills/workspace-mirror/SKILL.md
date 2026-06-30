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

## When to use

### 白い熊 hands over a fresh full export
They do `Setup → Export`; the JSON lands in `~/tmp/白い熊 自由作業盤*.json`. Re-explode + commit:
```bash
D="$HOME/〇/[666] 私資料/[666][60792] 白い熊 自由作業盤"
python3 "$D/scripts/explode.py"                 # newest ~/tmp export (or pass an explicit path)
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
