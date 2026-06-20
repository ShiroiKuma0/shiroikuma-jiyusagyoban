# Research -- OpenTasker

## Executive Summary

OpenTasker is a FOSS, local-first Android automation app (Kotlin/Compose/Room/WorkManager, MIT, API 26-36) with 49 actions, 7 context families, template expressions, Locale plugin hosting, Shizuku/Termux backends, encrypted backups, and F-Droid/Play distribution profiles. At v0.2.75 (~23k LOC, 72 unit tests, 4 instrumented tests), it is the most complete open-source Tasker alternative in active development.

**Strongest shape:** Trust-centered automation -- explicit failure modes, redacted diagnostics, sandboxed file actions, signature-protected external intents, bounded template evaluation, SHA-256 script verification, and a strict Shizuku command allowlist.

**Highest-value direction:** Harden for Android 17 (API 37, stable June 2026), expand test infrastructure, then pursue integrations (Home Assistant, MQTT, UnifiedPush) that differentiate from cloud-dependent competitors.

**Top 10 priorities:**
1. Android 17 target readiness (ACCESS_LOCAL_NETWORK runtime, background audio FGS+WIU, certificate transparency, orientation opt-out removal)
2. Compose UI test infrastructure (0 UI tests for 3347-line main screen file)
3. Centralize logging through AppLogger (17 android.util.Log calls bypass diagnostics)
4. FGS onTimeout() callback for Android 16 timeout enforcement
5. ProGuard/R8 rules hardening for Shizuku AIDL stubs and RE2J
6. Instrumented test expansion (4 tests / 498 LOC is thin for a 23k LOC app)
7. Weblate integration for community translations (13 empty locale skeletons)
8. Home Assistant webhook protocol integration
9. Compose stability optimization (kotlinx-collections-immutable)
10. Guided first-automation onboarding

## Product Map

- **Core workflows:** Create profiles from contexts, attach enter/exit tasks, configure 49 built-in actions + 7 flow controls, use bounded `{{ }}` template expressions, inspect live context source health, review step-level run logs, import/export OpenTasker JSON bundles, import Tasker XML, manage scene overlays, trigger tasks via widgets/QS tiles/shortcuts/NFC/external intents/Locale plugins.
- **User personas:** Privacy-first Android power users, Tasker migrants, F-Droid users, home-automation enthusiasts, Android sysadmins, advanced users wanting optional elevated execution.
- **Platforms:** Android API 26+, compile/target SDK 36. Standard/F-Droid/Play Gradle distributions. GitHub release APKs with SHA-256 checksums. Draft F-Droid metadata. Play manifest policy validation.
- **Key integrations:** Room persistence with StateFlow live queries, WorkManager periodic cleanup, AutomationService foreground dispatch, SAF document-picker imports/exports, Locale host protocol (setting dispatch + condition query + request-query events), signature-protected automation target receiver.

## Competitive Landscape

