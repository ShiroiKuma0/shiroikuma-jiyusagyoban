# OpenTasker Roadmap

**Current app version:** 0.2.75
**Last updated:** 2026-06-19

Items are organized by tier and priority. Completed items are deleted, not checked off. See CHANGELOG.md for shipped work.

---

## Tier Summary

| Tier | Goal | Exit criteria |
|---|---|---|
| Now | Ship a truthful, installable v0.3.0 beta on Android 13+ | Platform compliance verified, docs accurate, performance baselined |
| Next | Reach credible beta parity for core automation workflows | Scene/flow authoring, Shizuku/Termux execution, sharing publish, i18n |
| Later | Expand into power-user and ecosystem features | Encrypted backup, visual editor polish, community marketplace |
| Backlog | Smaller improvements by priority | P2 = medium value, P3 = nice-to-have |
| Under Consideration | Needs more proof, policy clearance, or design | Webhook receiver, Wear OS, NL generation, MQTT |
| Rejected | Conflicts with OpenTasker's philosophy | Cloud dependency, proprietary export, artificial limits |

---

## Research-Driven Additions

### P0 -- Root-cause fixes and platform compliance

- [ ] P0 -- Centralize all logging through AppLogger
  Why: 17 `android.util.Log` calls in 5 files bypass the diagnostic export redaction pipeline, making crash/issue reports incomplete.
  Evidence: Grep of source — `AutomationService.kt` (10), `TimeEventReceiver.kt` (4), `ProfileDao.kt` (1), `TaskDao.kt` (1), `SceneDao.kt` (1).
  Touches: `AutomationService.kt`, `TimeEventReceiver.kt`, `ProfileDao.kt`, `TaskDao.kt`, `SceneDao.kt`, `AppLogger.kt`
  Acceptance: Zero `android.util.Log` calls remain in `app/src/main`; lint or grep gate in CI prevents reintroduction.
  Complexity: S

- [ ] P0 -- Implement FGS onTimeout() callback
  Why: Android 16 enforces 6-hour timeout for dataSync/mediaProcessing FGS types. AutomationService uses `specialUse|location` but has no `onTimeout()` safety net — if platform behavior changes, the service will ANR.
  Evidence: Android 16 FGS timeout docs (`developer.android.com/develop/background-work/services/fgs/timeout`); `AutomationService.kt` lacks `onTimeout()` override.
  Touches: `AutomationService.kt`
  Acceptance: `onTimeout()` override logs the event, persists a diagnostic entry, and calls `stopSelf()` cleanly; unit test covers the callback path.
  Complexity: S

- [ ] P0 -- Add Compose UI test infrastructure
  Why: Zero UI tests exist for 8+ Compose screen files totaling ~7000 lines. `ActiveAutomationUi.kt` alone is 3347 lines. No `compose-ui-test-junit4` dependency. UI regressions are invisible to CI.
  Evidence: No `ui-test` dependency in `app/build.gradle.kts`; no test files matching `*UiTest*` or `*ScreenTest*` in `app/src/androidTest`.
  Touches: `app/build.gradle.kts` (add compose-ui-test deps), new `app/src/androidTest/` test files for profiles, tasks, and run-log screens.
  Acceptance: At least 3 Compose UI tests covering profile list rendering, task creation dialog, and run-log filtering; CI runs them on `testDebugUnitTest`.
  Complexity: M

- [ ] P0 -- Android 17 (API 37) target readiness pass
  Why: Android 17 is stable (June 2026). ACCESS_LOCAL_NETWORK becomes a runtime permission requiring user grant. Background audio requires FGS with while-in-use capabilities. Certificate transparency on by default. Large-screen orientation opt-out removed (was temporary in API 36).
  Evidence: Android 17 behavior change docs (`developer.android.com/about/versions/17/behavior-changes-17`, `/behavior-changes-all`); `PermissionOnboardingScreen.kt` already shows the permission but runtime request flow may be incomplete.
  Touches: `PermissionOnboardingScreen.kt`, `NetworkActions.kt`, `MediaActions.kt`, `SettingsActions.kt`, `AndroidManifest.xml`, `network_security_config.xml`
  Acceptance: App targets API 37; ACCESS_LOCAL_NETWORK is requested at runtime before LAN actions; media actions check FGS WIU state; CT opt-out evaluated; layout tested on sw600dp+.
  Complexity: L

