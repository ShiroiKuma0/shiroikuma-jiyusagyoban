# Research - OpenTasker

## Executive Summary
OpenTasker is a local-first Android automation app: a FOSS Tasker-style profile, context, task, action, scene, import/export, and plugin-host product built with Kotlin, Jetpack Compose, Room, WorkManager, and Material 3. Its strongest current shape is trust-centered Android automation: explicit unsupported-action failures, redacted diagnostics, signature-protected external intents, review-gated imports, F-Droid/Play metadata gates, optional Shizuku/Termux lanes, and a privacy-first no-cloud stance. Highest-value direction: make the v0.3 beta harder to break and easier to validate before expanding feature breadth. Priority opportunities are: 1) close Room schema and migration drift, 2) make Android 17 local-network permission handling cover every LAN socket action, 3) add Gradle dependency verification and dependency-update governance, 4) turn F-Droid reproducibility into a current release gate, 5) make Locale plugin compatibility repeatable with fixtures and a matrix, 6) split the largest Compose surface into workflow modules, and 7) keep existing roadmap work on target-SDK behavior, geofence evidence, macrobenchmarks, scenes, visual flow, Shizuku, Termux, i18n, accessibility, MQTT, UnifiedPush, and encrypted backup.

## Product Map
- Core workflows: create profiles from contexts, attach enter/exit tasks, configure built-in actions, use template expressions, inspect source health, review run logs, import/export OpenTasker JSON bundles, import Tasker XML, manage scenes, and trigger tasks through widgets, Quick Settings, shortcuts, NFC, external intents, and Locale-compatible plugins.
- User personas: privacy-first Android power users, Tasker migrants, F-Droid users, home-automation users, Android sysadmins, and advanced users who need optional elevated execution without making root/Shizuku mandatory.
- Platforms and distribution: Android API 26+, compile/target SDK 36, standard/F-Droid/Play Gradle distributions, GitHub release artifacts, draft F-Droid metadata, and Play policy validation tasks.
- Key integrations and data flows: Room stores profiles, tasks, contexts, scenes, variables, run logs, settings, and source state; WorkManager handles periodic cleanup; `AutomationService` owns runtime subscriptions and dispatch; SAF/document-picker flows protect imports and exports; Locale host APIs and a signature-protected receiver expose opt-in external automation.

## Competitive Landscape

### Tasker
- Does well: deep Android automation coverage, mature variables, Taskernet sharing, Locale plugin ecosystem, Shizuku support, scenes, and import/export culture.
- Learn: migration completeness, project organization, community sharing, and plugin compatibility expectations.
- Avoid: accreted complexity and unclear setup burden that make first-run trust harder.

### MacroDroid
- Does well: approachable trigger/action/constraint model, guided creation, templates, community sharing, privacy-adjacent triggers, and a clear freemium packaging story.
- Learn: setup guidance, template discoverability, and readable action creation.
- Avoid: artificial macro/action limits and proprietary portability.

### Automate
- Does well: visual flow authoring, searchable block catalog, explicit outputs, and a graph model that makes control flow inspectable.
- Learn: visual flow debugging and readable node documentation once OpenTasker's current list editor is stable.
- Avoid: forcing graph editing as the only mental model.

### Easer and Automation by Jens
- Do well: FOSS Android automation, F-Droid distribution, open community expectations, and a smaller but relevant set of triggers/actions.
- Learn: F-Droid-first release discipline and simple automation affordances.
- Avoid: older UI density and weak migration/plugin surfaces.

### Node-RED
- Does well: documented flows, reusable node ecosystem, inspectable wiring, and clear separation between editor/runtime concepts.
- Learn: flow documentation, importable examples, and testable node metadata.
- Avoid: server-required architecture that conflicts with OpenTasker's phone-local stance.

### Home Assistant Companion and openHAB Android
- Do well: phone-to-automation-server integration through sensors, notification commands, Tasker hooks, and explicit mobile permission guidance.
- Learn: user-visible diagnostics for background restrictions, location, and remote-trigger flows.
- Avoid: assuming a home server exists.

### Locale Plugin Ecosystem
- Does well: standard condition/setting/event contracts that many Android automation tools understand.
- Learn: compatibility matrices, fixture plugins, and opt-in boundaries for third-party automation.
- Avoid: broad plugin execution without review, audit, or signature/permission boundaries.

