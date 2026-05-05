# Changelog

## v0.2.53 - 2026-05-05

Locale request-query event handling.

- Added a foreground-runtime listener for Locale `ACTION_REQUEST_QUERY` broadcasts from condition plugins.
- Emits sanitized `event=locale_request_query` context events with the requested condition activity class and deterministic bundle JSON.
- Rejects blank or malformed activity class names and reuses primitive-only bundle sanitization for request-query payloads.
- Added package visibility for `REQUEST_QUERY` and JVM coverage for request-query event construction.

## v0.2.52 - 2026-05-05

Locale plugin configuration result handling.

- Added explicit edit-setting and edit-condition intent resolution for Locale-compatible plugin configuration activities.
- Fails closed when a plugin package exposes no matching configuration activity or multiple ambiguous activities.
- Added guarded configuration result parsing that accepts only primitive bundle values and emits deterministic JSON plus bounded blurb text.
- Reused the same string-only bundle safety policy for plugin-returned configuration data, rejecting null, nested, parcelable, and arbitrary object values.
- Added JVM coverage for deterministic bundle JSON encoding and primitive-only configuration result sanitization.

## v0.2.51 - 2026-05-05

Locale condition unknown-state handling.

- Added a bounded in-memory last-known-state cache for Locale condition plugin query results.
- Resolves `RESULT_CONDITION_UNKNOWN` to the last known state for the same plugin package and guarded bundle.
- Treats unknown condition results without history as unsatisfied instead of exposing an ambiguous success path.
- Added JVM coverage for last-known fallback, no-history behavior, and bundle-scoped cache keys.

## v0.2.50 - 2026-05-05

Locale condition plugin query baseline.

- Added `plugin.locale.query` to issue explicit `QUERY_CONDITION` ordered broadcasts to Locale/Tasker-compatible condition plugin receivers.
- Added guarded parsing for Locale condition result codes: satisfied, unsatisfied, unknown, and unrecognized-result fail-closed handling.
- Hardened Locale setting execution to resolve a single explicit receiver component before dispatch instead of broadcasting to an entire package.
- Extended Locale plugin discovery metadata with setting/condition receiver permissions for future disclosure UI.
- Added package-visibility queries for Locale execution receivers and JVM coverage for condition result-code mapping.

## v0.2.49 - 2026-05-05

Day schedule polish.

- Added a shared `DaySchedule` parser for day contexts with canonical weekday order, weekday/weekend/daily aliases, numeric day tokens, and inclusive day ranges such as `MON-FRI`.
- Updated Day context matching to use the shared parser so imported, typed, and UI-created schedules evaluate consistently.
- Replaced raw Day context editing with quick presets, individual day toggles, canonical save output, and validation that blocks invalid day schedules before saving.
- Improved profile and inspector summaries so Day contexts show human-readable labels such as `Weekdays`, `Weekends`, or `Every day`.
- Added JVM coverage for day aliases, wrapped ranges, numeric tokens, and ContextMatchEvaluator day matching.

## v0.2.48 - 2026-05-05

Post-reconnect unplugged evidence checks.

- Extended `tools/collect-location-evidence.ps1` with `-RequireRecentUnpluggedHistory`, `-MinimumUnpluggedHistorySeconds`, and `-MaximumUnpluggedHistoryAgeMinutes` for workflows where USB ADB is unavailable while the phone is unplugged.
- Added recent unplugged interval parsing from `dumpsys battery` power and battery-change history so post-reconnect runs can fail closed on duration.
- Captured post-reconnect API 36 evidence `build/device-evidence/location/20260505-125057`; the device history showed a recent unplugged interval from `2026-05-05T12:48:23.598` to `2026-05-05T12:50:14.389`, about 111 seconds, which was below the 600-second roadmap threshold.
- Captured follow-up API 36 evidence `build/device-evidence/location/20260505-143254`; the recent unplugged interval from `2026-05-05T14:21:53.052` to `2026-05-05T14:32:08.107` lasted 615.055 seconds and satisfied the 600-second post-reconnect history gate with GPS/network provider cadence evidence present.

## v0.2.47 - 2026-05-05

Location durability evidence gates.

- Extended `tools/collect-location-evidence.ps1` with structured battery parsing for plug state, charge counter, current, voltage, and sample deltas.
- Added `-RequireUnpluggedSample` so future battery evidence fails closed if the device is connected to USB/AC/wireless/dock power before or after the sample.
- Added `-RequireProviderCadenceEvidence` so Location evidence can assert that `dumpsys location` contains expected OpenTasker GPS/network cadence registrations or historical aggregates.
- Verified the new collector gates on connected API 36 device `SM-S938B` with evidence `build/device-evidence/location/20260505-120448`; the run correctly detected USB power and GPS/network cadence, so it is tooling evidence only, not an unplugged battery reliability claim.

## v0.2.46 - 2026-05-05

Background Location delivery evidence.

