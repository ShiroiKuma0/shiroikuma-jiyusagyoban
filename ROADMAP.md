# OpenTasker Roadmap

Source-backed product roadmap for OpenTasker v0.2.59 → v0.3.x. Reconciles current repo state with competitive research across Android automation apps, adjacent workflow engines, platform constraints (API 35–36), distribution policy, and dependency changelogs.

**Last updated:** 2026-06-06
**Roadmap version:** 2026.06.06 autonomous continuation pass (post-v0.2.59 platform and UX reconciliation)
**Current app version:** 0.2.59
**Planning rule:** items marked "Now" must ship before the v0.3.0 public beta claim. Items already completed in 0.2.x are retained in the **Completed Backlog** appendix for traceability and explicitly removed from active tiers.

## Reconciliation note (2026-05-06 pass)

The 2026-05-05 roadmap's `Now (N1–N10)` tier and most of `Next (X1–X12)` shipped across v0.2.2–v0.2.59. They are moved to **Completed Backlog** below and replaced with a fresh `Now` tier targeting v0.3.0 beta criteria. New tier work added from this pass: Android 16/API 36 foreground-service readiness, Quick Settings Tile trigger/action, predictive-back UX, Macrobenchmark/Baseline Profiles, Health Connect polling trigger (wellness), and broader background-geofence reliability evidence. Sources extended through S48; the June 2026 continuation note below corrects and supersedes the original `specialUse` timeout wording.

## Reconciliation note (2026-06-06 autonomous pass)

The June continuation pass verified the current repo after commit `c2412ad` and corrected the platform framing for N1. Current Android documentation says the six-hour foreground-service timeout applies to `dataSync` and `mediaProcessing` foreground-service types, while OpenTasker's active `AutomationService` declares `specialUse|location`; `specialUse` remains a Play declaration and review risk, not the timeout type itself. This pass keeps N1 as a beta gate but reframes it around target-SDK-36 readiness, FGS boot/background start behavior, Play `specialUse` evidence, and guardrails for any future `dataSync` or `mediaProcessing` work. It also deepens N3 Quick Settings Tile requirements from Tasker, MacroDroid, Automate, Home Assistant, and Android's TileService guidance; adds N10 for target-SDK-36 adaptive layout and intent-redirection hardening; and records a continuation state for the next autonomous cycle. [L8][L9][S49][S50][S51][S52][S53][S54][S55][S56][S57][S61]

---

## State of the Repo

OpenTasker is an Android/Kotlin automation app targeting API 35 with Jetpack Compose, Material 3, Room, Coroutines, WorkManager, DataStore, Gson, and Hilt dependencies. The project goal is a privacy-first, fully on-device, open-source Tasker/MacroDroid/Automate alternative.

The active APK now has a Room-backed Compose management UI for profiles, tasks, action lists, context lists, scenes with text/button/slider/image element editing, scaled canvas previews, drag-to-move layout edits, and tap/long-press task binding pickers, run logs, setup/onboarding status, flow graphs with node deep links into existing editors, conditional action labels, scrollable lane overviews, screen-reader summaries, and picker-backed Add Context/Add Step shortcuts, live context inspection with geofence dwell detail, notification listener event triggers, NFC tag event triggers and a write helper, calendar/sun event triggers with editor presets and adb smoke evidence, day schedule aliases/ranges/presets, a foreground automation service that starts from app launch and boot, a FOSS platform location source with balanced provider cadence and policy-aware setup copy, FOSS geofence radius/accuracy/dwell evaluation with persisted inside-since state and stale dwell-key cleanup, an installable disabled Location evidence template, an adb-backed Location/geofence evidence harness with Room/run-log/logcat assertions plus provider-cadence, unplugged-sample, and post-reconnect unplugged-history gates, API 36 smoke evidence for background Location event delivery, bounded runtime template argument and condition expansion, per-expression template run-log diagnostics, explicit regex template policy, a Tasker XML-to-OpenTasker-bundle migration parser, Locale setting dispatch and condition query actions with explicit receiver targeting, best-effort last-known unknown-state handling, guarded configuration result parsing, request-query event handling, and an adb plugin validation harness, OpenTasker bundle share manifests, a F-Droid readiness build profile with metadata/version gates, historical fdroidserver lint/build evidence that now needs a v0.2.59 refresh, APK payload comparison tooling, centralized Gradle dependency version governance, optional Shizuku readiness status for elevated-action planning, and optional Termux script readiness status for future scripting. Older `.kt.bak` editor snapshots are still not compiled, and advanced capability gating remains required before public beta claims.

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
7. Quick Settings should be treated as a first-class trigger/action surface, not a minor shortcut. Android recommends tiles only for frequent, fast-access actions and warns that users must manually add them; Tasker, MacroDroid, Automate, and Home Assistant all expose tile configuration, state, labels, icons, subtitles, and/or long-press behavior, which sets user expectations for OpenTasker. [S54][S55][S56][S57][S61]
8. The current platform compliance risk is broader than Android 15 foreground-service timeouts: Android 16 target behavior affects large-screen resizability, predictive back in 3-button navigation, intent redirection hardening, granular health permissions, and fixed-rate scheduling behavior. OpenTasker already compiles SDK 36 but still targets SDK 35, so target-SDK-36 rehearsal should be explicit before any beta claim. [L8][S52][S53]
9. Health Connect is now more plausible than the May roadmap implied, but it must be designed as an opt-in polling/sync integration with granular permissions, background-read permission handling, history limits, source attribution, and a settings toggle. It should not be described as a real-time trigger unless Android adds an event delivery contract. [S58][S59]
10. UI maintainability is now a product risk, not just code hygiene: `ActiveAutomationUi.kt` is 2,891 lines, `SceneLibraryScreen.kt` is 927 lines, and `strings.xml` still contains only five strings while most Compose UI copy remains inline. This supports keeping P2/P12/N9 active and adding testable split/i18n acceptance criteria. [L9]

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

## Now (v0.3.0 beta gate)

### N1 (2026.06.06) - Target SDK 36 / foreground-service and platform readiness pass

**Status:** 2026-06-06 correction. Compile SDK is already 36 and API-36 smoke evidence exists, but the app still targets SDK 35. The active manifest declares `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_LOCATION`, and `android:foregroundServiceType="specialUse|location"` for `AutomationService`; it does not currently declare `dataSync` or `mediaProcessing`. Android's current six-hour timeout documentation applies to `dataSync` and `mediaProcessing`, not to `specialUse`; `specialUse` remains a Play Console declaration/review risk and a boot/background-start behavior risk. [L8][S49][S50][S51]
**Description:** Add a target-SDK-36 readiness pass that (a) audits all FGS callsites from `MainActivity` and `BootReceiver`, (b) documents why the automation engine needs `specialUse|location` and prepares Play declaration/demo evidence, (c) adds regression evidence for boot-start, app-launch start, background-start denial behavior, notification visibility, and location-prerequisite handling, (d) adds `Service.onTimeout()` handling and adb timeout tests only if a future slice introduces `dataSync` or `mediaProcessing`, (e) rehearses Android 16 target behavior around adaptive large-screen layouts, predictive back in 3-button navigation, intent redirection hardening, fixed-rate scheduling, and health permissions, and (f) updates the Setup/Inspector UI so users can see when platform constraints block a profile family.
**Sources:** foreground-service timeout docs [S49], Android 15 FGS type changes [S50], Play FGS declaration requirements [S51], Android 16 target behavior [S52], Android 16 all-app behavior [S53], local FGS startup audit [L8][L12], existing Location evidence harness [L6].
**Category:** platform/OS, reliability, distribution, observability.
**Impact:** 5 — without this, the next Android release cycle silently breaks long-running automations and risks Play attestation rejection.
**Effort:** 4 — touches AutomationService lifecycle, capability registry, evidence harness, and docs.
**Risk:** Medium — self-restart must avoid loops; tests must cover both foreground and background start paths.
**Dependencies:** Capability registry (done v0.2.8), evidence harness (done v0.2.43–v0.2.48).
**Novelty:** Parity with platform requirements.
**Tier reason:** Platform compliance is the single highest-impact correctness item before any beta claim.

### N2 (2026.05.06) - Background geofence durability evidence across devices/providers

**Status:** Single-device API-36 evidence captured (v0.2.46–v0.2.48). No multi-device, multi-provider, or multi-OEM data; no Doze/standby-bucket transition evidence; no post-reboot persisted-dwell verification.
**Description:** Extend `tools/collect-location-evidence.ps1` and the API-36 smoke runs to cover (a) at least three devices spanning Samsung One UI / Pixel stock / a third OEM with documented battery-killer behavior, (b) GPS-only, network-only, and combined provider modes, (c) Doze deep-idle and rare-bucket entries with `dumpsys deviceidle` forced transitions, (d) post-reboot persisted-dwell restoration, and (e) OEM battery-killer audit (One UI Sleeping/Deep Sleep, MIUI MIUI Optimization, ColorOS, Realme). Publish a per-device evidence matrix in `docs/FOSS_GEOFENCING.md`.
**Sources:** Don't Kill My App [S28], OEM Doze override behavior 2025 [S43], Easer reliability complaints [S1][S48], existing harness [L6].
**Category:** reliability, platform/OS, observability.
**Impact:** 5 — current FOSS geofence claim is a single-device data point; broad reliability is the single most-requested missing feature in OSS Tasker alternatives.
**Effort:** 5 — requires physical-device variety, time, and OEM-by-OEM debug.
**Risk:** High — some OEMs cannot be reliably tamed without root/Shizuku; results may force a "best-effort" disclosure.
**Dependencies:** Existing FOSS geofence baseline; capability registry; setup checklist OEM hints.
**Novelty:** Leapfrog — OSS competitors do not publish this evidence at all.
**Tier reason:** The product positions itself as a privacy-first FOSS automation engine; without device-verified geofence reliability the headline claim is hollow.

### N5 (2026.05.06) - Macrobenchmark + Baseline Profile for cold start

**Status:** Not started. No benchmark module, no baseline profile shipped.
**Description:** Add an `app:benchmark` module using `androidx.benchmark.macro` to measure cold start, AutomationService start-to-context-subscription latency, and Profiles tab time-to-first-frame. Generate and ship a Baseline Profile in the release artifact. Wire CI to fail on cold-start regressions beyond a published threshold.
**Sources:** Macrobenchmark + Baseline Profiles [S47], Compose-Kotlin compatibility map [S41].
**Category:** performance, dev-experience, testing.
**Impact:** 3 — measurable startup win on cold launch and after install.
**Effort:** 3.
**Risk:** Low — benchmark instability on shared CI runners is the main risk; mitigated by running on connected device only.
**Dependencies:** Compose/Kotlin upgrade batch already complete (v0.2.59).
**Novelty:** Leapfrog — OSS competitors do not ship baseline profiles.
**Tier reason:** Hardens the upgrade work just landed and creates a regression gate for future upgrades.

### N6 (2026.05.06) - Sharing preview UI for community bundles

**Status:** Manifest baseline shipped in v0.2.28; no in-app preview screen for incoming community bundles.
**Description:** Build the import-side review experience promised by L5: dedicated bundle preview screen showing required permissions, action capability warnings, variable scope impact, scene/overlay implications, source attribution, and a clear disabled-by-default install button. Reuse the existing migration-warning component from the Tasker XML import flow.
**Sources:** Community sharing risks [S5][S10][S16][S17], Tasker XML review flow [L1].
**Category:** UX, security, plugin ecosystem.
**Impact:** 4 — the missing piece between "we have an export format" and "users can safely share".
**Effort:** 3.
**Risk:** Medium — preview must not silently strip warnings.
**Dependencies:** Open JSON bundle (done v0.2.13), Tasker import review (done v0.2.58).
**Novelty:** Parity.
**Tier reason:** Sharing is a stated v0.3 goal; preview UI is the long pole.

### N7 (2026.05.06) - Locale Condition Context UX

**Status:** Engine baseline shipped in v0.2.54 (host condition queries, request-query handling, last-known fallback). No first-class Condition context row exists in the Context picker; users must wire conditions through Locale plugin actions instead of as pure context predicates.
**Description:** Add a `condition=plugin` Context row that lets users pick a Locale condition plugin, configure it via the plugin's edit activity, and have ProfileMatcher subscribe through the existing host. Surface query results, last-known state, and unknown-state fallback in the Context Inspector.
**Sources:** Locale SDK [S7], Tasker plugin sample [S8], local plugin host [L1].
**Category:** plugin ecosystem, UX, observability.
**Impact:** 4.
**Effort:** 3.
**Risk:** Low/Medium — contract already proven by validation harness.
**Dependencies:** Capability registry; Inspector.
**Novelty:** Parity.
**Tier reason:** Closes the asymmetry between setting plugins (usable) and condition plugins (engine-only).