### P1 -- Trust, reliability, and maintainability

- [ ] P1 -- Harden ProGuard/R8 rules for Shizuku, RE2J, and Room
  Why: Current rules only keep model classes, kotlinx-serialization, and manifest entries. Shizuku AIDL stubs, RE2J pattern internals, and Room-generated DAO implementations may be stripped in release builds.
  Evidence: `app/proguard-rules.pro` is 7 lines; Shizuku API uses reflection for IPC; RE2J uses `sun.misc.Unsafe` fallbacks.
  Touches: `app/proguard-rules.pro`
  Acceptance: Release APK passes full test suite; Shizuku shell execution, RE2J regex evaluation, and Room queries verified in release build.
  Complexity: S

- [ ] P1 -- Expand instrumented test coverage
  Why: 4 instrumented tests (498 LOC) for a 23k LOC app. Critical paths untested on-device: Room DAO query correctness with real SQLite, backup encrypt/decrypt round-trip, bundle import/export round-trip with ID remapping.
  Evidence: `app/src/androidTest/` contains only `DatabaseBackupManagerInstrumentedTest.kt`, `DatabaseMigrationInstrumentedTest.kt`, `RunLogDaoInstrumentedTest.kt`, `OpenTaskerBundleRepositoryInstrumentedTest.kt`.
  Touches: New files in `app/src/androidTest/`
  Acceptance: At least 8 instrumented tests covering DAO queries (profile/task/variable CRUD), backup encrypt-decrypt round-trip, and import-export-reimport identity.
  Complexity: M

- [ ] P1 -- Add WorkManager test artifact
  Why: `RunLogPruneWorker` uses WorkManager but `work-testing` is not a dependency; the worker's retention logic is untested in the WorkManager lifecycle.
  Evidence: `app/build.gradle.kts` has no `work-testing` dependency; `RunLogPruneWorker` has no corresponding test.
  Touches: `app/build.gradle.kts`, new `RunLogPruneWorkerTest.kt`
  Acceptance: `TestWorkerBuilder` test verifies retention cutoff logic and pruning behavior.
  Complexity: S

- [ ] P1 -- Decompose ActiveAutomationUi.kt further
  Why: 3347 lines after initial split. Profile editor, task editor, action editor, and context editor still share one file. High regression risk for UI polish and i18n work.
  Evidence: `wc -l` on the file; `ActiveAutomationModuleSplitTest.kt` guards the boundary but the file is still 3x the size of the next-largest screen.
  Touches: `ActiveAutomationUi.kt`, new files for profile editor, task editor, action editor, context editor composables.
  Acceptance: `ActiveAutomationUi.kt` under 1500 lines; extracted composables each under 800 lines; existing split-guard test updated; no UI regression.
  Complexity: L

### P2 -- Quick wins, parity, and integrations

- [ ] P2 -- Weblate integration for community translations
  Why: 13 locale skeletons exist but are completely empty. Manual PR workflow for translations creates friction. Weblate (hosted.weblate.org, GPL-3.0) is the F-Droid ecosystem standard and integrates with Git natively.
  Evidence: `app/src/main/res/values-de/strings.xml` (and 12 others) are empty `<resources>` stubs. README documents PR-based translation workflow.
  Touches: Weblate project setup (external), `.weblate` config, README translation section update.
  Acceptance: Weblate project connected to repo; translators can submit translations via web UI; merged translations arrive as Git commits.
  Complexity: S

- [ ] P2 -- Add kotlinx-collections-immutable for Compose stability
  Why: StateFlow emissions with mutable `List<Profile>`, `List<Task>`, etc. cause unnecessary recompositions even with strong skipping mode. `kotlinx-collections-immutable` provides stable collection types the Compose compiler can skip.
  Evidence: No `kotlinx-collections-immutable` in `libs.versions.toml`; Compose strong skipping treats rebuilt List instances as changed.
  Touches: `gradle/libs.versions.toml`, `app/build.gradle.kts`, ViewModel state classes emitting lists.
  Acceptance: Compose compiler metrics show list-bearing composables as skippable; no recomposition regressions.
  Complexity: S

