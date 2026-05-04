# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.0-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker v0.2.0** — a fully open-source, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android. Profiles, contexts, tasks, actions, scenes, and variables — without the proprietary lock-in.

> **Status:** v0.2.0 complete with full UI layer: profile/task CRUD, action editor (43 types), context picker, database persistence via Room. Engine fully operational with context sources. Ready for automation execution and run log visualization.

---

## Highlights

✅ **Core engine operational** — profiles → contexts → tasks → actions pipeline  
✅ **43 built-in actions** — settings, apps, files, network, media, system  
✅ **Reactive contexts** — battery, headphones, screen, WiFi, app foreground, time, SMS/events  
✅ **AMOLED-first** — Catppuccin Mocha palette, light theme toggle  
✅ **Compose UI** — full CRUD for profiles/tasks/contexts/actions with dynamic forms  
✅ **Database persistence** — Room DAOs with StateFlow live updates  
✅ **Action metadata system** — dynamic form generation for all 43 actions  
✅ **Context configuration** — UI for all 6 context families (app, time, day, location, state, event)  
✅ **100% Kotlin** — modern, type-safe, coroutine-friendly  
⏳ **Profile execution** — AutomationService wired to TaskRunner for context triggers  
⏳ **Run log viewer** — real-time StateFlow-based execution history  
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
./gradlew assembleDebug
# or Release:
./gradlew assembleRelease
```

Install on device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Signed release APK attached to each [GitHub Release](https://github.com/SysAdminDoc/OpenTasker/releases).

---

## Next Phase (v0.3)

1. **Profile execution** — wire AutomationService to ProfileMatcher and TaskRunner
2. **Run log viewer** — StateFlow-based real-time task execution display
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