- Verified the installed/enabled `Location evidence log` template on connected API 36 device `SM-S938B` with the app sent home.
- Used a shell-owned GPS test provider to deliver the template coordinates while `AutomationService` stayed foreground with `specialUse|location`.
- Captured Room evidence under `build/device-evidence/location/20260505-085413` showing a successful `Location evidence log Task` run log after evidence collection started.
- Extended `tools/collect-location-evidence.ps1` so `-RequireRunLogMessagePattern` can match the recent run-log message, task name, or the triggered task's action JSON.

## v0.2.45 - 2026-05-05

Location event evidence assertions.

- Extended `tools/collect-location-evidence.ps1` to snapshot the debug app's Room database through `run-as`.
- Writes `room-summary.json` with profile, task, and recent run-log counts/details when local Python/SQLite support is available.
- Added optional `-RequireRunLogMessagePattern` and `-RequireLogcatPattern` checks so a background Location run can fail closed unless execution evidence is present.
- Kept database capture non-fatal for non-debug or non-`run-as` builds while preserving foreground-service validation.

## v0.2.44 - 2026-05-05

Location evidence template.

- Added a disabled-by-default `Location evidence log` profile template for configuring a test radius with latitude, longitude, radius, max-accuracy, and dwell slots.
- The template installs as a normal Location context plus a log action, so future device smoke work can verify actual Location event delivery without manual context construction.
- Kept the template setup-required with explicit foreground/background location and device Location prerequisites.
- Added JVM coverage for the template catalog entry and generated Location context config.

## v0.2.43 - 2026-05-05

Location device evidence harness.

- Added `tools/collect-location-evidence.ps1` to collect adb-backed foreground-service, permission, location, logcat, and battery snapshots for Location/geofence verification.
- The harness writes timestamped JSON summaries and raw evidence files under ignored `build/device-evidence/location/`.
- Supports optional permission grants and an app-to-home sample to verify the foreground automation service remains active while the app is backgrounded.
- Verified the harness against connected API 36 device `SM-S938B`; a 10-second home/background sample kept `AutomationService` foreground with `specialUse|location` and recorded battery snapshots.

## v0.2.42 - 2026-05-05

Foreground service launch repair.

- Started `AutomationService` from `MainActivity` using `ContextCompat.startForegroundService`.
- Kept boot receiver startup intact while ensuring app launch also activates the automation engine.
- Logged foreground-service startup failures from the activity path.
- Added a JVM source contract test for the activity-to-service startup path.
- Verified on a connected API 36 device that app launch starts the foreground service with the `specialUse|location` type after foreground/background location permissions and device location are enabled.

## v0.2.41 - 2026-05-05

Location policy disclosures.

- Added shared Android location policy disclosure copy for Setup and Context Inspector.
- Explains that Android 11+ background location is granted from app settings instead of the foreground permission dialog.
- Explains that approximate foreground access limits background precision.
- Adds Android 14+ foreground-service location gating copy when foreground and background location prerequisites are ready.
- Added JVM coverage for the location disclosure policy text.

## v0.2.40 - 2026-05-05

Geofence cadence tuning.

- Added a balanced location provider request policy for the FOSS `LocationManager` source.
- Requests GPS updates less aggressively than network updates to reduce baseline location polling pressure.
- Added cadence metadata to the waiting-for-location setup event for inspector/debug visibility.
- Extended location setup rechecks from 30 seconds to 60 seconds.
- Added JVM coverage for cadence defaults and validation.

## v0.2.39 - 2026-05-05

Geofence dwell cleanup.

- Added profile-scoped persisted dwell-state cleanup for deleted profiles.
- Cleared a profile's persisted Location dwell keys when its context list changes, preventing removed or reindexed geofences from retaining stale timers.
- Routed the active automation view model through the application context so profile edits can maintain location dwell storage.
- Kept enable/disable and profile metadata edits from resetting dwell timers when contexts are unchanged.

## v0.2.38 - 2026-05-05

Context Inspector dwell detail.

- Added per-profile Location observation enrichment in the Context Inspector using the same persisted dwell state as runtime matching.
- Added location check rows that show inside, outside, accuracy-blocked, or unknown dwell status with elapsed time against configured dwell duration.
- Kept source cards raw while profile check rows display geofence-specific dwell metadata for the selected profile/context.
- Added regression coverage for transformed Location observations during profile inspection.

## v0.2.37 - 2026-05-05

Persisted geofence dwell state.

- Added profile/context-scoped Location dwell keys with config hashes so edited geofences do not reuse stale inside-since state.
- Added a pure dwell-state tracker that preserves `insideSinceEpochMs` across accurate inside samples and clears it when a sample leaves the radius.
- Persisted dwell state in app-local preferences so dwell timers can survive process restarts.
- Wired ProfileMatcher to enrich Location context events with persisted dwell metadata before FOSS geofence evaluation.
- Added regression coverage for first-entry persistence, dwell carry-forward, outside clearing, low-accuracy preservation, and stable key hashing.

