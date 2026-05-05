# OpenTasker Roadmap

Source-backed product roadmap for OpenTasker v0.2.58. This file reconciles the current local repository state with competitive research across Android automation apps, adjacent workflow engines, Android platform constraints, distribution policy, and dependency changelogs.

**Last updated:** 2026-05-05
**Roadmap version:** 2026.05 research pass  
**Current app version:** 0.2.58
**Planning rule:** items marked "Now" must ship before any public beta claim beyond "minimal automation engine preview."

---

## State of the Repo

OpenTasker is an Android/Kotlin automation app targeting API 35 with Jetpack Compose, Material 3, Room, Coroutines, WorkManager, DataStore, Gson, and Hilt dependencies. The project goal is a privacy-first, fully on-device, open-source Tasker/MacroDroid/Automate alternative.

The active APK now has a Room-backed Compose management UI for profiles, tasks, action lists, context lists, scenes, run logs, setup/onboarding status, read-only flow graphs, live context inspection with geofence dwell detail, notification listener event triggers, NFC tag event triggers and a write helper, calendar/sun event triggers with editor presets and adb smoke evidence, day schedule aliases/ranges/presets, a foreground automation service that starts from app launch and boot, a FOSS platform location source with balanced provider cadence and policy-aware setup copy, FOSS geofence radius/accuracy/dwell evaluation with persisted inside-since state and stale dwell-key cleanup, an installable disabled Location evidence template, an adb-backed Location/geofence evidence harness with Room/run-log/logcat assertions plus provider-cadence, unplugged-sample, and post-reconnect unplugged-history gates, API 36 smoke evidence for background Location event delivery, bounded runtime template argument and condition expansion, per-expression template run-log diagnostics, explicit regex template policy, a Tasker XML-to-OpenTasker-bundle migration parser, Locale setting dispatch and condition query actions with explicit receiver targeting, best-effort last-known unknown-state handling, guarded configuration result parsing, request-query event handling, and an adb plugin validation harness, OpenTasker bundle share manifests, a F-Droid readiness build profile with metadata/version gates, local fdroidserver lint/build evidence, and APK payload comparison tooling, centralized Gradle dependency version governance, optional Shizuku readiness status for elevated-action planning, and optional Termux script readiness status for future scripting. Older `.kt.bak` editor snapshots are still not compiled, and advanced capability gating remains required before public beta claims.

Key local constraints:

| Area | Current truth | Roadmap implication |
|---|---|---|
| UI | Active profile/task/action/context/run-log/setup/inspector management UI is restored; advanced stale snapshots remain unused | Continue hardening UI with capability gating |
| Engine | Foreground automation service starts from app launch and boot; registries are wired through `OpenTaskerApp_NoHilt` | Keep non-Hilt runtime stable until Hilt migration is deliberate |
| Actions | Built-in action IDs align with UI metadata, success-shaped stubs fail honestly, Locale setting and condition plugin actions target explicit receivers with guarded configuration result parsing, request-query event handling, validation harness support, and best-effort last-known unknown-state handling, and the UI now blocks unsupported privileged actions | Capability registry can expand into context and permission-aware execution gates |
| Contexts | Time ticks use app-owned AlarmManager scheduling, Day contexts support weekday/weekend/daily aliases, day ranges, presets, canonical save output, and readable summaries, WiFi events use dynamic NetworkCallback, app-open events use foreground-service-owned UsageStats polling, notification posts can emit filtered Event contexts, NFC tag scans can emit tag-filtered Event contexts with a write helper, local calendar/sun ticks can emit filtered Event contexts with editor presets and provider smoke evidence, and Location contexts can receive platform GPS/network fixes with balanced provider cadence, policy disclosures, persisted dwell metadata, stale-key cleanup, inspector dwell detail, repeatable adb evidence collection, provider-cadence/unplugged-sample/post-reconnect history harness gates, and a focused API 36 background delivery smoke test; remaining context work is broader Location/geofence reliability verification | Permission/onboarding and platform-safe replacements remain P0/P1 |
| Persistence | Room schema export now exists; backup/restore hardened | Migration tests and upgrade strategy can be built on this |
| Release | Debug/release builds pass locally; release signing is env-var driven; F-Droid profile metadata, lint, local build, and APK payload comparison tooling are in place | Play readiness still needs policy and signing-track work |
| Docs | README and architecture docs were corrected to v0.2.11; stale checkpoint/audit files that overclaimed old snapshots were removed | Roadmap tiers below supersede old milestone claims |

---

## Research Findings That Drive This Roadmap

1. The credible Android OSS competitor field is almost empty. Easer is the main FOSS direct comparator, but it is single-maintainer, slow-moving, and has open reliability issues around timers, Doze, WiFi, and Boolean logic. [S1][S2]
2. Closed-source Android automation is mature but fragmented: Tasker is powerful and hard to learn, MacroDroid is simpler but proprietary/freemium, Automate has a strong flow editor but opaque formats and a block limit, and IFTTT is cloud/subscription-centered. [S16][S17][S18][S19][S20]
3. The biggest Android automation pain point is no longer "how many actions exist"; it is whether automations survive Doze, OEM battery killing, exact-alarm restrictions, background service limits, notification permission denial, package visibility, and Play policy review. [S22][S23][S24][S25][S26][S27][S28]
4. The best adjacent systems all solve blank-canvas and debuggability problems with templates, blueprints, visual flows, live state inspectors, run logs, reusable subflows, and open import/export. [S9][S10][S11][S12][S13][S14][S15]
5. Plugin compatibility is table stakes. Locale/Tasker-compatible plugins are the de facto Android automation extension protocol; OpenTasker should become both a host and a target. [S7][S8][S16]
6. F-Droid is strategically aligned with OpenTasker's privacy/on-device positioning, but Google Play Services geofencing and non-reproducible signing/build-tool choices would block inclusion. [S31][S32]

---

## Tier Summary

| Tier | Goal | Exit criteria |
|---|---|---|
| Now | Make the product truthful, installable, and reliable on Android 13-15 | Active CRUD/editor UI works, platform permissions are handled, broken/stubbed paths fail honestly, build/release remains green |
| Next | Reach credible beta parity for core automation workflows | Templates, run log/debugging, import/export, key contexts, and Locale-compatible plugin host are usable |
| Later | Expand into power-user and ecosystem features | Visual flow editor, scenes/overlays, Shizuku power-user mode, advanced scripting, sharing marketplace |
| Under Consideration | Valuable but needs more proof, policy clearance, or architecture decisions | Natural-language generation, sync, Wear OS, Play/F-Droid flavor split, multi-device |
| Rejected | Conflicts with OpenTasker's philosophy or near-term maintainability | Cloud dependency, proprietary export, artificial paywalls, server requirement |

---

## Now

### N1 - Reintegrate the active automation UI

