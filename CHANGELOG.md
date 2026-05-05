# Changelog

## v0.2.26 - 2026-05-05

Shizuku readiness baseline.

- Added package visibility and runtime status detection for the Shizuku manager without linking the Shizuku API.
- Added an optional Setup checklist row for Shizuku power mode that is excluded from required readiness progress.
- Added elevated-action hints for Shizuku candidates while keeping restricted actions blocked.
- Documented the safe readiness scope and added tests for status, action hints, and manifest package visibility.

## v0.2.25 - 2026-05-05

Scene library baseline.

- Added a Room-backed Scenes tab that lists persisted scenes and supports safe scene creation/deletion.
- Added scene validation for positive dimensions, empty scenes, element bounds, and missing tap/long-press task bindings.
- Added scene cards with canvas summaries, element/binding previews, overlay-permission readiness status, and validation messages.
- Documented the scene baseline and updated roadmap/version metadata for L2.
- Added unit coverage for scene validation warnings, geometry errors, missing task references, and valid bounded elements.

## v0.2.24 - 2026-05-05

Visual flow baseline.

- Added a pure automation flow graph model that maps profiles to contexts, enter/exit tasks, actions, edges, and warnings.
- Added an optional Flow tab that renders read-only per-profile graphs from the active Room data without replacing the list/form editor.
- Added graph warnings for missing tasks, empty contexts, and empty task lanes.
- Documented the visual flow baseline and updated roadmap/version metadata for L1.
- Added unit coverage for enter chains, exit chains, missing task references, and empty-context warnings.

## v0.2.23 - 2026-05-05

Dependency modernization baseline.

- Added a Gradle version catalog for Android, Kotlin, Compose, Room, WorkManager, Coroutines, Hilt, Gson, and test dependency versions.
- Converted root and app Gradle plugin/dependency declarations to catalog aliases without changing dependency versions.
- Documented the staged dependency modernization order, risk rules, and verification gates for future upgrade batches.
- Updated F-Droid draft metadata and version metadata for the centralized dependency baseline.

## v0.2.22 - 2026-05-05

F-Droid readiness baseline.

- Added an `openTaskerDistribution=fdroid` Gradle profile without changing existing Android variant names.
- Pinned Android build tools to `35.0.0` and exposed `BuildConfig.DISTRIBUTION`.
- Added `verifyFdroidReadiness` to block common proprietary dependency families from the F-Droid profile.
- Added CI coverage for the F-Droid release profile.
- Added F-Droid readiness docs and a draft fdroiddata metadata file for `com.opentasker.app`.

## v0.2.21 - 2026-05-05

Tasker XML import baseline.

- Added a secure Tasker XML parser that converts common task/profile/variable structures into an OpenTasker JSON bundle.
- Added a migration report model with mapped actions, unsupported Tasker action placeholders, skipped profile/context warnings, variable counts, and scene exclusions.
- Added an explicit unsupported imported Tasker action runtime failure path and capability metadata.
- Documented the supported import surface and updated roadmap/README/version metadata for X10.
- Added regression tests for action mapping, unsupported action preservation, profile skipping, variable import, scene warnings, and Wait conversion.

## v0.2.20 - 2026-05-05

Calendar and sun trigger baseline.

- Added a local CalendarProvider event bridge that emits redacted `event=calendar` metadata for busy current or upcoming events.
- Added sunrise/sunset matching with user-provided latitude/longitude, offset minutes, and bounded trigger windows.
- Added Calendar access onboarding, Event context editor fields for calendar/sun filters, and Inspector setup copy.
- Promoted the meeting-mode calendar template from planned to setup-required installation.
- Updated roadmap/docs/version metadata and regression tests for calendar filtering, sun calculations, and template installation.

## v0.2.19 - 2026-05-05

NFC tag trigger baseline.

- Added an NFC event bridge that accepts tag/tech/NDEF discovery intents and emits `event=nfc` context events with normalized tag IDs.
- Routed cold-start and foreground NFC intents through `MainActivity` into the existing Event context source.
- Added NFC tag ID filtering to Event contexts and exposed an NFC tag ID field in the context editor.
- Promoted the nightstand NFC sleep template from planned to setup-required installation.
- Updated inspector/setup copy, roadmap/docs/version metadata, and regression tests for NFC matching and template installation.

