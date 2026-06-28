# Research - OpenTasker

## Executive Summary
OpenTasker is a local-first Android automation app for profiles, contexts, tasks, actions, scenes, flows, widgets, Locale plugins, Shizuku/Termux power paths, Tasker migration, encrypted backup, and diagnostic run logs. Its strongest current shape is now broader than the previous research pass: recent commits landed API 37 targeting, logging boundary guards, F-Droid metadata sync, accessibility source gates, string-resource extraction for core screens, critical Compose UI tests, Shizuku/Termux execution baselines, scene/flow authoring, and release truth tests. The highest-value direction is beta trust hardening: fix the public backup failure, refresh stale F-Droid build evidence, validate Android 17 local-network and background-service behavior on devices, capture real TalkBack/large-screen evidence, complete setup/backups localization, split the scene editor, and then expand migration, sharing, and local automation bridges.

Top opportunities:
- P0: fix GitHub issue #3, where Create Local Backup reports missing `profiles`, `tasks`, and `run_logs` despite existing data.
- P1: refresh F-Droid lint/build/reproducibility evidence; `docs/FDROID_READINESS.md` still cites `0.2.70` while Gradle and metadata are `0.2.75`/`77`.
- P1: run an API 37 device matrix for `ACCESS_LOCAL_NETWORK`, foreground-service timeout behavior, overlay scenes, Bluetooth, background location, notifications, and predictive back.
- P1: convert setup/backup visible copy to resources and seed at least one real locale path beyond empty Weblate skeleton files.
- P1: add rendered TalkBack/font-scale/large-screen evidence for setup, editors, scene canvas, flow graph, import review, and run-log diagnostics.
- P1: split `SceneLibraryScreen.kt` after the active-automation split; it is now the largest UI file at 1,271 lines.
- P1: harden Termux script allowlist/hash UX and bounded stdout/stderr/exit-code capture before promoting script output workflows.
- P1: add device-backed Shizuku action evidence and per-action risk copy before broad elevated-action release claims.
- P2: expand Tasker XML import/export mappings and migration reports for the next safest action/context families.
- P2: add a local community-share preview/import review path before any remote marketplace.

## Product Map
- Core workflows: create profiles from contexts, attach task/action sequences, run them through `AutomationService`, inspect failures in run logs, and keep guarded features behind Setup.
- Core workflows: author scenes and visual flows, import/export OpenTasker bundles and Tasker XML, share profile manifests, run widgets/shortcuts/QS tiles, and back up/restore local data.
- User personas: privacy-focused F-Droid users, Tasker migrants, Android power users, home-automation users, degoogled-device users, and users who need explicit control over Shizuku, Termux, SMS, notifications, local network, overlays, and location.
- Platforms and distribution: Android API 26+, `compileSdk`/`targetSdk` 37 in `app/build.gradle.kts`, Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, Compose BOM 2026.05.00, Room 2.8.4, WorkManager 2.11.2, standard/F-Droid/Play profiles, MIT license.
- Key integrations and data flows: Room + DataStore feed Compose StateFlow screens; `AutomationService` executes tasks; `TaskRunner` expands variables/templates and writes run logs; Locale, Shizuku, Termux, SAF, widgets, QS tiles, NFC, broadcasts, local-network actions, JSON bundles, Tasker XML, encrypted backups, and diagnostics cross trust boundaries.