**Status:** Baseline completed in v0.2.2. Active navigation now manages profiles, tasks, action lists, context lists, and run logs against Room; deeper platform capability gating continues under N2/N7.  
**Description:** Restore the richer profile/task/action/context CRUD screens from source snapshots into active navigation, or rebuild equivalent screens using the current hardened engine and repositories.  
**Sources:** Local repo state [L1], MacroDroid wizard simplicity [S17], Tasker learning-curve signal [S16], Home Assistant visual editor/blueprints [S12].  
**Category:** UX, reliability, dev-experience.  
**Impact:** 5 - the app cannot be evaluated as an automation product from the current minimal screen.  
**Effort:** 4 - much code exists but must be reconciled with current repositories and non-Hilt runtime.  
**Risk:** Medium; stale snapshots may reference old DI/navigation/state assumptions.  
**Dependencies:** Keep `OpenTaskerApp_NoHilt` runtime stable; verify all screens compile under current Material/Compose versions.  
**Novelty:** Parity.  
**Tier reason:** This is the highest-value correctness fix because docs, screenshots, and usability hinge on it.

### N2 - Add first-run permission and reliability onboarding

**Status:** Baseline completed in v0.2.3. The active Setup tab shows status and routes users to runtime permission requests or Android special-access settings.  
**Description:** Add an in-app setup checklist for notification permission, exact alarm access, battery optimization exemption, usage access, notification listener, overlay access, location/background location, Bluetooth/WiFi permissions, SMS, DND access, and package visibility explanations. Each item must show why it is needed, current status, and the exact settings action when Android requires special access.  
**Sources:** Android notification permission [S26], exact alarm behavior [S25], Play permission declarations [S29][S30][S33], Doze/OEM killing [S22][S28], MacroDroid helper pain [S17].  
**Category:** UX, reliability, security, platform/OS, distribution.  
**Impact:** 5 - automations silently fail without these gates.  
**Effort:** 4 - many settings intents and status probes, but mostly app-layer work.  
**Risk:** Medium; some OEM settings screens are non-standard.  
**Dependencies:** UI reintegration; central permission capability model.  
**Novelty:** Parity plus polish.  
**Tier reason:** Reliability onboarding is the difference between "works in development" and "works on user phones."

### N3 - Remove `USE_EXACT_ALARM` and harden exact scheduling

**Status:** Baseline completed in v0.2.4. `USE_EXACT_ALARM` is removed, app-owned time ticks reschedule through `AlarmManager`, and exact-alarm denial falls back to inexact `setWindow()` delivery.  
**Description:** Keep `SCHEDULE_EXACT_ALARM`, remove `USE_EXACT_ALARM`, add `AlarmManager.canScheduleExactAlarms()` guards, register for permission-state changes, and gracefully degrade to inexact `setWindow()`/WorkManager with clear status text.  
**Sources:** Android 14 exact alarm docs [S25], platform audit [L3].  
**Category:** reliability, security, platform/OS, distribution.  
**Impact:** 5 - wrong exact-alarm declaration can break Play review and scheduling.  
**Effort:** 2 - contained manifest and scheduler changes.  
**Risk:** Low; fallback behavior must be explicit.  
**Dependencies:** Permission onboarding.  
**Novelty:** Parity.  
**Tier reason:** This is a high-confidence platform compliance fix.

### N4 - Replace static WiFi connectivity receiver with NetworkCallback

**Status:** Baseline completed in v0.2.5. The manifest `CONNECTIVITY_CHANGE` receiver is gone; AutomationService owns a `ConnectivityManager.NetworkCallback` and emits WiFi trigger events with permission-aware SSID fallback.  
**Description:** Remove reliance on manifest-declared `CONNECTIVITY_CHANGE`, use dynamic `ConnectivityManager.registerNetworkCallback()`, use `NetworkCapabilities.transportInfo as? WifiInfo` on API 31+, and handle `NEARBY_WIFI_DEVICES`/location permission requirements.  
**Sources:** Android 7 broadcast restrictions [S24], Android WiFi permission changes [S27], platform audit [L3].  
**Category:** correctness, reliability, platform/OS.  
**Impact:** 5 - current manifest receiver is non-functional on the app's entire minSdk range.  
**Effort:** 3 - requires context source lifecycle integration.  
**Risk:** Medium; SSID visibility varies by permission/location state.  
**Dependencies:** Permission model; AutomationService context subscription lifecycle.  
**Novelty:** Parity.  
**Tier reason:** A broken trigger family must be fixed before adding new triggers.

### N5 - Replace or foreground AppOpenService polling

**Status:** Baseline completed in v0.2.6. The separate background `AppOpenService` is removed; AutomationService now owns `UsageStatsManager` polling, pauses explicitly when usage access is denied, and emits app opened/closed events on foreground package changes.  
**Description:** Replace the plain background `AppOpenService` polling loop with a platform-safe usage-stats context source, WorkManager-backed periodic sampling, or a properly declared foreground path where user-visible monitoring is required. Add usage-access onboarding via `ACTION_USAGE_ACCESS_SETTINGS`.  
**Sources:** UsageStatsManager docs [S34], Android background service limits [S23], platform audit [L3].  
**Category:** correctness, reliability, performance, platform/OS.  
**Impact:** 4 - app-open triggers are core automation use cases.  
**Effort:** 3 - refactor service lifecycle and persistence.  
**Risk:** Medium; polling too frequently harms battery and standby buckets.  
**Dependencies:** Permission onboarding; context inspector.  
**Novelty:** Parity.  
**Tier reason:** Background polling is brittle on modern Android.

### N6 - Replace success-shaped stubs with real implementations or explicit unsupported failures

**Status:** Baseline completed in v0.2.7. Built-in actions and context sources are registered at startup, UI metadata IDs match runtime IDs, several action paths now execute real behavior, and privileged/unimplemented actions fail explicitly instead of reporting success.  
**Description:** Audit all action/context classes for TODO, placeholder, `return success`, and unimplemented behavior. Each must either perform the action, report a specific permission/platform limitation, or be hidden behind capability checks until implemented.  
**Sources:** Local source scan [L2], Tasker/MacroDroid action-count expectations [S16][S17], Automate block breadth [S18].  
**Category:** correctness, UX, observability, testing.  
**Impact:** 5 - false success corrupts trust and run logs.  
**Effort:** 4 - multiple action families.  
**Risk:** Low/Medium; changing false-success to failure may expose existing incomplete paths but improves truthfulness.  
**Dependencies:** Run log error model; capability registry.  
**Novelty:** Parity.  
**Tier reason:** The app must never claim an automation ran when it did not.

### N7 - Add capability registry and action/context gating

**Status:** Baseline completed in v0.2.8. A central action capability registry now marks supported/setup-required/unsupported actions, annotates picker/editor rows, and blocks unsupported privileged actions before they are added.  
**Description:** Centralize platform, permission, API-level, distribution-flavor, and feature-implementation capability checks. Use it to enable/disable UI rows, annotate unsupported actions, explain missing setup, and block execution with actionable errors.  
**Sources:** Android policy/API constraints [S25][S26][S27][S29][S30][S33], competitors' permission complexity [S16][S17][S18].  
**Category:** architecture, UX, reliability, security.  
**Impact:** 5 - prevents unsupported actions from appearing usable.  
**Effort:** 4 - affects registries, forms, execution, and onboarding.  
**Risk:** Medium; must avoid duplicating checks across layers.  
**Dependencies:** UI reintegration; permission onboarding.  
**Novelty:** Leapfrog.  
**Tier reason:** It converts Android fragmentation into explicit product behavior.