## v0.2.18 - 2026-05-05

Notification listener trigger baseline.

- Added a `NotificationListenerService` event bridge that emits `event=notification` context events without logging notification text.
- Merged notification events into the existing Event context source for profile matching and context inspection.
- Added package allowlists, title/body filters, bounded regex matching, and fail-closed invalid-regex behavior for Event contexts.
- Expanded the context editor for notification event filters and updated docs/version metadata for the X7 baseline.

## v0.2.17 - 2026-05-05

Context inspector baseline.

- Added an Inspector tab with live registered context-source health, latest observed values, setup status, and source errors.
- Added per-profile match explanations that show whether enabled profiles currently match and which context blocks activation.
- Added a reusable context-inspection model with tests for source health, missing events, all-context matching, and inverted contexts.
- Updated roadmap, project notes, README metadata, and app version metadata for the X6 baseline.

## v0.2.16 — 2026-05-04

Automation mode baseline.

- Added per-profile automation modes: single, restart, queued, and parallel.
- Added a Room v1-to-v2 migration that persists `automationMode` on profiles.
- Added profile editor mode selection and profile cards showing the current mode.
- Updated `AutomationService` dispatch so re-triggers can be skipped, restarted, queued, or run in parallel.
- Added unit coverage for profile entity automation-mode round trips and legacy fallback.

## v0.2.15 — 2026-05-04

External automation target baseline.

- Added a permission-scoped exported receiver for documented external automation intents.
- Added external actions to run tasks, enable/disable profiles, query automation status, and pass task variables.
- Persisted external task runs to the Room run log with action trace summaries.
- Added manifest permission strings and security documentation for external callers.
- Added unit coverage for external variable-name validation and documented variable extra names.

## v0.2.14 — 2026-05-04

Locale plugin host baseline.

- Added Locale/Tasker-compatible setting plugin dispatch through a new `plugin.locale.fire` action.
- Added explicit package validation, string-only JSON bundle decoding, bundle size limits, blurb handling, and timeout wrapping.
- Added plugin discovery metadata for Locale edit-setting/edit-condition packages and requested permission disclosure.
- Added manifest package visibility queries for Locale-compatible plugin discovery.
- Documented the supported plugin host surface and added parser/trust-boundary unit tests.

## v0.2.13 — 2026-05-04

Open JSON bundle baseline.

- Added schema-versioned OpenTasker JSON bundle models for profiles, tasks, actions, contexts, variables, scenes, and metadata.
- Added deterministic export ordering and capability requirement metadata for setup-required or unsupported actions.
- Added import planning/reporting with warnings for unsupported actions and lossy missing-reference handling.
- Added Room-backed export/import repository logic with task ID remapping, variable upsert, profile remapping, and scene element link remapping.
- Documented the v1 JSON bundle format and added unit coverage for sorting, capability metadata, validation, and JSON round trips.

## v0.2.12 — 2026-05-04

Profile template baseline.

- Added an on-device profile template catalog with eight roadmap-backed starter patterns.
- Added slot substitution for template names, context configs, and action arguments.
- Added a Compose template picker and slot form that installs templates as disabled profiles with starter tasks.
- Gated planned calendar, NFC, and external-intent templates so they are visible but cannot create broken profiles yet.
- Added unit coverage for catalog completeness, unsupported-action gating, slot expansion, and planned-template blocking.

## v0.2.11 — 2026-05-04

Public documentation truthfulness pass.

- Corrected README action counts and active runtime-context claims to match the compiled APK.
- Clarified that plugin hosting, Tasker XML import/export, day schedules, and location/geofence runtime support are planned or still being hardened rather than shipped.
- Updated architecture docs to describe the current foreground-service trigger monitors and action capability gates.
- Removed stale audit/checkpoint documents that overclaimed completion against older source snapshots.

## v0.2.10 — 2026-05-04

Regression-test hardening pass.

