# CLAUDE.md — guide for Claude Code in this repo

**shiroikuma-jiyusagyoban** — 白い熊's fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker), a
FOSS, Tasker-style Android automation app (native Kotlin + Jetpack Compose, Room, WorkManager; no native
code). Renamed to install side-by-side with upstream. The fork's reason for existing: add a **generic
"Send Intent" action** so OpenTasker tasks can fire arbitrary Android intents — specifically the
token-gated automation intents exposed by the sister apps (e.g. `白い熊 GNU Jami` /
`shiroikuma.jami`'s send-message / place-call / open-conversation intents).

This repo (`ShiroiKuma0/shiroikuma-jiyusagyoban`) is a fork. We track upstream
(`SysAdminDoc/OpenTasker`) on `master` and layer our customizations on `custom`.

## Read this first

Before any work, read **`.claude/skills/build-apk/SKILL.md`** (canonical build + transfer) and
**`.claude/skills/upstream-new-version/SKILL.md`** (upstream sync + rebase).

## Tasker reference projects (for porting)

白い熊's full Tasker setup lives **version-controlled, one XML per entity**, at
`~/〇/[666] 私資料/[666][1074] tasker/` — each `<project>/` has `tasks/*.tsk.xml`,
`profiles/*.prf.xml`, `scenes/*.scn.xml`, `_project.xml`. This is the source when porting a Tasker
project into the app (e.g. `時間   日付` → the kanji clock; `電池線` → the battery line). The path
has spaces and literal `[...]`, so quote it: `"$HOME/〇/[666] 私資料/[666][1074] tasker"`. Tasker action
codes are decoded via that dir's `.claude/skills/tasker-action-codes/references/action_codes.md`.

## Workspace mirror (our app's current content)

A version-controlled **exploded** mirror of the whole 白い熊 自由作業盤 workspace lives at
`~/〇/[666] 私資料/[666][60792] 白い熊 自由作業盤` — one JSON per task/profile/scene/widget-template,
grouped by project (`<project>/{tasks,profiles,scenes}/`, `_widgets/`, `_orphans/`, `_globals/`). Consult
the per-item JSON directly instead of asking 白い熊 to screenshot. Rebuild it from a fresh full export with
`scripts/explode.py`. Canonical procedure: **`.claude/skills/workspace-mirror/SKILL.md`**.

## Fork workflow — READ THIS FIRST

### Git remotes & branches
- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-jiyusagyoban` (push here).
- `upstream` → `https://github.com/SysAdminDoc/OpenTasker` (fetch only).
- `master` — mirrors upstream, **fast-forward only**, no fork work.
- `custom` — all our work; rebased onto `master` on each upstream sync.

### Our customizations (install identity + build)
| What | Value | Where |
| --- | --- | --- |
| applicationId | `shiroikuma.jiyusagyoban` | `app/build.gradle.kts` → `defaultConfig` |
| namespace (R/BuildConfig pkg) | `com.opentasker.app` (**unchanged** from upstream) | `app/build.gradle.kts` |
| App label | `白い熊 自由作業盤` | `app_name` in `app/src/main/res/values/strings.xml` |
| App icon | black-yellow (yellow foreground + black background) | `app/src/main/res/mipmap/*`, `values/colors.xml` |
| Version tail | `versionName = "<base>+<base date>.<HH-MM>.g<sha8>+NNN"`, `versionCode = <base>*10000+N` | `app/build.gradle.kts` fork blocks |
| Signing | gitignored `keystore.properties` → `~/.android-keystores/shiroikuma-jiyusagyoban.jks` (alias `sagyoban`) | `app/build.gradle.kts` |

### Versioning & APK naming
- **Upstream tracking: `git`** — `custom` is rebased onto every upstream commit, so the fork
  versionName pins the upstream base: `<upstream>+<base date>.<HH-MM>.g<sha>+<BUILD_NUMBER, 3 digits>`.
  See the global **`git-versioning`** skill. (Upstream sat on `0.2.79` for the whole 10-commit
  stretch that became `+2`/`+3` — the literal alone says nothing about how current we are.)
- Upstream base lives in `app/build.gradle.kts` as `appVersionName` (e.g. `0.2.79`) / `appVersionCode`
  (e.g. `81`) — these track upstream and update automatically on rebase. **Never hand-edit them.**
- The pin is `git merge-base HEAD master` (the upstream commit our patches sit on — not our HEAD, not
  `master`'s tip) shortened to 8 chars, plus that commit's own committer date **and time, in UTC**
  (`%ct` epoch → `yyyy-MM-dd.HH-mm`; never `--date=format:`, which renders the commit's own zone).
  It moves only on a sync. The time is not decoration: two syncs on one day used to leave the random
  sha as the deciding sort field, so the newer APK landed anywhere in the list (白い熊, 2026-08-12).
- `BUILD_NUMBER` (in `gradle.properties`) is our per-build `N`:
  `versionName = "<base>+<YYYY-MM-DD>.<HH-MM>.g<sha8>+<NNN>"`, `versionCode = <base code>*10000 + N`.
  Zero-padded to 3 digits **in the name only**; `versionCode` and `gradle.properties` keep the plain
  integer. The `buildFork` task bumps it after every successful build.
- **`BUILD_NUMBER` runs MONOTONICALLY. Reset it to `1` ONLY when `appVersionCode` itself moves** —
  never merely because a sync moved the `.g<sha>` pin. (白い熊, 2026-08-10; this supersedes the
  2026-08-02 "reset on every version change" rule, which was wrong.)
- **Why:** an installer compares `versionCode` and nothing else — the date and sha in `versionName`
  are cosmetic, so a fresh pin does not make a build newer. `versionCode = appVersionCode * 10000 + N`,
  and upstream leaves `appVersionCode` standing for months (0.2.79 took ten commits without a bump;
  0.2.82 is on its third sync). Resetting `N` on such a sync therefore sends `versionCode` *backwards*
  — 840030 installed, 840002 offered — and makes **every** sync a downgrade by construction.
- The `buildFork` task enforces this: it records `LAST_BUILT_VERSION_CODE` in `gradle.properties` and
  **refuses to build** a `versionCode` that does not exceed it, rather than letting the failure be
  discovered on the phone. Raise `BUILD_NUMBER` past the last built tail; never lower it.
- `adb install -r -d` (`-d` allows a version-code downgrade) stays the escape hatch for a *deliberate*
  rollback to an older APK, not the routine path for a sync. Never `adb uninstall` to work around an
  install refusal — that wipes the workspace database.
- APK: `shiroikuma-jiyusagyoban_<versionName>_arm64-v8a.apk`, copied to `~/tmp/`. The versionName
  contains no `_` — but **not** for the reason this file used to give. Those globs are
  `shiroikuma-jiyusagyoban_*.apk` with a greedy `*`, picked with `ls -t`, and nothing splits an APK
  name on `_`, so an underscore would not break them (checked 2026-08-12). The real ban is
  **Debian**: `_` is not legal in a Debian version and the electron-builder sister forks build
  `.deb`/`.rpm` from this same string. `~` is banned too — `git check-ref-format` rejects it, so
  `/publish-version` could not tag the release.

### Build commands
```bash
# Our build: signed release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFork < /dev/null
# Release APK only (no copy / no bump)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleRelease
```
Distribution profiles (`-PopenTaskerDistribution=standard|fdroid|play`) are upstream's; we ship the
default **`standard`**.

### Seeing the UI without the phone — Compose screenshot previews
```bash
# Render every @PreviewTest preview to app/src/screenshotTestDebug/reference/ (and .../outputs/…/rendered/)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew updateDebugScreenshotTest < /dev/null
# Compare the current render against those committed references instead of overwriting them
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew validateDebugScreenshotTest < /dev/null
```
Previews live in `app/src/screenshotTest/kotlin/…`. This is how a layout gets LOOKED at from this end:
白い熊's phone is normally locked, so `adb shell screencap` returns black or the keyguard and no
screenshot of the running app is possible.

- **Every preview needs `@PreviewTest` (`com.android.tools.screenshot.PreviewTest`) as well as
  `@Preview`.** The engine discovers methods carrying `@PreviewTest`; `@Preview` alone is invisible to
  it and the task dies with *"There are test sources present … did not discover any tests"*, which
  reads like a broken source set rather than a missing annotation. Nothing in this toolchain generates
  the annotation — searched: it exists only in `screenshot-validation-api`, and neither the Gradle
  plugin, the Compose compiler plugin, KSP, nor `compose-preview-detector` emits it. (Diagnosed
  2026-08-17; the plugin version is not the cause — alpha15 and alpha16 behave identically.)
- **Upstream's own `ComposeScreenshotPreviews.kt` was deleted from the fork**, with its 44 reference
  PNGs. It was written against upstream's screen signatures, which this fork has changed
  (`OpenTaskerTheme(darkTheme=/amoled=/highContrast=)`, `ProfilesScreen`'s added `groupOps`/
  `projectFilter`/`projects`/`onReorderProjects`, `DiagnosticsScreen` removed outright), so it could
  not compile and took the whole source set down with it. On a sync, **keep our deletion** — same call
  as the snapshot-schedule UI.

### Toolchain
- JDK **21** at `/usr/lib/jvm/java-21-openjdk-amd64` (the host default `java` is JDK 11; Gradle 9.x
  aborts on it — always set `JAVA_HOME`).
- Android SDK at `~/android-sdk`; `compileSdk 36`, `targetSdk 35`, `minSdk 26`,
  `buildToolsVersion 36.0.0`. Gradle wrapper 9.4.1.

## Architecture (upstream OpenTasker)
- Compose UI; `MainActivity`. Engine in `app/src/main/java/com/opentasker/core/` — `engine/Action.kt`
  defines the trivial `Action` interface (`id`, `category`, `suspend run(ctx, args: Map<String,String>)`)
  plus `ActionRegistry`. `actions/*Actions.kt` hold the built-ins; `actions/ActionMetadata.kt` drives the
  editor form fields. Persistence via Room.
- **Our feature work** (the "Send Intent" action) goes under `actions/` (impl + registration in the
  built-in bootstrap + an `ActionMetadata` entry), plus a `<queries>` entry in `AndroidManifest.xml` so
  the explicit intent to `shiroikuma.jami` resolves on Android 11+.

## Hard rules
- **Never tell 白い熊 to build a task by hand.** Whenever 白い熊 needs a task (test task, example,
  repro, a widget-driver task, etc.), export it as an importable **JSON bundle** (`OpenTaskerBundle`
  schema — `schemaVersion`/`tasks[]/actions[]` with `type` + `args`) and `adb push` it to
  `/sdcard/tmp/` for 白い熊 to import. Never a tabular spec or "add these actions" instructions.
- **Version every pushed JSON uniquely with a full datetime.** Each bundle/JSON `adb push`ed to the
  phone must carry a `yyyy-MM-dd_HH-mm-ss` stamp (our format — `date +%Y-%m-%d_%H-%M-%S`, to the
  SECOND) in its FILENAME, e.g. `volume-panel-reorder_2026-06-26_12-56-22.json`. Date-only (or a
  `b`/`(2)` suffix) is **not** enough — a same-day re-push must get a fresh stamp so it never collides
  and 白い熊 always knows which is current. State the exact current filename in the handover.
  (See the `version-pushed-files` memory.)
- **NEVER delete an APK from the phone.** No `rm` of a superseded
  `shiroikuma-jiyusagyoban_*.apk` in `/sdcard/tmp/`, no "prune older copies", no tidying — push the
  new one and leave every earlier one where it is. The unique full-datetime/version filename is what
  tells 白い熊 which is current; deleting is never needed for that and throws away a build they may
  still want to roll back to. (白い熊, 2026-08-02 — reinstates the never-prune rule for APKs and
  supersedes the 2026-07-12 "keep only the LATEST copy" note. Matches the global `adb-push` /
  `after-build` standing rule of 2026-07-25.) Imported JSON bundles are unaffected: those may still
  be tidied once imported.
- **Always ask 白い熊 to confirm a shipped bundle, then sync the mirror.** Whenever you hand over a JSON
  bundle, explicitly **request confirmation** that it imported / works. The moment 白い熊 OKs it, update
  the workspace mirror (`~/〇/[666] 私資料/[666][60792] …`) to match and **commit it** — see the
  `workspace-mirror` skill.
- **Always run `adb` with `dangerouslyDisableSandbox: true`** (the sandbox blocks adb's server
  socket, so `adb devices` shows empty). Every `adb` invocation goes through the unsandboxed path.
- **Install builds automatically over wireless adb** (白い熊, 2026-07-12 — reverses the old
  never-install rule): after every build, `adb install -r -d` the new APK, push a copy to
  `/sdcard/tmp/` (leaving every older APK in place), and `am start` the app. `adb uninstall` stays
  forbidden. Full cycle: the
  `dev-cycle` section of the `build-apk` skill.
- **Dev cycle (2026-07-12).** The app exposes a broadcast bridge (`WorkspaceTransferReceiver`) for
  headless workspace transfer — `EXPORT_WORKSPACE` writes a full export to `/sdcard/tmp/`,
  `IMPORT_BUNDLE` imports a JSON from there (see the `workspace-mirror` skill for the exact
  commands). Before starting new task/profile/scene work: export all → explode into the mirror →
  commit (baseline). During development: build → install → import bundles via the bridge → 白い熊
  tests. On acceptance: export all → sync + commit the mirror → **move** (not copy) the final export
  to `/sdcard/〇/[979] バックアップ/[979][60792] 白い熊 自由作業盤/` and the final APK to
  `/sdcard/〇/[979] バックアップ/` → clean `/sdcard/tmp/` of the cycle's remaining intermediates
  (after a completed cycle it holds none of our files).
- **Never commit/push unprompted. On 白い熊's "Push", commit + push BOTH repos.** (1) the code repo —
  `custom` → `git push --force-with-lease origin custom`, `master` fast-forwards (this is the only one
  on github); (2) the workspace mirror at `~/〇/[666] 私資料/[666][60792] …` — a **local `git commit`
  only; it has NO github/remote and never will**. Build-only until 白い熊 says "Push."
- **A Room schema bump must be replayed before it ships:** `python3 scripts/check-room-migration.py`.
  Room validates the live database against its exported JSON at OPEN time, so a mismatch as small as a
  missing `DEFAULT NULL` throws rather than opening — and only on a device holding the old database. A
  clean build, a fresh install and the full JVM suite can all pass while every existing install crashes
  on start. Upstream never hits this because it declares column additions as generated
  `AutoMigration`s; the fork registers every migration by hand, so any column whose entity carries
  `@ColumnInfo(defaultValue = …)` must spell the `DEFAULT` out in its own `ALTER TABLE`.
- `keystore.properties` and `*.jks` are gitignored — never commit them.
- On a new upstream version, run the `upstream-new-version` skill (rebase `custom`, reset
  `BUILD_NUMBER=1`, build `+1`).

## Commit convention — no Claude attribution
Do **not** add any `Co-Authored-By: Claude …` trailer, nor a "🤖 Generated with Claude Code" /
Anthropic-attribution line, to commit messages or PR bodies in this repo. End the message at the last
line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