### N8 - Make run logs execution-grade

**Status:** Baseline completed in v0.2.9. Task execution now records action-level trace summaries with status, action type, label, duration, and failure message in run-log entries.  
**Description:** Expand run logs from task-level completion into step-level traces with action input summary, expanded variables, permission/capability failure reason, duration, retry/cooldown decision, and source trigger. Add filtering by profile/task/error.  
**Sources:** Node-RED debug sidebar [S9], Huginn agent health/events [S11], Home Assistant state inspector [S12], n8n executions/error handling [S10].  
**Category:** observability, UX, debugging, testing.  
**Impact:** 4 - automation users need to understand why a rule did or did not fire.  
**Effort:** 4 - schema/UI changes plus engine instrumentation.  
**Risk:** Medium; logs may contain sensitive data, so redaction/export choices matter.  
**Dependencies:** Capability registry; variable expansion boundaries.  
**Novelty:** Parity plus polish.  
**Tier reason:** Debuggability is a core product feature in automation tools.

### N9 - Add high-value regression tests around platform and parser boundaries

**Status:** Baseline completed in v0.2.10. Added regression tests around cron parsing and variable scoping, and hardened malformed cron step/range handling to fail closed.  
**Description:** Add focused JVM/unit tests for variable expansion limits, malformed action parameters, capability gating, scheduler fallback decisions, import/export schema validation, repository migrations, and stub-failure behavior. Add instrumentation tests only for flows that cannot be covered on JVM.  
**Sources:** Easer admits broad test gaps [S1], Room schema/migration guidance [S35], existing local validation tests [L4].  
**Category:** testing, reliability, dev-experience.  
**Impact:** 4 - prevents regressions in fragile automation paths.  
**Effort:** 3 - test infrastructure already works.  
**Risk:** Low.  
**Dependencies:** Capability registry; migration strategy.  
**Novelty:** Parity.  
**Tier reason:** The app is entering platform-fragile behavior; tests should cover those seams.

### N10 - Correct public docs to distinguish active product, source snapshots, and planned features

**Status:** Completed in v0.2.11. README and architecture docs now separate compiled features from planned plugin/import/geofence/day-schedule work, and stale historical checkpoint/audit files were removed.  
**Description:** Keep README, architecture docs, changelog, and roadmap aligned with the active APK. Avoid claiming full CRUD UI, design-system coverage, action counts, scheduling, or plugin support until compiled and verified.  
**Sources:** Local docs/source mismatch [L1][L5].  
**Category:** docs, dev-experience, release.  
**Impact:** 4 - misleading docs create support debt and release risk.  
**Effort:** 2.  
**Risk:** Low.  
**Dependencies:** UI reintegration status.  
**Novelty:** Maintenance.  
**Tier reason:** Truthful docs are part of release hardening.

---

## Next

### X1 - Profile templates and guided creation wizard

**Status:** Completed in v0.2.12. Added a template catalog, guided slot form, disabled-by-default Room installation, safety notes, and planned-template gating for trigger patterns that did not have runtime support yet; the NFC template was promoted to setup-required installation in v0.2.19 and the calendar template was promoted in v0.2.20.
**Description:** Ship curated on-device templates with variable slots: work-hours DND, headphones connected media profile, low-battery saver, meeting mode from calendar, nightstand NFC sleep mode, WiFi arrival profile, app usage reminder, and "find my phone" notification/action patterns.  
**Sources:** MacroDroid wizard/templates [S17], Home Assistant blueprints [S12], n8n templates [S10], Node-RED flow sharing [S9].  
**Category:** UX, docs, accessibility.  
**Impact:** 5.  
**Effort:** 4.  
**Risk:** Low/Medium; templates must not request excessive permissions by default.  
**Dependencies:** Active UI, capability registry, import/export format.  
**Novelty:** Parity plus polish.  
**Tier reason:** Solves the blank-canvas problem before adding advanced editors.

### X2 - Open JSON import/export with schema versioning

**Status:** Completed in v0.2.13. Added schema-versioned bundle models, deterministic export ordering, capability metadata, validation/import warnings, Room-backed import/export with ID remapping, and format documentation.  
**Description:** Define a portable JSON bundle for profiles, tasks, actions, contexts, variables, and metadata. Include schema version, app version, capability requirements, lossy import warnings, and deterministic ordering for diffs.  
**Sources:** Node-RED JSON flows [S9], Huginn scenarios [S11], MacroDroid/Automate opaque export pain [S17][S18], F-Droid reproducibility goals [S31].  
**Category:** data, migration, docs, distribution.  
**Impact:** 5.  
**Effort:** 4.  
**Risk:** Medium; schema drift must be planned early.  
**Dependencies:** Capability registry; Room schema strategy.  
**Novelty:** Leapfrog for FOSS Android automation.  
**Tier reason:** Open data is a strategic differentiator and unlocks templates/sharing/import.

### X3 - Locale-compatible plugin host

**Status:** Completed in v0.2.54. Added explicit setting and condition receiver targeting, explicit edit-setting/edit-condition activity resolution, guarded configuration result parsing, `plugin.locale.query`, ordered-broadcast result parsing for satisfied/unsatisfied/unknown states, optional result-variable storage, best-effort last-known fallback for `RESULT_CONDITION_UNKNOWN`, sanitized `ACTION_REQUEST_QUERY` event handling, receiver permission metadata for disclosure, package-visibility queries for execution receivers, docs, trust-boundary tests, and `tools/validate-locale-plugin.ps1` for sample-plugin/device validation evidence.
**Description:** Implement the Locale/Tasker host intent contract for condition and setting plugins, with safe result handling, permission disclosure, timeout/cancellation, and a sample plugin validation harness.  
**Sources:** Locale SDK [S7], Termux:Tasker [S3], Tasker plugin sample [S8], Pocket Casts plugin module [S8].  
**Category:** plugin ecosystem, integrations, security.  
**Impact:** 5.  
**Effort:** 5.  
**Risk:** Medium/High; third-party plugins are trust-boundary code and need sandboxed intent validation.  
**Dependencies:** Capability registry; action result/error model.  
**Novelty:** Parity.  
**Tier reason:** Plugin compatibility gives OpenTasker useful integrations before it has hundreds of native actions.

### X4 - OpenTasker as automation target

**Status:** Baseline completed in v0.2.15. Added a custom-permission receiver for task execution, profile enable/disable, status queries, variable extras, run-log persistence, docs, and contract tests.  
**Description:** Expose documented, permission-scoped intents so other apps can trigger profiles/tasks, query profile status, or pass variables into a task. Include examples and security controls for exported receivers/services.  
**Sources:** openHAB Android Tasker plugin [S5], NetGuard intent API lesson [S6], Locale SDK [S7].  
**Category:** integrations, security, dev-experience.  
**Impact:** 4.  
**Effort:** 3.  
**Risk:** Medium; exported surfaces must be locked down.  
**Dependencies:** Stable profile/task IDs; capability registry.  
**Novelty:** Parity plus openness.  
**Tier reason:** An automation app should be both a host and a callable component.

