---
name: build-apk
description: Build the signed release APK with the buildFork Gradle task, then always ask whether to scp it to skhw (first choice) or adb push it to the connected phone. Always build first without asking for permission to build — the ONLY question you ever ask is the transfer question afterward. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the release APK and optionally send to the phone

This is **shiroikuma-jiyusagyoban** — 白い熊's fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker),
renamed to `shiroikuma.jiyusagyoban` ("白い熊 自由作業盤") so it installs side-by-side with upstream. Pure
Kotlin/Compose, no native code, no Fossify Commons.

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or
> you've made changes ready to test), run the build immediately. Do **not** ask "shall I build?".
> The **only** question in this whole flow is the `AskUserQuestion` about transferring the APK, asked
> **after** a successful build.

> **The push destination is ALWAYS `/sdcard/tmp/`.** Never `adb install` — 白い熊 installs manually.

## Steps

1. **Note the output filename.** Read the current version + build number:
   - `grep -nE 'appVersionName|appVersionCode' app/build.gradle.kts` (the upstream base, e.g. `0.2.60` / `62`)
   - `grep -E '^BUILD_NUMBER' gradle.properties` (the `N` used for THIS build, **before** the task bumps it)
   - APK will be `shiroikuma-jiyusagyoban_<appVersionName>+<BUILD_NUMBER>_arm64-v8a.apk`.
   - versionCode for this build = `appVersionCode * 10000 + BUILD_NUMBER`.

2. **Build** (needs JDK 21 — the default `java` on this host is JDK 11, and Gradle 9.x aborts on it):
   - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFork < /dev/null`
     (`< /dev/null` guarantees it never blocks on stdin.)
   - `buildFork` runs `assembleRelease` (R8 minify + shrink, signed from `keystore.properties`),
     copies the signed APK to `~/tmp/<apk name>`, and auto-increments `BUILD_NUMBER` in `gradle.properties`.
   - It prints `>>> <path>` and `>>> versionCode <n>`. Confirm `BUILD SUCCESSFUL` and use those for the
     exact filename/code.
   - If it fails to resolve a signing key, check that `keystore.properties` exists at the repo root
     (gitignored; points at `~/.android-keystores/shiroikuma-jiyusagyoban.jks`, alias `sagyoban`).
   - If it fails with **`SDK location not found`**, create the gitignored `local.properties` at the repo
     root with `sdk.dir=/home/shiroikuma/android-sdk` (a background shell doesn't inherit `ANDROID_HOME`).

3. **At the end of every successful build, ALWAYS ask** via `AskUserQuestion` how to transfer the APK —
   no exceptions, no assuming. Options, in this order: **"Scp to skhw"** (FIRST) / **"adb push"** /
   **"No, just build"**. Fire this as soon as the build reports `BUILD SUCCESSFUL`.

4. **Transfer per the answer:**
   - **Scp to skhw** — invoke the global **scp** skill (copies the newest APK in `~/tmp/` to `skhw:~/tmp/`).
     If skhw is unreachable (its tunnel is served by the phone's sshd and may be down), report that and
     offer the adb push instead.
   - **adb push:**
     - `adb devices` — confirm a device is connected.
     - `adb shell mkdir -p /sdcard/tmp`
     - `adb push ~/tmp/<apk name> /sdcard/tmp/<apk name>`
     - Verify: `adb shell ls -l /sdcard/tmp/<apk name>`.
     - Never `adb install` — 白い熊 installs manually from `/sdcard/tmp/`.

## Notes / invariants

- **One distribution: `standard`** (the default; keeps the SMS action). The F-Droid/Play
  `-PopenTaskerDistribution` profiles and the `verify*` tasks are upstream's — we don't ship them.
- **buildToolsVersion is `36.0.0`** (pinned by upstream); it's installed under `~/android-sdk/build-tools/`.
- **Never commit/push on your own.** Wait for 白い熊's explicit "Push". Build artifacts (`*.apk`) and
  `keystore.properties` are gitignored.
- **No Claude attribution** in any commit (see repo `CLAUDE.md`).