### N9 (2026.05.06) - Documentation truth pass post-v0.2.59

**Status:** Stale older snapshots removed in v0.2.11; current README still has a single 1700-character status sentence that is hard to skim. AC-2026-06-06-7 also found stale README Kotlin/Android Studio wording, CLAUDE.md v0.2.58/Gradle/workflow references, and F-Droid readiness evidence still describing v0.2.58/code 60 and Build Tools 35.0.0 while the app is v0.2.59/code 61 on Build Tools 36.0.0. [L14]
**Description:** Refresh README, CLAUDE.md, ARCHITECTURE.md, IMPROVEMENT_PLAN.md, and FDROID_READINESS.md to reflect v0.2.59. Restructure the README opening from one mega-sentence into a scannable feature list. Sync version strings, toolchain versions, workflow filenames, fdroid metadata, Gradle properties, ROADMAP, and CHANGELOG.
**Sources:** Local repo state [L1][L5], existing improvement plan [L7], dependency/release audit [L14].
**Category:** docs, dev-experience.
**Impact:** 3.
**Effort:** 2.
**Risk:** Low.
**Dependencies:** None.
**Novelty:** Maintenance.
**Tier reason:** Truthful, skimmable docs are part of the v0.3 beta gate.

### N10 (2026.06.06) - Target-SDK-36 adaptive layout, predictive back, and intent hardening rehearsal

**Status:** Not started as a named gate. The app compiles SDK 36 and includes a monochrome launcher icon layer, but target SDK remains 35; no explicit large-screen/adaptive-layout test matrix, target-36 compat-flag run, or intent-redirection audit is tracked. [L8]
**Description:** Before raising `targetSdk` to 36, run an Android 16 rehearsal that covers (a) large screens and desktop/windowing where orientation and aspect-ratio restrictions are ignored at `sw600dp`, (b) predictive back behavior in gesture and 3-button modes, (c) intent-redirection hardening for imported Tasker/Locale/external intents and any nested intents, (d) fixed-rate scheduling semantics for any polling loops, (e) Bluetooth bond-loss event handling if Bluetooth context work expands, and (f) Health Connect granular permission implications if X5 moves forward. Record screenshots/evidence for phone, foldable/tablet-width emulator, and desktop/freeform if available.
**Sources:** Android 16 target behavior changes [S52], Android 16 all-app behavior and predictive-back 3-button support [S53], current manifest/build config [L8], incoming-intent audit [L12], UI line-count and inline-copy audit [L9].
**Category:** platform/OS, UX, accessibility, security, testing.
**Impact:** 4 - prevents target-SDK upgrade regressions in navigation, layouts, and intent safety.
**Effort:** 3 - mostly test matrix, manifest/audit work, and targeted UI fixes.
**Risk:** Medium - large-screen bugs may expose deeper Compose layout assumptions in the large active UI files.
**Dependencies:** N4 predictive back; P2 UI split; N1 platform evidence harness.
**Novelty:** Platform compliance plus visible polish.
**Tier reason:** The app already compiles against SDK 36; target-SDK rehearsal keeps the beta plan ahead of Play and Android platform deadlines.

---

## Next (post-v0.3 beta, v0.3.x → v0.4)

### X1 (2026.05.06) - Scene editor finishing pass

**Status:** Element creation/editing, drag-to-move, and scaled previews shipped in v0.2.59. Resize handles, multi-select layout edits, alignment guides, and overlay launch (`SYSTEM_ALERT_WINDOW`) remain.
**Description:** Add resize-handle drag, snap-to-grid + alignment guides, multi-select with bulk move/align/delete, then wire scene-launch action through the existing overlay-permission onboarding so a task can show a configured scene. Keep Android 15 background-FGS overlay restrictions in scope.
**Sources:** Tasker scenes [S16], Android 15 overlay/FGS behavior [S36], Play foreground-service declaration requirements [S51], Android 16 target behavior [S52].
**Category:** UX, platform/OS.
**Impact:** 4.
**Effort:** 4.
**Risk:** Medium — overlay launch is policy-sensitive.
**Dependencies:** N1 (FGS audit), capability registry.
**Novelty:** Parity.

### X2 (2026.05.06) - Visual flow editor authoring

**Status:** Read-only flow graph + node deep links + lane overview shipped v0.2.51–v0.2.59. Authoring (drag-to-add, branch/subflow visualization, conditional-action authoring on the canvas, zoom gestures, drag-mutation) remains.
**Description:** Add canvas-side authoring: drag from a node palette, route edges between context/enter/exit/action nodes, render explicit conditional-branch and parallel/subflow markers as soon as flow-control action types exist, add pinch-zoom and pan, and persist canvas-side edits back through the existing repositories.
**Sources:** Automate flowchart [S18], Node-RED flow editor [S9], openHAB Blockly [S13].
**Category:** UX, accessibility.
**Impact:** 4.
**Effort:** 5.
**Risk:** High — easy to overwhelm beginners; must remain optional alongside the list editor.
**Dependencies:** Stable flow-control action types; templates; X1.
**Novelty:** Parity for Android automation; leapfrog among OSS options.

### X3 (2026.05.06) - Shizuku elevated backend (real opt-in execution)

**Status:** Readiness/detection only (v0.2.26). No Shizuku API dependency, permission request, or shell execution.
**Description:** Add an opt-in Shizuku integration with explicit user opt-in screen, permission request, isolated `ShizukuShellRunner`, an allowlist of elevated commands (force-stop, secure settings keys, private DNS, mobile data toggle, screen-off), action-level capability flag, run-log audit trail, and a kill-switch in Setup.
**Sources:** Shizuku [S6], Tasker ADB workarounds [S16].
**Category:** platform/OS, security, power-user.
**Impact:** 4.
**Effort:** 5.
**Risk:** High — trust boundary; mis-routed commands brick devices.
**Dependencies:** N1 (FGS / capability), action safety boundaries (P11).
**Novelty:** Leapfrog vs OSS Easer/Essentials.

### X4 (2026.05.06) - Termux scripting dispatch (real execution)

**Status:** Readiness/detection only (v0.2.27). The `script.termux.run` action is gated to fail-closed; no `RUN_COMMAND` request, no stdout/stderr capture.
**Description:** Add the `com.termux.permission.RUN_COMMAND` request flow, an explicit per-script allowlist with hash pinning, scoped working-directory enforcement, structured stdout/stderr/exit-code capture, output→variable mapping, and a hard cap on dispatch frequency. Document a threat model for Termux as an execution sandbox.
**Sources:** Termux:Tasker [S3], openHAB scripting [S13], AppDaemon sandbox [S15].
**Category:** integrations, security.
**Impact:** 4.
**Effort:** 4.
**Risk:** High — arbitrary execution; allowlist + capture must be tested.
**Dependencies:** Run-log step traces; capability registry.
**Novelty:** Parity.

### X5 (2026.05.06) - Health Connect polling trigger (wellness)

**Status:** Not started.
**Description:** Add a Health Connect read-only polling/sync trigger using `androidx.health.connect.client`. Subscribe via WorkManager periodic worker (default 30 min; user-tunable from 15 min to 24 h within HC quota), fire `event=healthconnect` Context events for sleep stage transitions, heart-rate threshold crossings, step-goal milestones, and exercise-session end. No write paths. Include a user-visible sync toggle, direct Manage Access link, background-read permission handling, 30-day history-limit handling, source attribution, and a clear statement that this is polling/sync rather than real-time event delivery.
**Sources:** Health Connect API + automation triggers (no real-time, polling required) [S45], background/history permissions and 30-day read limits [S58][S59], wellness automation request signal [S17].
**Category:** integrations, platform/OS, privacy.
**Impact:** 3.
**Effort:** 4.
**Risk:** Medium — Health Connect permissions are sensitive; F-Droid track must NOT ship the dependency by default (capability-flag it under a build flavor or runtime detect).
**Dependencies:** Capability registry; F-Droid build profile.
**Novelty:** Leapfrog among FOSS options.

### X6 (2026.05.06) - Variable expression engine v3: arrays, JSON paths, persistence

**Status:** v0.2.30–v0.2.35 shipped bounded `{{ ... }}` templates with arrays, JSON paths, math/string functions, traces, and explicit regex rejection. Persistent task-local vs. global vs. event scope is partially modeled; nested-array writes, structured-output persistence, and a debugger step view remain.
**Description:** Add (a) writeable nested array/JSON paths in the global scope with size limits, (b) a Run-Log expression-debugger view that shows expansion at each action step inline with the existing per-expression diagnostics, and (c) a `var.persist` action that explicitly hardens an in-task variable to global scope.
**Sources:** Tasker variables [S16], HA Jinja [S12], Huginn Liquid [S11].
**Category:** data, observability.
**Impact:** 4.
**Effort:** 4.
**Risk:** Medium — engine performance and quota enforcement.
**Dependencies:** Run log step traces (done v0.2.9); template engine (done v0.2.35).
**Novelty:** Parity.

### X7 (2026.05.06) - Profile sharing publish channel (post-N6)

**Status:** Manifest + Discussions submission text shipped v0.2.28; in-app preview lands in N6. Network publishing, signed/verified templates, screenshots, and trust UI remain.
**Description:** After N6 ships, add (a) a curated GitHub-Discussions-backed feed pulled offline, (b) detached-signature support for "verified" templates, and (c) screenshot attachments. Treat the feed as read-only; submission stays through Discussions.
**Sources:** Taskernet [S16], Node-RED flow library [S9], n8n templates [S10].
**Category:** plugin ecosystem, distribution.
**Impact:** 4.
**Effort:** 5.
**Risk:** Medium — review burden; trust UI must not imply Google-Play-style vetting.
**Dependencies:** N6.
**Novelty:** Leapfrog.

### X8 (2026.05.06) - Dependency/release governance and Room 3 + AGP 10 prep

**Status:** AC-2026-06-06-7 verified the catalog is already on the modern v0.2.59 stack: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.7, Compose BOM 2026.04.01, Room 2.8.4, WorkManager 2.11.2, Dagger/Hilt 2.59.2, Lifecycle 2.10.0, Navigation 2.9.8, DataStore 1.2.1, and Gson 2.14.0. Room 3 remains alpha-only, and Compose BOM 2026.05.00 is newer than the pinned BOM, so this item is no longer a broad "modernize dependencies" task; it is a governance gate for controlled future updates. [L14]
**Description:** Keep Room 3 and AGP 10 as monitored future migrations. When Room 3 reaches stable, plan one migration commit covering both `androidx.room` databases plus migration tests. Before any Compose BOM, WorkManager, Hilt/KSP/Kotlin, AGP, or Gradle update, record the exact release-source reason, compatibility matrix, affected runtime risks, and verification results. Do not adopt AGP 10 before the Hilt decision in N8 and a release/F-Droid verification plan is green.
**Sources:** local dependency/release audit [L14], Gradle releases [S67], AGP releases [S68], Kotlin releases [S69], KSP releases [S70], Room releases [S71], WorkManager releases [S72], Compose BOM docs [S74], Dagger/Hilt releases [S78].
**Category:** dev-experience, data.
**Impact:** 3.
**Effort:** 4.
**Risk:** Medium/High — Room 3 is a data-risk migration.
**Dependencies:** N8 Hilt decision.
**Novelty:** Maintenance.

### X9 (2026.05.06) - i18n / l10n bootstrap

**Status:** Not started — strings live inline in Compose code per Improvement Plan P12.
**Description:** Move all user-facing strings to `strings.xml` per existing Compose-friendly patterns, set up a `values-<locale>` skeleton for at least one additional locale, and document the contributor translation workflow (Weblate model from Easer). Capability/error/setup explanations are highest priority because they are user-facing on every onboarding.
**Sources:** Easer Weblate [S2], Improvement Plan P12 [L7].
**Category:** accessibility, i18n, dev-experience.
**Impact:** 3.
**Effort:** 4.
**Risk:** Low.
**Dependencies:** N9 docs pass.
**Novelty:** Parity.


---

## Completed Backlog (shipped v0.2.0 → v0.2.59)

