# Research — OpenTasker

## Executive Summary
OpenTasker is a local-first Android automation app built with Kotlin, Jetpack Compose, Room, WorkManager, a foreground automation service, Locale-plugin hosting/target support, Shizuku and Termux backends, encrypted backup/restore, visual scene/flow authoring, widgets, quick settings, NFC, JSON import/export, and diagnostic run logs. Its strongest current shape is privacy-preserving Android automation for F-Droid and power users who want Tasker-like capability without mandatory cloud accounts. The highest-value direction is beta hardening before ecosystem expansion: replace remaining direct platform logs with the `AppLogger` diagnostics path; fix stale F-Droid metadata and release evidence; add a docs/toolchain truth gate; finish the `ActiveAutomationUi.kt` split; complete string-resource extraction and translation plumbing; add repeatable accessibility and editor UI tests; add a repeatable Locale plugin fixture; then evaluate Home Assistant, MQTT, UnifiedPush/ntfy, Glance, Navigation3, and bounded accessibility automation.

## Product Map
- Core workflows: create profiles from contexts, attach task/action sequences, run automations through `AutomationService`, inspect failures in run logs, and manage setup/permissions before enabling high-risk features.
- Core workflows: author scenes and visual flows, import/export profiles and Tasker-compatible data, share templates, back up encrypted app data, and run diagnostics.
- User personas: privacy-focused F-Droid users, Tasker migrants, Android power users/sysadmins, home-automation users, degoogled-device users, and users who need explicit permission control for Shizuku, Termux, notifications, widgets, NFC, and local-network actions.
- Platforms and distribution: Android API 26+, `compileSdk`/`targetSdk` 37 in `app/build.gradle.kts`, Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, standard/F-Droid/Play build profiles, GitHub Actions CI, and draft F-Droid metadata.
- Key integrations and data flows: Room entities/DAOs feed Compose StateFlow screens; WorkManager prunes run logs; `AutomationService` executes actions; Locale, Shizuku, Termux, SAF, widgets, quick settings, NFC, broadcasts, HTTP/LAN actions, encrypted backup, JSON export/import, and diagnostic logs cross the app boundary.

## Competitive Landscape
- Tasker: deep action coverage, scenes, plugin ecosystem, Shizuku-style elevated actions, calendar/contact breadth, App Factory, and AI-assisted task creation. OpenTasker should learn from Tasker's action breadth and plugin maturity, but avoid its proprietary lock-in, steep learning curve, and complexity-heavy defaults.
- MacroDroid: guided templates, simple macro creation, community templates, Wear OS support, and approachable onboarding. OpenTasker should learn from its template-first entry path and discoverability, but avoid artificial free-tier limits and cloud-dependent community features.
- Automate: flowchart automation, live flow debugging, and community-shared flows. OpenTasker should learn from visual flow labels, execution tracing, and importable examples, but avoid making graph editing the only authoring path.
- Samsung Modes and Routines: low-friction device routines, natural-language assisted creation, calendar/weather/location/device-condition triggers, and vendor-integrated permissions. OpenTasker should learn from its guided creation and contextual suggestions, but avoid vendor-only features that cannot work across F-Droid and non-Samsung devices.
- Easer, PhoneProfilesPlus, and Automation by Jens: FOSS profile automation with explicit logical operators, profile modes, HTTP events, cell-learning ideas, and long-lived Android survivability lessons. OpenTasker should learn from their condition model and F-Droid resilience, but avoid dormant UX patterns and narrow feature silos.
- AutoJs6 and related Auto.js forks: powerful script and accessibility automation, broad ecosystem demand, and Shizuku/elevated execution patterns. OpenTasker should learn from opt-in power-user extension points, but avoid unbounded code execution and accessibility-service abuse.
- Home Assistant, Node-RED, and n8n: strong integration catalogs, webhooks, MQTT/event buses, sensors, audit trails, and flow automation. OpenTasker should learn from their local-protocol bridges and observability, but avoid turning the app into a server-required product.

## Security, Privacy, and Reliability
- Verified risk: direct `android.util.Log` calls remain outside `AppLogger.kt`, including `OpenTaskerApp_NoHilt.kt`, `AppUsageMonitor.kt`, `ConnectivityMonitor.kt`, `WiFiNetworkMonitor.kt`, `TimeEventScheduler.kt`, `ShakeDetector.kt`, `CrashLogHandler.kt`, `AutomationTargetReceiver.kt`, `BootReceiver.kt`, `BluetoothContextEvents.kt`, `NotificationActionReceiver.kt`, `TaskExecutionHelper.kt`, and `NotificationTriggerService.kt`. These bypass the app-level diagnostics and redaction boundary.
- Verified risk: `fdroid/metadata/com.opentasker.app.yml` still advertises `0.2.73`/version code `75` while `app/build.gradle.kts` builds `0.2.75`/version code `77`; `tools/verify-fdroid-release.ps1` expects metadata, Gradle, changelog, and output paths to agree.
- Verified risk: README and docs still contain stale release/toolchain claims, including SDK 36-era wording and "readiness" language for shipped Shizuku, Termux, scene, and flow features. This creates release and onboarding drift.
- Likely risk: `app/src/main/res/xml/network_security_config.xml` uses IP-like domain entries for private ranges; Android domain-config entries are hostname oriented, so LAN cleartext behavior should be validated with the existing HTTP/local-network policy before changing it.
- Likely risk: Termux script dispatch is intentionally delegated, but `app/src/main/java/com/opentasker/core/actions/ScriptActions.kt` should make any script allowlist/hash expectations explicit and testable before more script automation is promoted.
- Missing guardrails: repeatable TalkBack/accessibility evidence, Locale plugin compatibility fixtures, API 37/local-network device evidence, and reproducible F-Droid release evidence.
- Recovery and rollback needs: keep encrypted backup staged-restore behavior covered, add export/import migration tests as schemas evolve, and make run-log diagnostics consistently redacted through `AppLogger`.

