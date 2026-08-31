---
name: build-apk
description: Build the signed release APK with the buildFork Gradle task, then deliver it automatically — wireless adb connect, adb install -r, push to /sdcard/tmp (never deleting older APKs), am start (fall back to /after-build's scp path if the phone is unreachable). Always build first without asking for permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the release APK and optionally send to the phone

This is **shiroikuma-jiyusagyoban** — 白い熊's fork of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker),
renamed to `shiroikuma.jiyusagyoban` ("白い熊 自由作業盤") so it installs side-by-side with upstream. Pure
Kotlin/Compose, no native code, no Fossify Commons.

> **ALWAYS build, then ALWAYS deliver — no asking, no exceptions (白い熊, 2026-07-05).**
> After ANY functional change you make (anything 白い熊 would want to test on the phone), build
> **immediately** and deliver via the **dev cycle** below. Do **not** stop at a compile-check; do **not**
> say "compile-verified, say build"; do **not** offer to build or wait for a "build" instruction. This is
> build-and-**DELIVER** only — it does **not** commit or push; a commit/push still waits for 白い熊's
> explicit "Push". (Skip the build only for non-functional edits — docs, comments — per the
> `no-pointless-rebuilds` rule.)

> **Every `adb push` goes ONLY to `/sdcard/tmp/`.** This holds for the APK *and any other
> file* (import bundles, configs, logs, screenshots) — never `/sdcard/Download/` or anywhere else.
> **Install automatically** (`adb install -r -d`, 白い熊 2026-07-12); `adb uninstall` stays forbidden.
> `-d` (allow version-code downgrade) is REQUIRED: `BUILD_NUMBER` resets to 1 on every upstream sync,
> so when upstream ships commits without bumping `appVersionCode`, the new build's code is *lower*
> than the installed one and plain `-r` fails with `INSTALL_FAILED_VERSION_DOWNGRADE`. `-d` still
> installs as an update, so the workspace database survives.

## Steps

1. **Note the output filename.** Read the current version + build number:
   - `grep -nE 'appVersionName|appVersionCode' app/build.gradle.kts` (the upstream base, e.g. `0.2.60` / `62`)
   - `grep -E '^BUILD_NUMBER' gradle.properties` (the `N` used for THIS build, **before** the task bumps it)
   - `git merge-base HEAD master | cut -c1-8` + that commit's date
     (`git show -s --format=%cd --date=format:%Y-%m-%d <sha>`) — the upstream-base pin.
   - APK will be
     `shiroikuma-jiyusagyoban_<appVersionName>.<base date>.g<sha8>+<NNN>_arm64-v8a.apk`
     (`N` zero-padded to 3 digits in the name only). See the global **`git-versioning`** skill.
   - versionCode for this build = `appVersionCode * 10000 + BUILD_NUMBER` (plain, unpadded `N`).

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

3. **At the end of every successful build, ALWAYS run the dev cycle** (白い熊, 2026-07-12) — no
   exceptions, no asking. Every adb call UNSANDBOXED (`dangerouslyDisableSandbox: true`).

   ### dev-cycle
   ```bash
   adb devices                        # empty list? → wireless connect:
   #   the phone's adbd already listens on TCP 5555; get the IP over ssh:
   #   ssh skhw "ifconfig 2>/dev/null | grep -A1 wlan"   → adb connect <ip>:5555
   adb install -r -d ~/tmp/shiroikuma-jiyusagyoban_<ver>_arm64-v8a.apk   # -d: see the note above
   adb push ~/tmp/shiroikuma-jiyusagyoban_<ver>_arm64-v8a.apk /sdcard/tmp/
   # NO prune. Leave every older shiroikuma-jiyusagyoban_*.apk in /sdcard/tmp exactly where it is.
   adb shell am start -n shiroikuma.jiyusagyoban/com.opentasker.app.MainActivity
   ```
   Then announce the installed version. If the phone is unreachable even after the wireless-connect
   attempt, fall back to the global **/after-build** (scp to `skhw:~/tmp/`) and say the install is
   pending. Bundles for the cycle are imported headlessly via the broadcast bridge — commands in the
   `workspace-mirror` skill.

## Notes / invariants

- **One distribution: `standard`** (the default; keeps the SMS action). The F-Droid/Play
  `-PopenTaskerDistribution` profiles and the `verify*` tasks are upstream's — we don't ship them.
- **buildToolsVersion is `36.0.0`** (pinned by upstream); it's installed under `~/android-sdk/build-tools/`.
- **Never commit/push on your own.** Wait for 白い熊's explicit "Push". Build artifacts (`*.apk`) and
  `keystore.properties` are gitignored.
- **No Claude attribution** in any commit (see repo `CLAUDE.md`).
