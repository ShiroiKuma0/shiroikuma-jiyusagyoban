# OpenTasker

[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker v0.1.0** — a fully open-source, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. Profiles, contexts, tasks, actions, scenes, and variables — without the proprietary lock-in.

> **Status:** scaffolding / pre-alpha. Architecture and core data model are in place; runtime engine and UI are under active build-out. See [ROADMAP.md](ROADMAP.md).

---

## Why

Tasker is the gold-standard automation app on Android, but it is closed source, paid, and ties users to a single developer. OpenTasker aims to:

- Match Tasker feature-for-feature where practical
- Stay 100% FOSS (MIT) — no telemetry, no ads, no IAPs
- Modernize the codebase: Kotlin + Jetpack Compose + Material 3 + coroutines
- AMOLED-black by default; light theme optional
- Keep the same `.prj.xml` / `.tsk.xml` import surface where feasible so existing Tasker users can migrate profiles
- Expose a public plugin SDK with a stable AIDL contract

## Core concepts (mirrors Tasker)

| Concept | Description |
|---|---|
| **Profile** | Pairing of one or more **Contexts** with one or more **Tasks**. Active while contexts match. |
| **Context** | Trigger — Application, Time, Day, Location, State, Event. |
| **Task** | Ordered list of **Actions** with flow control (If/Else/For/Goto/Wait/Stop). |
| **Action** | Atomic unit of work — toggle WiFi, send notification, run shell, HTTP request, etc. |
| **Scene** | User-defined floating UI made of elements (Button, Slider, Map, Web, …). |
| **Variable** | `%name`-style global or local; expanded at action runtime. |
| **Plugin** | 3rd-party app exposing actions/conditions/events via AIDL. |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full breakdown.

## Stack

- **Kotlin 2.0**, **Jetpack Compose**, **Material 3**
- **Room** for profile/task/scene persistence
- **WorkManager** + foreground `AutomationService` for trigger evaluation
- **Coroutines / Flow** for context streams
- `minSdk 26` (Android 8.0), `targetSdk 35`

## Build

```bash
git clone https://github.com/SysAdminDoc/OpenTasker.git
cd OpenTasker
./gradlew assembleRelease
```

Signed release APK is attached to each [GitHub Release](https://github.com/SysAdminDoc/OpenTasker/releases).

## License

MIT — see [LICENSE](LICENSE).
