# OpenTasker Roadmap

Source-backed product roadmap for OpenTasker v0.2.59 → v0.3.x. Reconciles current repo state with competitive research across Android automation apps, adjacent workflow engines, platform constraints (API 35–36), distribution policy, and dependency changelogs.

**Last updated:** 2026-05-06
**Roadmap version:** 2026.05.06 research pass (post-v0.2.59 reconciliation)
**Current app version:** 0.2.59
**Planning rule:** items marked "Now" must ship before the v0.3.0 public beta claim. Items already completed in 0.2.x are retained in the **Completed Backlog** appendix for traceability and explicitly removed from active tiers.

## Reconciliation note (2026-05-06 pass)

The 2026-05-05 roadmap's `Now (N1–N10)` tier and most of `Next (X1–X12)` shipped across v0.2.2–v0.2.59. They are moved to **Completed Backlog** below and replaced with a fresh `Now` tier targeting v0.3.0 beta criteria. New tier work added from this pass: Android 16 (`specialUse` 6h FGS timeout), Quick Settings Tile trigger/action, predictive-back UX, Macrobenchmark/Baseline Profiles, Health Connect polling trigger (wellness), and broader background-geofence reliability evidence. Sources extended through S48.

---

## State of the Repo

OpenTasker is an Android/Kotlin automation app targeting API 35 with Jetpack Compose, Material 3, Room, Coroutines, WorkManager, DataStore, Gson, and Hilt dependencies. The project goal is a privacy-first, fully on-device, open-source Tasker/MacroDroid/Automate alternative.

The active APK now has a Room-backed Compose management UI for profiles, tasks, action lists, context lists, scenes with text/button/slider/image element editing, scaled canvas previews, drag-to-move layout edits, and tap/long-press task binding pickers, run logs, setup/onboarding status, flow graphs with node deep links into existing editors, conditional action labels, scrollable lane overviews, screen-reader summaries, and picker-backed Add Context/Add Step shortcuts, live context inspection with geofence dwell detail, notification listener event triggers, NFC tag event triggers and a write helper, calendar/sun event triggers with editor presets and adb smoke evidence, day schedule aliases/ranges/presets, a foreground automation service that starts from app launch and boot, a FOSS platform location source with balanced provider cadence and policy-aware setup copy, FOSS geofence radius/accuracy/dwell evaluation with persisted inside-since state and stale dwell-key cleanup, an installable disabled Location evidence template, an adb-backed Location/geofence evidence harness with Room/run-log/logcat assertions plus provider-cadence, unplugged-sample, and post-reconnect unplugged-history gates, API 36 smoke evidence for background Location event delivery, bounded runtime template argument and condition expansion, per-expression template run-log diagnostics, explicit regex template policy, a Tasker XML-to-OpenTasker-bundle migration parser, Locale setting dispatch and condition query actions with explicit receiver targeting, best-effort last-known unknown-state handling, guarded configuration result parsing, request-query event handling, and an adb plugin validation harness, OpenTasker bundle share manifests, a F-Droid readiness build profile with metadata/version gates, local fdroidserver lint/build evidence, and APK payload comparison tooling, centralized Gradle dependency version governance, optional Shizuku readiness status for elevated-action planning, and optional Termux script readiness status for future scripting. Older `.kt.bak` editor snapshots are still not compiled, and advanced capability gating remains required before public beta claims.

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

## Now (v0.3.0 beta gate)

### N1 (2026.05.06) - Android 16 / API 36 readiness pass

**Status:** Not started. Compile SDK is already 36 (v0.2.59) and the API-36 device smoke runs are in place, but no code recognizes the Android 16 `specialUse` 6-hour FGS timeout, FGS-from-background restrictions, exact-alarm denial regressions, or revised foreground-service "user benefit" expectations.
**Description:** Add an Android 16 conformance pass that (a) declares all FGS types used (`specialUse`, `location`, `dataSync`) per active code path, (b) instruments the AutomationService for the 6-hour `specialUse` timeout with graceful self-restart and run-log entry, (c) tightens FGS-start callsites to only run when the app is foreground or when permitted by the FGS-from-background allowlist, (d) adds documented Play Console attestation copy for the `specialUse` declaration in case of dual-track distribution, and (e) extends the device-evidence harness with API-36 timeout-recovery and FGS-start-restriction smoke runs.
**Sources:** Android 15 specialUse timeout/Play attestation [S43], Android 16 behavior changes (FGS, location, exact alarms) [S43], local FGS startup audit [L3], existing Location evidence harness [L6].
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

### N3 (2026.05.06) - Quick Settings Tile trigger and tile action