## Architecture Assessment
- `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt` is still about 3,201 lines and owns navigation, CRUD, import/export, editor state, permission prompts, and dispatch wiring. The untracked split files `ActionEditorDialogs.kt`, `ContextEditorDialogs.kt`, `EditorDialogs.kt`, and `ActiveAutomationViewModel.kt` indicate the correct boundary is already being extracted.
- `app/src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt` is about 1,225 lines and should be split after the active-automation screen, especially around editor state, rendering, and persistence helpers.
- Compose UI test coverage exists for run logs, but profile creation, task/action/context editors, onboarding permission rows, import/export failure states, scene creation, and destructive flows still need instrumentation tests.
- Unit coverage has grown around DAOs, encryption, WorkManager pruning, permission denial, and validation paths; the next useful test expansion is cross-boundary UI behavior and release metadata checks, not more generic model tests.
- Localization is structurally incomplete: `values-ar`, `values-de`, `values-es`, `values-fr`, `values-hi`, `values-it`, `values-ja`, `values-ko`, `values-pl`, `values-pt-rBR`, `values-ru`, `values-tr`, and `values-zh-rCN` contain empty `strings.xml` files, while UI sources still contain many hardcoded user-facing labels/messages.
- Dependency posture is modern: API 37 target/compile, AGP 9.2.1, Kotlin 2.3.21, Compose BOM 2026.05.00, Room 2.8.4, WorkManager 2.11.2, Navigation Compose 2.9.8, and kotlinx-serialization 1.11.0. Room 3 and Navigation3 are not immediate migrations because the app's higher-risk gaps are release correctness, logging, accessibility, and UI decomposition.

## Rejected Ideas
- Cloud-required automation backend: rejected because it conflicts with the app's local-first/F-Droid privacy posture; source class: Home Assistant, Node-RED, n8n comparison.
- Mandatory root, Shizuku, or ADB for core workflows: rejected because setup friction and store-policy risk would make the main app less reliable; source class: Tasker and AutoJs6 elevated-action ecosystems.
- Unbounded JavaScript or accessibility scripting: rejected because it creates high abuse, review, and user-trust risk; source class: AutoJs6/AutoInput-style automation and Google Play AccessibilityService policy.
- App Factory/APK generator in the next cycle: rejected because signing, permissions, generated-code policy, and support surface are too large before beta hardening; source class: Tasker App Factory.
- Room 3 migration now: rejected because current Room 2.8.4 is recent and the roadmap has higher-confidence release and UX gaps; source class: AndroidX Room release notes.
- Full Home Assistant `mobile_app` clone first: rejected as a first integration step because registration, auth, sensors, notifications, and lifecycle management are larger than an outbound webhook/MQTT bridge; source class: Home Assistant Android companion docs.
- Google Home or Matter direct control now: rejected because Play Services/vendor requirements and certification burden conflict with F-Droid portability; source class: Android/home automation platform research.
- Closed template marketplace: rejected because open JSON sharing and local import/export fit the project better; source class: MacroDroid community templates and FOSS distribution constraints.
- Calendar CRUD actions in this roadmap: rejected for this cycle because `WRITE_CALENDAR` is a sensitive permission expansion and release correctness, logging, accessibility, and editor reliability are higher-confidence beta blockers; source class: Tasker and Samsung Modes and Routines.
- API 37 `OnAlarmListener` scheduler rewrite now: rejected for this cycle because `app/src/main/java/com/opentasker/automation/scheduler/TimeEventScheduler.kt` already has exact/inexact fallback behavior and device evidence matters more than an API-specific simplification; source class: Android 17 platform docs.

## Sources
Direct competitors and adjacent products:
- https://tasker.joaoapps.com/
- https://tasker.joaoapps.com/userguide/en/
- https://www.macrodroid.com/
- https://llamalab.com/automate/
- https://github.com/renyuneyun/Easer
- https://github.com/henrichg/PhoneProfilesPlus
- https://server47.de/automation/
- https://github.com/SuperMonster003/AutoJs6
- https://github.com/home-assistant/android

Integrations and automation ecosystems:
- https://github.com/twofortyfouram/android-plugin-api-for-locale
- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/RikkaApps/Shizuku-API
- https://companion.home-assistant.io/docs/notifications/notification-commands/
- https://companion.home-assistant.io/docs/core/sensors/
- https://github.com/binwiederhier/ntfy
- https://github.com/UnifiedPush/android-connector
- https://github.com/hannesa2/paho.mqtt.android
- https://nodered.org/docs/
- https://github.com/n8n-io/n8n

Android platform, distribution, and tooling:
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/jetpack/androidx/releases/glance
- https://developer.android.com/jetpack/androidx/releases/navigation3
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://f-droid.org/en/docs/Reproducible_Builds/
- https://docs.gradle.org/current/userguide/dependency_verification.html
- https://hosted.weblate.org/

## Open Questions
- Which exact Android 17/API 37 device or emulator matrix should be treated as release evidence for local-network permission, foreground-service behavior, TalkBack, geofence durability, and notification restrictions?
- Should the first Home Assistant bridge be outbound webhooks, MQTT, or full `mobile_app` registration?
- Which Shizuku elevated actions are acceptable for Play/F-Droid-facing release claims, and which should remain advanced/off-by-default only?
