# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.70-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker** is a fully open-source, on-device, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android.

---

## Features

### Automation engine

- **Profiles, contexts, tasks, actions** — a complete Room-backed automation pipeline with a Compose UI
- **7 context families** — Application, Time, Day, Location, State, Event, and Plugin (Locale/Tasker condition)
- **49 built-in actions** plus engine-handled flow control (`task.run`, `if`/`else`/`end if`, `for each`/`end for`, `stop`)
- **Template expressions** — bounded `{{ ... }}` expansion with scoped variables, arrays, JSON paths, string/math/date functions, traces, and strict regex policy
- **Automation modes** — per-profile single, restart, queued, and parallel re-trigger behavior
- **Profile groups** — organize profiles into named groups with filter chips

### Triggers (contexts)

- Time/day schedules with presets, aliases, and ranges
- Device state (battery, charging, headphones, screen, airplane, power save, Wi-Fi SSID)
- App foreground detection via UsageStats
- Wi-Fi and data/internet connectivity via NetworkCallback
- Notification listener with package/title/body filters
- NFC tag scans with normalized ID matching and a one-time NDEF write helper
- Calendar windows with redacted event metadata
- Sunrise/sunset filters with coordinate, offset, and window support
- Shake, Bluetooth connect/disconnect, package install/remove/replace
- Quick Settings tile tap, home-screen widget/shortcut, boot
- FOSS platform location/geofence — GPS/network fixes, balanced provider cadence, radius/accuracy/dwell evaluation, persisted dwell state, and API 36 background delivery evidence
- Locale/Tasker condition plugins — polled as first-class context predicates with last-known-state caching

### Actions (49 registered + 7 engine-handled)

| Category | Count | Examples |
|----------|------:|---------|
| Settings | 11 | Wi-Fi, Bluetooth, brightness, volume, airplane, mobile data, screen timeout, DND, ringer mode, torch, tile state |
| App | 7 | launch intent, launch app, kill, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 5 | HTTP GET/POST, ping, download, Wake-on-LAN |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 6 | vibrate, reboot, lock, screen off, wake, log |
| Notification | 3 | notify/toast, cancel, TTS speak |
| Variable | 1 | set variable |
| Flow | 1+7 | wait; engine: task.run, if/else/end if, for each/end for, stop |
| Plugin | 2 | Locale setting dispatch, Locale condition query |
| Script | 1 | gated Termux script run (not yet wired) |
| Import | 1 | unsupported Tasker action placeholder |

Privileged actions (airplane, mobile data, screenshot, reboot, screen off) are gated to fail honestly. SMS is available in standard/F-Droid builds; Play builds omit SMS/phone-state permissions.

### Reliability and observability

- OEM battery-killer detection with per-vendor remediation (Samsung, Xiaomi, OnePlus, Oppo, Realme, Vivo, Huawei, etc.)
- Setup checklist covering notifications, exact alarms, battery optimization, usage access, overlays, location, Bluetooth, SMS, DND, Shizuku, and Termux
- Context inspector with live source health, latest values, and per-profile match explanations
- Step-level run logs with action traces, template diagnostics, warning counts, and configurable retention
- Diagnostic report sharing with automatic sensitive-data redaction
- Crash log capture and local diagnostic export

### Interoperability

- **Locale/Tasker plugin host** — setting dispatch, condition queries, configuration parsing, request-query events, bundle validation, and last-known-state fallback
- **Locale/Tasker condition context** — condition plugins as first-class profile predicates polled every 30 seconds
- **External automation target** — signature-scoped intents to run tasks, toggle profiles, query status, and pass variables
- **OpenTasker JSON bundles** — schema-versioned export/import with warnings, capability review, and disabled-by-default installation
- **Tasker XML import** — preview with migration/capability warnings, mapped and unsupported action reporting
- **Profile sharing** — offline share manifests with safety findings and GitHub Discussions submission text

### UI and theming

- AMOLED-first Catppuccin Mocha (dark) and Latte (light) palettes, high contrast mode
- Guided profile templates with variable slots and safety notes
- Scene element editor with drag-to-move, scaled canvas previews, and tap/long-press task bindings
- Read-only flow graphs mapping profiles to contexts, tasks, and actions
- Profile and task search bars
- Saveable editor/dialog state across rotation and resize

### Distribution

- F-Droid readiness profile with dependency-policy and metadata verification
- Play distribution profile with SMS/phone-state manifest policy gate
- GitHub Actions CI with SHA-pinned actions and least-privilege permissions
- Environment-driven release signing
- SQLite database backup/restore
- APK payload comparison harness for reproducibility checks

### Power-user readiness (detection only)

- Shizuku manager status detection and elevated-action hints
- Termux/Termux:Tasker package detection and script readiness status

---

## Architecture

```
AutomationService (foreground)
  ↓
ProfileMatcher (monitors context streams)
  ↓
ContextSources (app, time, state, event, location, plugin)
  ↓
TaskRunner (executes action list with flow control)
  ↓
ActionRegistry (built-ins + capability gates + Locale plugin dispatch)
  ↓
Room DB (persistent storage + StateFlow live queries)
```

No Hilt — manual dependency wiring via `OpenTaskerApp_NoHilt`. MVVM with Compose, Room, coroutines, DataStore, and WorkManager.

---

## Build & Run

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release build (unsigned without keystore env vars):
```bash
./gradlew :app:assembleRelease
```

F-Droid profile:
```bash
./gradlew -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata
```

Play manifest policy check:
```bash
./gradlew -PopenTaskerDistribution=play :app:verifyPlayManifestPolicy
```

---

## Development

| Property | Value |
|----------|-------|
| Kotlin | 2.3.21 |
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| KSP | 2.3.7 |
| Build Tools | 36.0.0 |
| JDK | 17 or 21 |
| Min SDK | 26 (Android 8.0) |
| Compile SDK | 36 |
| Target SDK | 36 |
| Room | 2.8.4 |
| Compose BOM | 2026.04.01 |
| WorkManager | 2.11.2 |

All dependency versions are centralized in `gradle/libs.versions.toml`.

---

## Planned

See [ROADMAP.md](ROADMAP.md) for the full backlog. Key remaining work:

- Broad device-verified background geofence reliability evidence
- Target SDK 36 platform readiness pass (FGS, predictive back, large-screen QA)
- Macrobenchmark and Baseline Profile for cold-start performance
- Scene resize handles, multi-select layout edits, and overlay launch
- Visual flow editor authoring (drag/drop, branch visualization)
- Shizuku elevated execution backend
- Termux real script dispatch
- i18n/l10n bootstrap
- Health Connect polling trigger (wellness)
- Variable expression engine v3 (writeable nested paths, debugger view)

---

## License

MIT — see [LICENSE](LICENSE).

## Contributing

Issues and pull requests welcome. See [ROADMAP.md](ROADMAP.md) for planned features.
