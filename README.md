# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.66-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker** is a fully open-source, on-device, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. No cloud, no accounts, no telemetry — automations run entirely on your device.

**What it does today:**

- **Profiles → contexts → tasks → actions** — a complete Room-backed automation pipeline with a Compose UI
- **Triggers (contexts):** time/day schedules, device state, app foreground, WiFi, data/internet connectivity, notifications, NFC tags, calendar windows, sunrise/sunset, shake, Bluetooth connect/disconnect, package install/remove/replace, Quick Settings tile, home-screen widget/shortcut, boot, and FOSS platform location/geofence
- **49 built-in actions** plus a `task.run` sub-task action for reusable building blocks; unsupported privileged actions fail honestly with backend hints
- **Template expressions** — bounded `{{ ... }}` expansion in arguments and conditions with scopes, arrays, JSON paths, string/math functions, traces, and a strict regex policy
- **Reliability tooling** — OEM battery-killer detection with per-vendor setup guidance, a setup checklist, context inspector with live source health, and step-level run logs with retention
- **Interop** — Locale/Tasker plugin host (setting + condition), signature-scoped external automation intents, OpenTasker JSON bundle import/export, Tasker XML import, and offline profile-share manifests
- **Distribution** — F-Droid and Play readiness profiles, dependency version governance, and SQLite backup/restore
- **Power-user readiness (detection only):** Shizuku and Termux status surfaces; elevated/script execution is not wired yet

**Planned:** broad device-verified background geofence reliability, elevated (Shizuku) execution, Termux script dispatch, a visual flow authoring editor, and richer plugin UX. See [ROADMAP.md](ROADMAP.md).

> **Status:** the current source version is `0.2.66`. Device-evidence claims (location/calendar/sun) are single-device API 36 data points on `SM-S938B`, not broad background-geofence reliability guarantees. The latest polish pass improves IME handling, compact small-screen bottom navigation with a More menu, squared-off navigation/FAB affordances, variable and scene-element deletion safety, saveable editor/context/template/scene-element state, scene-element nudge controls for non-drag movement, numeric form keyboards, larger day-token touch targets, and accessibility roles for widget/flow/form switch targets.

---

## Highlights