### X5 - Automation modes: single, restart, queued, parallel

**Status:** Baseline completed in v0.2.16. Added persisted per-profile automation modes, migration support, UI selection, single/restart/queued/parallel dispatch logic, and profile entity tests.  
**Description:** Add per-profile re-trigger behavior modeled after Home Assistant automation modes. Current cooldown behavior becomes one policy instead of the whole concurrency model.  
**Sources:** Home Assistant automation modes [S12], current cooldown logic [L1].  
**Category:** correctness, reliability, UX.  
**Impact:** 4.  
**Effort:** 3.  
**Risk:** Medium; cancellation and shared variables must be correct.  
**Dependencies:** TaskRunner cancellation, variable scope.  
**Novelty:** Leapfrog against many Android apps.  
**Tier reason:** Retrigger semantics are a hidden reliability bug class.

### X6 - Context inspector

**Status:** Baseline completed in v0.2.17. Added a live Inspector tab with registered context-source health, latest observed values, setup state, source errors, and per-profile match/blocking explanations backed by a tested pure inspection model.
**Description:** Add a live screen showing monitored context values, permission status, last update time, source health, and why each enabled profile currently does or does not match.  
**Sources:** Home Assistant Developer Tools/States [S12], Node-RED debug panel [S9], Huginn agent health [S11].  
**Category:** observability, UX, debugging.  
**Impact:** 4.  
**Effort:** 4.  
**Risk:** Low/Medium; some context values are sensitive.  
**Dependencies:** Capability registry; run log instrumentation.  
**Novelty:** Leapfrog.  
**Tier reason:** Reduces support burden and makes automation explainable.

### X7 - Notification listener trigger

**Status:** Baseline completed in v0.2.18. Added a notification listener service that emits `event=notification` context events through the existing Event source, with package allowlists, title/body filters, bounded regex matching, redacted Android logging, setup visibility in the inspector, and regression tests.
**Description:** Implement notification-access onboarding and a `NotificationListenerService` context with app/source filters, title/body matching, regex option with limits, package allowlists, and redacted logging.  
**Sources:** Tasker AutoNotification demand [S16], MacroDroid notification triggers [S17], Android notification listener constraints [L3].  
**Category:** platform/OS, security, UX.  
**Impact:** 4.  
**Effort:** 4.  
**Risk:** Medium; notifications can contain sensitive content.  
**Dependencies:** Capability registry; privacy redaction model.  
**Novelty:** Parity.  
**Tier reason:** Notification-driven automation is a common high-value Android use case.

### X8 - NFC trigger

**Status:** Write-helper flow completed in v0.2.55. Added NFC tag/tech/NDEF intent handling through `MainActivity`, an in-memory `event=nfc` bridge for the Event context source, normalized tag ID matching, an event-editor tag ID field, a one-time NDEF text-record write helper for writable/formattable tags, a setup-required nightstand NFC template, inspector copy, and regression tests.
**Description:** Add NFC tag trigger support with tag ID filtering, write-helper flow, and templates for physical-location automations.  
**Sources:** Home Assistant Android NFC [S4], openHAB Android NFC [S5], Automate NFC support [S18].  
**Category:** platform/OS, UX, offline.  
**Impact:** 4.  
**Effort:** 3.  
**Risk:** Low; mostly permission/intent handling.  
**Dependencies:** Active UI; import/export.  
**Novelty:** Parity.  
**Tier reason:** NFC is reliable, offline, and avoids many background restrictions.

### X9 - Calendar and sun triggers

**Status:** Completed in v0.2.57. Added local CalendarProvider polling through the Event context source, redacted `event=calendar` metadata for busy current/upcoming events, state/calendar/before-window filters, sunrise/sunset matching from configured latitude/longitude plus offset/window settings, Calendar access onboarding, Event editor fields, calendar/sun preset chips, a setup-required meeting-mode template, regression tests, `tools/collect-calendar-sun-evidence.ps1`, and API 36 smoke evidence `build/device-evidence/calendar-sun/20260505-152622` showing Calendar permission, provider queries, and foreground service readiness.
**Description:** Add calendar-event triggers ("during meeting", "before next event") and sunrise/sunset triggers with offsets. Keep calendar data local and log only redacted event metadata by default.  
**Sources:** Home Assistant calendar/sun triggers [S12], MacroDroid/Automate calendar support [S17][S18], Android calendar permission model.  
**Category:** UX, platform/OS, privacy.  
**Impact:** 4.  
**Effort:** 4.  
**Risk:** Medium; calendar contents are sensitive.  
**Dependencies:** Permission onboarding; privacy redaction.  
**Novelty:** Parity.  
**Tier reason:** These are high-frequency real-world automation patterns.

### X10 - Tasker XML import with migration report

**Status:** Completed in v0.2.58. v0.2.21 added a secure Tasker XML parser that converts common task/profile/variable XML into an OpenTasker JSON bundle, preserves unmapped actions as explicit unsupported placeholders, reports mapped actions, skipped profiles, unsupported contexts, scene exclusions, variable losses, and capability warnings, and includes regression tests. v0.2.58 adds the user-facing document picker, bounded XML preview, migration/capability review dialog, confirmed Room import path, and disabled-by-default imported profiles.
**Description:** Parse Tasker `.prj.xml`/`.tsk.xml` exports into OpenTasker JSON/Room entities. Show mapped actions, unsupported actions, variable losses, scene exclusions, and required permissions before import.  
**Sources:** Tasker market dominance [S16], migration friction from competitors [S17][S18], open JSON strategy [S9][S11].  
**Category:** migration, UX, data.  
**Impact:** 5.  
**Effort:** 5.  
**Risk:** Medium/High; Tasker format breadth is large and lossy import must be honest.  
**Dependencies:** Open JSON import/export; capability registry; action mapping table.  
**Novelty:** Leapfrog among FOSS options.  
**Tier reason:** Captures experienced users without requiring them to rebuild years of workflows.

### X11 - F-Droid readiness track