## Security, Privacy, and Reliability
- Verified: `.github/workflows/build.yml` and `.github/workflows/release.yml` already use full-SHA pinned actions and least-privilege permissions, so older research that called out tag-pinned workflows is stale.
- Verified: `gradle/verification-metadata.xml` is absent, while `gradle/libs.versions.toml` carries the active third-party dependency set. Gradle and Android build docs both support dependency verification as the next supply-chain control.
- Verified: `app/src/main/java/com/opentasker/core/storage/AppDatabase.kt` is at Room schema version 5 and the exported `app/schemas/com.opentasker.core.storage.AppDatabase/5.json` exists only as an untracked file in the working tree. `DatabaseMigrationInstrumentedTest.kt` covers selected migrations but not every adjacent path through version 5.
- Verified: `app/src/main/AndroidManifest.xml` declares `android.permission.ACCESS_LOCAL_NETWORK` and `PermissionOnboardingScreen.kt` exposes it, but `NetworkActions.kt` checks it only for HTTP/Download URL paths. `PingAction` and `WakeOnLanAction` are LAN-capable socket actions that do not use the same Android 17 guard.
- Verified: `AndroidManifest.xml` sets `android:allowBackup="false"` and external intent entry is protected by a signature permission; the trust boundary should stay same-signer unless a separate user-review flow is designed.
- Verified: `tools/verify-fdroid-release.ps1` and F-Droid metadata are current enough to gate version values, but `docs/FDROID_READINESS.md` still contains local build evidence from an older version. A fresh `fdroidserver` or documented blocker should be part of the release gate.
- Likely: Android 17 background audio and Android 16/17 behavior changes remain runtime-sensitive even with compile/target SDK 36; existing roadmap device evidence items should stay above new feature breadth.
- Needs live validation: local-network permission behavior, foreground-service denial behavior, geofence durability, and macrobenchmarks require API 35-37 devices/emulators; static inspection is not enough.

## Architecture Assessment
- `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt` is a broad multi-workflow file covering navigation, ViewModel state, profiles, tasks, run logs, imports, dialogs, and secondary surfaces. This raises regression risk for polish work; split by workflow before larger UI changes.
- `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt` has good HTTP URL and size guards, but LAN permission logic should be centralized so each socket-like action cannot drift from platform policy.
- `app/src/main/java/com/opentasker/core/storage/DatabaseMigrations.kt` documents the migration protocol, but CI needs to prove schema exports and adjacent migrations whenever `AppDatabase` changes.
- `docs/LOCALE_PLUGIN_HOST.md` and `tools/validate-locale-plugin.ps1` describe manual validation against an installed sample plugin. A synthetic fixture plugin and compatibility matrix would make plugin-host regressions testable.
- `app/src/main/java/com/opentasker/ui/DesignSystem.kt` exists, while many screens still own local spacing and copy. Existing roadmap items for DesignSystem token adoption and strings centralization are still valid; do not duplicate them.
- Test gaps that matter most before feature breadth: Room 1->5 and adjacent migration tests, Android 17 LAN guard tests, Locale plugin fixture tests, F-Droid release evidence, and UI smoke tests around split workflow modules.
- Documentation gaps that matter most: current F-Droid build evidence, platform-behavior evidence matrices, and plugin compatibility results.

## Rejected Ideas
- Cloud-required automation backend - rejected from Tasker/IFTTT-style parity pressure because it conflicts with OpenTasker's local-first README and existing roadmap philosophy.
- Mandatory root/Shizuku/ADB - rejected despite Tasker/Shizuku and AutoX-style elevated automation; elevated execution should remain opt-in and action-scoped.
- FBP-only visual editor - rejected despite Automate and Node-RED influence because OpenTasker's list/form editor is the lower-friction default.
- Proprietary marketplace or closed Taskernet clone - rejected despite Taskernet and MacroDroid community value; OpenTasker should keep open JSON bundles and reviewable sharing.
- Unbounded JavaScript/accessibility scripting - rejected despite AutoX-style power because it creates high security, accessibility-service, and review risk for a F-Droid-oriented app.
- Server-dependent home-automation model - rejected despite Home Assistant, openHAB, and Node-RED strengths; OpenTasker should integrate with servers without requiring one.
- Cloud crash analytics by default - rejected because local redacted diagnostics fit the privacy stance better.
- Standalone APK/App Factory export as near-term work - rejected for now because signing, policy, and code-generation surface area are much larger than the beta trust gaps.

## Sources

### Direct and OSS competitors
- https://tasker.joaoapps.com/
- https://tasker.joaoapps.com/userguide/en/variables.html
- https://www.macrodroid.com/
- https://llamalab.com/automate/
- https://llamalab.com/automate/doc/flow.html
- https://github.com/renyuneyun/Easer
- https://f-droid.org/packages/com.jens.automation2/
- https://server47.de/automation/
- https://github.com/henrichg/phoneprofilesplus
- https://github.com/automan-bot/AutoX/blob/dev-test/README_en.md

### Adjacent products and ecosystems
- https://www.twofortyfouram.com/developer
- https://github.com/twofortyfouram/android-plugin-api-for-locale
- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/RikkaApps/Shizuku-API
- https://www.openhab.org/docs/apps/android
- https://companion.home-assistant.io/docs/notifications/notification-commands/
- https://nodered.org/docs/developing-flows/documenting-flows
- https://flows.nodered.org/
- https://github.com/binwiederhier/ntfy/issues/31

### Standards, distribution, security, and dependencies
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://source.android.com/docs/security/features/private-space
- https://docs.gradle.org/current/userguide/dependency_verification.html
- https://developer.android.com/build/dependency-verification
- https://f-droid.org/en/docs/Reproducible_Builds/
- https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://developer.android.com/jetpack/androidx/releases/room

## Open Questions
- Which API 37 device or emulator will be used for local-network, background-audio, and foreground-service behavior validation?
- Should F-Droid readiness target upstream-signed reproducible evidence before submission, or source-build acceptance first?
- Which real Locale plugins should become compatibility fixtures beyond a synthetic test plugin?