✅ **Core engine operational** — profiles → contexts → tasks → actions pipeline  
✅ **49 registered action definitions** — supported actions run, restricted/script/import-placeholder actions are gated or fail explicitly
✅ **Reusable sub-tasks** — the `task.run` action calls another task by id/name (shared variables, depth-bounded to 8 levels)
✅ **Flow control** — `if`/`else`/`end if` branching, `for each`/`end for` loops over array variables, and `stop`, interpreted with balanced-block validation
✅ **Reactive context sources** — app foreground, time, day schedules, state, WiFi, data/internet connectivity, notifications, NFC tag scans/write helper, calendar windows, sunrise/sunset matching, shake, Bluetooth connect/disconnect, package lifecycle, Quick Settings tile, home-screen widget/shortcut, boot, and platform location fixes are wired; broader device-verified background location event-delivery remains planned runtime work
✅ **OEM reliability guidance** — detects Samsung/Xiaomi/OnePlus/Oppo/Realme/Vivo/Huawei/etc. and surfaces per-vendor battery-killer remediation with deep-links and dontkillmyapp.com references
✅ **Template expression runtime** — action arguments and conditions support bounded `{{ ... }}` expansion with scoped variables, arrays, JSON paths, string/math functions, traces, and warnings
✅ **FOSS geofence source/evaluator** — Location context matching supports platform GPS/network fixes, balanced provider cadence, policy disclosures, radius, accuracy, persisted dwell checks, stale-key cleanup, inspector dwell detail, a Location evidence template, adb run-log/logcat evidence collection, provider-cadence/unplugged-sample/post-reconnect history gates, and API 36 background event-delivery smoke evidence without Play Services
✅ **AMOLED-first** — Catppuccin Mocha (dark) and Latte (light) palettes, following the system theme  
✅ **Compose UI reintegration started** — active navigation now manages profiles, tasks, actions, contexts, and run logs from Room  
✅ **Profile templates** — guided starter profiles with variable slots, safety notes, disabled-by-default installation, and a setup-required Location evidence template
✅ **Scene element editor** — Room-backed scene list/create/delete plus button, text, slider, and image element editing with tap/long-press task bindings, scaled canvas previews, and drag-to-move layout edits
✅ **Read-only flow graphs** — optional Flow tab maps profiles to contexts, enter/exit tasks, action steps, and warnings
✅ **Shizuku readiness** — optional package/status detection and elevated-action hints without executing privileged calls
✅ **Termux scripting readiness** — optional Termux/Termux:Tasker detection and a blocked script action without arbitrary execution
✅ **Open JSON bundles** — schema-versioned profile/task/context/action/variable/scene export and disabled-by-default import through Android's document picker with warnings and capability review
✅ **Database backup/restore** — Setup can create local SQLite backups, export a fresh backup with Android's file picker, and import a validated backup for restore on the next app restart
✅ **Profile sharing manifests** — offline share summaries with safety findings and GitHub Discussions submission text
✅ **Tasker XML import UI** — Tasker XML files can be selected, previewed with migration/capability warnings, and imported into Room as disabled profiles for review
✅ **F-Droid/Play distribution readiness** — property-based `fdroid` and `play` profiles, dependency-policy and metadata checks, local fdroidserver lint/build evidence, Play SMS/phone-state manifest policy gate, and APK payload comparison harness
✅ **Dependency governance** — Gradle version catalog centralizes plugin/library versions before staged upgrades
✅ **Locale plugin host baseline** — explicit setting-plugin dispatch, condition-plugin query execution, configuration result parsing, request-query events, package validation, bundle limits, last-known unknown-state fallback, timeout wrapping, and adb validation harness
✅ **External automation target** — signature-scoped intents to run tasks, toggle profiles, query status, and pass variables
✅ **Automation modes** — per-profile single, restart, queued, and parallel re-trigger behavior  
✅ **Setup checklist** — notifications, exact alarms, battery optimization, usage access, notification access, overlays, location, Bluetooth, SMS when included, and DND access
✅ **Scheduled time ticks** — AlarmManager exact scheduling when allowed, inexact fallback when Android denies exact alarms  
✅ **WiFi trigger monitor** — dynamic NetworkCallback path instead of restricted manifest connectivity broadcasts  
✅ **App-open monitor** — foreground-service-owned UsageStats polling with explicit missing-permission pause behavior  
✅ **Runtime action registry** — UI action IDs map to real implementations or explicit unsupported failures  
✅ **Capability gating** — action picker marks setup requirements and blocks unsupported privileged actions  
✅ **Run log traces** — task history includes action-level status, duration, failure reasons, sanitized template expansions, warning counts, and per-expression diagnostics
✅ **Run log retention** — task history is bounded by configurable Log tab presets; the default keeps 30 days or 1,000 entries and prunes in the background
✅ **Context inspector** — live source health, latest observed values, and per-profile match explanations<br>
✅ **Notification listener events** — notification access can feed `event=notification` contexts with package/title/body filters<br>
✅ **NFC tag events** — tag scans can feed `event=nfc` contexts with normalized tag ID matching<br>
✅ **Calendar and sun events** — Calendar access feeds redacted `event=calendar` windows; coordinate-based `sunrise`/`sunset` filters support offsets and editor presets; adb smoke evidence covers permission/provider/service readiness<br>
✅ **Regression coverage** — cron parsing, variable scoping, and template expression edge cases are test-covered
✅ **Database persistence** — Room DAOs with StateFlow live updates  
✅ **Action metadata system** — dynamic form generation for all registered action definitions
✅ **Context configuration UI** — editor supports all 6 context families while runtime support continues to harden by family  
✅ **100% Kotlin** — modern, type-safe, coroutine-friendly  
✅ **Profile execution** — AutomationService wired to TaskRunner for context triggers  
✅ **Run log persistence** — task execution history is written to Room with configurable retention
✅ **Tasker compat** — `.prj.xml`/`.tsk.xml` exports can be previewed and imported through a disabled-by-default review flow

---

## Architecture

```
AutomationService (foreground)
  ↓
ProfileMatcher (monitors context streams)
  ↓
ContextSources (app, time, state, event, location)
  ↓
TaskRunner (executes action list)
  ↓
ActionRegistry (built-ins + capability gates + Locale setting and condition plugin dispatch)
  ↓
Room DB (persistent storage)
```

