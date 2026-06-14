# CLAUDE.md — guide for Claude Code in this repo

**shiroikuma-sagyoban** — 白い熊's fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker), a
FOSS, Tasker-style Android automation app (native Kotlin + Jetpack Compose, Room, WorkManager; no native
code). Renamed to install side-by-side with upstream. The fork's reason for existing: add a **generic
"Send Intent" action** so OpenTasker tasks can fire arbitrary Android intents — specifically the
token-gated automation intents exposed by the sister apps (e.g. `白い熊 GNU Jami` /
`shiroikuma.jami`'s send-message / place-call / open-conversation intents).

This repo (`ShiroiKuma0/shiroikuma-sagyoban`) is a fork. We track upstream
(`SysAdminDoc/OpenTasker`) on `master` and layer our customizations on `custom`.

## Read this first

Before any work, read **`.claude/skills/build-apk/SKILL.md`** (canonical build + transfer) and
**`.claude/skills/upstream-new-version/SKILL.md`** (upstream sync + rebase).

## Fork workflow — READ THIS FIRST

### Git remotes & branches
- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-sagyoban` (push here).
- `upstream` → `https://github.com/SysAdminDoc/OpenTasker` (fetch only).
- `master` — mirrors upstream, **fast-forward only**, no fork work.
- `custom` — all our work; rebased onto `master` on each upstream sync.

### Our customizations (install identity + build)
| What | Value | Where |
| --- | --- | --- |
| applicationId | `shiroikuma.sagyoban` | `app/build.gradle.kts` → `defaultConfig` |
| namespace (R/BuildConfig pkg) | `com.opentasker.app` (**unchanged** from upstream) | `app/build.gradle.kts` |
| App label | `白い熊 作業盤` | `app_name` in `app/src/main/res/values/strings.xml` |
| App icon | black-yellow (yellow foreground + black background) | `app/src/main/res/mipmap/*`, `values/colors.xml` |
| Version tail | `versionName = "<base>+N"`, `versionCode = <base>*10000+N` | `app/build.gradle.kts` fork blocks |
| Signing | gitignored `keystore.properties` → `~/.android-keystores/shiroikuma-sagyoban.jks` (alias `sagyoban`) | `app/build.gradle.kts` |

### Versioning & APK naming
- Upstream base lives in `app/build.gradle.kts` as `appVersionName` (e.g. `0.2.60`) / `appVersionCode`
  (e.g. `62`) — these track upstream and update automatically on rebase.
- `BUILD_NUMBER` (in `gradle.properties`) is our per-build `N`: `versionName = "<base>+N"`,
  `versionCode = <base>*10000 + N`. The `buildFork` task bumps it after every successful build; the
  upstream-sync skill resets it to `1`.
- APK: `shiroikuma-sagyoban_<versionName>_arm64-v8a.apk`, copied to `~/tmp/`.

### Build commands
```bash
# Our build: signed release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFork < /dev/null
# Release APK only (no copy / no bump)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleRelease
```
Distribution profiles (`-PopenTaskerDistribution=standard|fdroid|play`) are upstream's; we ship the
default **`standard`**.

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
- **Never `adb install` / `adb uninstall`.** Push to `/sdcard/tmp/`; 白い熊 installs manually.
- **Never commit/push unprompted.** Build-only until 白い熊 says **"Push"** (`custom` rebases →
  `git push --force-with-lease origin custom`; `master` fast-forwards).
- `keystore.properties` and `*.jks` are gitignored — never commit them.
- On a new upstream version, run the `upstream-new-version` skill (rebase `custom`, reset
  `BUILD_NUMBER=1`, build `+1`).

## Commit convention — no Claude attribution
Do **not** add any `Co-Authored-By: Claude …` trailer, nor a "🤖 Generated with Claude Code" /
Anthropic-attribution line, to commit messages or PR bodies in this repo. End the message at the last
line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