The 2026-05-05 roadmap's `Now (N1–N10)`, `Next (X1–X12)`, and parts of `Later (L1, L2, L4, L6, L7)` were delivered between v0.2.2 and v0.2.59. Each item below cross-references the release that closed it; CHANGELOG.md is canonical for change-level detail.

| Legacy ID | Title | Shipped in |
|---|---|---|
| N1 | Reintegrate the active automation UI | v0.2.2 |
| N2 | First-run permission and reliability onboarding | v0.2.3 |
| N3 | Remove `USE_EXACT_ALARM`, harden exact scheduling | v0.2.4 |
| N4 | Replace static WiFi receiver with NetworkCallback | v0.2.5 |
| N5 | Replace AppOpenService polling with foreground UsageStats | v0.2.6 |
| N6 | Replace success-shaped stubs with real impls or honest failures | v0.2.7 |
| N7 | Capability registry and action/context gating | v0.2.8 |
| N8 | Execution-grade run logs (step-level traces) | v0.2.9 |
| N9 | High-value regression tests around platform/parser boundaries | v0.2.10 |
| N10 | Doc truthfulness pass | v0.2.11 |
| X1 | Profile templates and guided creation wizard | v0.2.12, v0.2.19 (NFC), v0.2.20 (calendar) |
| X2 | Open JSON import/export with schema versioning | v0.2.13 |
| X3 | Locale-compatible plugin host (setting + condition baseline) | v0.2.14, v0.2.49–v0.2.54 |
| X4 | OpenTasker as automation target (signature-scoped intent API) | v0.2.15 |
| X5 | Automation modes (single, restart, queued, parallel) | v0.2.16 |
| X6 | Context inspector | v0.2.17 |
| X7 | Notification listener trigger | v0.2.18 |
| X8 | NFC trigger + write helper | v0.2.19, v0.2.55 (write helper) |
| X9 | Calendar and sun triggers + adb evidence harness | v0.2.20, v0.2.56–v0.2.57 |
| X10 | Tasker XML import (engine + review UI) | v0.2.21, v0.2.58 |
| X11 | F-Droid readiness track | v0.2.22, v0.2.58 |
| X12 | Dependency modernization plan (Hilt, Room, WorkManager, Compose, AGP, K2/KSP) | v0.2.23, v0.2.59 batches |
| L1 (read-only) | Visual flow/graph editor — read-only model + deep links + lane overview | v0.2.24, v0.2.51–v0.2.59 |
| L2 (baseline) | Scene/overlay editor — Room baseline + element editing + drag-to-move + scaled previews | v0.2.25, v0.2.59 |
| L4 (gated) | Sandboxed scripting escape hatch — Termux readiness + gated `script.termux.run` | v0.2.27 |
| L6 (FOSS baseline) | FOSS geofencing — `LocationManager` source, dwell, evidence harness, API-36 single-device smoke | v0.2.29, v0.2.36–v0.2.48 |
| L7 | Variable/template expression system v2 — bounded `{{ ... }}` engine | v0.2.30–v0.2.35 |

Carry-forward open work (now tracked in current Now/Next):

- Scene resize handles, multi-select, alignment guides, overlay launch → **X1 (Next)**
- Flow editor authoring (drag/drop, branch viz, zoom) → **X2 (Next)**
- Shizuku elevated execution → **X3 (Next)**
- Termux real script dispatch → **X4 (Next)**
- Multi-device/multi-OEM geofence durability evidence → **N2 (Now)**
- Locale Condition Context UX → **N7 (Now)**
- Sharing preview UI → **N6 (Now)**
- Hilt-vs-non-Hilt drift, AGP-9-flag follow-through → **N8 (Now)**
- Variable engine v3 (writeable nested paths, debugger view) → **X6 (Next)**
- Room 3 + AGP 10 prep → **X8 (Next)**

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
| Matter / Google Home actor | Bridges OpenTasker to smart-home actuation without HA dependency | Matter SDK on Android is still nascent; commissioning UX is heavy | [S12] |
| Wear OS 5 watch companion | Wrist triggers and quick-run tiles are competitor parity | Smaller audience; phone-side reliability still ahead | [S17] |
| App-cloning `POST_NOTIFICATIONS` quirks (Samsung/MIUI dual-app) | Cloned-app users hit silent notify failures | Hard to reproduce without OEM hardware; carry-forward as docs ticket | [S33] |

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
| Dependency staged upgrade plan | Dev-experience | Table-stakes | Next | [L14][S67][S68][S69][S70][S71][S72][S74][S78] |
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
| Target-SDK-36 platform readiness (`specialUse|location`, FGS start rules, target behavior rehearsal) | Platform/OS | Emerging | Now | [S49][S50][S51][S52][S53] |
| Multi-device geofence durability evidence | Platform/reliability | Rare in OSS | Now | [L6][S22][S28] |
| Quick Settings Tile trigger | Platform/UX | Common | Now | [S46][S17] |
| Predictive back gesture | UX/platform | Emerging | Now | [S44] |
| Macrobenchmark + Baseline Profile | Performance/dev | Emerging | Now | [S47] |
| Health Connect polling trigger | Integrations | Rare in automation | Next | [S45] |
| AGP 9 opt-out flag resolution | Dev-experience | Rare | Now | [S38][L7] |
| i18n/l10n bootstrap | Accessibility/i18n | Common | Next | [S48][L7] |
| Wear OS 5 companion | Mobile | Emerging | Under Consideration | [S17] |
| Matter / Google Home actor | Integrations | Emerging | Under Consideration | [S12] |
| App-cloning POST_NOTIFICATIONS edge case | Reliability | Rare | Under Consideration | [S33] |

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

## 2026-06-06 Autonomous Continuation Cycles

### Cycle AC-2026-06-06-1 - Repo and roadmap resume audit

**Files reviewed:** `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md`, `README.md`, `docs/IMPROVEMENT_PLAN.md`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, recent `git log -10`, and recent commit stats through `c2412ad`.

**Key findings:**

- The active roadmap already reconciles v0.2.59 and correctly treats root `ROADMAP.md` as canonical.
- `rtk git log -10` could not run because `rtk` is unavailable in this shell; plain `git log -10 --oneline` succeeded.
- Working tree had an untracked `AGENTS.md` before this pass; this pass did not create or modify it.
- Current code compiles against SDK 36 but targets SDK 35; `AutomationService` declares `specialUse|location`, not `dataSync` or `mediaProcessing`.
- Direct repo writes are available; this file was updated directly in place.

**Roadmap changes:** Updated the roadmap date/version, added a June reconciliation note, corrected N1 platform framing, deepened N3 Quick Settings requirements, added N10, added new local/source tags, and created this continuation state.

### Cycle AC-2026-06-06-2 - Platform and policy source refresh

**Sources reviewed:** foreground-service timeout docs, Android 15 FGS type changes, Google Play foreground-service declaration help, Android 16 target behavior changes, Android 16 all-app behavior changes, Android Quick Settings tile docs, Tasker Quick Settings setup action, MacroDroid Quick Settings tile trigger docs, Automate Quick Settings tile block docs, Home Assistant Quick Settings docs, Health Connect data/permission docs, and Macrobenchmark/Baseline Profile measurement docs. [S49][S50][S51][S52][S53][S54][S55][S56][S57][S58][S59][S60][S61]

**Key findings:**

- N1 needed correction: `specialUse` is not the documented six-hour timeout type; `dataSync` and `mediaProcessing` are.
- Android 16 target behavior creates additional beta gates around adaptive large screens, predictive back in 3-button navigation, intent redirection hardening, fixed-rate scheduling, health permissions, and Bluetooth bond-loss handling.
- Quick Settings tile parity should include slot ownership, labels, state, subtitles, icon constraints, manual add guidance, run-log visibility, and failure behavior when a tile is removed or unavailable.
- Health Connect should remain a polling/sync integration with explicit background/history permissions and a user-visible sync toggle.

### Cycle AC-2026-06-06-3 - UI architecture and target-SDK-36 large-screen risk audit

**Files reviewed:** `ActiveAutomationUi.kt`, `SceneLibraryScreen.kt`, `AutomationFlowScreen.kt`, and `strings.xml` with focus on screen routing, dialog state, canvas/layout behavior, accessibility labels, and target-SDK-36 adaptive-layout risk. [L9][L11]

**Key findings:**

- `ActiveAutomationUi.kt` still combines `ActiveAutomationViewModel`, factory, top-level router, profile/task/run-log screens, Tasker import review, template picker/slot dialogs, task/profile dialogs, action picker/config dialogs, context picker/config dialogs, field renderers, rows, cards, and helpers. That makes P2 more urgent before Quick Settings, Health Connect, or more plugin UX adds another editor family.
- Screen selection and draft dialog state are held in plain `remember` variables, so rotation, freeform resize, foldable posture changes, or Android 16 large-screen recreation can drop the selected tab or unsaved dialog inputs.
- `TemplatePickerDialog`, `TemplateSlotDialog`, `ActionPickerDialog`, `ActionConfigDialog`, and `ContextConfigDialog` use fixed 420-460 dp content heights. These are likely acceptable on phones but need a bounded adaptive max-height strategy for landscape, split-screen, and `sw600dp` modes.
- `AutomationFlowScreen` uses a horizontally scrolling lane view with fixed 68 dp lane labels and node widths. It now has accessibility summaries, but target-SDK-36 large-screen QA should verify keyboard/rotary navigation, scroll affordance visibility, and whether condition labels need more than the current short "if" marker.
- `SceneLibraryScreen` uses `BoxWithConstraints` and `SceneCanvasProjector`, but preview height is capped at 280 dp and movement is drag-only on the canvas. The numeric editor gives a fallback, yet N10 should still validate tall/wide scenes, resize handles, alignment guides, keyboard/screen-reader movement controls, and screenshot evidence on tablet/freeform widths.
- `strings.xml` contains only app name, external automation permission copy, and generic success/error prefixes. P12 should move stable labels/errors/empty states by workflow as part of screen extraction, not as a broad one-shot string migration.

**Roadmap changes:** Added L11 and this cycle record; expanded P2/P12/N10 addenda below with concrete file targets, state-retention checks, and large-screen QA criteria.

### Cycle AC-2026-06-06-4 - Platform receiver, foreground-service, and incoming-intent audit

**Files reviewed:** `AutomationService.kt`, `BootReceiver.kt`, `MainActivity.kt`, `AutomationTargetReceiver.kt`, `LocalePluginHost.kt`, `LocalePluginRequestQueryEvents.kt`, `NfcContextEvents.kt`, `NfcTagWriteSession.kt`, `TaskerXmlImport.kt`, `ActiveAutomationUi.kt` Tasker import intake, and `AndroidManifest.xml`. [L12]

**Key findings:**

