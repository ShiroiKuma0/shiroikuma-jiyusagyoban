# Research - OpenTasker

Updated: 2026-06-15 (stricter release-polish pass; replaces previous research content)

## Executive Summary

OpenTasker is a Kotlin/Compose Android automation app at source version 0.2.64 with a Room-backed profile -> context -> task -> action engine, 49 registered runtime actions, engine-handled flow control, FOSS LocationManager geofencing, Locale/Tasker plugin hosting, Tasker XML import, OpenTasker JSON transfer, Quick Settings/widget triggers, OEM battery guidance, and F-Droid/Play release checks. The repo's best trait is explicit failure behavior: unsupported privileged actions fail honestly, imports are review-gated, sensitive run-log data is redacted, destructive variable deletion is now confirmed, and prior high-risk guards remain in place.

The highest-value direction is trust and scale before more action breadth. Existing roadmap items already cover target-SDK readiness, Android 17 audio gating, work/private-profile app automation, TalkBack accessibility, profile/task organization, action implementation tests, WorkManager resilience, diagnostics, Tasker XML export, scoped LAN cleartext, date/time templates, camera/mic privacy triggers, Wake-on-LAN, and optional BYO-key AI authoring.

Recent continuation passes found three additional non-duplicate gaps; v0.2.62 closed the first one in code and tests, leaving the latter two as active roadmap work:

1. `http.post` response/download paths were bounded, but the request body was not size-capped and was written without `setFixedLengthStreamingMode`; v0.2.62 now caps request bodies at 1 MB, uses fixed-length streaming, and tests legacy `body` compatibility.
2. GitHub Actions workflows use tag-pinned actions and the build workflow has no explicit least-privilege `permissions`; the repo also lacks Gradle dependency verification metadata and Dependabot/Renovate config.
3. Release shrinker rules still keep removed Hilt-era classes while the manifest now uses `.OpenTaskerApp_NoHilt`, leaving release hardening stale after the no-Hilt migration.

## Product Map

- Core workflows: build profiles from contexts, attach enter/exit tasks, configure action/template arguments, inspect source health, review run logs, import/export bundles, and trigger tasks from widgets, Quick Settings, shortcuts, external intents, Locale/Tasker plugins, NFC, calendar, time, package, app-usage, notification, and location sources.
- User personas: privacy-first Android power users, Tasker migrants, F-Droid users, home-automation/sysadmin users, and BYOD users whose personal/work/private profiles can affect app automation correctness.
- Platforms and distribution: Android API 26+, compileSdk 36, targetSdk 36, GitHub APKs, draft F-Droid metadata, Play policy gates, and flavor-specific SMS/phone permissions.
- Data flows: Room stores profiles, tasks, scenes, variables, run logs, app settings, and source state; configs are JSON blobs; `AutomationService` owns context subscriptions and task dispatch; SAF/document-picker flows protect import/export.
- Key dependencies: Compose Material3, Room, WorkManager, kotlinx serialization plus Gson, AppCompat, Shizuku API, and Gradle/GitHub Actions release tooling.

## Competitive Landscape

### Tasker

- Does well: deepest Android automation ecosystem, Projects tabs, Taskernet sharing, Locale plugin ecosystem, Shizuku support, and AI generation that can emit importable profiles/tasks/widgets.
- Learn: project/task organization, search, BYO-key generation against the app's own export format, and mature round-trip import/export.
- Avoid: accreted UI complexity and setup burden.

### MacroDroid

- Does well: approachable trigger/action/constraint model, AI Macro Builder, large built-in library, community templates, and camera/mic-in-use privacy triggers.
- Learn: guided creation, privacy triggers through platform signals, and template/community UX.
- Avoid: proprietary/freemium limits and opaque portability.

### Automate

- Does well: mature visual flowchart model, large block catalog, app-usage statistics blocks, and explicit variable outputs.
- Learn: visual flow authoring and debugging once OpenTasker's list editor and runtime trust gaps are stable.
- Avoid: making graph editing the only mental model.

### vFlow

- Does well: fast-moving visual workflow editor, module registry, typed "magic variables", Shizuku/root worker separation, searchable workflow picker, AI agent flow, hotspot control, and app-switch modules.
- Learn: typed data-flow, searchable workflow selection, privilege-worker isolation, and Shizuku-gated elevated actions.
- Avoid: limited distribution surface and weaker Tasker/F-Droid/Locale interop.

### Automation by Jens

- Does well: active F-Droid/GPL Android automation app with location, NFC, notifications, calendar, Bluetooth, speech/audio, tethering, HTTP, and script actions.
- Learn: FOSS baseline breadth and translation/community expectations.
- Avoid: older UI and limited plugin/migration ecosystem.

## Security, Privacy, and Reliability