### Tasker (v6.6.20, Feb 2026, $4.99)
- **Does well:** Deepest Android automation (~400 actions). Full Shizuku integration (airplane, mobile data, screenshot, permissions). Java Code action for direct API access. AI-assisted creation (Gemini/OpenRouter). Widget v2 with JSON structure. App Factory standalone APK export. Taskernet sharing.
- **Learn from:** Shizuku action breadth (12+ elevated actions), calendar CRUD (7 actions), variable prefix for multiple sets, clipboard import UX, AI generator workflow.
- **Avoid:** Accreted complexity that makes first-run trust harder, steep learning curve (#1 complaint), VPN/accessibility service Play Store friction.

### MacroDroid (v5.63.15, May 2026, free/$2.99)
- **Does well:** Lowest barrier to entry. 350+ combined triggers/actions/constraints. AI Macro Builder. Template marketplace with thousands of community macros. Wear OS companion. Scene-based drawers.
- **Learn from:** Template-first discoverability, guided creation, AI builder UX, Wear OS integration, community marketplace.
- **Avoid:** 5-macro free tier limit, cloud-routed webhooks, less powerful structured data handling.

### Automate (v1.51.1, Apr 2026, free/premium IAP)
- **Does well:** Visual flowchart editor with 400+ blocks. No feature-gating on free tier (only block limit). Active community flow sharing. Bimonthly updates.
- **Learn from:** Flowchart debugging, searchable block catalog, community flow library.
- **Avoid:** Flowchart UI becomes unwieldy for complex automations, 30-block free limit.

### Samsung Modes & Routines (One UI 7-8, built-in)
- **Does well:** Zero-install, 32+ new actions in OU7, natural language routine creation, Routine Gallery community sharing, calendar/clock/notes CRUD actions, weather data triggers.
- **Learn from:** Calendar CRUD, natural language creation, zero-friction onboarding, weather data integration.
- **Avoid:** Samsung-only, no scripting, no HTTP requests, no plugin ecosystem.

### FOSS Competitors (Easer, PhoneProfiles Plus, Automation by Jens)
- **Easer** (v0.8.2.3, Apr 2022): Dormant. GPL-3.0, F-Droid. Boolean logic graph is conceptually elegant but minimal features, outdated UI. OpenTasker already exceeds this on every axis.
- **PhoneProfiles Plus** (v7.1.2, Sep 2025): Apache 2.0, IzzyOnDroid. Profile-centric (device settings only). 20+ events, 15+ actions. Active but Java-only, no Compose. Not a general-purpose automation tool.
- **Automation by Jens** (v1.8.7, Mar 2026): GPL-3, F-Droid native. Truly minimal (~12 triggers, ~6 actions). One-person project. Location-focused.

### Emerging: AI Agent Automation
- **Mythara:** Open-source local-first agentic AI for Android. 65+ on-device tools, Shizuku integration. Represents convergence of LLM agents and device automation. Not a direct competitor but signals where the space is headed.
- **AutoJs6** (v6.7.0, Mar 2026, 6k stars): Dominant Auto.js fork. JavaScript IDE, Shizuku support, plugin center. Primarily Chinese-language community. MPL-2.0.

### iOS Shortcuts (v26.4)
- Apple Intelligence integration (on-device AI actions), 100+ new actions in v26, macOS personal automations, cross-device continuity. Android lacks a first-party equivalent.
- Android's advantage: third-party apps (Tasker) are far more powerful, intent system enables deeper inter-app communication, accessibility service enables UI automation Shortcuts cannot do.

## Security, Privacy, and Reliability

- **Verified clean:** No TODO/FIXME/HACK markers in 208 source files. Zero CVEs for Room, kotlinx-serialization, RE2J, or Shizuku in 2024-2026.
- **Verified strong:** AES-256-GCM encrypted backups with PBKDF2 600k iterations. Sandboxed file actions confined to `user_files/` with canonical path traversal prevention. Shizuku command allowlist (6 pre-defined action sets only). Termux SHA-256 script hash verification + 1s frequency cap. CI SHA-pinned actions + least-privilege permissions. Gradle dependency verification with SHA-256 checksums.
- **Logging inconsistency:** `android.util.Log` used directly in 5 files (17 calls) alongside `AppLogger` (39 calls, 8 files). Diagnostic exports rely on AppLogger; direct Log calls bypass the redaction pipeline. Files: `AutomationService.kt`, `TimeEventReceiver.kt`, `ProfileDao.kt`, `TaskDao.kt`, `SceneDao.kt`.
- **ProGuard gap:** Current rules keep model classes, serialization, and manifest entry points only. Missing: Shizuku AIDL stubs (`dev.rikka.shizuku`), RE2J internals, Room generated code beyond entities. Release builds may strip required reflective access paths.
- **Locale fire receiver:** `LocaleSettingFireReceiver` is exported without a permission guard (expected for Locale protocol -- hosts must send FIRE_SETTING). The receiver validates intent action and forwards through signature-protected `AutomationTargetReceiver`, so the attack surface is bounded to triggering existing tasks by ID. No caller identity validation beyond the Locale protocol.
- **Network security config:** Domain-config entries (`10.0.0.0`, `172.16.0.0`, `192.168.0.0`) use hostname matching, which does not match IP address ranges. Cleartext traffic to LAN IP addresses relies on the base-config (blocked) unless the device resolves a hostname. In practice, HTTP actions to raw LAN IPs are blocked by the config, which is safe but may surprise users expecting LAN HTTP to work.
- **Android 17 exposure:** `ACCESS_LOCAL_NETWORK` is declared in manifest but enforcement changes in API 37 -- it becomes a runtime permission requiring user grant. Background audio restrictions require FGS with while-in-use capabilities for media actions. Certificate transparency on by default for apps targeting API 37.
- **FGS timeout:** Android 16 enforces 6-hour timeout for `dataSync`/`mediaProcessing` FGS types. `AutomationService` uses `specialUse|location` which may have different timeout behavior, but no `onTimeout()` callback is implemented as a safety net.

## Architecture Assessment

- **ActiveAutomationUi.kt (3347 lines):** Still the largest file after initial split. Contains navigation, ViewModel state, profile/task CRUD, action/context editors, dialogs, and import flows. Run-log and import-review dialogs were extracted (v0.2.73), but profile/task/action editors remain monolithic. High regression risk for UI changes.
- **Test pyramid imbalance:** 72 unit test files (6.8k LOC) vs 4 instrumented test files (498 LOC) vs 0 Compose UI tests. No `compose-ui-test-junit4` dependency. No `work-testing` dependency for WorkManager. Permission denial paths, DST edge cases, and process death restoration are untested.
- **Compose stability:** No `kotlinx-collections-immutable` dependency. StateFlow emissions with mutable `List<Profile>`, `List<Task>`, etc. cause unnecessary recompositions even with strong skipping mode. No Compose compiler metrics enabled in CI.
- **Logging inconsistency:** Mixed `android.util.Log` and `AppLogger` usage. `AutomationService.kt` alone has 10 direct Log calls that bypass diagnostic export redaction.
- **Design system adoption:** `DesignSystem.kt` defines spacing, radius, elevation, component size, and opacity tokens. v0.2.74 adopted tokens across 5 major screens. Remaining screens still use hardcoded values.
- **i18n coverage:** `strings.xml` has 170+ entries (241 lines). `ActiveAutomationUi.kt` has 277 occurrences of inline string literals or `stringResource()` calls -- many strings are still hardcoded. 13 locale skeletons exist but are completely empty.
- **Widget implementation:** Uses XML-based `widget_task.xml` layout. Glance (Compose-based widgets with unit testing) is available at v1.2.0-rc01 but not adopted.
- **Navigation:** Uses Navigation Compose 2.9.8 (Nav2). Navigation3 (`androidx.navigation3` v1.2.0-alpha03) is the Compose-first successor with type-safe metadata DSL and scene strategies.

## Rejected Ideas

| Idea | Source | Reason |
|---|---|---|
| Cloud-required automation backend | Tasker/IFTTT parity pressure | Violates on-device/privacy-first positioning |
| Mandatory root/Shizuku/ADB | Tasker/AutoX elevated automation | Elevated execution must remain opt-in and action-scoped |
| FBP-only visual editor | Automate flowchart model | List/form editor is the lower-friction default |
| Proprietary marketplace / closed Taskernet clone | Taskernet, MacroDroid community | Open JSON bundles and reviewable sharing are core values |
| Unbounded JavaScript/accessibility scripting | AutoX/AutoJs6 power model | High security, accessibility-service, and review risk for F-Droid |
| Server-dependent home-automation model | HA/openHAB/Node-RED architecture | OpenTasker integrates with servers without requiring one |
| Cloud crash analytics | Firebase Crashlytics pattern | Local redacted diagnostics fit privacy stance |
| Standalone APK/App Factory export | Tasker App Factory differentiator | Signing/policy/code-generation surface is too large for beta phase |
| Google Home APIs as primary integration | Google Home SDK v1.9.0 | Requires Google Play Services v26.20.31+ -- hard blocker for F-Droid/degoogled |
| Room 3.0 migration now | Room 3.0 KMP rewrite | Breaking migration from Room 2.x; current 2.8.4 is functional. Evaluate after v1.0 |
| GPL plugin SDK | Plugin ecosystem licensing | Would reduce adoption; MIT is better for ecosystem growth |
| Silent background automation | Competitor patterns | Unsafe, policy-hostile, and untrustworthy |
| Full Matter SDK embedding | connectedhomeip Apache 2.0 | Build complexity enormous; better to integrate via HA's Matter controller |

## Sources

### Direct competitors
- https://tasker.joaoapps.com/
- https://tasker.joaoapps.com/userguide/en/variables.html
- https://www.macrodroid.com/
- https://llamalab.com/automate/
- https://github.com/renyuneyun/Easer
- https://github.com/henrichg/phoneprofilesplus
- https://server47.de/automation/
- https://github.com/SuperMonster003/AutoJs6

### Adjacent products and ecosystems
- https://github.com/twofortyfouram/android-plugin-api-for-locale
- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/RikkaApps/Shizuku-API
- https://companion.home-assistant.io/docs/notifications/notification-commands/
- https://nodered.org/docs/developing-flows/documenting-flows
- https://github.com/binwiederhier/ntfy
- https://github.com/UnifiedPush/android-connector
- https://github.com/hannesa2/paho.mqtt.android

### Android platform and standards
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/jetpack/androidx/releases/navigation3
- https://developer.android.com/jetpack/androidx/releases/glance

### Distribution and security
- https://f-droid.org/en/2025/05/21/making-reproducible-builds-visible.html
- https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://keepandroidopen.org/en/
- https://docs.gradle.org/current/userguide/dependency_verification.html

### Community and UX
- https://hosted.weblate.org/
- https://github.com/nickalcala/compose-stable-marker
- https://developer.android.com/topic/performance/baselineprofiles/overview

## Open Questions

- Which API 37 device or emulator will be used for ACCESS_LOCAL_NETWORK runtime behavior, background audio restrictions, and orientation enforcement validation?
- Should the HTTP webhook receiver (Ktor CIO) use `connectedDevice` or `specialUse` FGS type, and does it need a separate service from AutomationService?
- Is hannesa2/paho.mqtt.android v4.x WorkManager-based delivery sufficient for MQTT QoS 1+ message guarantees, or does OpenTasker need its own MQTT foreground service?