- [ ] P2 -- Enable Compose compiler metrics CI gate
  Why: No visibility into which composables are skippable/restartable or which parameters are unstable. Stability issues cause silent performance degradation.
  Evidence: `-P android.enableComposeCompilerMetrics` not set in CI; no stability reports generated.
  Touches: `.github/workflows/build.yml`, Compose compiler configuration.
  Acceptance: CI generates and archives Compose stability report; known-unstable composables documented.
  Complexity: S

- [ ] P2 -- Home Assistant webhook protocol integration
  Why: HA is the dominant open-source home automation platform. Tasker requires a separate third-party plugin. Implementing the HA mobile_app webhook protocol directly (register, expose sensors, accept notification commands) is a major differentiator.
  Evidence: HA companion app webhook protocol documented (`companion.home-assistant.io`); Tasker needs "Home Assistant Plug-In for Tasker" (third-party); no FOSS automation app integrates natively.
  Touches: New `core/integrations/HomeAssistantWebhook.kt`, `PermissionOnboardingScreen.kt` (HA setup), action metadata.
  Acceptance: OpenTasker registers as a mobile_app with a local HA instance; battery, connectivity, and location exposed as HA entities; HA notification commands trigger OpenTasker tasks.
  Complexity: L

- [ ] P2 -- Guided first-automation onboarding
  Why: Steep learning curve is the #1 complaint about Tasker and the main barrier for MacroDroid growth. OpenTasker's template system exists but isn't the first thing users see.
  Evidence: Tasker and MacroDroid user feedback; MacroDroid's template-first home screen; Samsung Routines' natural-language creation success.
  Touches: `MainActivity.kt` (first-launch detection), new onboarding composable, `ProfileTemplates.kt` (surface as primary CTA).
  Acceptance: First launch shows a 3-step guided creation flow; template browsing is the primary CTA on the home screen; dismiss state persisted.
  Complexity: M

- [ ] P2 -- Permission denial path test coverage
  Why: Automation apps request many permissions; rarely tested what happens when each is denied. Current tests assume granted state.
  Evidence: UX research finding; `PermissionOnboardingScreen.kt` handles display but action-level denial paths (notification, location, Bluetooth, usage stats) are not systematically tested.
  Touches: New test files for action guard denial paths.
  Acceptance: Every permission-gated action has a test verifying the failure message when permission is denied.
  Complexity: S

### P3 -- Nice-to-have and evaluation

- [ ] P3 -- Evaluate Glance for widget rewrite
  Why: Current widget uses XML layout (`widget_task.xml`). Glance 1.2.0-rc01 provides Compose-based widgets with unit testing (`runGlanceAppWidgetUnitTest`), IDE preview, and alpha/tinting APIs.
  Evidence: `app/src/main/res/layout/widget_task.xml` is XML-based; `TaskWidgetProvider.kt` uses RemoteViews; Glance `1.2.0-rc01` on `developer.android.com/jetpack/androidx/releases/glance`.
  Touches: Evaluation spike only; no production code changes.
  Acceptance: Written evaluation with APK size impact, API surface comparison, and migration effort estimate.
  Complexity: S

- [ ] P3 -- Evaluate Navigation3 migration
  Why: Navigation Compose 2.9.8 (Nav2) is current. Navigation3 (`androidx.navigation3` v1.2.0-alpha03) is the Compose-first successor with type-safe metadata DSL, scene strategies, overlay scenes, and adaptive layout integration.
  Evidence: `libs.versions.toml` uses `navigationCompose = "2.9.8"`; Navigation3 releases at `developer.android.com/jetpack/androidx/releases/navigation3`.
  Touches: Evaluation spike only; no production code changes.
  Acceptance: Written evaluation with migration scope, breaking changes, and recommended timing.
  Complexity: S