**Status:** Not started. No `TileService` is registered.
**Description:** Add (a) a `TileService` that publishes a user-configurable Quick Settings tile per profile (label, icon, on/off semantics), bound to the existing intent API as a same-signature trigger; (b) a built-in `tile.set` action that updates a tile's label/icon/state from a task; and (c) onboarding copy explaining the manual "Edit tiles" gesture Android requires. Register only when the user opts in.
**Sources:** Android `TileService` API [S46], Tasker Quick Settings tile feature [S16], MacroDroid tile triggers [S17].
**Category:** platform/OS, UX, integrations.
**Impact:** 4 — Quick Settings is a high-frequency, low-friction trigger surface absent from current OSS alternatives.
**Effort:** 2 — single service + action + small UI.
**Risk:** Low — well-documented stable API since API 24.
**Dependencies:** Capability registry; intent API.
**Novelty:** Parity with Tasker; leapfrog vs Easer.
**Tier reason:** High value-to-effort ratio; the only Now-tier non-platform-compliance item that ships measurable user value in days.

### N4 (2026.05.06) - Predictive back gesture support

**Status:** Not started. Activity does not opt in to `enableOnBackInvokedCallback`; Compose navigation paths use legacy back behavior.
**Description:** Opt the activity into the predictive back callback in `AndroidManifest.xml`, audit Compose `BackHandler`/`OnBackPressedCallback` callsites for forward-compat behavior, ensure editor/picker dialogs and the Setup checklist all handle predicted back without losing in-progress edits, and add a regression smoke for the gesture on API 36.
**Sources:** Predictive back for Compose [S44], Android 15 platform changes [S36].
**Category:** UX, accessibility, platform/OS.
**Impact:** 3 — visible polish; without it the app feels behind on flagship devices.
**Effort:** 2.
**Risk:** Low — the manifest opt-in is the largest single change.
**Dependencies:** None.
**Novelty:** Parity.
**Tier reason:** Cheap polish that closes a visible gap before beta.

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

### N8 (2026.05.06) - Resolve AGP 9 opt-out flags and Hilt-vs-non-Hilt drift

**Status:** AGP 9 migrated to built-in Kotlin/new DSL in v0.2.59 with opt-out flags removed. The `OpenTaskerApp_NoHilt` runtime path remains the active app entry while Hilt modules continue to compile but are bypassed. Improvement plan P10 still flagged "not started".
**Description:** Make a deliberate decision: either complete the Hilt migration (delete `OpenTaskerApp_NoHilt`, route all wiring through `@HiltAndroidApp`, run the existing instrumentation suite) or remove the Hilt module surface entirely until needed. Document the decision and fix any leftover Dagger graph that won't compile under the chosen path.
**Sources:** Improvement plan P10 [L7], Dagger releases [S39], local DI inventory [L1].
**Category:** architecture, dev-experience, testing.
**Impact:** 3 — eliminates a known drift bug class and unblocks future module splits.
**Effort:** 4.
**Risk:** Medium — Hilt-only migration is touch-everything; delete is faster but loses optionality.
**Dependencies:** Dependency modernization (done v0.2.59).
**Novelty:** Maintenance.
**Tier reason:** Drift is cheaper to resolve before Room 3 / Compose Material 3 expressive land.

### N9 (2026.05.06) - Documentation truth pass post-v0.2.59

**Status:** Stale older snapshots removed in v0.2.11; current README still has a single 1700-character status sentence that is hard to skim. CLAUDE.md still references v0.2.58 status.
**Description:** Refresh README, CLAUDE.md, ARCHITECTURE.md, and IMPROVEMENT_PLAN.md to reflect v0.2.59. Restructure the README opening from one mega-sentence into a scannable feature list. Sync version strings (CLAUDE.md, badges, fdroid metadata, gradle properties, ROADMAP, CHANGELOG).
**Sources:** Local repo state [L1][L5], existing improvement plan [L7].
**Category:** docs, dev-experience.
**Impact:** 3.
**Effort:** 2.
**Risk:** Low.
**Dependencies:** None.
**Novelty:** Maintenance.
**Tier reason:** Truthful, skimmable docs are part of the v0.3 beta gate.

---

## Next (post-v0.3 beta, v0.3.x → v0.4)

### X1 (2026.05.06) - Scene editor finishing pass

**Status:** Element creation/editing, drag-to-move, and scaled previews shipped in v0.2.59. Resize handles, multi-select layout edits, alignment guides, and overlay launch (`SYSTEM_ALERT_WINDOW`) remain.
**Description:** Add resize-handle drag, snap-to-grid + alignment guides, multi-select with bulk move/align/delete, then wire scene-launch action through the existing overlay-permission onboarding so a task can show a configured scene. Keep Android 15 background-FGS overlay restrictions in scope.
**Sources:** Tasker scenes [S16], Android 15 overlay/FGS [S36], Android 16 FGS specialUse [S43].
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
**Description:** Add a Health Connect read-only polling trigger using `androidx.health.connect.client`. Subscribe via WorkManager periodic worker (default 30 min; user-tunable from 15 min to 24 h within HC quota), fire `event=healthconnect` Context events for sleep stage transitions, heart-rate threshold crossings, step-goal milestones, and exercise-session end. No write paths. Disclose the Play Health Connect permission requirement in Setup.
**Sources:** Health Connect API + automation triggers (no real-time, polling required) [S45], wellness automation request signal [S17].
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