**Status:** Completed in v0.2.58. v0.2.22 added a property-based `openTaskerDistribution=fdroid` build profile, pinned build tools, `BuildConfig.DISTRIBUTION`, proprietary dependency-family verification through `verifyFdroidReadiness`, CI coverage for the F-Droid release profile, F-Droid readiness docs, and a draft fdroiddata metadata file. v0.2.58 syncs the draft metadata to version `0.2.58` / code `60`, pins it to source commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`, adds `verifyFdroidMetadata`, adds release-tag/APK-payload comparison support in `tools/verify-fdroid-release.ps1`, documents the submission checks, and verifies local `fdroid lint` plus WSL fdroidserver 2.4.4 `fdroid build --no-tarball com.opentasker.app:60` with Java 17 and Android SDK 35.
**Description:** Add an `fdroid` build profile/flavor policy, pin reproducible build inputs where needed, avoid proprietary geofencing dependencies in F-Droid artifacts, document metadata, and verify unsigned release APK reproducibility assumptions.  
**Sources:** F-Droid reproducible builds [S31], F-Droid inclusion policy [S32], platform audit [L3].  
**Category:** distribution/packaging, security, docs.  
**Impact:** 4.  
**Effort:** 3.  
**Risk:** Medium; build tooling changes can affect release artifacts.  
**Dependencies:** Release workflow stability.  
**Novelty:** Strategic differentiator.  
**Tier reason:** F-Droid is the natural distribution channel for a privacy-first Android automation app.

### X12 - Dependency modernization plan

**Status:** Baseline completed in v0.2.23. Plugin and library versions are centralized in `gradle/libs.versions.toml`, Gradle declarations use catalog aliases, and `docs/DEPENDENCY_MODERNIZATION.md` defines the staged upgrade order and verification gate. Follow-up batches upgraded Hilt/Dagger from `2.46` to intermediate `2.52`, Room from `2.6.1` to `2.8.4` on the existing `androidx.room` line, WorkManager from `2.9.1` to `2.11.2`, the compatible Compose/AndroidX UI set to Compose BOM `2025.07.00` and Activity Compose `1.10.1`, the compatible runtime-support subset to Core KTX `1.16.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.9.0`, and Gson `2.14.0`, the compiler set to Kotlin/Compose plugin `2.2.21` plus KSP `2.2.21-2.0.5`, and the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0` while keeping target SDK `35` and runtime startup unchanged. Kotlin `2.3.21`/KSP `2.3.7` is deferred because Hilt `2.52` cannot load the new KSP task class and Hilt `2.59.2` requires AGP 9.0+. Remaining follow-up is the API 36-unblocked AndroidX dependency set: Core KTX `1.17.x+`, newer Activity/Navigation lines, and AGP-sensitive Lifecycle/Compose/Hilt Navigation lines.
**Description:** Upgrade dependencies in staged, reversible steps: Hilt/Dagger through an intermediate compatible release, Room, WorkManager, Compose BOM, Coroutines, Kotlin/KSP/Compose plugin alignment, and AGP only when build/reproducibility constraints are clear.  
**Sources:** Kotlin releases [S37], AGP releases [S38], Dagger releases [S39], Room releases [S35], WorkManager releases [S40], Compose releases [S41].  
**Category:** dev-experience, security, testing, distribution.  
**Impact:** 3.  
**Effort:** 4.  
**Risk:** Medium/High; Hilt migration has known generated-code changes and the app currently bypasses Hilt at runtime.  
**Dependencies:** Existing build green; tests.  
**Novelty:** Maintenance.  
**Tier reason:** Dependency lag should be corrected deliberately, not mixed with feature work.

---

## Later

### L1 - Visual flow/graph editor

**Status:** Baseline completed in v0.2.24. Added a pure automation graph model and optional read-only Flow tab that renders profile context nodes, enter/exit task lanes, action step nodes, edges, and incomplete-reference warnings. Editing, branching authoring, canvas interactions, and node deep links remain later L1 slices.
**Description:** Add an optional visual graph view for branching, parallelism, subflows, and profile chaining while keeping the list/form editor as the primary power-user path.  
**Sources:** Automate flowchart editor [S18], Node-RED flows [S9], openHAB Blockly [S13], ioBroker Blockly [S14].  
**Category:** UX, accessibility, plugin ecosystem.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** High; editor complexity can overwhelm beginners if it becomes mandatory.  
**Dependencies:** Stable JSON model; templates; active CRUD UI.  
**Novelty:** Parity.  
**Tier reason:** Valuable after the simpler editor and templates are reliable.

### L2 - Scene/overlay editor

**Status:** Baseline completed in v0.2.25. Added a Room-backed Scenes tab for scene shell creation/deletion, scene/element previews, overlay-access readiness, and validation of dimensions, bounds, and tap/long-press task references. Element editing, scaled canvas layout, and overlay launch remain later L2 slices.
**Description:** Build custom UI scenes/overlays with buttons, text, sliders, images, and task bindings. Handle `SYSTEM_ALERT_WINDOW` onboarding and Android 15 background-FGS overlay restrictions.  
**Sources:** Tasker scenes [S16], AppDaemon dashboard analogy [S15], Android 15 overlay/FGS changes [S36].  
**Category:** UX, platform/OS, security.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** High; overlays are policy-sensitive and easy to abuse.  
**Dependencies:** Permission onboarding; active UI; action binding model.  
**Novelty:** Parity.  
**Tier reason:** Advanced but not needed for core beta reliability.

### L3 - Optional Shizuku power-user backend

**Status:** Baseline completed in v0.2.26. Added Shizuku manager package visibility/status detection, an optional setup checklist row excluded from required readiness progress, and elevated-action hints for Shizuku candidates. OpenTasker still has no Shizuku API dependency, permission request, shell execution, or enabled privileged action path.
**Description:** Add an opt-in Shizuku integration for elevated actions such as secure settings, force-stop, private DNS, and restricted system toggles. Keep all core workflows functional without it.  
**Sources:** Shizuku [S6], Tasker ADB command center [S16], MacroDroid helper workaround [S17].  
**Category:** platform/OS, security, power-user.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** High; trust boundary and support complexity are significant.  
**Dependencies:** Capability registry; elevated-action isolation; docs.  
**Novelty:** Leapfrog.  
**Tier reason:** High power-user value but should not distract from non-elevated reliability.

### L4 - Sandboxed scripting escape hatch

**Status:** Baseline completed in v0.2.27. Added a gated `script.termux.run` action, runtime failure path, Termux and Termux:Tasker package visibility/status detection, optional setup checklist status, and docs/tests. OpenTasker still does not request `com.termux.permission.RUN_COMMAND`, dispatch Termux intents, run scripts, parse stdout/stderr, or map script output variables.
**Description:** Provide a first-class "Run Script" action through Termux integration initially, with structured stdout/stderr/exit-code capture and variable output parsing. Consider embedded JS only after threat modeling.  
**Sources:** Termux:Tasker [S3], n8n code node [S10], AppDaemon sandbox model [S15], openHAB scripting [S13].  
**Category:** integrations, security, dev-experience.  
**Impact:** 4.  
**Effort:** 4.  
**Risk:** High; arbitrary execution requires clear user warnings and sandbox boundaries.  
**Dependencies:** Run log, capability registry, user file/path restrictions.  
**Novelty:** Parity.  
**Tier reason:** Power-user unlock after safe core actions are mature.

### L5 - Profile sharing library