## Competitive Landscape
- Tasker: still sets the ceiling for action breadth, scenes, variables, plugins, App Factory, and migration expectations. OpenTasker should learn from its plugin/migration depth, but avoid proprietary lock-in, opaque complexity, and store-policy fragility.
- MacroDroid: wins on beginner UX, trigger-action-constraint mental model, community templates, and overlay/custom UI discovery. OpenTasker should learn from template-first creation and share browsing, but avoid free-tier limits and cloud-dependent sharing.
- Automate: wins on visual flowcharts, 400+ blocks, app integration, HTTP/webhooks, and community flows. OpenTasker should learn from graph debugging and reusable examples, but keep form/list editing as the stable authoring path.
- Samsung Modes and Routines: wins on recommended routines, quick panel access, widgets, device-integrated permissions, and low-friction suggestions. OpenTasker should learn from guided mode/routine setup, but avoid Samsung-only assumptions.
- Easer and PhoneProfilesPlus: show long-lived FOSS lessons around profile modes, event/state condition models, and Android background survival. OpenTasker should learn from explicit profile semantics, but avoid dormant UX and narrow action surfaces.
- Auto.js/AutoJs6 and newer Android phone-control frameworks: prove demand for scripting, accessibility, OCR, and device-control automation. OpenTasker should keep deterministic rule-based automation, explicit permission gates, and bounded script bridges while rejecting arbitrary UI-control scripting.
- Home Assistant, Node-RED, n8n, ntfy, UnifiedPush, and MQTT clients: show strong demand for local protocol bridges, webhooks, event buses, and push-trigger paths. OpenTasker should add narrow local bridges first, not a server-required architecture.

## Security, Privacy, and Reliability
- Verified bug: GitHub issue #3 reports Create Local Backup failing with `Backup is missing required table(s): profiles, tasks, run_logs`; the relevant validation path is `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt`.
- Verified risk: `DatabaseBackupManager.backup()` copies only `opentasker.db` after a WAL checkpoint; issue #3 suggests either the live DB file is not initialized/closed as expected, validation is checking the wrong file/name, or the backup path is racing app startup. Add a regression that reproduces Create Local Backup through `ActiveAutomationViewModel`.
- Verified drift: `docs/FDROID_READINESS.md` still says local metadata tracks `0.2.70`/`72`, while `fdroid/metadata/com.opentasker.app.yml` and `app/build.gradle.kts` are `0.2.75`/`77`.
- Verified risk: Android 17 local-network docs make `ACCESS_LOCAL_NETWORK` mandatory for target SDK 37 local TCP/UDP traffic; OpenTasker declares and checks it for network actions, but needs device evidence for HTTP, Ping, WoL, future MQTT, denial, revocation, and permission re-request flows.
- Verified risk: source-level accessibility gates exist, but there is no rendered TalkBack/font-scale/large-screen evidence for the densest screens.
- Verified risk: locale skeleton directories exist, but every `values-*` `strings.xml` file is only 6 lines/278 bytes; Setup/backup copy in `PermissionOnboardingScreen.kt` still has hardcoded visible strings.
- Verified risk: Termux dispatch exists, but docs state script hash checks are not user-facing and stdout/stderr capture is not a complete output-ingestion pipeline.
- Verified risk: Shizuku command execution is allowlisted and kill-switch gated, but docs still require device evidence and user-facing risk copy for each elevated action variant.
- Missing guardrails: repeatable Locale fixture app, rendered accessibility evidence, API 37 device evidence, F-Droid local build evidence, scene overlay device evidence, and backup create/export/import end-to-end issue regression.
- Recovery and rollback needs: backup should create a known-good rollback, failed restores should preserve the failed payload for diagnostics, and run-log/diagnostic exports should surface backup failure cause without leaking data.

## Architecture Assessment
- `app/src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt` is 1,271 lines and owns scene list, element editing, canvas projection, dragging/resizing, multi-select, alignment guides, validation, and overlay actions; split editor state, canvas controls, cards, and dialogs next.
- `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt` is 790 lines and mixes permission inventory, backup UX, theme selection, OEM settings routing, and visible copy; backup/setup localization and test seams should be separated.
- `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt` is 360 lines and includes backup creation, export, import, staging, pending restore apply, validation, and bounded copy helpers; issue #3 makes this boundary a near-term reliability focus.
- `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt` is 691 lines; action breadth is useful, but future MQTT/Home Assistant/Tasker mappings should preserve registry/runtime parity tests.
- `app/src/main/java/com/opentasker/core/expressions/TemplateExpressionEngine.kt` is 630 lines and already has strong fail-closed policy; do not expand into arbitrary code execution.
- Compose instrumentation now covers critical flows, but not rendered TalkBack traversal, 200% font scale, split-screen/large-screen layouts, scene overlay launch, graph editing on real devices, or backup issue #3.
- `docs/LOCALE_PLUGIN_HOST.md`, `docs/TERMUX_SCRIPTING.md`, `docs/SHIZUKU.md`, `docs/SCENES.md`, and `docs/VISUAL_FLOW.md` contain concrete "Next Work" that should feed implementation without new research.