- `AutomationService` calls `startForegroundCompat()` in `onCreate`, returns `START_STICKY` from `onStartCommand`, creates a minimum-importance ongoing notification, and on API 34+ calls `startForeground(NOTIF_ID, notification, foregroundServiceTypes())`. Its type mask always includes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` and adds `LOCATION` only when foreground/background location permission and provider prerequisites are present.
- There is no `onTimeout()` override, which is acceptable for today's declared `specialUse|location` types but should become mandatory if a future worker path declares `dataSync` or `mediaProcessing`.
- `MainActivity.startAutomationService()` wraps `ContextCompat.startForegroundService()` with `runCatching` and logs startup failure. `BootReceiver` starts the foreground service directly on `ACTION_BOOT_COMPLETED` without the same failure handling, telemetry, or run-log evidence.
- `AutomationTargetReceiver` is exported but protected by the app's signature permission. It uses `goAsync()`, supports only three explicit actions, resolves task/profile by id or name, and caps external variable values at 4,096 characters; no nested `Intent` extras were found in this receiver.
- Locale plugin execution validates plugin package names, caps plugin bundle JSON at 16 KB, resolves exactly one broadcast receiver or configuration activity, and uses explicit components for execution/configuration. The dynamic Locale request-query intake is registered with `ContextCompat.RECEIVER_EXPORTED`, accepts request-query broadcasts, validates activity class names, and serializes the supplied bundle into an event payload.
- NFC event/write paths read typed `Tag` parcelables on API 33+, accept only NFC tag actions, clear the pending write plan after a write attempt, and publish write failures to the UI. Tasker XML import is bounded by `TASKER_XML_IMPORT_MAX_BYTES`, parses from a user-selected URI, and disables document type/external-entity loading in the XML parser.

**Roadmap changes:** Added L12 and this cycle record; expanded N1 boot/start evidence criteria, added N10 incoming-intent hardening criteria, refreshed the research log, and advanced the continuation state to setup/inspector and run-log evidence design.

### Cycle AC-2026-06-06-5 - Setup/inspector platform-blocked state and Quick Settings run-log model audit

**Files reviewed:** `PermissionOnboardingScreen.kt`, `ContextInspectorScreen.kt`, `ContextInspector.kt`, `RunLogEntry.kt`, `RunLogDao.kt`, `RunLogDiagnostics.kt`, `TaskRunner.kt`, `AutomationService.kt` run-log insertion paths, `ActiveAutomationUi.kt` run-log UI, `docs/IMPROVEMENT_PLAN.md`, `docs/ARCHITECTURE.md`, and `docs/EXTERNAL_INTENTS.md`. [L13]

**Key findings:**

- Setup already covers a broad permission checklist: notification permission, exact alarms, battery optimization, usage access, notification access, calendar, overlay, foreground/background location, nearby WiFi, Bluetooth connect, SMS, DND policy access, optional Shizuku, optional Termux, and app visibility. It still does not expose live automation-service health, boot-start health, foreground-service type actually in use, notification-channel visibility, or OEM-specific risk beyond the generic battery-optimization row.
- Context Inspector has a useful source-health model (`Active`, `Waiting`, `Needs setup`, `Missing`, `Error`) and per-profile match explanations. Its specialized setup checks currently cover app usage, exact alarms/inexact fallback, state/WiFi access, notification/calendar event readiness, and location provider/permission detail. It does not yet map setup failures to affected profile families in a compact dashboard, and no Quick Settings tile source exists yet.
- Run logs are stored as a simple `RunLogEntry` with task id/name, timestamp, duration, success, and free-form message. The structured source/outcome/action trace data is encoded inside the message and parsed by `RunLogDiagnostics`; this is workable for a first Quick Settings tile trigger but will strain filtering/reporting if more trigger surfaces keep arriving.
- `AutomationService` and `AutomationTargetReceiver` both create `TaskRunner`, run a task, and write run-log rows with different source strings (`Profile: ...` and `External intent`). A Quick Settings implementation should not add a third hand-rolled execution path; it should extract a shared task-execution/logging helper that accepts source metadata, trigger metadata, event variables, and skip/failure reasons.
- The run-log UI already has status filters, task filters, query search, summary counts, structured source display, skipped-run classification, and action trace rows. Quick Settings can reuse this if tile runs write source strings such as `Quick Settings Tile: <slot label>` plus metadata lines for slot id, tile state, locked-device state, and configured target.
- Existing docs explicitly track P8/P16/P19 as started rather than done. That supports keeping Quick Settings behind run-log/source metadata hardening and expanding Setup/Inspector into a platform reliability dashboard before claiming v0.3 beta readiness.

**Roadmap changes:** Added L13 and this cycle record; expanded N1/P19 platform-dashboard criteria, expanded N3 Quick Settings run-log architecture criteria, refreshed the research log, and advanced continuation to external Quick Settings pain-point research and dependency/release evidence refresh.

### Cycle AC-2026-06-06-6 - Quick Settings pain-point and state-drift research refresh

**Sources reviewed:** Android Quick Settings implementation guide, Android `TileService` reference, MacroDroid Quick Settings trigger/setup docs and forum threads, Home Assistant Android Quick Settings docs and Android 16 issue, Tasker/AutoNotification community reports about tile labels, icons, and state drift. [S54][S46][S56][S61][S62][S63][S64][S65][S66]

**Key findings:**

- User confusion starts before the first tile tap: Android still requires manual panel editing unless the app uses the platform add-tile request flow where available; MacroDroid and Home Assistant both document a configure-then-add sequence rather than assuming the tile is discoverable.
- State drift is the main support risk. MacroDroid guidance and community threads repeatedly distinguish between a tile tap, a toggle state, and the underlying automation/device state; users expect a tile to reflect changes made elsewhere, not just changes caused by tapping the tile.
- Tile UI updates can be delayed, OEM-specific, or dependent on lifecycle events such as collapsing the shade or refreshing the tile after configuration. Community reports across MacroDroid, Tasker/AutoNotification, and Home Assistant show label/icon/state updates as fragile surfaces, especially across Android/OEM upgrades.
- Android 16/OEM behavior remains a live risk: Home Assistant has a recent Android 16 OriginOS report where Quick Settings tiles lost labels, entity status, and click behavior after an OS update. OpenTasker should treat tile support as a device-evidence feature, not a pure unit-test feature.
- The safe first OpenTasker slice should be conservative: limited slots, explicit slot ownership, a visible "configured but not added" state, manual refresh/reconcile action, run-log entries for `onTileAdded`, `onStartListening`, `onClick`, `onTileRemoved`, and no claim that tile state always mirrors external state unless a `tile.set` action or source-of-truth binding updated it.

**Roadmap changes:** Added external source tags S62-S66; expanded N3 with state-drift/OEM evidence criteria; refreshed the research log and continuation state toward dependency/release evidence and test-coverage mapping.

### Cycle AC-2026-06-06-7 - Dependency/release and F-Droid evidence refresh

**Files reviewed:** `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, root Gradle repository config, `.github/workflows/build.yml`, `.github/workflows/release.yml`, `docs/FDROID_READINESS.md`, `fdroid/metadata/com.opentasker.app.yml`, `tools/verify-fdroid-release.ps1`, `README.md`, `CLAUDE.md`, and `CHANGELOG.md`. [L14]

**External sources reviewed:** Gradle releases, Android Gradle Plugin releases, Kotlin releases, KSP releases, Room releases, WorkManager releases, Activity releases, Compose BOM docs and April 2026 Compose update notes, Lifecycle releases, Navigation releases, Dagger/Hilt releases, Kotlin serialization JSON docs, F-Droid build metadata and reproducible-build docs, and GitHub Actions artifact docs. [S67][S68][S69][S70][S71][S72][S73][S74][S75][S76][S77][S78][S79][S80][S31][S81]

**Key findings:**

- The local dependency catalog is already very current for v0.2.59: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.7, Compose BOM 2026.04.01, Room 2.8.4, WorkManager 2.11.2, Dagger/Hilt 2.59.2, Lifecycle 2.10.0, Navigation 2.9.8, DataStore 1.2.1, and Gson 2.14.0. AGP's current compatibility docs line up with Gradle 9.4.1, Build Tools 36.0.0, JDK 17, and API 36.x support. [L14][S68]
- X8 should shift from broad upgrade intent to dependency governance. Compose BOM 2026.05.00 is newer than the pinned 2026.04.01 BOM, but it should be evaluated with UI snapshots, Macrobenchmark/Baseline Profile coverage, release, and F-Droid gates rather than bumped opportunistically. Room 3 remains a future data migration, not a current stable upgrade.
- The normal build workflow runs unit tests, debug/release assembly, F-Droid readiness, and F-Droid metadata checks, including the F-Droid profile. It does not run lint, while Improvement Plan P7 says normal CI should run lint before release-pipeline hardening is complete. [L14]
- The release workflow runs lint in its validation job, builds F-Droid and signed release APKs, and uploads a GitHub release asset. It does not yet prove the workflow input version matches `appVersionName`, does not call `tools/verify-fdroid-release.ps1`, does not run `fdroid lint`/`fdroid build`, and does not publish checksums, normalized payload digest, SBOM, provenance, or attestation artifacts. [L14][S31][S80][S81]
- F-Droid metadata is current at versionName `0.2.59`, versionCode `61`, and commit `02e047a01befd08c2bc62bdc29f97e0eb613e3c6`, with `openTaskerDistribution=fdroid`, preassemble readiness verification, and an unsigned APK output path. `docs/FDROID_READINESS.md` is stale in several places: it still mentions build tools `35.0.0`, upstream APK `OpenTasker-v0.2.58.apk`, and local fdroidserver evidence for v0.2.58/code 60. [L14]
- `README.md` and `CLAUDE.md` need a doc-truth pass before beta claims: README still has stale Kotlin/Android Studio wording while the catalog is Kotlin 2.3.21, and CLAUDE.md still carries v0.2.58 / Gradle 8.9 / old workflow references. [L14]

**Roadmap changes:** Added L14 and S67-S81, reframed X8 around dependency/release governance, added P7/X8 release acceptance criteria, refreshed the research log, and advanced continuation to a doc-truth/release-pipeline patch plan.

### Detailed Feature Spec Addenda

#### Addendum: P2 UI architecture split plan