- Prior high-risk issues remain fixed: URL scheme allowlist, file read caps, response/download caps, notification ID overflow, screen-timeout unit conversion, explicit unsupported privileged actions, SMS Play gating, bounded template expansion, run-log redaction, and backup/restore staging.
- Target-SDK wording risk remains: live Google target-API pages checked on 2026-06-15 still publish the API 35 / August 31, 2025 requirement, not a public API 36 Play deadline. Target-36 code has shipped, but docs must not mark an unpublished deadline as verified.
- Android 17 audio risk remains: `MediaActions.kt` and TTS/sound code can run from background profiles while `AutomationService` uses `specialUse|location`; background audio hardening can fail playback or volume changes silently unless a valid foreground-service/while-in-use path exists.
- Work profile / Private Space risk remains: app-open contexts, package broadcasts, and app launch actions are package-name/current-profile based; no model currently records `UserHandle`, profile labels, hidden-profile state, or profile-local disclosure.
- Accessibility gap is narrowed but remains: action checkbox rows, profile/context form switches, widget task rows, and flow graph targets now expose better roles/state; numeric fields request numeric keyboards and small day-token controls meet 48 dp minimum height. A full TalkBack sweep and accessibility-checks instrumentation gate are still missing.
- Action test gap is narrowed but remains: `core/actions/ActionGuardsTest.kt` now covers key network, URL, wait, ping, and Wake-on-LAN guards, while file, media, settings, app, notification, and download edge cases still need coverage.
- Network hardening gap closed in v0.2.62: `HttpPostAction` caps request bodies at 1 MB, uses fixed-length streaming, and keeps legacy `body` compatibility under the same cap.
- New supply-chain gap: `.github/workflows/build.yml` and `release.yml` consume tag-pinned actions (`@v4`), the build workflow has no explicit `permissions`, `gradle/verification-metadata.xml` is absent, and no Dependabot config is present.
- New release-hardening gap: `app/proguard-rules.pro` still keeps `OpenTaskerApp`, `Hilt_OpenTaskerApp`, and a Dagger/Hilt type pattern while `AndroidManifest.xml` uses `.OpenTaskerApp_NoHilt`.

## Architecture Assessment

- `ActiveAutomationUi.kt` remains too broad: navigation, ViewModel state, import/export, dialogs, and screen composables are concentrated in one file. This blocks small, testable accessibility and saveable-state improvements.
- App/profile boundaries are implicit. App-open contexts, package events, app-launch actions, and plugin discovery are package-name based, so identical packages across personal/work/private profiles cannot be distinguished.
- Action registry and metadata parity is now covered by JVM tests, including engine-handled flow-control allowlisting and the dynamic form keys that previously drifted from runtime argument names.
- `TemplateExpressionEngine.kt` has safe string/math/array/JSON pipes but no bounded date/time formatting function, leaving a concrete Tasker migration gap.
- `TaskerXmlImport.kt` is parse-only. A writer for the mappable subset would support round-trip migration and reduce lock-in.
- WorkManager now backs periodic run-log pruning. Remaining architecture work is retention/service re-arm evidence plus worker-level tests.
- Release and CI hardening trails app architecture changes. The no-Hilt migration removed runtime architecture but left stale shrinker rules; CI still trusts mutable tags and unverified Gradle artifacts.

## Rejected Ideas

| Idea | Reason | Source |
|---|---|---|
| Treat API 36 Play deadline as verified today | Public Google pages checked on 2026-06-14 still state API 35 / August 31, 2025; target-36 remains platform-readiness work, not a verified public Play deadline. | Google Play target API docs |
| Normal-app hotspot/tether toggle | Privileged on modern Android; should remain Shizuku-gated and fail honestly without elevation. | vFlow parity research; Android privilege model |
| Clipboard-change as reliable background trigger | Android background clipboard restrictions make it unreliable outside foreground/IME contexts. | Android clipboard/privacy model; vFlow parity research |
| Cloud-required backend or mandatory AI | Conflicts with no-cloud/no-telemetry positioning. Any AI must be explicit, user-initiated, and BYO-key. | Project philosophy; F-Droid posture |
| Google Fit triggers | Fit APIs are sunset in favor of Health Connect. | Health Connect migration docs |
| Cloud crash reporting | Telemetry-hosted crash reporting conflicts with privacy posture; local user-initiated diagnostic export fits. | Project philosophy |

## Sources

### Competitors

- https://tasker.joaoapps.com/
- https://tasker.joaoapps.com/changes/changes6.5.html
- https://tasker.joaoapps.com/userguide/en/activity_main.html
- https://www.macrodroid.com/
- https://llamalab.com/automate/
- https://github.com/ChaoMixian/vFlow
- https://github.com/ChaoMixian/vFlow/releases
- https://f-droid.org/packages/com.jens.automation2/

### Community / Adjacent

- https://github.com/guifelix/awesome-tasker
- https://docs.ntfy.sh/integrations/
- https://github.com/ActivityWatch/aw-android/issues/135
- https://github.com/timschneeb/awesome-shizuku

### Android Platform / API

- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://support.google.com/googleplay/android-developer/answer/16926792
- https://developer.android.com/work/managed-profiles
- https://developer.android.com/reference/android/content/pm/LauncherApps
- https://developer.android.com/reference/android/app/usage/UsageStatsManager
- https://developer.android.com/reference/java/net/HttpURLConnection
- https://developer.android.com/privacy-and-security/local-network-permission

### Dependency / Security

- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/jetpack/androidx/releases/room3
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://github.com/advisories/GHSA-4jrv-ppp4-jm57
- https://docs.github.com/en/actions/reference/security/secure-use
- https://docs.github.com/en/actions/tutorials/authenticate-with-github_token
- https://developer.android.com/build/dependency-verification
- https://docs.gradle.org/current/userguide/dependency_verification.html

## Open Questions

1. Does OpenTasker's Play Console show any API 36 enforcement date that is not yet reflected in public Google docs?
2. On Android 17, do background TTS/sound/volume actions fail with `AudioHardening` full or partial denial when no location while-in-use grant is active?
3. Should OpenTasker support explicit work/private profile targeting via `LauncherApps`, or disclose profile-local limits until users install OpenTasker in each profile?
4. Should CI adopt full SHA pinning immediately for all actions, or pair it with Dependabot/Renovate first so pinned actions remain maintainable?
5. Should the stale Hilt shrinker keeps be removed immediately, or retained until release-build symbol inspection confirms no generated references remain?
