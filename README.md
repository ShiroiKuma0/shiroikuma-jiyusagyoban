# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.21-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker v0.2.21** — a fully open-source, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. Profiles, contexts, tasks, actions, JSON bundles, Tasker XML import planning, external automation intents, context inspection, notification listener triggers, NFC tag triggers, calendar/sun triggers, and a conservative Locale plugin host baseline are active now; scenes, variable tooling, and broader plugin UX are planned.

> **Status:** v0.2.21 adds a Tasker XML parser that produces OpenTasker JSON bundles with an explicit migration report, while keeping local calendar/sun triggers, NFC tag events, notification listener events, the context inspector, per-profile automation modes, signature-scoped external intents, Locale plugin dispatch, guided templates, profile/task/action/context/run-log management, setup checks, platform-safe monitors, capability gates, and action-level run log traces active.

---

## Highlights

✅ **Core engine operational** — profiles → contexts → tasks → actions pipeline  
✅ **41 registered action definitions** — supported actions run, restricted/import-placeholder actions are gated or fail explicitly
✅ **Reactive context sources** — app foreground, time, state, event, WiFi, app-open monitoring, notifications, NFC tag scans, calendar windows, and sunrise/sunset matching are wired; day/location are planned runtime work
✅ **AMOLED-first** — Catppuccin Mocha palette, light theme toggle  
✅ **Compose UI reintegration started** — active navigation now manages profiles, tasks, actions, contexts, and run logs from Room  
✅ **Profile templates** — guided starter profiles with variable slots, safety notes, and disabled-by-default installation  
✅ **Open JSON bundles** — schema-versioned profile/task/context/action/variable/scene export and import planning  
✅ **Tasker XML import planning** — common Tasker task/profile/variable XML converts to OpenTasker bundles with migration warnings
✅ **Locale plugin host baseline** — explicit setting-plugin dispatch with package validation, bundle limits, and timeout wrapping  
✅ **External automation target** — signature-scoped intents to run tasks, toggle profiles, query status, and pass variables
✅ **Automation modes** — per-profile single, restart, queued, and parallel re-trigger behavior  
✅ **Setup checklist** — notifications, exact alarms, battery optimization, usage access, notification access, overlays, location, Bluetooth, SMS, and DND access  
✅ **Scheduled time ticks** — AlarmManager exact scheduling when allowed, inexact fallback when Android denies exact alarms  
✅ **WiFi trigger monitor** — dynamic NetworkCallback path instead of restricted manifest connectivity broadcasts  
✅ **App-open monitor** — foreground-service-owned UsageStats polling with explicit missing-permission pause behavior  
✅ **Runtime action registry** — UI action IDs map to real implementations or explicit unsupported failures  
✅ **Capability gating** — action picker marks setup requirements and blocks unsupported privileged actions  
✅ **Run log traces** — task history includes action-level status, duration, and failure reasons  
✅ **Context inspector** — live source health, latest observed values, and per-profile match explanations<br>
✅ **Notification listener events** — notification access can feed `event=notification` contexts with package/title/body filters<br>
✅ **NFC tag events** — tag scans can feed `event=nfc` contexts with normalized tag ID matching<br>
✅ **Calendar and sun events** — Calendar access feeds redacted `event=calendar` windows; coordinate-based `sunrise`/`sunset` filters support offsets<br>
✅ **Regression coverage** — cron parsing and variable scoping edge cases are test-covered  
✅ **Database persistence** — Room DAOs with StateFlow live updates  
✅ **Action metadata system** — dynamic form generation for all 40 registered action definitions  
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
ContextSources (app, time, state, event)
  ↓
TaskRunner (executes action list)
  ↓
ActionRegistry (built-ins + capability gates + Locale setting plugin dispatch)
  ↓
Room DB (persistent storage)
```

### Contexts
- **Runtime-wired now:** Application foreground detection, time ticks, device state broadcasts, event broadcasts, WiFi network changes, app-open monitoring, notification listener events, NFC tag events, calendar windows, and sunrise/sunset event filters.
- **Configured in UI but still being hardened:** Day schedules and location/geofence contexts.

### Actions (41 registered definitions)
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
| Plugin | 1 | Locale setting dispatch |
| Import | 1 | unsupported Tasker action placeholder |

Some actions are intentionally disabled or marked setup-required because Android restricts normal apps from changing airplane mode, mobile data, screenshots, reboot, screen-off, and similar privileged operations.

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

1. **F-Droid readiness** — flavor policy, reproducibility assumptions, metadata, and release checks
2. **Dependency modernization** — staged AndroidX/Kotlin/Room/WorkManager updates with device verification
3. **Locale condition queries** — complete query execution for condition plugins with timeout and result handling
4. **Tasker import UI** — file picker, migration preview, and import confirmation around the parser baseline
5. **Calendar/sun polish** — richer presets, calendar privacy review, and device-level smoke coverage
6. **NFC write-helper polish** — optional tag-writing flow around the active tag ID trigger baseline

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

