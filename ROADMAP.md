# OpenTasker Roadmap

**Current app version:** 0.2.81
**Last updated:** 2026-08-02

Only open work belongs here; git history and `CHANGELOG.md` are the release record.

## Audit-Discovered Additions (2026-07-17 deep audit)

Findings surfaced by the 2026-07-17 audit that were verified but deferred (the audit's
direct fixes shipped in v0.2.76 across the engine, actions, context, UI, and theming layers).

### P2 — Reliability and product depth

### P3 — Correctness, a11y, and polish

## Research-Driven Additions

### P1 -- Maintainability, accessibility, and localization

### P2 -- Ecosystem integrations and observability

### P3 -- Evaluations

- [ ] P3 — Evaluate Glance widget migration
  Why: As of 2026-07-29, widgets use RemoteViews XML; Glance could reduce widget/UI divergence but may add dependency and capability tradeoffs.
  Evidence: `app/src/main/java/com/opentasker/widget/TaskWidgetProvider.kt`; `app/src/main/res/layout/widget_task.xml`; https://developer.android.com/jetpack/androidx/releases/glance
  Touches: widget package, Gradle dependencies, widget tests.
  Acceptance: Recommendation records APK-size impact, missing Glance features, testability gains, and migration steps; no production rewrite happens before the decision is accepted.
  Complexity: S

- [ ] P3 — Evaluate Navigation3 timing
  Why: The app uses Navigation Compose 2.9.8, while Navigation3 is still an alpha-track migration target and the UI split is a higher-priority risk.
  Evidence: `gradle/libs.versions.toml`; https://developer.android.com/jetpack/androidx/releases/navigation3
  Touches: navigation setup, route/state ownership, deep-link/editor-state handling.
  Acceptance: Recommendation states wait/migrate criteria, minimum stable version, expected code movement, and risks to editor state and deep links.
  Complexity: S

- [ ] P3 — Evaluate bounded accessibility automation as an opt-in advanced module
  Why: Tasker/AutoInput, MacroDroid, and AutoJs6 show demand for tap/read automation, but unbounded accessibility scripting is a high policy and trust risk.
  Evidence: https://github.com/SuperMonster003/AutoJs6; https://support.google.com/googleplay/android-developer/answer/10964491; no `AccessibilityService` entry in `app/src/main/AndroidManifest.xml`
  Touches: manifest/service design, action metadata, setup disclosure, permission UX, policy documentation.
  Acceptance: Threat model and policy review define whether bounded tap/swipe/read actions can ship, what data is never collected, and why arbitrary scripting is excluded.
  Complexity: M

## Research-Driven Additions

### P0 -- Data integrity and runtime truth

### P1 -- Release evidence and device trust

## Research-Driven Additions

### P2 -- Authoring depth, capabilities, and hardening



### P3 -- New triggers, actions, and modernization

## Audit-Discovered Additions

## Research-Driven Additions (2026-07-14 pass)

### P1 -- Variable-runtime correctness (root cause)

### P1 -- Platform-survival compliance

### P2 -- Exported-surface and intent hardening

### P2 -- Engine reliability

### P2 -- Local-first authoring primitives

### P2 -- Data-at-rest and forward-compat

### P3 -- New platform-signal triggers and actions

## Research-Driven Additions

### P0 — Now: data integrity and shipped-runtime truth

### P1 — Next: trust, reliability, and complete authoring

### P2 — Later: observability, precision, and staged modernization

## Research-Driven Additions (2026-07-23 pass)

As of 2026-07-29, the app sets `targetSdk = 37`; the P1 items below are mandatory Android 17 behavior changes that are only partially handled (code-actionable now, no device required to migrate the APIs). Items already present in ROADMAP.md or Roadmap_Blocked.md (clipboard/contacts, share-target, companion-device, USB, screen-recording, app-archive, ProgressStyle, predictive-back, 16 KB alignment, MQTT/UnifiedPush/HA, dry-run preflight, media-active context, global search, AND/OR/NOT groups, live variable inspector, active-execution cancellation, automation-mode test coverage, dependency batch) are intentionally NOT repeated here.

### P1 — Android 17 (target SDK 37) platform survival

### P2 — Observability, debuggability, and reliability


### P3 — New platform triggers and actions no FOSS competitor exposes




## Research-Driven Additions

### P0 — Data integrity and runtime truth

### P1 — Reliability, recovery, and explainability

### P2 — Authoring and resilience depth

## Research-Driven Additions (2026-08-01)

The 2026-08-01 research pass found that the remaining high-value product, resilience, Android 17, import, observability, and authoring opportunities already have actionable entries above. Only the two non-duplicate findings below are appended.

### P1 — Supply-chain integrity

## Research-Driven Additions (2026-08-02)

The 2026-08-02 pass reconciled the current tree, recent commits, Android 16/17 platform guidance, dependency/security sources, competitors, adjacent workflow systems, community signals, and academic research. Existing roadmap items were deduplicated; the additions below are limited to new gaps.

### P1 — Execution truth and release trust

### P2 — Product evidence and maintainability