**Status:** Baseline completed in v0.2.28. Added an offline `ProfileShareLibrary` manifest for OpenTasker JSON bundles with stable slugs, bundle counts, unverified trust state, capability/import safety findings, and GitHub Discussions submission markdown. Network publishing, signed/verified templates, screenshots UI, and community import review remain later L5 slices.
**Description:** Build a community sharing path around open JSON bundles: copy/share URL, local import, screenshots/permission warnings, signed/verified templates, and GitHub Discussions as the initial submission channel.  
**Sources:** Taskernet [S16], MacroDroid template/community [S17], Automate community [S18], Node-RED flow library [S9], n8n templates [S10].  
**Category:** plugin ecosystem, docs, distribution.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** Medium/High; imported automations can be dangerous if not reviewed.  
**Dependencies:** JSON schema, capability warnings, import sandbox.  
**Novelty:** Leapfrog for OSS Android automation.  
**Tier reason:** Needs a stable import format and safety UX first.

### L6 - Advanced location without proprietary lock-in

**Status:** Started across v0.2.29, v0.2.36, v0.2.37, v0.2.38, v0.2.39, v0.2.40, v0.2.41, v0.2.42, v0.2.43, v0.2.44, v0.2.45, v0.2.46, v0.2.47, and v0.2.48. v0.2.29 added a pure `FossGeofenceEvaluator` with Haversine distance, radius checks, optional max accuracy, and dwell-time evaluation, then wired active Location context matching and legacy geofence distance checks through it. v0.2.36 adds a registered FOSS `LocationManager` source with GPS/network providers, fail-closed setup events, setup/inspector policy copy, and Android 14+ foreground-service location declarations. v0.2.37 persists inside-since dwell state per profile/context/config before Location context matching. v0.2.38 surfaces dwell state in Context Inspector profile rows. v0.2.39 clears persisted dwell keys when profiles are deleted or their context lists change. v0.2.40 adds a balanced provider request policy for GPS/network updates and less frequent setup rechecks. v0.2.41 centralizes Android 11+/14+ background-location and foreground-service disclosure copy. v0.2.42 fixes app-launch startup for the foreground automation service after device smoke testing exposed that only boot startup was wired, and an API 36 device smoke confirmed app launch starts the service foreground with `specialUse|location` after foreground/background location permissions and device location are enabled. v0.2.43 adds `tools/collect-location-evidence.ps1` for repeatable adb foreground-service, location, logcat, and battery snapshots; a 10-second API 36 home/background sample kept the service foreground with `specialUse|location`. v0.2.44 adds a setup-required disabled Location evidence template that installs a configurable Location context and log action for later background event-delivery smoke tests. v0.2.45 extends the evidence collector with debug Room snapshots, run-log summaries, and optional run-log/logcat pattern assertions. v0.2.46 verifies the installed/enabled `Location evidence log` template on an API 36 device while the app is sent home: a shell-owned GPS test provider delivered the template coordinates, `AutomationService` remained foreground with `specialUse|location`, and Room evidence `build/device-evidence/location/20260505-085413` recorded a successful `Location evidence log Task` run after evidence collection started. v0.2.47 extends the evidence collector with structured battery state/deltas plus fail-closed `-RequireUnpluggedSample` and `-RequireProviderCadenceEvidence` gates; API 36 evidence `build/device-evidence/location/20260505-120448` confirmed the provider-cadence gate and correctly reported the USB-powered sample as plugged tooling evidence. v0.2.48 adds `-RequireRecentUnpluggedHistory` with minimum-duration and maximum-age controls for post-reconnect validation when wireless ADB is unavailable; API 36 evidence `build/device-evidence/location/20260505-125057` captured about 111 seconds of recent unplugged history, below the 600-second roadmap threshold, and follow-up evidence `build/device-evidence/location/20260505-143254` captured 615.055 seconds of recent unplugged history with GPS/network cadence evidence present, satisfying the longer unplugged battery measurement. Broader multi-device/provider reliability verification and any optional Play-flavor backend remain later L6 slices.
**Description:** Implement FOSS-first geofencing through Android location APIs/manual Haversine, dwell time, accuracy tuning, and optional Play flavor backend only if the project explicitly accepts a Play-services flavor split.  
**Sources:** Android background location policy [S29], F-Droid inclusion policy [S32], Easer/LLAMA location lessons [S1][S21].  
**Category:** platform/OS, privacy, distribution.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** High; background location is policy-sensitive and battery-sensitive.  
**Dependencies:** Permission onboarding; F-Droid track; privacy policy.  
**Novelty:** Parity plus FOSS differentiation.  
**Tier reason:** Important, but background location should not land before disclosure/policy infrastructure.

### L7 - Variable/template expression system v2

**Status:** Completed in v0.2.35. v0.2.30 added a pure bounded `TemplateExpressionEngine`; v0.2.31 wires action arguments through legacy `%var` expansion and then `{{ ... }}` templates; v0.2.32 parses and renders sanitized expanded-argument summaries plus template warning counts; v0.2.33 persists and renders individual expression, source, value, and warning diagnostics; v0.2.34 applies bounded templates to action conditions before legacy predicate evaluation and skips fail-closed on warnings; v0.2.35 makes regex-like template functions explicitly unsupported while keeping bounded legacy `%var(regex:...)` separate.
**Description:** Evolve simple substitution into task-local/global scope, arrays, JSON paths, math/string functions, template expressions, and a debugger that shows expansion at each action step.  
**Sources:** Tasker variables [S16], Home Assistant Jinja templates [S12], Huginn Liquid templates [S11], Automate variable blocks [S18].  
**Category:** data, UX, observability.  
**Impact:** 4.  
**Effort:** 5.  
**Risk:** Medium; expression engines can create performance and injection issues.  
**Dependencies:** Run log step traces; parser tests.  
**Novelty:** Parity.  
**Tier reason:** Deep variables are essential, but unsafe parsing must not outrun validation.

---

## Under Consideration

| Item | Why it might matter | Why it is not tiered yet | Sources |
|---|---|---|---|
| Natural-language profile creation | Reduces learning curve further than templates | Needs privacy-preserving/on-device architecture and safety review | [S14][S16] |
| Wear OS companion | Useful for wrist triggers/actions and quick-run buttons | Smaller audience; phone reliability comes first | [S17] |
| Encrypted backup/sync | Smooth device migration without cloud trust | Requires key management and clear non-goals | [S17][S19] |
| HTTP webhook receiver | Excellent for home automation and n8n/Node-RED interop | Local server/background constraints and security model need design | [S10][S18] |
| App Factory / standalone APK export | Tasker differentiator for power users | Huge build/signing/security surface | [S16] |
| Multi-device/collaboration | Could enable family/shared automations | Contradicts on-device simplicity unless tightly bounded | [S12][S19] |
| IFTTT migration | Subscription backlash creates demand | IFTTT formats/API access may be unstable or unavailable | [S19] |
| iOS Shortcuts parity/import concepts | Shortcuts is a strong UX reference | Cross-platform import is likely low-fit for Android-first MVP | [S20] |
| Usage-duration triggers | App usage time is a useful wellness automation | Requires sensitive UsageStats access and careful privacy UX | [S4][S34] |
| Phone/call control actions | Common automation request | Play policy and Android API restrictions are high friction | [S30] |

---

## Rejected