### X8 (2026.05.06) - Room 3 + AGP 10 prep

**Status:** Room 2.8.4 / AGP 9.2.1 in v0.2.59. Room 3 is alpha-only (`3.0.0-alpha03`); AGP 10 will require dropping the temporary built-in-Kotlin shim that v0.2.59 already removed.
**Description:** Track Room 3 stable. When stable, plan a single migration commit covering both `androidx.room` databases plus migration tests; until then, monitor and document. Track AGP 10 release-candidate compatibility with Hilt/Kotlin/KSP; do not adopt before the Hilt decision in N8.
**Sources:** Room releases [S35], AGP releases [S38], Kotlin releases [S37].
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
| Android 16 / API 36 readiness (specialUse 6h timeout, FGS-start rules) | Platform/OS | Emerging | Now | [S43] |
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

## Self-Audit

- Every roadmap item above has a local evidence tag or source tag.
- No item depends on a competitor feature claim without a URL or local-repo source in the appendix.
- 2026-05-06 rotation: prior `Now (N1–N10)` and `Next (X1–X12)` items have all shipped (v0.2.2 → v0.2.59) and are archived in the **Completed Backlog** appendix; the active `Now`/`Next` blocks are entirely new and target the v0.3.0 beta gate.
- Geofence reliability claims remain explicitly gated to evidence already captured under [L6]; broader OEM/multi-device guarantees are not promised — `N2` exists precisely to gather that evidence before any guarantee is stated.
- Android platform restrictions are treated as product requirements, not implementation details (Android 16 specialUse 6h timeout, FGS-start rules, predictive back, Health Connect lacks real-time triggers — all sourced).
- F-Droid and Google Play constraints are separated because their acceptable dependency/policy profiles differ.
- Rejected items are explicitly preserved so they do not silently return as future churn.
- High-risk ideas (Shizuku, Termux, plugin host) remain in `Next` only because the capability registry, permission onboarding, run-log redaction, and import/export schema landed in v0.2.x and now make them safe to ship.
- Category coverage spot-check: security (N1, N6, N8), accessibility/i18n (N4, X9), observability (N5, [L6]), testing (N5, X4 evidence harness), docs (N9, N2), distribution (N1, N8), plugin (X3 carry-forward, X7), mobile/platform (N1, N3, N4, X5), offline/resilience (N2, X4 local exec), multi-user (Under Consideration only — local-first remains center), migration (X6 v3 expressions, Completed Backlog X10), upgrade (N8, X8). No category is empty.

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
| L6 | Local repo: `tools/collect-location-evidence.ps1`, `docs/FOSS_GEOFENCING.md`, `build/device-evidence/location/*` (single-device API-36 evidence captured during v0.2.36–v0.2.48) |
| L7 | Local repo: `docs/IMPROVEMENT_PLAN.md` (P10 Hilt drift, P12 i18n carry-forward) |

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
| S43 | Android 16 behavior changes (specialUse 6h timeout, FGS-start rules, Play attestation) | https://developer.android.com/about/versions/16/behavior-changes-all |
| S44 | Predictive back gesture for Compose | https://developer.android.com/develop/ui/views/animations/predictive-back/predictive-back-jetpack |
| S45 | Health Connect Android API (no real-time triggers, polling only) | https://developer.android.com/health-and-fitness/guides/health-connect |
| S46 | Quick Settings `TileService` reference | https://developer.android.com/reference/android/service/quicksettings/TileService |
| S47 | Macrobenchmark and Baseline Profiles | https://developer.android.com/topic/performance/baselineprofiles/overview |

### Dependency and build sources

| Tag | Source | URL |
|---|---|---|
| S37 | Kotlin releases | https://kotlinlang.org/docs/releases.html |
| S38 | Android Gradle Plugin releases | https://developer.android.com/build/releases/gradle-plugin |
| S39 | Dagger/Hilt releases | https://github.com/google/dagger/releases |
| S40 | WorkManager releases | https://developer.android.com/jetpack/androidx/releases/work |
| S41 | Compose releases | https://developer.android.com/jetpack/androidx/releases/compose |
| S42 | Google WiFi API issue | https://issuetracker.google.com/issues/128554616 |