- Hardened cron step/range parsing so malformed expressions fail closed instead of throwing.
- Added tests for malformed cron steps and valid minute/hour cron matching.
- Added tests for variable scope shadowing and missing-variable expansion.
- Updated README/roadmap metadata for the expanded regression coverage.

## v0.2.9 — 2026-05-04

Run log tracing baseline.

- Added action execution traces with index, label, action type, duration, status, and message.
- Persisted summarized action traces in task run-log messages.
- Expanded run-log cards to show multi-line action trace summaries.
- Added unit coverage for trace summary formatting.

## v0.2.8 — 2026-05-04

Capability gating baseline.

- Added a central action capability registry for supported, setup-required, and unsupported actions.
- Annotated task action rows and action picker cards with setup/unsupported status.
- Disabled unsupported privileged or unimplemented actions in the add-action flow.
- Added warning copy in action configuration dialogs for actions that require setup.
- Added unit coverage for capability gating defaults.

## v0.2.7 — 2026-05-04

Runtime registry and stub-failure hardening pass.

- Registered built-in action implementations and context sources during app startup.
- Aligned runtime action IDs with the action metadata IDs saved by the Compose editor.
- Replaced success-shaped action stubs with real behavior where practical and explicit unsupported failures where Android requires privileged access.
- Implemented notification, intent launch, SMS send, volume, media-key, HTTP POST, and HTTPS download execution paths.
- Removed unused placeholder context source files and stopped silently swallowing application-context polling errors.
- Added unit coverage to ensure every UI action metadata ID has a runtime action implementation.

## v0.2.6 — 2026-05-04

App-open trigger hardening pass.

- Removed the unused plain background `AppOpenService` and its manifest entry.
- Added a foreground-service-owned `AppUsageMonitor` that polls `UsageStatsManager` only when usage access is granted.
- Added opened/closed `AppEvent` dispatch when the foreground package changes.
- Shared usage-access detection between setup UI and the app-open monitor.
- Added focused unit coverage for foreground package selection.
- Updated README/roadmap metadata for app-open monitoring.

## v0.2.5 — 2026-05-04

WiFi trigger hardening pass.

- Replaced the manifest `CONNECTIVITY_CHANGE` receiver with a lifecycle-owned `ConnectivityManager.NetworkCallback`.
- Added WiFi event dispatch from the foreground automation service with duplicate-state suppression.
- Added Android 13 nearby WiFi devices permission metadata and setup checklist coverage.
- Added SSID normalization tests for quoted and unknown platform values.
- Updated README/roadmap metadata for platform-safe WiFi monitoring.

## v0.2.4 — 2026-05-04

Exact alarm hardening pass.

- Removed `USE_EXACT_ALARM` so OpenTasker no longer declares the alarm-clock/calendar-only permission.
- Added an app-owned time tick scheduler that uses exact `AlarmManager` delivery when allowed and inexact `setWindow()` fallback when exact alarms are denied.
- Replaced the manifest `TIME_TICK` dependency with an internal scheduled receiver and exact-alarm permission-change rescheduling.
- Added focused unit coverage for minute-boundary scheduling.
- Updated setup text and README/roadmap metadata for exact-alarm fallback behavior.

## v0.2.3 — 2026-05-04

Permission onboarding pass.

- Added a Setup tab with live status for Android runtime permissions and special access gates.
- Added direct request/open-settings actions for notifications, exact alarms, battery optimization, usage access, notification access, overlay access, foreground/background location, Bluetooth, SMS, and DND access.
- Added Bluetooth scan permission metadata for Android 12+ Bluetooth setup.
- Updated README/version metadata for the setup checklist.

## v0.2.2 — 2026-05-04

Active UI reintegration pass.

- Replaced the launcher-only status screen with a live Compose management UI.
- Added profile creation, editing, enable/disable toggling, deletion, and context attachment backed by Room.
- Added task creation, editing, deletion, and action add/edit/delete flows driven by the action metadata registry.
- Restored run-log browsing inside the active APK.
- Registered built-in action metadata during app startup so dynamic action forms are populated.
- Updated README/version metadata to reflect the active UI state.

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
- **Action editor:** Dynamic form generation for registered action definitions based on metadata registry
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