### Contexts
- **Runtime-wired now:** Application foreground detection, time ticks, day schedules with presets/ranges, device state broadcasts, event broadcasts, WiFi network changes, app-open monitoring, notification listener events, NFC tag events, calendar windows, sunrise/sunset event filters, and platform location fixes.
- **Configured in UI but still being hardened:** Location/geofence contexts. Location matching now receives FOSS platform GPS/network events and supports radius, accuracy, persisted dwell evaluation, a focused API 36 background event-delivery smoke test, and harness gates for provider cadence plus unplugged battery samples or post-reconnect battery history; broader multi-device reliability evidence remains planned runtime work.

### Actions (49 registered + 7 engine-handled Flow controls)
| Category | Count | Examples |
|----------|-------|----------|
| Settings | 11 | WiFi, Bluetooth, brightness, volume, airplane, mobile data, screen timeout, DND, ringer mode, torch, Quick Settings tile state |
| App | 7 | launch intent, launch app, kill, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 5 | HTTP GET/POST, ping, download, Wake-on-LAN |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 6 | vibrate, reboot, lock, screen off/wake, log |
| Notification | 3 | notification/toast, cancel, TTS speak |
| Variable | 1 | set variable |
| Flow | 1 + 7* | wait; *engine-handled: run sub-task (`task.run`), if/else/end if, for each/end for, stop |
| Plugin | 2 | Locale setting dispatch, Locale condition query |
| Script | 1 | gated Termux script run |
| Import | 1 | unsupported Tasker action placeholder |

Flow-control actions (`task.run`, `flow.if`/`flow.else`/`flow.endif`, `flow.foreach`/`flow.endfor`, `flow.stop`) are interpreted directly by the engine (TaskRunner) rather than the action registry, so the registry holds 49 implementations while the editor exposes those 7 extra control entries.

Some actions are intentionally disabled or marked setup-required because Android restricts normal apps from changing airplane mode, mobile data, screenshots, reboot, screen-off, and similar privileged operations. SMS send remains available in standard/F-Droid builds; Play builds omit SMS and phone-state permissions and mark the SMS action unsupported. Shizuku manager detection and Termux script bridge detection are available only as readiness signals; OpenTasker does not request Shizuku permission, request Termux `RUN_COMMAND`, execute elevated commands, or run scripts yet.

---

## Build & Run

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:testDebugUnitTest :app:assembleDebug
# or Release:
./gradlew :app:assembleRelease
# Play distribution policy check:
./gradlew -PopenTaskerDistribution=play :app:verifyPlayManifestPolicy
```

Install on device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release APKs are unsigned unless `OPEN_TASKER_RELEASE_KEYSTORE`, `OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD`, `OPEN_TASKER_RELEASE_KEY_ALIAS`, and `OPEN_TASKER_RELEASE_KEY_PASSWORD` are set in the build environment.

---

## Next Phase (v0.3)

1. **Dependency modernization batches** — staged AndroidX/Kotlin/Room/WorkManager updates with device verification
2. **Geofence durability** — broader background-location behavior evidence and reliability claims across more device/API/provider combinations
3. **Scene resize handles** — direct resize/multi-select layout edits before overlay launch
4. **Flow branch visualization** — branch/subflow display after explicit flow-control action types exist
5. **Shizuku API opt-in backend** — explicit user opt-in, permission request, isolated execution, and run-log audit trail
6. **Termux script dispatch** — explicit permission flow, script allowlisting, stdout/stderr capture, and output variable mapping
7. **Sharing preview UI** — import warnings, screenshots, permissions, and local review before importing community bundles
8. **Locale condition UX** — plugin-backed Condition context rows and clearer setup/error surfaces
9. **Dependency modernization follow-through** — staged library/plugin upgrades under the existing Gradle version-catalog plan

---

## Development

- **Toolchain:** Kotlin 2.3.21, Gradle 9.4.1, AGP 9.2.1, KSP 2.3.7, Build Tools 36.0.0, JDK 17/21
- **Min SDK:** 26 (Android 8.0)
- **Compile SDK:** 36 - **Target SDK:** 36
- **Permissions:** foreground service, PACKAGE_USAGE_STATS, system alert window (optional for scenes)
- **Architecture:** MVVM with Compose + Room + coroutines (manual DI via `OpenTaskerApp_NoHilt`; no Hilt)

---

## License

MIT — see [LICENSE](LICENSE).

## Contributing

Issues and pull requests welcome. See `ROADMAP.md` for planned features.

