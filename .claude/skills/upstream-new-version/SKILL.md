---
name: upstream-new-version
description: Rebase this fork onto a new upstream release of SysAdminDoc/OpenTasker. Use when 白い熊 says a new upstream version is out, asks to update/sync to upstream, bump to the new OpenTasker release, or rebase custom onto the latest upstream — then build the new +1.
---

# Sync shiroikuma-jiyusagyoban onto a new upstream OpenTasker release

This fork tracks [SysAdminDoc/OpenTasker](https://github.com/SysAdminDoc/OpenTasker). `master` mirrors
upstream (fast-forward only); `custom` carries our patches and is rebased onto each new upstream tip.

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors `upstream/master`. No fork work here. | fast-forward only |
| `custom` | Our patches; the working/dev branch. | rebased onto `master` each sync |

`origin` = `git@github.com:ShiroiKuma0/shiroikuma-jiyusagyoban` (push). `upstream` =
`https://github.com/SysAdminDoc/OpenTasker` (fetch only).

## Steps

1. **Fetch upstream:**
   - `git fetch upstream --tags`
   - Note the new base version upstream declares (these live in `app/build.gradle.kts`, NOT
     gradle.properties): `git show upstream/master:app/build.gradle.kts | grep -E 'appVersionName|appVersionCode'`.
   - **Summarize the new upstream work for 白い熊 BEFORE touching any conflicts** (白い熊's standing
     request): skim `git log --oneline master..upstream/master` + the new `CHANGELOG.md` sections
     (`git show upstream/master:CHANGELOG.md`) and give a short prose summary of the notable upstream
     features / fixes. Only then advance `master` + rebase.

2. **Advance `master`** (mirror; no fork work lives here):
   - `git checkout master`
   - `git merge --ff-only upstream/master`
   - `git push origin master`

3. **Rebase `custom`:**
   - `git checkout custom`
   - `git rebase master`
   - Resolve conflicts so **all** our customizations survive (table below). The new upstream
     `appVersionName` / `appVersionCode` values flow in automatically — keep **upstream's** values for
     those two literals; our fork-version block multiplies them, so we never edit them by hand.

4. **Reset the build tail:**
   - In `gradle.properties`, set **`BUILD_NUMBER=1`** (new upstream line starts its `+N` at 1).

5. **Verify our customizations are intact after the rebase:**

   | What | Expected | Where |
   | --- | --- | --- |
   | Installed app id | `shiroikuma.jiyusagyoban` | `app/build.gradle.kts` → `defaultConfig.applicationId` |
   | Code namespace | `com.opentasker.app` (unchanged from upstream) | `app/build.gradle.kts` → `namespace` |
   | App label | `白い熊 自由作業盤` | `app_name` in `app/src/main/res/values/strings.xml` |
   | Fork version | `versionCode = forkVersionCode`, `versionName = forkVersionName` + the fork blocks | `app/build.gradle.kts` |
   | Signing | `keystore.properties` block + `useKeystoreProperties` | `app/build.gradle.kts` |
   | Archive name + task | `archivesName = "shiroikuma-jiyusagyoban_…"` + `buildFork` task | `app/build.gradle.kts` |
   | Build tail | `BUILD_NUMBER=1` | `gradle.properties` |
   | Committed agent files | `CLAUDE.md`, `.claude/` un-ignored; only `.claude/settings.local.json` out | `.gitignore` |
   | Black-yellow icon | yellow foreground + black `ic_launcher_background` | `app/src/main/res/mipmap/…` + `values/colors.xml` |
   | Send-intent action | our `SendIntentAction` registered + manifest queries | `app/src/main/java/com/opentasker/core/actions/…`, `AndroidManifest.xml` |

   Conflict-prone files: `app/build.gradle.kts`, `gradle.properties`, `values/strings.xml`, `.gitignore`,
   and any action source we patched. If upstream restructured the actions registry, re-apply our
   `SendIntentAction` (impl + registration + metadata) against the new layout.

6. **Build the new `+1`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFork < /dev/null`), then deliver it via
   the global **/after-build** skill (no transfer prompt). This is the first build of the new upstream line (`<newVersion>+1`).

7. **Stop.** Let 白い熊 test. Commit/push only on their explicit **"Push"**. `custom` was rebased, so it
   needs `git push --force-with-lease origin custom`; `master` is a plain fast-forward.

## Hard rules
- Never `adb install` / `adb uninstall` — 白い熊 installs manually from `/sdcard/tmp/`.
- Never commit/push unprompted; wait for "Push".
- No Claude attribution in commits (see `CLAUDE.md`).
