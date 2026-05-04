# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.1-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker v0.2.1** — a fully open-source, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. Profiles, contexts, tasks, actions, scenes, and variables — without the proprietary lock-in.

> **Status:** v0.2.1 is a production-hardening build. The active APK currently ships a minimal Compose status screen while the richer CRUD screens remain in source snapshots pending reintegration. Core automation/storage code compiles, unit tests run, and debug/release APKs build with R8 enabled.

---

## Highlights

✅ **Core engine operational** — profiles → contexts → tasks → actions pipeline  
✅ **43 built-in actions** — settings, apps, files, network, media, system  
✅ **Reactive contexts** — battery, headphones, screen, WiFi, app foreground, time, SMS/events  
✅ **AMOLED-first** — Catppuccin Mocha palette, light theme toggle  
⏳ **Compose UI reintegration** — full CRUD screens exist as source snapshots and need to be restored into the active navigation graph  
✅ **Database persistence** — Room DAOs with StateFlow live updates  
✅ **Action metadata system** — dynamic form generation for all 43 actions  
✅ **Context configuration** — UI for all 6 context families (app, time, day, location, state, event)  
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
ActionRegistry (43 built-ins + plugin SDK)
  ↓
Room DB (persistent storage)
```

### Contexts (6 families)
- **Application** — foreground app detection (UsageStatsManager)
- **Time** — clock windows (09:00–17:00)
- **Day** — weekly schedule (SMTWTFS)
- **Location** — geofence (skeleton)
- **State** — device state (battery ≥80%, headphones, screen on/off, WiFi)
- **Event** — one-shots (SMS, boot, intents)

### Actions (43 built-in)
| Category | Count | Examples |
|----------|-------|----------|
| Settings | 7 | WiFi, Bluetooth, brightness, volume, airplane, mobile data, screen timeout |
| App | 6 | launch, kill, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 4 | HTTP GET/POST, ping, download |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 6 | vibrate, reboot, lock, screen off/wake, log |
| Flow | 3 | wait, set variable, TTS speak |

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

1. **UI reintegration** — restore the richer profile/task/context/action screens into the active APK navigation graph
2. **Permission onboarding** — explain and request Usage Stats, notifications, location, overlay, and settings-write access from the app
3. **Variable inspector** — display task runtime variables during execution
4. **Tasker import** — `.prj.xml` parser for profile migration
5. **Plugin SDK** — stable AIDL interface for custom actions
6. **Scheduled tasks** — AlarmManager-based background task execution

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