- [ ] Move `ActiveAutomationViewModel` and `ActiveAutomationViewModelFactory` into `ActiveAutomationViewModel.kt` before adding new editor families.
- [ ] Split top-level navigation/router state into `ActiveAutomationRoute.kt` or `ActiveAutomationScaffold.kt`; keep only cross-screen orchestration there.
- [ ] Extract screen files in this order: `ProfilesScreen.kt`, `TasksScreen.kt`, `RunLogScreen.kt`, `TaskerImportReviewDialog.kt`, `TemplateDialogs.kt`, `ActionEditorDialogs.kt`, `ContextEditorDialogs.kt`.
- [ ] Keep pure row/card components close to the screen first; only promote to shared components when reused by at least two screens.
- [ ] After each extraction, run `:app:compileDebugKotlin` and the directly related JVM tests; after the full split, run the repo verification gate (`compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `git diff --check`).
- [ ] Add at least one focused UI-state JVM/pure test where practical for run-log filters, Tasker import preview actions, and context config save output.

#### Addendum: P12 text centralization plan

- [ ] Move stable navigation labels, dialog titles, common button labels, permission/setup copy, empty states, and error labels into `strings.xml` by workflow during the P2 screen split.
- [ ] Leave generated dynamic summaries in code until their grammar is stable; do not bulk-migrate highly dynamic diagnostic strings in the first pass.
- [ ] Add string keys for Quick Settings Tile setup copy before implementing N3 so tile onboarding can be translated and reused in Setup/Inspector.
- [ ] Add a review rule that new user-facing Compose text in extracted screen files either references `stringResource()` or includes a short reason when dynamic text stays inline.

#### Addendum: N10 large-screen and state-retention QA

- [ ] Replace plain `remember` with `rememberSaveable` or ViewModel-backed state for selected screen, open dialog identity, picker filters, run-log filter/search state, and simple editor drafts that must survive rotation/freeform resize.
- [ ] Convert fixed 420-460 dp dialog lists to adaptive `heightIn`/window-relative constraints so small landscape windows and large desktop windows both remain usable.
- [ ] Test Flow canvas on phone portrait, phone landscape, `sw600dp` tablet, split-screen, and freeform widths; record whether horizontal scroll, node selection, and lane labels remain discoverable.
- [ ] Test Scene preview with tall, wide, and dense scenes; add fit/actual-size/zoom or pan follow-up if the 280 dp cap hides important layout relationships.
- [ ] Verify drag actions have equivalent numeric, keyboard, or button-based controls for accessibility before scene overlay launch ships.

#### Addendum: N1 platform readiness acceptance criteria

- [ ] `AutomationService` start paths from `MainActivity` and `BootReceiver` are covered by tests or adb evidence on API 35 and API 36.
- [ ] `BootReceiver` uses the same guarded startup behavior as `MainActivity`, records startup denial/failure evidence, and does not crash or loop if boot-start FGS launch is rejected.
- [ ] Service-start evidence captures `START_STICKY`, notification channel/id/importance, actual foreground-service type mask, and whether `LOCATION` is omitted when background-location prerequisites are missing.
- [ ] Play `specialUse` declaration copy explains the user-visible automation benefit, notification behavior, and user impact if the service is stopped.
- [ ] Evidence harness records foreground-service type, notification presence, start result, background-start failure if applicable, location prerequisites, and run-log outcome.
- [ ] Docs explicitly say six-hour timeout handling is required only if OpenTasker introduces `dataSync` or `mediaProcessing`.
- [ ] Setup/Inspector UI shows platform-blocked status for exact alarms, location, background location, notification access, notification permission, battery optimization, and OEM risk.
- [ ] Setup/Inspector also shows automation-service running state, boot-start status, foreground-service type currently active, notification channel visibility, and which profile/context/action families each missing platform access blocks.

#### Addendum: P19 platform reliability dashboard acceptance criteria

- [ ] Replace the flat setup checklist summary with a grouped platform-readiness model: Engine, Scheduling, Notifications, Location, App/Usage, Network/WiFi, Overlays/Scenes, External Integrations, and Optional Power-user Backends.
- [ ] Each readiness row exposes current state, affected automation families, remediation action, last checked time, and whether the issue blocks execution, reduces reliability, or only limits optional features.
- [ ] Context Inspector links a blocked profile/context check back to the relevant Setup row instead of only showing descriptive text.
- [ ] Service-health evidence includes running/not running, boot-start attempted, last foreground-service start result, active foreground-service type mask, notification channel status, and latest engine error if available.
- [ ] A JVM-testable status builder maps permissions/platform states to affected families so copy changes do not break the gating model.

#### Addendum: N3 Quick Settings Tile acceptance criteria

- [ ] A user can configure at least one OpenTasker tile slot with label, icon, state mode, and target task/profile.
- [ ] The app explains the Android Quick Settings manual add flow and detects when a configured tile has not yet been added.
- [ ] Tapping a tile emits a run-log event and triggers only the configured safe target.
- [ ] `tile.set` can update label/state/subtitle where supported and degrades clearly on older APIs.
- [ ] Removing a tile does not orphan or enable any profile unexpectedly; Inspector shows unavailable tile state.
- [ ] Tile-trigger execution uses a shared task-execution/logging helper instead of duplicating the private `AutomationService.runTask()` or external receiver code.
- [ ] Tile run logs include source `Quick Settings Tile: <slot label>` plus metadata for slot id, target task/profile id, lock-screen state when available, resulting tile state, and skipped/failure reason.
- [ ] Run-log filters can isolate tile-triggered runs without relying only on free-text search; if the Room schema stays unchanged for the first slice, the compatibility plan must explain how future typed source fields will migrate.
- [ ] Tile lifecycle events are observable: `onTileAdded`, `onStartListening`, `onClick`, and `onTileRemoved` each produce debuggable internal logs and user-visible run-log or Inspector evidence where relevant.
- [ ] The app distinguishes desired tile state, last pushed tile state, and underlying automation/device state so state drift is explicit rather than silently wrong.
- [ ] Device evidence covers at least one stock Android emulator, one Samsung/One UI or equivalent OEM device if available, and an Android 16 target run before Quick Settings leaves beta-gate status.

#### Addendum: P7/X8 dependency and release governance acceptance criteria

- [ ] Add a generated or manually refreshed dependency snapshot table covering Gradle, AGP, Kotlin, KSP, Compose BOM, Room, WorkManager, Dagger/Hilt, Lifecycle, Navigation, DataStore, Coroutines, serialization, and Gson, with current local version, upstream release source, compatibility constraint, and next review trigger.
- [ ] Dependency update PRs/patches record exact release-source links, compatibility notes, expected app risk, and the verification gate that ran; "latest version" alone is not an accepted reason.
- [ ] Compose BOM updates compare the BOM mapping and run at least compile, unit tests, release assembly, F-Droid profile assembly/readiness, app-start smoke, and a UI screenshot or Macrobenchmark/Baseline Profile gate once N5 exists.
- [ ] Room 3 remains deferred until a stable release exists; the first adoption plan must cover the active Room database, exported schemas, migration tests, backup/restore compatibility, and rollback notes.
- [ ] WorkManager updates are reviewed against background constraint fixes before Health Connect polling work starts; add worker tests for retry/constraint behavior before X5 ships.
- [ ] The normal build workflow either runs lint or documents why lint is release-only while P4 debt remains; P7 cannot be marked done until normal CI has a lint gate.
- [ ] The release workflow validates the requested release version equals `appVersionName`, tag `v<appVersionName>` points at the expected metadata commit, signing inputs are present before release assembly, and changelog notes are scoped to the released version.
- [ ] The release workflow calls `tools/verify-fdroid-release.ps1` and, when fdroidserver is available, runs `fdroid lint` and `fdroid build`; failures block the release.
- [ ] Release artifacts include the signed release APK, F-Droid-profile unsigned APK or build artifact, SHA-256 checksums, normalized APK payload digest used for reproducibility comparison, and GitHub Actions artifacts for post-release inspection.
- [ ] `docs/FDROID_READINESS.md`, `README.md`, `CLAUDE.md`, and `docs/IMPROVEMENT_PLAN.md` are corrected for v0.2.59, Gradle 9.4.1, Build Tools 36.0.0, Kotlin 2.3.21, current workflow filenames, and current fdroid metadata/evidence before N9 is closed.

#### Addendum: N10 target-SDK-36 rehearsal acceptance criteria

- [ ] A target-36 branch or local build runs the main flows on phone and `sw600dp`/freeform-width devices without clipped navigation, lost editor state, or broken scene/flow canvases.
- [ ] Predictive back is validated for Profiles, Tasks, Context picker, Action editor, Setup, Inspector, Flow, Scenes, Tasker import preview, and dialogs.
- [ ] External intents, Locale plugin result intents, Tasker import intents, NFC intents, and nested intents are audited for Android 16 intent-redirection behavior.
- [ ] Locale request-query broadcasts remain exported only if required for plugin compatibility; if they stay exported, tests cover malformed activity-class names, oversized/invalid bundles, unknown packages, ambiguous receivers, and result-timeout behavior.
- [ ] NFC and Tasker import tests cover forged or malformed intents/files without nested-intent launches, entity expansion, unbounded memory growth, or silent success.
- [ ] Fixed-rate or polling loops are checked for Android 16 missed-execution behavior.
- [ ] Health Connect remains gated behind an explicit future integration decision and does not introduce unreviewed health permissions.

## Research Log

| Date | Cycle | Research Area | Sources / Files Reviewed | Key Findings | Roadmap Changes |
|---|---|---|---|---|---|
| 2026-06-06 | AC-2026-06-06-1 | Local repo resume | `ROADMAP.md`, `README.md`, `CLAUDE.md`, `docs/IMPROVEMENT_PLAN.md`, `AndroidManifest.xml`, Gradle catalog, git log | Existing roadmap is mature; target SDK remains 35; `AutomationService` uses `specialUse|location`; untracked `AGENTS.md` pre-existed | Updated date/version, added local evidence tags, added continuation state |
| 2026-06-06 | AC-2026-06-06-2 | Platform and competitor refresh | Android FGS/target-36 docs, TileService docs, Tasker/MacroDroid/Automate/Home Assistant tile docs, Health Connect docs | Corrected N1; Quick Settings needs richer acceptance criteria; Health Connect must stay polling/sync | Revised N1, deepened N3, added N10, added source tags S49-S60 |
| 2026-06-06 | AC-2026-06-06-3 | UI architecture and target-SDK-36 layout risk | `ActiveAutomationUi.kt`, `SceneLibraryScreen.kt`, `AutomationFlowScreen.kt`, `strings.xml` | UI router/dialog/state surface is too concentrated; plain `remember` state and fixed dialog heights create resize/rotation risk; Flow and Scene canvases need large-screen/accessibility QA | Added L11, P2 split plan, P12 text plan, and N10 large-screen/state-retention criteria |
| 2026-06-06 | AC-2026-06-06-4 | FGS and incoming-intent audit | `AutomationService.kt`, `BootReceiver.kt`, `MainActivity.kt`, `AutomationTargetReceiver.kt`, Locale host/request-query files, NFC files, Tasker import, manifest | App-start FGS path is guarded but boot path is not; Locale dispatch uses explicit components; exported request-query intake needs malformed-input evidence; NFC/Tasker import are bounded but need target-36 regression tests | Added L12, N1 boot/start criteria, N10 incoming-intent criteria, and refreshed continuation state |
| 2026-06-06 | AC-2026-06-06-5 | Setup/inspector and run-log model | `PermissionOnboardingScreen.kt`, `ContextInspectorScreen.kt`, `ContextInspector.kt`, run-log model/DAO/diagnostics/UI, `TaskRunner.kt`, docs | Setup and Inspector cover many permissions/source states but lack engine/boot/FGS/channel service health; run logs encode source in free-form message; Quick Settings should share task execution/logging rather than add another runner | Added L13, P19 dashboard criteria, N3 tile run-log criteria, and refreshed continuation state |
| 2026-06-06 | AC-2026-06-06-6 | Quick Settings state-drift research | Android TileService docs, MacroDroid setup/state threads, Home Assistant Quick Settings docs and Android 16 issue, Tasker/AutoNotification tile reports | Manual add/setup, label/icon/state drift, lifecycle refresh, OEM/Android upgrade regressions, and lock-screen semantics are the risky parts of tile UX | Added S62-S66, expanded N3 lifecycle/state-drift/device-evidence criteria, and refreshed continuation state |
| 2026-06-06 | AC-2026-06-06-7 | Dependency/release and F-Droid evidence | Gradle catalog/wrapper, build/release workflows, `app/build.gradle.kts`, F-Droid metadata/docs/script, README/CLAUDE/CHANGELOG, official dependency/F-Droid/GitHub Actions sources | Catalog is already current enough that X8 should become governance; CI lacks normal lint; release flow lacks version/tag/fdroidserver/checksum/provenance gates; F-Droid and project docs contain stale v0.2.58/Build Tools/Kotlin/Gradle details | Added L14, S67-S81, P7/X8 acceptance criteria, and refreshed continuation state |

## Next Research Cycles

1. Build AC-2026-06-06-8 doc-truth and release-pipeline patch plan from `README.md`, `CLAUDE.md`, `docs/FDROID_READINESS.md`, `docs/IMPROVEMENT_PLAN.md`, `.github/workflows/*.yml`, and roadmap source tags.
2. Inspect release workflow implementation options for version/tag validation, `tools/verify-fdroid-release.ps1` integration, optional fdroidserver lint/build, checksums, normalized payload digest, and artifact upload.
3. Inspect test coverage around `SceneCanvasProjector`, `AutomationFlowGraph`, run-log filters, Locale plugin host, NFC events, and Tasker XML import to decide where target-36 regression tests should land.
4. Research Health Connect background/history permissions and Play declaration requirements in enough detail to decide whether X5 should remain Next or move to Under Consideration.
5. Research Android accessibility guidance for drag gestures, custom canvas controls, and equivalent keyboard/screen-reader alternatives.
6. Search Android 16 intent-redirection guidance and issue reports for broadcast/plugin edge cases that local code inspection cannot prove.
7. Audit `ProfileMatcherImpl.kt` for event-variable propagation so tile, NFC, notification, Locale request-query, and future Health Connect triggers can expose consistent source metadata.
8. Design the shared task-execution/logging helper boundary before any Quick Settings implementation starts.
9. Draft an N3 device-evidence script outline for tile add/listen/click/remove and state-update behavior.
10. Build a dependency snapshot/checklist format that can be regenerated or reviewed before each release.

## Continuation State

### Last Completed Cycle

AC-2026-06-06-7 - Dependency/release and F-Droid evidence refresh.

### Current Focus

Continue with AC-2026-06-06-8: doc-truth and release-pipeline patch plan. Start with `README.md`, `CLAUDE.md`, `docs/FDROID_READINESS.md`, `docs/IMPROVEMENT_PLAN.md`, `.github/workflows/build.yml`, `.github/workflows/release.yml`, `tools/verify-fdroid-release.ps1`, and the AC-7 source tags.

### Important Findings So Far

- Direct writes to root `ROADMAP.md` succeeded.
- `rtk` is unavailable in this shell; use plain `git` unless `rtk` becomes available.
- Do not commit or push from this thread unless explicitly asked.
- Existing untracked `AGENTS.md` predates this pass and should not be reverted.
- N1's old `specialUse` timeout framing was corrected; future cycles should preserve that correction.
- AC-2026-06-06-4 found the app-launch FGS path is guarded in `MainActivity`, but the boot FGS path in `BootReceiver` is not yet guarded or observable.
- Locale plugin execution uses package validation and explicit components, while exported request-query intake still needs malformed-input and compatibility evidence before target-SDK-36 hardening is considered complete.
- NFC and Tasker XML intake are bounded/typed in current code, but need regression tests for forged intents, malformed files, and entity-expansion defenses.
- Setup covers many permissions but not live engine/boot/foreground-service health, active FGS type, notification-channel visibility, or affected automation-family mapping.
- Context Inspector can explain source setup and profile matching, but Quick Settings does not yet exist as a source/action family and platform-blocked profiles do not link back to setup remediation rows.
- Run logs can already display source, skipped decisions, filters, and action traces; source is stored in the free-form message, so Quick Settings needs a shared execution/logging helper and probably a future typed source field.
- Quick Settings state drift is externally validated as a support risk: setup/add flow, label/icon/state refresh, OEM updates, and underlying state changes all need explicit product behavior and evidence.
- The first Quick Settings slice should expose lifecycle/state evidence and avoid promising perfect external-state mirroring before `tile.set` or source-of-truth bindings exist.
- UI split planning is now explicit: extract ViewModel/router/screens/dialog groups before adding Quick Settings or Health Connect editor surfaces.
- Target-SDK-36 QA needs state-retention checks because current screen/dialog/filter state uses plain `remember`.
- AC-2026-06-06-7 found the v0.2.59 dependency stack is already current enough that X8 should focus on governance, compatibility evidence, and release gates rather than broad modernization.
- Normal CI currently runs unit tests, debug/release assembly, F-Droid readiness, and metadata checks, but not lint; P7 cannot be called done until the build workflow has a lint gate or a documented temporary exception.
- The release workflow does not yet validate workflow input version against `appVersionName`, prove the release tag points at the metadata commit, run `tools/verify-fdroid-release.ps1`, run fdroidserver lint/build, or publish checksums/normalized payload digest/provenance artifacts.
- `docs/FDROID_READINESS.md`, `README.md`, and `CLAUDE.md` contain stale dependency/release details after the v0.2.59 modernization batch.

### Next Best Actions

1. Draft exact doc-truth edits for README, CLAUDE.md, F-Droid readiness docs, and Improvement Plan P5/P7/N9 language.
2. Draft release workflow hardening steps that can be implemented incrementally without publishing a release from this thread.
3. Add an N1/N10 evidence matrix listing exact adb/device checks, compat flags, expected logs, and screenshot artifacts for API 35/36 before target SDK changes.

### Unprocessed Leads

- Android 16 target-SDK large-screen behavior may break Scene and Flow canvases at `sw600dp`; AC-2026-06-06-3 added QA criteria but no screenshot evidence yet.
- `strings.xml` has only five strings; inline text centralization should be scoped by user-visible flows, not by bulk mechanical replacement.
- Quick Settings Tile implementation should probably reuse existing external-intent/task-run contracts but needs a slot table and run-log event model.
- A shared task-execution/logging helper is a likely prerequisite for Quick Settings; otherwise `AutomationService`, `AutomationTargetReceiver`, and `TileService` will drift.
- Quick Settings needs a tile lifecycle evidence script or manual checklist before implementation claims are credible.
- Release workflow hardening should be designed but not executed from this thread; no commit, tag, GitHub release, or APK publication is authorized.
- F-Droid docs need a fresh local evidence section after the current metadata and toolchain are validated.
- Boot startup needs denial/failure handling and evidence parity with app-launch startup before target-SDK-36 rehearsal.
- Locale request-query compatibility requires balancing exported dynamic receiver behavior against Android 16 intent-hardening expectations.
- Health Connect remains attractive for wellness automations but carries permission, Play declaration, and source-attribution complexity.

### Files Still To Inspect

- `README.md`
- `CLAUDE.md`
- `docs/FDROID_READINESS.md`
- `docs/IMPROVEMENT_PLAN.md`
- `.github/workflows/build.yml`
- `.github/workflows/release.yml`
- `tools/verify-fdroid-release.ps1`
- `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt`
- Task execution/logging extraction candidates across `AutomationService.kt` and `AutomationTargetReceiver.kt`
- Tests for Locale plugin host, request-query events, NFC events/write session, and Tasker XML import

### Searches Still To Run

- `"GitHub Actions" Android release checksum artifact provenance attestation APK`
- `"F-Droid" AllowedAPKSigningKeys Binaries reproducible upstream APK metadata`
- `"Health Connect" background read permission Play Console declaration automation app`
- `"Android 16" "Intent redirection" broadcast receiver plugin explicit component`
- `"Compose" predictive back dialog unsaved changes Android 16`
- `"Android Quick Settings TileService" lock screen long press requestListeningState OEM Android 16`

---

## Self-Audit

- Every roadmap item above has a local evidence tag or source tag.
- No item depends on a competitor feature claim without a URL or local-repo source in the appendix.
- 2026-05-06 rotation: prior `Now (N1–N10)` and `Next (X1–X12)` items have all shipped (v0.2.2 → v0.2.59) and are archived in the **Completed Backlog** appendix; the active `Now`/`Next` blocks are entirely new and target the v0.3.0 beta gate.
- Geofence reliability claims remain explicitly gated to evidence already captured under [L6]; broader OEM/multi-device guarantees are not promised — `N2` exists precisely to gather that evidence before any guarantee is stated.
- Android platform restrictions are treated as product requirements, not implementation details (target-SDK-36 behavior, FGS-start rules, Play `specialUse` declaration evidence, predictive back, and Health Connect polling/sync constraints are all sourced).
- F-Droid and Google Play constraints are separated because their acceptable dependency/policy profiles differ.
- Rejected items are explicitly preserved so they do not silently return as future churn.
- High-risk ideas (Shizuku, Termux, plugin host) remain in `Next` only because the capability registry, permission onboarding, run-log redaction, and import/export schema landed in v0.2.x and now make them safe to ship.
- Category coverage spot-check: security (N1, N6, N8), accessibility/i18n (N4, X9), observability (N5, [L6]), testing (N5, X4 evidence harness), docs (N9, N2), distribution (N1, N8), plugin (X3 carry-forward, X7), mobile/platform (N1, N3, N4, X5), offline/resilience (N2, X4 local exec), multi-user (Under Consideration only — local-first remains center), migration (X6 v3 expressions, Completed Backlog X10), upgrade (N8, X8). No category is empty.

---

## Research-Driven Additions (2026-06-09 deep research pass)

Items below were identified by the 2026-06-09 exhaustive research pass covering 34+ external sources, full repo walk, commit history, and competitive analysis. Each item was checked against existing Now/Next/Later/Under Consideration tiers to avoid duplicates. Only genuinely new or materially deepened items appear here.

### RD4 - Compose BOM 2026.05.00 evaluation gate (Next)

**Status:** Not started. Current BOM is 2026.04.01; 2026.05.00 is available.
**Description:** Evaluate Compose BOM 2026.05.00 with the following gate: (a) compile debug + release, (b) run all JVM tests, (c) F-Droid readiness + metadata checks, (d) device smoke on SM-S938B, (e) if N5 Macrobenchmark exists, run cold-start benchmark. The BOM includes testing v2 API migration (StandardTestDispatcher replaces UnconfinedTestDispatcher), new experimental APIs (Styles, MediaQuery, Grid, FlexBox), and Material3 shape-morphing chip overloads. Document compatibility notes per the governance criteria in X8.
**Sources:** Compose BOM April/May 2026 release notes, Compose testing v2 migration guide, X8 dependency governance criteria.
**Category:** dev-experience, UX.
**Impact:** 3 -- keeps the dependency stack current; unlocks new Compose APIs.
**Effort:** M -- evaluation + potential test migration for v2 API dispatcher change.
**Risk:** Medium -- testing v2 dispatcher change may require test adjustments.
**Dependencies:** X8 governance criteria.
**Novelty:** Maintenance.
**Tier:** Next.

### RD7 - Encrypted database backup/restore (Later)

**Status:** `DatabaseBackupManager.kt` exists but the research did not verify encryption support.
**Description:** Add encrypted backup/restore for Room databases using a user-supplied passphrase or device-bound key. Support export to a `.otbackup` file (ZIP containing encrypted SQLite + schema version + app version metadata) and import with schema compatibility validation. This enables safe device migration without cloud trust. Consider using SQLCipher or AndroidX security-crypto for encryption.
**Sources:** Room database backup libraries (rafi0101/Android-Room-Database-Backup, salehyarahmadi/RoomDatabaseBackupAndRestore), SQLCipher Room integration docs.
**Category:** data, security, UX.
**Impact:** 3 -- enables device migration; supports "Under Consideration" encrypted sync.
**Effort:** L -- encryption, key management, file format, schema compat, UI.
**Risk:** Medium -- key management complexity; must not lose data on failed restore.
**Dependencies:** Room schema export (done).
**Novelty:** Leapfrog -- no FOSS competitor offers encrypted backup.
**Tier:** Later.

### RD10 - HTTP webhook receiver trigger (Under Consideration)

**Status:** Not started. Already in existing "Under Consideration" table. This entry deepens the requirements.
**Description:** Add an opt-in local HTTP listener (bound to localhost or LAN) that accepts webhook POST requests and emits `event=webhook` context events. Requires: (a) explicit user opt-in with port selection, (b) authentication (API key or mTLS), (c) request validation (content-type, size limit, JSON-only), (d) CORS policy, (e) background-service integration that respects Android FGS constraints, (f) run-log entries with sanitized request metadata. This enables n8n/Node-RED/Home Assistant integration without cloud dependency.
**Sources:** n8n Home Assistant integration, Node-RED mobile companion patterns, Automate HTTP request block.
**Category:** integrations, security.
**Impact:** 4 -- bridges OpenTasker to the home-automation/workflow-engine ecosystem.
**Effort:** L -- local HTTP server on Android is non-trivial (Ktor/NanoHTTPD embedded); FGS and battery constraints.
**Risk:** High -- security surface (local server), battery impact, Play policy review.
**Dependencies:** N1 platform readiness; RD2 shared execution helper.
**Novelty:** Leapfrog among FOSS options.
**Tier:** Under Consideration -- high value but high complexity and security surface.

## Appendix A - Source Index

### Local repository evidence

| Tag | Source |
|---|---|
| L1 | Local repo: `MainActivity.kt`, `OpenTaskerApp_NoHilt.kt`, `.kt.bak` UI snapshots, README/CHANGELOG state as of commit `10578e4` |
| L2 | Local repo: TODO/stub scan across `app/src/main/java/com/opentasker/core/actions/**` and context/action implementations |
| L3 | Local repo/platform audit: `AndroidManifest.xml`, `AutomationService.kt`, `BootReceiver.kt`, `WiFiEventReceiver.kt`, `AppOpenService.kt`, `GeofenceTrigger.kt`, `build.gradle.kts` |
| L4 | Local repo: `InputValidationTest.kt`, Room schema export, Gradle validation after hardening |
| L5 | Local repo: previous roadmap, project notes, and README status mismatch |
| L6 | Local repo: `tools/collect-location-evidence.ps1`, `docs/FOSS_GEOFENCING.md`, `build/device-evidence/location/*` (single-device API-36 evidence captured during v0.2.36–v0.2.48) |
| L7 | Local repo: `docs/IMPROVEMENT_PLAN.md` (P10 Hilt drift, P12 i18n carry-forward) |
| L8 | Local repo 2026-06-06 audit: `AndroidManifest.xml`, `AutomationService.kt`, `BootReceiver.kt`, `MainActivity.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, git log through `c2412ad` |
| L9 | Local repo 2026-06-06 UI maintainability audit: `ActiveAutomationUi.kt` 2,891 lines, `SceneLibraryScreen.kt` 927 lines, `AutomationFlowScreen.kt` 524 lines, `strings.xml` five strings with most Compose UI copy inline |
| L10 | Local repo 2026-06-06 roadmap continuation state and direct write verification in root `ROADMAP.md` |
| L11 | Local repo 2026-06-06 UI section audit: `ActiveAutomationUi.kt` keeps ViewModel, screen router, dialogs, Tasker import, template picker, action/context editors, and row components together; dialog state uses `remember` rather than `rememberSaveable`; picker/editor dialogs use fixed 420-460 dp heights; Flow canvas is horizontal-scroll lanes; Scene preview caps projected canvas height at 280 dp and uses drag gestures for movement |
| L12 | Local repo 2026-06-06 platform/incoming-intent audit: `AutomationService.kt` starts foreground in `onCreate`, returns `START_STICKY`, uses `specialUse` plus conditional `location`, and has no `onTimeout`; `BootReceiver.kt` directly starts the service on boot without guarded failure telemetry; `MainActivity.kt` guards app-start service launch; `AutomationTargetReceiver.kt` is signature-permission-protected and caps variables; Locale host uses explicit components and 16 KB bundle JSON; Locale request-query receiver is dynamically exported; NFC paths use typed tag parcelables; Tasker XML import is size-bounded and disables external entities |
| L13 | Local repo 2026-06-06 setup/inspector/run-log audit: `PermissionOnboardingScreen.kt` covers notification, exact alarm, battery optimization, usage, notification listener, calendar, overlay, location, WiFi, Bluetooth, SMS, DND, Shizuku, Termux, and app visibility setup but not live service/boot/FGS/channel health; `ContextInspectorScreen.kt` and `ContextInspector.kt` expose source health and profile match reasons but no Quick Settings source or setup-row linking; `RunLogEntry.kt`, `RunLogDao.kt`, `RunLogDiagnostics.kt`, `TaskRunner.kt`, and `ActiveAutomationUi.kt` show a 100-entry free-form-message log with parsed source/outcome/action traces, filters, and skip handling; `AutomationService.kt` and `AutomationTargetReceiver.kt` duplicate task-run/log writing enough that Quick Settings should first extract shared execution/logging |
| L14 | Local repo 2026-06-06 dependency/release audit: `gradle/libs.versions.toml` pins Gradle stack versions including AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.7, Compose BOM 2026.04.01, Room 2.8.4, WorkManager 2.11.2, Dagger/Hilt 2.59.2, Lifecycle 2.10.0, Navigation 2.9.8, DataStore 1.2.1, Gson 2.14.0; Gradle wrapper is 9.4.1; `app/build.gradle.kts` uses compile SDK 36, target SDK 35, build tools 36.0.0, versionName 0.2.59, versionCode 61, release shrink/minify, env-var signing, F-Droid distribution checks, readiness and metadata verification tasks; `.github/workflows/build.yml` runs tests/assembly/F-Droid readiness/metadata but not lint; `.github/workflows/release.yml` runs lint/tests/F-Droid/release APK build and GitHub release upload but not version/tag/fdroidserver/checksum/provenance gates; `fdroid/metadata/com.opentasker.app.yml` is 0.2.59/code 61 at commit `02e047a01befd08c2bc62bdc29f97e0eb613e3c6`; `docs/FDROID_READINESS.md`, `README.md`, and `CLAUDE.md` still contain stale v0.2.58, Build Tools 35.0.0, Kotlin 2.0, Gradle 8.9, or old workflow references |

### Direct Android automation and plugin sources

| Tag | Source | URL |
|---|---|---|
| S1 | Easer repository | https://github.com/renyuneyun/Easer |
| S2 | Easer F-Droid listing | https://f-droid.org/packages/ryey.easer/ |
| S48 | Easer GitHub issues (last release April 2022, Android 15 incompatibility threads) | https://github.com/renyuneyun/Easer/issues |
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
| S55 | Tasker Quick Settings setup action | https://tasker.joaoapps.com/userguide/en/help/ah_set_quick_setting.html |
| S56 | MacroDroid Quick Settings tile trigger | https://macrodroidforum.com/wiki/index.php/Trigger%3A_Quick_Settings_tile |
| S57 | Automate Quick Settings tile show block | https://llamalab.com/automate/doc/block/quick_settings_tile_show.html |
| S61 | Home Assistant Android Quick Settings integration | https://companion.home-assistant.io/docs/integrations/android-quick-settings/ |
| S62 | MacroDroid Quick Settings tiles setup thread | https://www.macrodroidforum.com/index.php?threads%2Fquick-settings-tiles-setup.8938%2F= |
| S63 | MacroDroid Quick tiles trigger/config refresh thread | https://www.macrodroidforum.com/index.php?threads%2Fquick-tiles-trigger-fails.3973%2F= |
| S64 | Home Assistant Android issue: Android 16 OriginOS Quick Settings tiles not working | https://github.com/home-assistant/android/issues/6046 |
| S65 | Tasker/AutoNotification tile label update thread | https://groups.google.com/g/tasker/c/PTwbVjGLWbY |
| S66 | MacroDroid Configure Quick Tile state refresh thread | https://www.macrodroidforum.com/index.php?threads%2Fconfigure-quick-tile.4621%2F= |

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
| S43 | Android 16 behavior changes (legacy May source tag; superseded for FGS timeout details by S49-S53) | https://developer.android.com/about/versions/16/behavior-changes-all |
| S44 | Predictive back gesture for Compose | https://developer.android.com/develop/ui/views/animations/predictive-back/predictive-back-jetpack |
| S45 | Health Connect Android API (no real-time triggers, polling only) | https://developer.android.com/health-and-fitness/guides/health-connect |
| S46 | Quick Settings `TileService` reference | https://developer.android.com/reference/android/service/quicksettings/TileService |
| S47 | Macrobenchmark and Baseline Profiles | https://developer.android.com/topic/performance/baselineprofiles/overview |
| S49 | Foreground service timeout behavior (`dataSync` and `mediaProcessing`) | https://developer.android.com/develop/background-work/services/fgs/timeout |
| S50 | Android 15 foreground-service type changes | https://developer.android.com/about/versions/15/changes/foreground-service-types |
| S51 | Google Play foreground-service and full-screen intent declaration requirements | https://support.google.com/googleplay/android-developer/answer/13392821 |
| S52 | Android 16 behavior changes for apps targeting Android 16 or higher | https://developer.android.com/about/versions/16/behavior-changes-16 |
| S53 | Android 16 behavior changes for all apps | https://developer.android.com/about/versions/16/behavior-changes-all |
| S54 | Android Quick Settings tile UX and implementation guide | https://developer.android.com/develop/ui/views/quicksettings-tiles |
| S58 | Health Connect data types and additional background/history read permissions | https://developer.android.com/health-and-fitness/health-connect/data-types |
| S59 | Health Connect raw data reads, background reads, and 30-day history limit | https://developer.android.com/health-and-fitness/health-connect/read-data |
| S60 | Baseline Profile measurement with Macrobenchmark | https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile |

### Dependency and build sources

| Tag | Source | URL |
|---|---|---|
| S37 | Kotlin releases | https://kotlinlang.org/docs/releases.html |
| S38 | Android Gradle Plugin releases | https://developer.android.com/build/releases/gradle-plugin |
| S39 | Dagger/Hilt releases | https://github.com/google/dagger/releases |
| S40 | WorkManager releases | https://developer.android.com/jetpack/androidx/releases/work |
| S41 | Compose releases | https://developer.android.com/jetpack/androidx/releases/compose |
| S42 | Google WiFi API issue | https://issuetracker.google.com/issues/128554616 |
| S67 | Gradle releases | https://gradle.org/releases |
| S68 | Android Gradle Plugin 9.2 release notes and compatibility table | https://developer.android.com/build/releases/gradle-plugin |
| S69 | Kotlin releases | https://github.com/jetbrains/kotlin/releases |
| S70 | KSP releases | https://github.com/google/ksp/releases |
| S71 | Room release notes | https://developer.android.com/jetpack/androidx/releases/room |
| S72 | WorkManager release notes | https://developer.android.com/jetpack/androidx/releases/work |
| S73 | Activity release notes | https://developer.android.com/jetpack/androidx/releases/activity |
| S74 | Compose BOM docs | https://developer.android.com/develop/ui/compose/bom |
| S75 | Jetpack Compose April 2026 updates | https://android-developers.googleblog.com/2026/04/jetpack-compose-april-2026-updates.html |
| S76 | Lifecycle release notes | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| S77 | Navigation release notes | https://developer.android.com/jetpack/androidx/releases/navigation |
| S78 | Dagger/Hilt releases | https://github.com/google/dagger/releases |
| S79 | kotlinx.serialization JSON docs | https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/ |
| S80 | F-Droid build metadata reference | https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/ |
| S81 | GitHub Actions workflow artifacts | https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts |

---

## Research-Driven Additions (2026-06-09 engine-correctness and gap-closure pass)

New items from a code-level engine audit plus external platform/policy verification. Checked against N1-N10, X1-X9, RD1-RD10, Under Consideration, and Rejected tiers — no duplicates. Evidence details live in RESEARCH.md (same date).

### P0 — root-cause correctness

### P1 — trust, data safety, distribution

### P2 — hardening and table-stakes surface

### P3 — parity and power-user polish

---

## Research-Driven Additions (2026-06-10 trigger/action coverage and privacy pass)

New items from a trigger/action coverage audit, manifest privacy review, and external parity/integration research. Checked against N1-N10, X1-X9, RD1-RD25, Under Consideration, and Rejected tiers — no duplicates. Evidence details in RESEARCH.md (2026-06-10).

### P1 — trust, privacy, table-stakes actions

### P2 — parity and hygiene



### P3 — integration bets

- [ ] P3 — RD32: UnifiedPush `event=push` trigger
  Why: A FOSS push event source (UnifiedPush connector + user-chosen distributor such as ntfy, self-hostable, sub-1% battery via a single shared connection) gives OpenTasker remote triggering without Google services, polling, or RD10's listening-socket security surface — a leapfrog no automation app ships natively, and perfectly aligned with the F-Droid positioning.
  Evidence: https://unifiedpush.org/users/distributors/ntfy/; https://f-droid.org/en/2022/12/18/unifiedpush.html; no push path exists in the repo; RD10 webhook receiver remains Under Consideration for comparison.
  Touches: `org.unifiedpush.android-connector` dependency (Apache-2.0; verify F-Droid acceptability), a push event bridge following `NfcContextEvents`/`NotificationContextEvents` patterns emitting `event=push` with topic/payload filters (payload size-capped and sanitized in run logs), opt-in Setup row with distributor detection, RD2 shared execution helper for any direct task binding, docs.
  Acceptance: with a distributor installed and a topic configured, an external `curl` to the distributor triggers a profile within seconds with run-log evidence; without opt-in, no connector registration occurs; payload appears redacted per existing sensitive-argument rules.
  Complexity: M (after RD2/shared execution helper; pulse semantics are fixed)

- [ ] P3 — RD34: Native MQTT publish action (subscribe trigger as a follow-up spike)
  Why: Home Assistant community demand for Android-side MQTT is real and currently met only by Tasker's third-party MQTT plugin; an outbound-only `mqtt.publish` action bridges OpenTasker to HA/Node-RED without RD10's inbound listening socket, and a later subscribe trigger could supersede RD10 entirely.
  Evidence: https://community.home-assistant.io/t/setting-up-taskers-mqtt-client-plugin/321777; https://community.home-assistant.io/t/presence-detection-via-tasker-and-mqtt-android-only/21643; no MQTT code in repo.
  Touches: client library decision spike first (Eclipse Paho EPL-2.0 vs. HiveMQ Apache-2.0 — license/APK-size/F-Droid check, recorded under X8 governance), new `mqtt.publish` action in `core/actions/NetworkActions.kt` pattern (TLS default, LAN-plaintext only if RD20's private-range opt-in lands, credential storage via DataStore with run-log redaction), action metadata/capability gating, tests with an embedded broker.
  Acceptance: a task can publish a templated payload to a configured broker/topic with QoS 0/1 and run-log evidence; credentials never appear in logs or exports; the library decision and size delta are documented; subscribe trigger explicitly deferred to a follow-up item.
  Complexity: L

## Research-Driven Additions (2026-06-12 action-truth and plugin-bridge pass)

New items from an action truthfulness, notification lifecycle, plugin interoperability, and package-event research pass. Checked against existing N, X, RD, Under Consideration, and Rejected tiers; no duplicates. Evidence details in RESEARCH.md (2026-06-12).


- [ ] P3 - RD37: OpenTasker Locale/Tasker plugin target bridge
  Why: OpenTasker can host Locale-compatible plugins, but Tasker/MacroDroid-style ecosystems also expect automation apps to expose plugin targets that other tools can discover and invoke. A guarded plugin target bridge would let users call approved OpenTasker tasks from Tasker, MacroDroid, and other Locale-compatible hosts without weakening the existing signature-only external intent boundary.
  Evidence: `LocalePluginHost.kt` implements host-side plugin dispatch/query; `EXTERNAL_INTENTS.md` keeps direct external execution signature-scoped; Tasker plugin documentation defines Locale-compatible plugin actions; MacroDroid documents Locale/Tasker plugin actions.
  Touches: manifest plugin target declarations, edit/config Activity or Compose bridge, explicit user consent token, RD2 shared execution helper, Run Log source `locale_plugin_target`, docs, adb/instrumentation harness.
  Acceptance: Tasker/MacroDroid can add an "OpenTasker: Run task/profile" plugin action through the Locale edit flow; fired plugin actions run only user-approved task/profile IDs with bounded extras and consent revocation; all invocations record source, caller, target, and result in Run Log; denied or stale plugin payloads fail closed; tests cover edit flow serialization, fire flow execution, revoked consent, malformed extras, and package/caller attribution.
  Complexity: L

## Research-Driven Additions (2026-06-12 exhaustive research pass)

New items from an exhaustive research pass covering 5 parallel research agents (OSS competitors, Android platform, commercial competitors, community signals, dependency/security), full repo code review, and 50+ external sources. Each item was checked against every existing N, X, RD, Under Consideration, and Rejected tier — no duplicates. Evidence details in RESEARCH.md (2026-06-12).

### P2 — hardening and hygiene

## Audit follow-ups (engineering/UX/theming pass)

Items found during a deep audit that need human decision, on-device verification, or implementation work beyond an audit pass.

- [ ] P2 — On-device verification of the completed light (Catppuccin Latte) theme
  Why: The light scheme was completed with full container/outline tokens, but this environment has no emulator. Verify contrast and surface layering across every mode and nested surface (modals, popovers, dropdowns, run-log/scene/flow cards, toasts) on a device before claiming theme parity.
  Where: app/src/main/java/com/opentasker/ui/theme/Theme.kt, all ui/screens/*

- [ ] P2 — Manual theme toggle + persistence + high-contrast mode
  Why: The app follows the system theme only (OpenTaskerTheme reads isSystemInDarkTheme with no override); there is no user-selectable light/dark/high-contrast preference or persistence. A persisted DataStore-backed theme setting plus a high-contrast scheme is premium-table-stakes.
  Where: app/src/main/java/com/opentasker/ui/theme/Theme.kt, Setup screen

- [ ] P3 — Adopt DesignSystem spacing/radius/elevation tokens across screens
  Why: DesignSystem defines Spacing/Radii/Elevation/ComponentSize scales but the UI hardcodes dp values throughout, so the design system is decorative and spacing/radius drift is unguarded.
  Where: app/src/main/java/com/opentasker/ui/theme/DesignSystem.kt, all ui/screens/*

- [ ] P3 — Declare android:allowBackup explicitly and confirm backup privacy posture
  Why: allowBackup is implicit (defaults true); backup rules correctly exclude the DB + shared prefs, but user_files/ and downloads/ written by file actions are still eligible for backup/transfer. Decide the privacy posture for a privacy-first app and declare the flag explicitly.
  Where: app/src/main/AndroidManifest.xml, app/src/main/res/xml/backup_rules.xml, data_extraction_rules.xml

- [ ] P3 — Add a match-timeout to user-authored regex template/variable ops
  Why: regex/replace ops bound pattern and input length but a crafted user pattern (e.g. nested quantifiers) can still backtrack on the bounded input. A wall-clock match timeout would fully neutralize ReDoS from self-authored automations.
  Where: app/src/main/java/com/opentasker/core/engine/variables/VariableExpander.kt

## Research-Driven Additions (2026-06-14 exhaustive research pass)

Items below come from a fresh code audit (all prior P0/P1 bugs verified fixed) plus external platform/competitor/community research. Each was checked against every existing N, X, RD, P, Under Consideration, and Rejected tier — no duplicates. Strategic gaps already tiered elsewhere (AI authoring → Under Consideration; UnifiedPush → RD32; Shizuku → X3; large-screen → N10) are discussed in RESEARCH.md, not duplicated here. Evidence details in RESEARCH.md (2026-06-14).

### P1 — platform deadlines and trust

- [ ] P1 — Gate background TTS/sound actions behind a while-in-use FGS path (Android 17)
  Why: Android 17 makes background audio fail *silently* unless the foreground service holds a while-in-use capability; `AutomationService` declares `specialUse|location`, whose WIU is granted only while location is actively in use — so newly-shipped TTS/sound actions can no-op when fired from a non-location profile.
  Evidence: https://developer.android.com/about/versions/17/changes/bg-audio ; tts/sound impls in app/src/main/java/com/opentasker/core/actions/MediaActions.kt and BuiltInActions.kt ; foregroundServiceType="specialUse|location" in app/src/main/AndroidManifest.xml:102.
  Touches: AndroidManifest.xml (FGS type / optional mediaPlayback path), AutomationService.kt, MediaActions.kt, BuiltInActions.kt, ActionCapabilities.kt (honest-failure when WIU unavailable), Android 17 device evidence harness.
  Acceptance: on an Android 17 device, a background-triggered Speak/Play-Sound action either produces audio or records an explicit honest failure in the Run Log (never a silent no-op); capability gating reflects the WIU state; tests cover the gated path.
  Complexity: M

- [ ] P1 — Commit targetSdk 35 → 36 (Play deadline Aug 31, 2026)
  Why: Google Play requires all app updates to target API 36 by Aug 31, 2026; the app compiles SDK 36 but still targets 35. N1/N10 scope the rehearsal — this item is the commitment to actually raise `targetSdk`, ship the edge-to-edge/predictive-back/intent-redirection fallout, and re-verify the F-Droid/Play submission.
  Evidence: https://developer.android.com/google/play/requirements/target-sdk ; https://support.google.com/googleplay/android-developer/answer/11926878 ; targetSdk = 35 in app/build.gradle.kts ; predictive back already shipped (commit 9b7021c).
  Touches: app/build.gradle.kts (targetSdk), AndroidManifest.xml, edge-to-edge insets in ui/screens/*, intent-redirection hardening, fdroid/metadata, CI release gates.
  Acceptance: app targets SDK 36, builds debug+release, passes JVM tests + F-Droid readiness, runs on SM-S938B with correct insets and predictive-back in gesture + 3-button modes; fdroid metadata and version strings updated.
  Complexity: M (depends on N1/N10 rehearsal)

### P2 — accessibility, testing, and state hardening

- [ ] P2 — Screen-reader / TalkBack accessibility pass
  Why: ~16 icon-only IconButtons pass `contentDescription = null` and there is no `Role`/`stateDescription` outside PremiumComponents.kt, so TalkBack users cannot identify or operate action/context row controls — a table-stakes trust gap the engine work skipped.
  Evidence: contentDescription=null at app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt:1556,1561,1568,1680,1685,1693,1698,1704 (and add/nav icons); semantics only in app/src/main/java/com/opentasker/ui/components/PremiumComponents.kt.
  Touches: ui/screens/ActiveAutomationUi.kt, ui/screens/*, ui/components/*, strings.xml (label resources, dovetails with X9 i18n).
  Acceptance: every interactive icon-only control has a meaningful contentDescription; toggles expose Role.Switch + stateDescription; a manual TalkBack sweep of Profiles/Tasks/Actions/Contexts/RunLog reaches and announces every control; an accessibility-checks instrumentation test passes on core screens.
  Complexity: M

- [ ] P2 — Unit tests for action implementations
  Why: every package except the action layer has JVM tests; NetworkActions/FileActions/SettingsActions/MediaActions/SystemActions/AppActions/BuiltInActions hold the security guards (byte caps, scheme allowlist, timeouts) yet have zero coverage, so regressions in those guards are invisible.
  Evidence: no app/src/test/java/com/opentasker/core/actions/ directory exists; guards live at FileActions.kt:25, NetworkActions.kt:44/168, AppActions.kt:85, BuiltInActions.kt:232.
  Touches: new app/src/test/java/com/opentasker/core/actions/* with a fake ActionContext (Robolectric or pure where possible).
  Acceptance: tests cover the file-read cap, HTTP-response/download byte bounds, OpenUrl scheme allowlist (including blocked schemes), flow.wait cap, and notification-id allocation; CI runs them in :app:testDebugUnitTest.
  Complexity: M

- [ ] P2 — Config-change and process-death state retention (rememberSaveable)
  Why: 0 `rememberSaveable` vs 98 `remember` across ui/ means editor drafts, dialog state, and filter selections are lost on rotation, multi-window resize, or background process death — increasingly visible as target SDK 36 brings edge-to-edge/large-screen behavior.
  Evidence: grep across app/src/main/java/com/opentasker/ui/ shows zero rememberSaveable; concrete loss sites are the action/context editor dialogs and Run Log filters in ActiveAutomationUi.kt.
  Touches: ui/screens/ActiveAutomationUi.kt and any dialog/editor state holders; add Saver implementations for non-primitive draft state.
  Acceptance: rotating or resizing the action editor, context editor, and Run Log filter preserves in-progress input and selection; a process-death simulation (developer-options "don't keep activities") retains editor drafts.
  Complexity: M

### P3 — parity quick wins and hygiene

- [ ] P3 — Wake-on-LAN action
  Why: a magic-packet `wol` action is a repeatedly-requested, low-effort automation primitive (Tasker feature-request board) that fits the existing network-action pattern and the home-automation persona, and no live FOSS competitor ships it cleanly.
  Evidence: https://tasker.helprace.com/s1-general/ideas/accepted (Wake-on-LAN, 8 votes); no WoL/magic-packet code in repo.
  Touches: app/src/main/java/com/opentasker/core/actions/NetworkActions.kt (UDP broadcast magic packet), ActionMetadata.kt, ActionCapabilities.kt, tests.
  Acceptance: a task with a configured MAC (and optional broadcast IP/port) sends a valid magic packet, logs success/failure, and is covered by a packet-construction unit test.
  Complexity: S

- [ ] P3 — Date/time format template function (getDateFormatted parity)
  Why: the template engine has no date/time formatting function, a concrete Tasker-import parity gap and a top migrant request (`getDateFormatted`) that causes imported-task failures when date logic is involved.
  Evidence: applyFunction dispatcher at app/src/main/java/com/opentasker/core/expressions/TemplateExpressionEngine.kt:373 has no date function; https://tasker.helprace.com/s1-general/ideas/accepted (getDateFormatted, 10 votes).
  Touches: TemplateExpressionEngine.kt (new bounded `date`/`format` function with explicit pattern allowlist), docs, tests; map the Tasker function during XML import in core/transfer/.
  Acceptance: `{{ now | date:'yyyy-MM-dd HH:mm' }}` (or equivalent) expands deterministically with a bounded pattern set; invalid patterns fail closed with a warning; tests cover format, default, and rejection cases.
  Complexity: S

- [ ] P3 — Passive (battery-friendly) location listener mode
  Why: the FOSS location source uses active GPS/network provider updates; a passive-provider listener that piggybacks on other apps' fixes is an explicit top community request for geofencing without constant GPS drain, and complements N2 durability work.
  Evidence: https://tasker.helprace.com/s1-general/ideas/accepted (passive location listeners, 8 votes); active-provider cadence in app/src/main/java/com/opentasker/core/contexts/LocationContextSourceImpl.kt.
  Touches: LocationContextSourceImpl.kt (PASSIVE_PROVIDER registration + opt-in toggle), Location context editor, Context Inspector cadence copy, docs.
  Acceptance: a Location context can opt into passive mode; with another app requesting fixes, the profile still matches from passive updates; setup/inspector copy explains the battery/latency tradeoff; behavior is covered by a source-contract test.
  Complexity: S/M

- [ ] P3 — Camera-in-use / microphone-recording trigger and constraint
  Why: privacy-automation primitives (auto-DND while recording, log camera use) that MacroDroid paywalls; Android's `AppOpsManager` active-op watcher (OP_CAMERA / OP_RECORD_AUDIO) provides them with no cloud and no accessibility dependency — a FOSS-aligned differentiator.
  Evidence: MacroDroid 2026 Camera-In-Use trigger + Microphone-Recording constraint (competitor research); AppOpsManager.startWatchingActive (platform API); no camera/mic-use code in repo.
  Touches: a new event bridge following core/contexts/NotificationContextEvents.kt pattern emitting `event=camera`/`event=mic`, opt-in Setup row, ProfileMatcher wiring, capability gating, docs, tests.
  Acceptance: with the watcher enabled, starting/stopping camera or mic in any app fires the context within seconds and is observable in the Context Inspector; without opt-in no watcher registers; run-log evidence present.
  Complexity: M

- [ ] P3 — Scope LAN cleartext via Network Security Config (Android 17 usesCleartextTraffic deprecation)
  Why: the manifest declares neither `usesCleartextTraffic` nor `networkSecurityConfig` while a LAN/private-host HTTP opt-in exists; Android 17 deprecates `usesCleartextTraffic`, so a scoped Network Security Config is the forward-compatible way to permit LAN cleartext without exposing all hosts.
  Evidence: https://developer.android.com/about/versions/17/behavior-changes-17 (usesCleartextTraffic deprecation); no networkSecurityConfig in app/src/main/AndroidManifest.xml; LAN HTTP opt-in in core/actions/NetworkActions.kt (commit e693037).
  Touches: app/src/main/res/xml/network_security_config.xml (new), AndroidManifest.xml, NetworkActions.kt comments, docs.
  Acceptance: cleartext is permitted only to private/LAN address ranges via Network Security Config; public-host cleartext stays blocked; the LAN opt-in behavior is unchanged and documented.
  Complexity: S

