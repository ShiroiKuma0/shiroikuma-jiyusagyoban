# Changelog

## v0.2.1 — 2026-05-04

Production hardening pass.

- Fixed Windows and Linux Gradle bootstrap scripts so builds work from paths containing `--`.
- Aligned app version metadata and README badge to the shipped APK version.
- Re-enabled release minification and resource shrinking while keeping unsigned release builds possible without local secrets.
- Consolidated release CI and added a push/PR build workflow.
- Removed tracked local build artifacts and machine-specific configuration from the repository.
- Replaced broken Hilt runtime entrypoints with the active non-Hilt application singleton wiring.
- Hardened shell, intent, file, network, notification, settings, geofence, receiver, backup, and JSON parsing paths.
- Added Room schema export and focused validation unit tests.
- Improved shared Compose component semantics and light-theme error contrast.

## v0.2.0 — 2026-05-04

Full UI layer with database integration and action editor.

- **Database integration:** Room DAOs with StateFlow live updates for profiles and tasks
- **Profile CRUD:** Create, edit, delete profiles with persistence
- **Task CRUD:** Create, edit, delete tasks with action lists
- **Action editor:** Dynamic form generation for all 43 actions based on metadata registry
- **Context picker:** Multi-select context families with predicate configuration (app, time, day, location, state, event)
- **Action metadata system:** Comprehensive metadata for all built-in actions with field types and validation
- **Task list screen:** Dedicated view to browse and manage all tasks
- **Profile enable/disable toggle:** Toggle profiles on/off with database update
- **Gradle 8.9 toolchain:** Updated from 8.7 for AGP 8.7.2 compatibility
- **Lint baseline:** Suppressed MissingPermission and CoarseFineLocation warnings

## v0.1.0 — 2026-05-03

Initial scaffold.

- Project skeleton: Kotlin 2.0 + Jetpack Compose + Material 3
- Core data model: Profile / Context / Task / Action / Scene / Variable
- AMOLED-black default theme
- Architecture document (`docs/ARCHITECTURE.md`)
- Roadmap (`ROADMAP.md`) tracking parity with Tasker feature surface
- MIT license, shields.io badges