## v0.2.36 - 2026-05-05

Live FOSS location source baseline.

- Added a registered `location` context source backed by Android `LocationManager`, with GPS/network providers and last-known-fix seeding.
- Added fail-closed source events for missing permissions, disabled providers, unavailable services, and source errors.
- Declared the Android 14+ location foreground-service contract while keeping background geofence reliability gated behind background location and device verification.
- Updated Setup and Context Inspector copy for foreground, approximate, precise, and background location states.
- Added regression coverage for location event metadata, runtime source registration, and manifest foreground-service location declarations.

## v0.2.35 - 2026-05-05

Template regex policy.

- Made regex-like template functions (`match`, `matches`, `regex`, and `replace`) explicitly unsupported.
- Preserved fail-closed behavior by keeping the original template token when regex-like functions are used.
- Kept existing bounded legacy `%var(regex:...)` behavior separate from the new template engine.
- Added regression coverage for explicit regex-template rejection.

## v0.2.34 - 2026-05-05

Template condition expansion.

- Added bounded `{{ ... }}` expansion to action conditions before legacy predicate evaluation.
- Preserved legacy `%var` condition behavior and applied template expansion only when a condition contains template tokens.
- Made template condition warnings fail closed by skipping the action instead of running on an unsafe or unknown expression.
- Added regression coverage for template conditions, JSON path conditions, and warning-based condition skips.

## v0.2.33 - 2026-05-05

Per-expression template diagnostics.

- Persisted bounded per-expression template trace lines beneath action trace summaries.
- Parsed template trace lines back into structured run-log diagnostics with argument name, source scope, expression, value, and optional warning.
- Rendered individual template expressions in Run Log trace rows, including source scope and redacted values for sensitive arguments.
- Added regression coverage for persisted template trace lines, sensitive expression redaction, and structured parsing.

## v0.2.32 - 2026-05-05

Template run-log diagnostics.

- Parsed template expansion details out of action trace messages into structured run-log diagnostics.
- Added per-step expanded argument summaries and template warning counts to the Run Log UI.
- Preserved ordinary parenthesized failure messages while recognizing generated template detail suffixes.
- Added regression coverage for parsing expanded argument details, warning counts, and normal parenthesized messages.

## v0.2.31 - 2026-05-05

Runtime template argument expansion.

- Wired action argument expansion through the bounded `TemplateExpressionEngine` after legacy `%var` expansion.
- Added `VariableStore` template snapshots for task-local, event, global, and array scopes.
- Added sanitized expanded-argument summaries, template warnings, and per-argument expansion traces to `ActionExecutionTrace`.
- Redacted sensitive argument names such as tokens, keys, secrets, cookies, and passwords from run-log summaries.
- Added regression coverage for runtime template expansion, event scope lookup, array lookup, warning propagation, and summary redaction.

## v0.2.30 - 2026-05-05

Template expression engine baseline.

- Added a pure `TemplateExpressionEngine` for bounded `{{ ... }}` template expansion.
- Added task/event/global scope precedence, explicit scope prefixes, array indexing/count/join support, and JSON path reads from scoped values.
- Added safe string and math pipe functions with traces and warnings for debugging expansion behavior.
- Added fail-closed limits for template length, expression count, function chains, resolved value size, output size, and unknown functions.
- Documented the template expression baseline and added regression coverage for scope, defaults, string/math transforms, JSON paths, arrays, and expansion limits.

## v0.2.29 - 2026-05-05

FOSS geofence evaluator baseline.

- Added a pure `FossGeofenceEvaluator` with Haversine distance, radius checks, optional max accuracy, and dwell-time evaluation.
- Wired active Location context matching through the FOSS evaluator without adding Play Services dependencies.
- Added Location editor fields for max accuracy and dwell seconds.
- Reused the same evaluator for the older geofence trigger distance path and added regression coverage for radius, accuracy, dwell, and active context matching.

## v0.2.28 - 2026-05-05

Profile sharing manifest baseline.

- Added a pure profile-share manifest model for OpenTasker bundles with stable slugs, counts, trust state, and submission metadata.
- Added safety findings for unsupported/setup-required actions, schema warnings, lossy import warnings, and missing screenshots.
- Added GitHub Discussions submission markdown generation without adding network publishing or verified-template claims.
- Documented the sharing baseline and added unit coverage for manifest counts, blockers, slug validation, and submission text.

## v0.2.27 - 2026-05-05

Termux script readiness baseline.

- Added a gated `script.termux.run` action with metadata and a runtime failure path that does not execute scripts.
- Added Termux and Termux:Tasker package visibility and optional setup status detection.
- Added Setup checklist copy for the Termux script bridge while excluding it from required readiness progress.
- Documented the non-executing scripting baseline and added tests for package constants, manifest queries, and capability gating.

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