- [ ] P3 -- Calendar CRUD actions
  Why: Tasker 6.5 added 7 calendar actions (get/edit events, reminders, attendees). Samsung Routines added full calendar CRUD. OpenTasker reads calendar events (`CalendarSunContextEvents.kt`) but cannot create, update, or delete them.
  Evidence: Tasker v6.5 changelog; Samsung One UI 8 changelog; `CalendarSunContextEvents.kt` is read-only.
  Touches: New `CalendarActions.kt` with create/update/delete event actions, `ActionMetadata.kt` entries, `RuntimeRegistries.kt` registration.
  Acceptance: Actions to create, update, and delete calendar events with title, time, duration, and calendar ID arguments; read-only CalendarProvider access upgraded to read-write with permission gating.
  Complexity: M

- [ ] P3 -- Evaluate AlarmManager OnAlarmListener (API 37)
  Why: Android 17 adds `setExactAndAllowWhileIdle` overload accepting `OnAlarmListener` instead of `PendingIntent`. Does not require `SCHEDULE_EXACT_ALARM` permission. Could simplify time trigger scheduling.
  Evidence: Android 17 features docs; `TimeEventScheduler.kt` currently uses PendingIntent-based alarms with exact/inexact fallback.
  Touches: Evaluation spike; `TimeEventScheduler.kt` if adopted.
  Acceptance: Written evaluation comparing listener-based vs PendingIntent alarms for reliability, Doze behavior, and permission UX.
  Complexity: S

- [ ] P3 -- Accessibility Service rule-based UI automation
  Why: Google Play explicitly allows "deterministic/rule-based automation" via Accessibility Service. Tasker (AutoInput), AutoJs6, and MacroDroid all offer UI automation. OpenTasker has no UI automation capability.
  Evidence: Google Play Permission Declaration Form policy (allows "if trigger X, perform action Y" with human-defined scripts); F-Droid has no restrictions; `PermissionOnboardingScreen.kt` does not include accessibility service.
  Touches: New `AccessibilityAutomationService.kt`, new UI tap/swipe/read actions, `ActionMetadata.kt`, manifest declaration.
  Acceptance: Rule-based tap, swipe, and text-read actions that work with TalkBack-compatible semantics; modular opt-in (app works without it); Play Store declaration form prepared.
  Complexity: XL

---

## Backlog

---

## Under Consideration

| Item | Why it might matter | Why it is not tiered yet |
|---|---|---|
| HTTP webhook receiver (RD10) | n8n/Node-RED/HA interop without cloud | Local HTTP server security surface, FGS/battery constraints |
| Natural-language profile creation | Reduces learning curve beyond templates | Needs privacy-preserving on-device architecture |
| Wear OS companion | Wrist triggers and quick-run tiles | Smaller audience; phone reliability comes first |
| Encrypted backup sync | Smooth device migration without cloud trust | Encrypted backup shipped; cloud sync requires key management design |
| App Factory / standalone APK export | Tasker differentiator for power users | Huge build/signing/security surface |
| Multi-device/collaboration | Family/shared automations | Contradicts on-device simplicity unless tightly bounded |
| IFTTT migration | Subscription backlash creates demand | IFTTT formats/API access may be unstable |
| Usage-duration triggers | App usage time as wellness automation | Sensitive UsageStats access and privacy UX |
| Phone/call control actions | Common automation request | Play policy and Android API restrictions are high friction |
| Matter / Google Home actor | Smart-home actuation without HA dependency | Matter SDK on Android is nascent; commissioning UX is heavy |

---

## Rejected

| Item | Reason |
|---|---|
| Cloud-required automation backend | Violates on-device/privacy-first positioning |
| Proprietary export format | Creates the lock-in OpenTasker is meant to avoid |
| Artificial macro/block/action limits | Conflicts with FOSS positioning |
| Mandatory Shizuku/root/ADB | Too much setup friction; elevated mode must remain optional |
| GPL plugin SDK requirement | Would reduce plugin adoption; MIT is better for ecosystem |
| Server dependency (HA/openHAB style) | OpenTasker should stay standalone |
| FBP-only editor | Flowcharts are too heavy as the only interface |
| Silent background automation | Unsafe, policy-hostile, and untrustworthy |

