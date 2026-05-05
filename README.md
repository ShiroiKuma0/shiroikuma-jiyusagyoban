# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.53-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker v0.2.53** — a fully open-source, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. Profiles, contexts, tasks, actions, day schedule presets/ranges, runtime template argument and condition expansion, per-expression template diagnostics, explicit regex template policy, FOSS geofence evaluation with a live platform location source, a disabled Location evidence template, app-launch service startup, adb-backed location evidence collection with run-log/logcat assertions, provider-cadence, unplugged-sample, and post-reconnect unplugged-history gates, background Location event-delivery smoke evidence, balanced provider cadence, policy-aware location setup copy, persisted dwell state, inspector dwell details, and stale-key cleanup, scene library management, read-only flow graphs, JSON bundles, profile sharing manifests, Tasker XML import planning, F-Droid build readiness, dependency version governance, optional Shizuku readiness, optional Termux script readiness, external automation intents, context inspection, notification listener triggers, NFC tag triggers, calendar/sun triggers, and conservative Locale setting/condition plugin host actions with configuration and request-query handling are active now; broad device-verified background geofence reliability, elevated backends, script execution, and broader plugin UX are planned.

> **Status:** v0.2.53 listens for Locale condition plugin `REQUEST_QUERY` broadcasts while the foreground runtime is active and emits sanitized `event=locale_request_query` context events. v0.2.52 adds explicit Locale edit-setting/edit-condition activity resolution and guarded configuration result parsing. v0.2.51 adds best-effort last-known-state handling for Locale condition plugins. v0.2.48 evidence `build/device-evidence/location/20260505-143254` shows a 615.055-second recent unplugged interval that satisfied the 600-second post-reconnect history gate with GPS/network provider cadence evidence present. This is still a single-device durability data point, not a broad background geofence reliability claim; broader geofence reliability still needs multi-device verification.

---

## Highlights

✅ **Core engine operational** — profiles → contexts → tasks → actions pipeline  
✅ **43 registered action definitions** — supported actions run, restricted/script/import-placeholder actions are gated or fail explicitly
✅ **Reactive context sources** — app foreground, time, day schedules, state, event, WiFi, app-open monitoring, notifications, NFC tag scans, calendar windows, sunrise/sunset matching, and platform location fixes are wired; broader device-verified background geofencing remains planned runtime work
✅ **Template expression runtime** — action arguments and conditions support bounded `{{ ... }}` expansion with scoped variables, arrays, JSON paths, string/math functions, traces, and warnings
✅ **FOSS geofence source/evaluator** — Location context matching supports platform GPS/network fixes, balanced provider cadence, policy disclosures, radius, accuracy, persisted dwell checks, stale-key cleanup, inspector dwell detail, a Location evidence template, adb run-log/logcat evidence collection, provider-cadence/unplugged-sample/post-reconnect history gates, and API 36 background event-delivery smoke evidence without Play Services
✅ **AMOLED-first** — Catppuccin Mocha palette, light theme toggle  
✅ **Compose UI reintegration started** — active navigation now manages profiles, tasks, actions, contexts, and run logs from Room  
✅ **Profile templates** — guided starter profiles with variable slots, safety notes, disabled-by-default installation, and a setup-required Location evidence template
✅ **Scene library baseline** — Room-backed scene list/create/delete with validation and overlay readiness
✅ **Read-only flow graphs** — optional Flow tab maps profiles to contexts, enter/exit tasks, action steps, and warnings
✅ **Shizuku readiness** — optional package/status detection and elevated-action hints without executing privileged calls
✅ **Termux scripting readiness** — optional Termux/Termux:Tasker detection and a blocked script action without arbitrary execution
✅ **Open JSON bundles** — schema-versioned profile/task/context/action/variable/scene export and import planning  
✅ **Profile sharing manifests** — offline share summaries with safety findings and GitHub Discussions submission text
✅ **Tasker XML import planning** — common Tasker task/profile/variable XML converts to OpenTasker bundles with migration warnings
✅ **F-Droid readiness** — property-based `fdroid` profile, dependency-policy check, and metadata draft
✅ **Dependency governance** — Gradle version catalog centralizes plugin/library versions before staged upgrades
✅ **Locale plugin host baseline** — explicit setting-plugin dispatch, condition-plugin query execution, configuration result parsing, request-query events, package validation, bundle limits, last-known unknown-state fallback, and timeout wrapping
✅ **External automation target** — signature-scoped intents to run tasks, toggle profiles, query status, and pass variables
✅ **Automation modes** — per-profile single, restart, queued, and parallel re-trigger behavior  
✅ **Setup checklist** — notifications, exact alarms, battery optimization, usage access, notification access, overlays, location, Bluetooth, SMS, and DND access  
✅ **Scheduled time ticks** — AlarmManager exact scheduling when allowed, inexact fallback when Android denies exact alarms  
✅ **WiFi trigger monitor** — dynamic NetworkCallback path instead of restricted manifest connectivity broadcasts  
✅ **App-open monitor** — foreground-service-owned UsageStats polling with explicit missing-permission pause behavior  
✅ **Runtime action registry** — UI action IDs map to real implementations or explicit unsupported failures  
✅ **Capability gating** — action picker marks setup requirements and blocks unsupported privileged actions  
✅ **Run log traces** — task history includes action-level status, duration, failure reasons, sanitized template expansions, warning counts, and per-expression diagnostics
✅ **Context inspector** — live source health, latest observed values, and per-profile match explanations<br>
✅ **Notification listener events** — notification access can feed `event=notification` contexts with package/title/body filters<br>
✅ **NFC tag events** — tag scans can feed `event=nfc` contexts with normalized tag ID matching<br>
✅ **Calendar and sun events** — Calendar access feeds redacted `event=calendar` windows; coordinate-based `sunrise`/`sunset` filters support offsets<br>
✅ **Regression coverage** — cron parsing, variable scoping, and template expression edge cases are test-covered
✅ **Database persistence** — Room DAOs with StateFlow live updates  
✅ **Action metadata system** — dynamic form generation for all 43 registered action definitions
✅ **Context configuration UI** — editor supports all 6 context families while runtime support continues to harden by family  
✅ **100% Kotlin** — modern, type-safe, coroutine-friendly  
✅ **Profile execution** — AutomationService wired to TaskRunner for context triggers  
✅ **Run log persistence** — task execution history is written to Room  
⏳ **Tasker compat** — architecture matches for future `.prj.xml` import

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

