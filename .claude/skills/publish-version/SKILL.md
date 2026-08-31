---
name: publish-version
description: Publish the latest local build as a GitHub release of this fork — refresh the README (fork-style, major features), write a very specific CHANGELOG entry, tag the bare versionName, ensure the default branch is `custom`, and create the release with the ~/tmp APK attached. Use when 白い熊 says publish / release / cut a version / ship it to GitHub.
---

# Publish a version of shiroikuma-jiyusagyoban to GitHub

Ship the **latest already-built** APK as a GitHub release, with a polished README and an exhaustive
CHANGELOG, landing the repo homepage on our fork work (`custom`).

> **Never rebuild to publish.** Attach the newest APK already in `~/tmp/` (see the global
> `no-pointless-rebuilds` rule). The version you publish = that APK's versionName.

## 0. Detect the version

- Newest fork APK: `ls -t ~/tmp/shiroikuma-jiyusagyoban_*.apk | head -1`.
- The **versionName** is the filename field between the first `_` and `_arm64` — e.g.
  `shiroikuma-jiyusagyoban_0.2.68+10_arm64-v8a.apk` → **`0.2.68+10`**. Use it verbatim everywhere
  (tag, README badge, release title, changelog heading). If there is no APK in `~/tmp/`, stop and tell
  白い熊 to build first — do **not** build it yourself.

## 1. Ensure the homepage lands on `custom`

The GitHub repo's **default branch must be `custom`** (so visitors see our fork, not the
upstream-mirroring `master`):
```bash
gh repo view ShiroiKuma0/shiroikuma-jiyusagyoban --json defaultBranchRef --jq '.defaultBranchRef.name'
# if it is not "custom":
gh repo edit ShiroiKuma0/shiroikuma-jiyusagyoban --default-branch custom
```

## 2. Refresh `README.md` (fork-style, major features)

Keep the centered-header, "**a fork of X with major additions**" style — modelled on the sibling
**shiroikuma-futokxkb** README. Structure:

- **Centered header block** (`<div align="center">`): the app icon
  (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, width 120), the title **白い熊 自由作業盤**, the
  name gloss, a one-line "FOSS Tasker-style automation app" tagline, and a
  **"A fork of [OpenTasker](…) with major additions: …"** sentence that names the headline features.
- The **side-by-side install** note (package `shiroikuma.jiyusagyoban`).
- The **latest-release line** — update the version to the one from step 0:
  `**📥 Latest release: [\`<versionName>\`](…/releases/latest)** — [all releases & APK downloads »](…/releases)`.
- Then a **section per major feature** (emoji heading + a few sentences each), in importance order.
  **Pick the updates that matter most vs stock OpenTasker** and describe them invitingly. Maintain/extend
  these to reflect everything currently shipped — at the time of writing the headline set is:
  - 🎯 **Send Intent** — fire arbitrary Android intents (the fork's reason to exist; drives the sister apps).
  - 🪟 **Scenes** — a floating-overlay UI builder: 11 element types, input→variable binding, live
    updates, per-element & panel styling, a system-wide overlay, and a full editor (duplicate / z-order /
    live preview / drag-resize).
  - 🧩 **Home-screen widgets + template library** — a styled-bitmap widget engine, a visual layout
    editor (Tasker Widget V2 import), a named-template library, and a pull/placeholder refresh model.
  - 🕐 **The kanji clock (時間と日付)** — a fully app-driven port: calc tasks compose spoken-kanji
    time/date into variables, 勘亭流-font widgets, a per-minute trigger, and live WiFi/Airplane/Battery
    widgets (read state + Shizuku toggles).
  - ⚙️ **~100 actions** — variables & arrays, date/time, dialogs, accessibility gestures, and a
    **Shizuku-powered elevated tier** (shell, airplane, etc.).
  - 🗂️ **Projects + persistent project-scoped variables** — case-based scoping that survives reboots.
  - 🎨 **Black-and-yellow theme + a UI customization page**; unified JSON import/export; multi-select;
    per-tab name search; per-tab sorting with drag-and-drop; a Help tab.
- A closing **"Built on OpenTasker"** + license (MIT) note.

Write real, specific prose — not a bullet dump. Keep it inviting, like the futokxkb README.

## 3. Update `CHANGELOG.md` — exhaustive

Add a new section **above** the previous one:
```
## <versionName> — <YYYY-MM-DD>
```
(use the current date from the environment). **Be very specific — list everything built since the last
release**: every new action (by display name + id), every feature, UI change, engine change, schema /
DB-migration bump, and notable fix. Group with `###` subsections (Actions — …, Scenes, Widgets, Clock,
Variables, UI & navigation, Infrastructure, Fixes). Cross-check against `git log --oneline <lastTag>..custom`
and the prior CHANGELOG section so nothing is missed. This file is the authoritative, GitHub-readable record.

## 4. Commit, tag, push, release

```bash
git add README.md CHANGELOG.md
git commit -F - <<'MSG'
docs: changelog + README for <versionName> release
MSG
git push origin custom

# Annotated tag = the bare versionName, NO "v" prefix.
git tag -a "<versionName>" -m "白い熊 自由作業盤 <versionName>"
git push origin "<versionName>"

# Release notes = this version's CHANGELOG section. Use a LITERAL match (index($0,h)==1), NOT a regex:
# the "+N" tail puts a "+" in the versionName, and "+" is a regex metachar, so /^## <versionName>/
# would fail to match. index() treats the header as a plain string.
mkdir -p .scratch
awk -v h="## <versionName>" 'index($0,h)==1{p=1;next} /^## /{if(p)exit} p' CHANGELOG.md > .scratch/release-notes.md
gh release create "<versionName>" \
  --repo ShiroiKuma0/shiroikuma-jiyusagyoban \
  --title "白い熊 自由作業盤 <versionName>" \
  --notes-file .scratch/release-notes.md \
  ~/tmp/shiroikuma-jiyusagyoban_<versionName>_arm64-v8a.apk
```
Then verify: `gh release list` shows it as **Latest** and `gh release view "<versionName>" --json assets`
lists the APK. Report the release URL.

## Hard rules / invariants
- Scratch files (the `release-notes.md`) go in the gitignored **`.scratch/`**, never `~/tmp/`
  (see `scratch-dir-not-tmp`). `gh`/`scp`/`git push` run **unsandboxed** (`dangerouslyDisableSandbox: true`).
- **No Claude/Anthropic attribution** in the commit, tag, or release body (repo `CLAUDE.md`).
- Tag is the **bare versionName** (e.g. `0.2.68+10`), no `v` — distinct from upstream's `vX.Y.Z` tags.
- Don't touch `master` here (it only mirrors upstream); the release is cut from `custom`.