---

## Completed (v0.2.60 - v0.2.70)

Items shipped since the last roadmap rotation. Earlier completions (v0.2.0 - v0.2.59) are in CHANGELOG.md.

| Item | Shipped in |
|---|---|
| N6 - Sharing preview UI (bundle review dialog) | v0.2.62 |
| N7 - Locale condition plugin context UX | v0.2.69 |
| N8 - Hilt decision (removed Hilt/Dagger) | v0.2.61 |
| Profile group/folder organization | v0.2.70 |
| Diagnostic report share button + redaction tests | v0.2.69 |
| CI SHA-pinned actions + least-privilege permissions | v0.2.61+ |
| rememberSaveable state retention | v0.2.62 |
| Accessibility contentDescription coverage | v0.2.62 |
| Profile and task search bars | v0.2.62 |
| Date/time format template function | v0.2.61 |
| android:allowBackup=false declared | v0.2.61 |
| Gson removed (kotlinx-serialization only) | v0.2.61 |
| WorkManager periodic run-log pruning | v0.2.61 |
| Crash capture + diagnostic report builder | v0.2.61 |
| targetSdk raised to 36 | v0.2.61 |
| Typed run-log source/sourceLabel columns | v0.2.60 |
| task.run sub-task action (depth-bounded) | v0.2.60 |
| Flow control: if/else/endif, foreach, stop | v0.2.60 |
| Bluetooth connect/disconnect trigger | v0.2.60 |
| OEM battery-killer detection + setup guidance | v0.2.60 |
| Theme toggle (dark/light/high-contrast) | v0.2.61 |
| Wake-on-LAN action | v0.2.61 |
| Passive location provider | v0.2.62 |
| Camera/mic event triggers | v0.2.62 |

---

## Source Index

### Local evidence tags

| Tag | Description |
|---|---|
| L1 | Core repo: MainActivity, OpenTaskerApp_NoHilt, UI snapshots, README/CHANGELOG |
| L6 | Location evidence: tools/collect-location-evidence.ps1, FOSS_GEOFENCING.md, single-device API-36 data |
| L8 | 2026-06-06 manifest/service/build audit through c2412ad |
| L9 | 2026-06-06 UI maintainability audit (ActiveAutomationUi.kt 2891 lines, strings.xml 5 entries) |
| L12 | 2026-06-06 platform/incoming-intent audit (FGS, BootReceiver, Locale, NFC, Tasker XML) |
| L14 | 2026-06-06 dependency/release audit (catalog versions, CI workflows, F-Droid metadata) |

### External sources

| Tag | Source |
|---|---|
| S1 | [Easer](https://github.com/renyuneyun/Easer) |
| S3 | [Termux:Tasker](https://github.com/termux/termux-tasker) |
| S6 | [Shizuku](https://github.com/RikkaApps/Shizuku) |
| S7 | [Locale plugin SDK](https://github.com/twofortyfouram/android-monorepo) |
| S9 | [Node-RED](https://github.com/node-red/node-red) |
| S10 | [n8n](https://github.com/n8n-io/n8n) |
| S12 | [Home Assistant](https://www.home-assistant.io/docs/automation/) |
| S16 | [Tasker](https://tasker.joaoapps.com/userguide/en/) |
| S17 | [MacroDroid](https://www.macrodroid.com/) |
| S18 | [Automate](https://llamalab.com/automate/doc/) |
| S28 | [Don't Kill My App](https://dontkillmyapp.com/) |
| S31 | [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) |
| S45 | [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) |
| S46 | [TileService](https://developer.android.com/reference/android/service/quicksettings/TileService) |
| S47 | [Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/overview) |
| S49 | [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| S52 | [Android 16 target behavior](https://developer.android.com/about/versions/16/behavior-changes-16) |
| S53 | [Android 16 all-app behavior](https://developer.android.com/about/versions/16/behavior-changes-all) |