### Actions (43 registered definitions)
| Category | Count | Examples |
|----------|-------|----------|
| Settings | 7 | WiFi, Bluetooth, brightness, volume, airplane, mobile data, screen timeout |
| App | 7 | launch intent, launch app, kill, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 4 | HTTP GET/POST, ping, download |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 6 | vibrate, reboot, lock, screen off/wake, log |
| Notification | 2 | notification/toast, TTS speak |
| Variable | 1 | set variable |
| Flow | 1 | wait |
| Plugin | 2 | Locale setting dispatch, Locale condition query |
| Script | 1 | gated Termux script run |
| Import | 1 | unsupported Tasker action placeholder |

Some actions are intentionally disabled or marked setup-required because Android restricts normal apps from changing airplane mode, mobile data, screenshots, reboot, screen-off, and similar privileged operations. Shizuku manager detection and Termux script bridge detection are available only as readiness signals; OpenTasker does not request Shizuku permission, request Termux `RUN_COMMAND`, execute elevated commands, or run scripts yet.

---

## Build & Run

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:testDebugUnitTest :app:assembleDebug
# or Release:
./gradlew :app:assembleRelease
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
3. **Scene element editor** — add text/button/slider/image controls and task binding pickers before overlay launch
4. **Flow graph deep links** — node selection that opens the relevant profile, task, context, or action editor
5. **Shizuku API opt-in backend** — explicit user opt-in, permission request, isolated execution, and run-log audit trail
6. **Termux script dispatch** — explicit permission flow, script allowlisting, stdout/stderr capture, and output variable mapping
7. **Sharing preview UI** — import warnings, screenshots, permissions, and local review before importing community bundles
8. **Locale condition queries** — complete query execution for condition plugins with timeout and result handling
9. **Tasker import UI** — file picker, migration preview, and import confirmation around the parser baseline
10. **F-Droid submission hardening** — release tag discipline, fdroidserver lint/build, and reproducible binary comparison

---

## Development

- **IDE:** Android Studio Koala+ (Kotlin 2.0)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35
- **Permissions:** foreground service, PACKAGE_USAGE_STATS, system alert window (optional for scenes)
- **Architecture:** MVVM with Compose + Room + coroutines

---

## License

MIT — see [LICENSE](LICENSE).

## Contributing

Issues and pull requests welcome. See `ROADMAP.md` for planned features.