| Item | Reason | Sources |
|---|---|---|
| Cloud-required automation backend | Violates OpenTasker's fully on-device/privacy-first differentiator | [S19] |
| Proprietary export format | Creates the lock-in OpenTasker is meant to avoid | [S17][S18] |
| Artificial macro/block/action limits | Conflicts with FOSS positioning and is a common competitor complaint | [S18][S19] |
| Mandatory Shizuku/root/ADB | Too much setup friction; elevated mode should remain optional | [S6][S17] |
| GPL plugin SDK requirement | Would reduce plugin adoption; MIT remains better for ecosystem growth | [S1][S7] |
| Server dependency like Home Assistant/openHAB | Adjacent systems are useful references, but OpenTasker should stay standalone | [S12][S13] |
| FBP-only editor | Flowcharts are powerful but too heavy as the only interface | [S18][S9] |
| Silent background automation without persistent disclosure | Unsafe, policy-hostile, and untrustworthy | [S22][S23][S29][S33] |

---

## Feature Harvest Matrix

This matrix captures the research harvest and deduped backlog. "Prevalence" is based on competitor/community presence: Rare, Emerging, Common, or Table-stakes.

| Feature / enhancement | Category | Prevalence | Tier | Source tags |
|---|---|---:|---|---|
| Active profile/task/action/context CRUD UI | UX | Table-stakes | Now | [L1][S16][S17] |
| First-run permission checklist | UX/platform | Common | Now | [S25][S26][S29][S33] |
| Battery optimization/OEM guidance | Reliability | Common | Now | [S22][S28][S17] |
| Exact alarm fallback and permission-state handling | Reliability | Table-stakes | Now | [S25] |
| Dynamic WiFi network callback | Platform | Table-stakes | Now | [S24][S27] |
| UsageStats app-open trigger redesign | Platform | Common | Now | [S34] |
| Capability registry | Architecture | Emerging | Now | [S25][S29][S33] |
| Honest unsupported-action failures | Correctness | Table-stakes | Now | [L2] |
| Step-level run log | Observability | Common | Now | [S9][S10][S11] |
| Log redaction | Security | Common | Now | [S10][S29] |
| Parser/variable regression tests | Testing | Table-stakes | Now | [S35][L4] |
| Room migration tests | Testing | Common | Now | [S35] |
| Docs truthfulness | Docs | Table-stakes | Now | [L5] |
| Template wizard | UX | Common | Next | [S12][S17][S10] |
| Curated starter templates | UX/docs | Common | Next | [S10][S12][S17] |
| Open JSON profile export | Data | Common | Next | [S9][S11] |
| Open JSON profile import | Migration | Common | Next | [S9][S11] |
| JSON schema versioning | Data | Common | Next | [S35] |
| Locale-compatible plugin host | Plugin | Table-stakes | Next | [S7][S3][S8] |
| OpenTasker intent API target | Integrations | Common | Next | [S5][S6][S7] |
| Sample plugin | Dev-experience | Common | Next | [S8] |
| Plugin validation harness | Testing | Emerging | Next | [S7][S8] |
| Automation modes | Reliability | Emerging | Next | [S12] |
| Context inspector | Observability | Common | Next | [S12][S9] |
| Notification listener trigger | Platform | Common | Next | [S16][S17] |
| NFC trigger | Platform/offline | Common | Next | [S4][S5][S18] |
| Calendar trigger | Platform | Common | Next | [S12][S17][S18] |
| Sunrise/sunset trigger | Platform | Common | Next | [S12] |
| Tasker XML import | Migration | Emerging | Next | [S16][S17] |
| Migration report | UX/migration | Emerging | Next | [S16] |
| F-Droid reproducibility profile | Distribution | Common | Next | [S31][S32] |
| Play policy declaration checklist | Distribution | Common | Next | [S29][S30][S33] |
| Dependency staged upgrade plan | Dev-experience | Table-stakes | Next | [S37][S38][S39][S40][S41] |
| Hilt migration decision | Architecture | Table-stakes | Next | [S39][L1] |
| WorkManager upgrade | Reliability | Common | Next | [S40] |
| Room upgrade | Data | Common | Next | [S35] |
| Compose BOM upgrade | UX/dev | Common | Next | [S41] |
| Visual graph editor | UX | Common | Later | [S18][S9] |
| Blockly/block editor | Accessibility/UX | Common | Later | [S13][S14] |
| Scene/overlay editor | UX/platform | Common | Later | [S16][S36] |
| Shizuku elevated backend | Platform | Emerging | Later | [S6][S17] |
| Termux script action | Integrations | Common | Later | [S3] |
| Structured script output variables | Data | Emerging | Later | [S3][S15] |
| Profile sharing library | Plugin/community | Common | Later | [S9][S10][S16] |
| Signed/verified community templates | Security | Emerging | Later | [S10][S31] |
| FOSS geofence backend | Platform/privacy | Common | Later | [S29][S32] |
| Dwell-time geofence | UX/platform | Common | Later | [S12] |
| Variable scope model | Data | Common | Later | [S12][S16] |
| Template expressions | Data | Common | Later | [S11][S12] |
| JSON path expressions | Data | Common | Later | [S11][S18] |
| Regex expression limits | Security | Common | Later | [L4] |
| HTTP webhook receiver | Integrations | Common | Under Consideration | [S10][S18] |
| Encrypted backup/sync | Data/security | Common | Under Consideration | [S17][S19] |
| Wear OS companion | Mobile | Emerging | Under Consideration | [S17] |
| Natural-language profile generator | UX | Emerging | Under Consideration | [S14][S16] |
| App Factory | Distribution | Rare | Under Consideration | [S16] |
| IFTTT applet migration | Migration | Rare | Under Consideration | [S19] |
| iOS Shortcuts concept import | Migration | Rare | Under Consideration | [S20] |
| Usage-duration wellness trigger | UX/privacy | Emerging | Under Consideration | [S4][S34] |
| Phone/call operations | Platform/policy | Common | Under Consideration | [S30] |
| Multi-device collaboration | Multi-user | Emerging | Under Consideration | [S12] |
| Cloud-required backend | Architecture | Common elsewhere | Rejected | [S19] |
| Proprietary export format | Data | Common elsewhere | Rejected | [S17][S18] |
| Artificial free-tier limits | Licensing | Common elsewhere | Rejected | [S18][S19] |
| Mandatory root/ADB/Shizuku | Platform | Common workaround | Rejected | [S6][S17] |
| GPL-only plugin ecosystem | Licensing | Rare | Rejected | [S1][S7] |
| Server dependency | Architecture | Common adjacent | Rejected | [S12][S13] |
| Flow-only UI | UX | Common | Rejected | [S18][S9] |
| Silent background service behavior | Security/policy | Deprecated | Rejected | [S22][S23][S33] |

---

## Category Coverage Audit