## Rejected Ideas
- Cloud-required automation backend: rejected because it conflicts with local-first/F-Droid privacy; source: Home Assistant, Node-RED, n8n comparison.
- Full Home Assistant `mobile_app` clone first: rejected because registration, auth, sensors, notifications, and lifecycle scope are too large before a webhook/MQTT bridge; source: Home Assistant companion docs.
- Unbounded JavaScript, arbitrary shell, or accessibility scripting: rejected because it creates high abuse, policy, and trust risk; source: Auto.js/AutoJs6 and Google Play AccessibilityService policy.
- AccessibilityService for app detection: rejected because OpenTasker already uses UsageStats and the policy history is fragile; source: Tasker accessibility-policy discussions.
- App Factory/APK generator now: rejected because generated signing, permission review, support burden, and policy complexity are larger than beta hardening; source: Tasker feature comparison.
- Closed template marketplace: rejected because offline bundle manifests and local import review better fit F-Droid and privacy; source: MacroDroid/Automate community feature comparison.
- Room 3 migration now: rejected because Room 2.8.4 is current enough and backup/device-evidence risks are higher confidence; source: AndroidX release research and current `gradle/libs.versions.toml`.
- Navigation3 migration now: already tracked as a P3 evaluation; do not migrate while editor state and graph/scene tests are still being hardened.
- Glance widget rewrite now: already tracked as a P3 evaluation; RemoteViews widgets should stay until capability/test tradeoffs are measured.
- Wear OS companion now: rejected for this research pass because phone reliability, F-Droid readiness, backup trust, and local bridges are stronger near-term fit.

## Sources
Project evidence:
- https://github.com/SysAdminDoc/OpenTasker
- https://github.com/SysAdminDoc/OpenTasker/issues/3

Direct competitors and adjacent products:
- https://tasker.joaoapps.com/
- https://tasker.joaoapps.com/userguide/en/
- https://www.macrodroid.com/
- https://llamalab.com/automate/
- https://www.samsung.com/us/support/answer/ANS10002538/
- https://github.com/renyuneyun/Easer
- https://github.com/henrichg/PhoneProfilesPlus
- https://server47.de/automation/
- https://github.com/SuperMonster003/AutoJs6

Local automation and integration ecosystems:
- https://github.com/twofortyfouram/android-plugin-api-for-locale
- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/RikkaApps/Shizuku-API
- https://github.com/home-assistant/android
- https://companion.home-assistant.io/docs/core/sensors/
- https://github.com/binwiederhier/ntfy
- https://github.com/UnifiedPush/android-connector
- https://github.com/hannesa2/paho.mqtt.android
- https://nodered.org/docs/
- https://github.com/n8n-io/n8n

Android platform, policy, distribution, and tooling:
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://f-droid.org/en/docs/Reproducible_Builds/
- https://docs.gradle.org/current/userguide/dependency_verification.html
- https://hosted.weblate.org/

## Open Questions
- Which physical API 37 devices/emulators should count as release evidence for local network, foreground services, overlay scenes, geofence durability, TalkBack, notifications, and Bluetooth?
- Should the first Home Assistant bridge be outbound webhook, MQTT publish, or a constrained `mobile_app` subset?
- Which Shizuku commands are acceptable for public release claims after device evidence, and which stay advanced/off-by-default only?