| Category | Covered by |
|---|---|
| Security | Capability registry, log redaction, plugin host sandboxing, Shizuku isolation, Play policy checklist |
| Accessibility | Templates/wizard, future Blockly/visual editor, honest status/error text, permission explanations |
| i18n/l10n | Easer/Weblate research suggests translation workflow; add strings-only UI as implementation rule during UI reintegration |
| Observability/telemetry | Step-level run log, context inspector, source health, migration report; no cloud telemetry planned |
| Testing | Parser tests, migration tests, capability tests, scheduler fallback tests, plugin harness |
| Docs | Truthful README/architecture/changelog, plugin guide, import/export schema docs, F-Droid metadata |
| Distribution/packaging | GitHub Actions, env-var signing, F-Droid reproducibility, Play policy declarations |
| Plugin ecosystem | Locale host, OpenTasker target intents, sample plugin, sharing library |
| Mobile/platform | Android 13-15 permissions, foreground service types, exact alarms, WiFi/Bluetooth, UsageStats, overlay |
| Offline/resilience | Fully on-device, NFC triggers, FOSS geofencing, WorkManager/AlarmManager fallback |
| Multi-user/collab | Considered but not prioritized; local-first single-user remains the product center |
| Migration | Tasker XML import, JSON import/export, migration report, schema versioning |
| Upgrade strategy | Room schema export, migration tests, dependency staging, build flavor/reproducibility plan |

---

## Self-Audit

- Every roadmap item above has a local evidence tag or source tag.
- No item depends on a competitor feature claim without a URL or local-repo source in the appendix.
- Old claims that v0.3/v0.4 UI/features are complete have been superseded by the current active-APK state.
- Android platform restrictions are treated as product requirements, not implementation details.
- F-Droid and Google Play constraints are separated because their acceptable dependency/policy profiles differ.
- Rejected items are explicitly preserved so they do not silently return as future churn.
- High-risk ideas are staged after the capability registry, permission onboarding, import/export schema, and run-log improvements that make them safe to ship.

---

## Appendix A - Source Index

### Local repository evidence

| Tag | Source |
|---|---|
| L1 | Local repo: `MainActivity.kt`, `OpenTaskerApp_NoHilt.kt`, `.kt.bak` UI snapshots, README/CHANGELOG state as of commit `10578e4` |
| L2 | Local repo: TODO/stub scan across `app/src/main/java/com/opentasker/core/actions/**` and context/action implementations |
| L3 | Local repo/platform audit: `AndroidManifest.xml`, `AutomationService.kt`, `BootReceiver.kt`, `WiFiEventReceiver.kt`, `AppOpenService.kt`, `GeofenceTrigger.kt`, `build.gradle.kts` |
| L4 | Local repo: `InputValidationTest.kt`, Room schema export, Gradle validation after hardening |
| L5 | Local repo: previous roadmap, project notes, and README status mismatch |

### Direct Android automation and plugin sources

| Tag | Source | URL |
|---|---|---|
| S1 | Easer repository | https://github.com/renyuneyun/Easer |
| S2 | Easer F-Droid listing | https://f-droid.org/packages/ryey.easer/ |
| S3 | Termux:Tasker | https://github.com/termux/termux-tasker |
| S4 | Home Assistant Android companion | https://github.com/home-assistant/android |
| S5 | openHAB Android app | https://github.com/openhab/openhab-android |
| S6 | Shizuku | https://github.com/RikkaApps/Shizuku |
| S7 | Locale plugin SDK monorepo | https://github.com/twofortyfouram/android-monorepo |
| S8 | Tasker plugin sample/library | https://github.com/joaomgcd/TaskerPluginSample |

### Adjacent automation architecture sources

| Tag | Source | URL |
|---|---|---|
| S9 | Node-RED | https://github.com/node-red/node-red |
| S10 | n8n | https://github.com/n8n-io/n8n |
| S11 | Huginn | https://github.com/huginn/huginn |
| S12 | Home Assistant automation docs/core | https://www.home-assistant.io/docs/automation/ |
| S13 | openHAB automation/core | https://www.openhab.org/docs/automation/ |
| S14 | ioBroker JavaScript adapter | https://github.com/ioBroker/ioBroker.javascript |
| S15 | AppDaemon | https://github.com/AppDaemon/appdaemon |

### Commercial/community Android automation sources

| Tag | Source | URL |
|---|---|---|
| S16 | Tasker user guide / plugin ecosystem | https://tasker.joaoapps.com/userguide/en/ |
| S17 | MacroDroid site | https://www.macrodroid.com/ |
| S18 | Automate documentation/site | https://llamalab.com/automate/doc/ |
| S19 | IFTTT plans | https://ifttt.com/plans |
| S20 | Samsung Modes and Routines reference | https://www.samsung.com/us/support/answer/ANS10002538/ |
| S21 | LLAMA Play listing | https://play.google.com/store/apps/details?id=com.kebab.Llama |

### Android platform and policy sources

| Tag | Source | URL |
|---|---|---|
| S22 | Android Doze and App Standby | https://developer.android.com/training/monitoring-device-state/doze-standby |
| S23 | Android 8 background execution limits | https://developer.android.com/about/versions/oreo/background |
| S24 | Android 7 background/broadcast changes | https://developer.android.com/about/versions/nougat/android-7.0-changes#bg-opt |
| S25 | Android 14 exact alarms | https://developer.android.com/about/versions/14/changes/schedule-exact-alarms |
| S26 | Android notification runtime permission | https://developer.android.com/develop/ui/views/notifications/notification-permission |
| S27 | Android 13 nearby WiFi devices permission | https://developer.android.com/about/versions/13/behavior-changes-13#nearby-wifi-devices-permission |
| S28 | Don't Kill My App OEM database | https://dontkillmyapp.com/ |
| S29 | Google Play background location policy | https://support.google.com/googleplay/android-developer/answer/9799150 |
| S30 | Google Play SMS/call log policy | https://support.google.com/googleplay/android-developer/answer/9047303 |
| S31 | F-Droid reproducible builds | https://f-droid.org/docs/Reproducible_Builds/ |
| S32 | F-Droid inclusion policy | https://f-droid.org/docs/Inclusion_Policy/ |
| S33 | Google Play accessibility policy | https://support.google.com/googleplay/android-developer/answer/10964491 |
| S34 | UsageStatsManager API | https://developer.android.com/reference/android/app/usage/UsageStatsManager |
| S35 | Room migrations and schema export | https://developer.android.com/training/data-storage/room/migrating-db-versions |
| S36 | Android 15 behavior changes | https://developer.android.com/about/versions/15/behavior-changes-15 |

### Dependency and build sources

| Tag | Source | URL |
|---|---|---|
| S37 | Kotlin releases | https://kotlinlang.org/docs/releases.html |
| S38 | Android Gradle Plugin releases | https://developer.android.com/build/releases/gradle-plugin |
| S39 | Dagger/Hilt releases | https://github.com/google/dagger/releases |
| S40 | WorkManager releases | https://developer.android.com/jetpack/androidx/releases/work |
| S41 | Compose releases | https://developer.android.com/jetpack/androidx/releases/compose |
| S42 | Google WiFi API issue | https://issuetracker.google.com/issues/128554616 |

