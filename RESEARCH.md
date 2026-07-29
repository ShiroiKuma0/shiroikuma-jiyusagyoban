# Research — OpenTasker
Date: 2026-07-29 — replaces all prior research.

## Executive Summary

OpenTasker 0.2.79 is already a substantial local-first Android automation system: 60 registered actions plus seven engine-handled flow actions, seven context families, Room-backed state, Keystore-backed secrets, bounded imports, encrypted backups, diagnostics, and separate F-Droid/Play release policies. Its strongest direction is not a broader action count; it is becoming the automation tool users can trust to explain, preserve, and safely execute complex local workflows. The immediate gaps are import/reference integrity, capability claims that diverge from runtime reality, secret-bearing editor summaries, unbounded external/parallel execution, and state surfaces that report health without freshness.

Top opportunities, in priority order:

1. **[Verified] Make bundle import collision-safe and reference-complete.** `OpenTaskerBundle.kt:396-432` remaps profile/scene task IDs but inserts task action arguments unchanged, and `:403-409` overwrites same-name variables without preserving an existing secret.
2. **[Verified] Make one executable capability contract authoritative.** `ActionCapabilities.kt:33-71` defaults known-but-unlisted actions to `Supported`; `app.kill` is therefore advertised as usable although `AppActions.kt:46-54` always fails, while `screen.timeout` needs unexposed Write Settings access.
3. **[Verified] Centralize task-reference discovery before destructive changes.** `ActiveAutomationViewModel.kt:271-281` checks only profile enter/exit IDs before deletion, not `task.run`, notification-button, or scene tap/long-press references.
4. **[Verified] Redact sensitive action summaries by metadata.** `ActiveAutomationLists.kt:741-767` renders every raw argument, including HTTP `headers` and `authorization` fields defined in `ActionMetadata.kt`.
5. **[Verified] Move long external runs out of `BroadcastReceiver.goAsync()`.** `AutomationTargetReceiver.kt:50-95` waits for the complete task, while Android requires broadcast work to finish in roughly 10 seconds and recommends a service/job for longer work.
6. **[Verified] Add execution-admission and feedback-loop guardrails.** `AutomationService.kt:474-483` launches every `PARALLEL` retrigger without a concurrency ceiling; automation-security research shows that capability chains and feedback interactions need understandable warnings and runtime containment.
7. **[Verified] Make Setup and Diagnostics demand- and freshness-aware.** Permission onboarding presents the platform inventory rather than requirements of enabled automations, while Inspector/health state can remain active or healthy without a fresh observation.
8. **[Verified] Add review and cancellation to staged restore.** `DatabaseBackupManager.kt` validates, journals, snapshots, and rolls back correctly, but `ActiveAutomationViewModel.kt:589-593` stages a selected database immediately for the next restart without a content review or cancel path.
9. **[Verified] Add structured failure recovery.** The engine has `continueOnError`, but no bounded retry/backoff or failure-catch block comparable to Automate and server-side workflow engines.
10. **[Verified] Finish scene media validity and accessibility.** New image elements start with invalid source `"Image"` (`SceneElementDrafts.kt:43`), while the runtime accepts only content/resource URIs and the editor does not require a valid source or an accessible description/decorative choice.

## Product Map

### Core workflows

- **[Verified]** Create profiles from Application, Time, Day, Location, State, Event, and Locale-plugin contexts; bind enter/exit tasks; choose single, restart, queued, or parallel execution.
- **[Verified]** Build ordered tasks from metadata-driven actions and flow markers; expand local/global variables; persist secret globals in Android Keystore-backed storage.
- **[Verified]** Inspect live context state, run logs, traces, diagnostics, and setup status; undo recent entity edits.
- **[Verified]** Import/export OpenTasker JSON and Tasker XML; create encrypted/plain database backups; restore through a validated restart journal.
- **[Verified]** Bridge other tools through Locale-compatible plugins, signature-scoped intents, approved Termux scripts, and a deliberately non-operational Shizuku capability shell.

### User personas

- **[Likely]** Privacy/FOSS users who require offline operation, inspectable behavior, and F-Droid-compatible distribution.
- **[Likely]** Android power users migrating from Tasker, MacroDroid, or Automate who need expressive local automation and honest platform limits.
- **[Likely]** Less-experienced users who depend on templates, setup guidance, readable failures, and safe previews.
- **[Likely]** Integration authors connecting home automation, Locale plugins, Termux, or same-signer companion apps.

### Platforms and distribution

- **[Verified]** Kotlin/Jetpack Compose Android application; min SDK 26, compile/target SDK 37, Java 17, Room schema 8.
- **[Verified]** Standard/F-Droid and Play policy variants; SMS is excluded from the Play manifest. Release verification is intentionally local and produces Play/F-Droid artifacts and SBOM/advisory reports.
- **[Verified]** No account, cloud control plane, server, desktop client, or iOS surface; durable data remains on device unless the user explicitly exports it.

### Key integrations and data flows

- **[Verified]** Context sources → `ProfileMatcher` → `AutomationService` → `TaskRunner`/action registry → redacted `run_logs`.
- **[Verified]** Room stores profiles, tasks, scenes, variables, edit history, and logs; DataStore/preferences hold setup/runtime policy; Android Keystore protects secret-variable material.
- **[Verified]** Storage Access Framework carries bundles and backups; imports are bounded before Room transactions and installed profiles are disabled for review.
- **[Verified]** Locale broadcasts and the external target receiver enter through declared IPC boundaries; Termux runs only allowlisted paths/hashes.

## Competitive Landscape

### Tasker

- **[Verified] Does well:** broad Android action/context coverage, demand-started monitored sources, detailed run-log states/IDs, variable tooling, collision-aware sharing, and a mature plugin/community ecosystem.
- **Learn:** make monitor demand, execution identity, import conflicts, and variable provenance visible; preserve OpenTasker's typed metadata and safer defaults.
- **Avoid:** stringly configuration, dense legacy navigation, and capability growth that depends on opaque plugins or undocumented privilege workarounds.

### MacroDroid

- **[Verified] Does well:** approachable trigger/action/constraint authoring, reusable blocks, templates, local/community sharing, backup, and per-step logging.
- **Learn:** distinguish required setup from optional integrations, surface active macros clearly, and make common automation paths finishable without documentation.
- **Avoid:** making a cloud template market or subscription tier part of the core local workflow.

### Automate

- **[Verified] Does well:** visual fibers, cooperative/parallel execution, explicit Failure Catch, community flows, and graph-shaped control flow.
- **Learn:** add bounded catch/retry semantics and validate complex graphs before expanding direct graph persistence.
- **Avoid:** replatforming the proven list editor around a graph before migration, accessibility summaries, and reference repair are complete.

### vFlow

- **[Verified] Does well:** modern flow-first Android UX and rapid FOSS iteration; 2026-03-04 community feedback calls it promising, praises its easy-to-understand flow UI, and notes incomplete localization.
- **Learn:** visual clarity and low-friction first-flow creation are differentiators even before feature parity.
- **Avoid:** incomplete localization and a breadth race that outpaces reliability or migration guarantees.

### Samsung Modes and Routines

- **[Verified] Does well:** suggested routines, plain-language setup, obvious active-state feedback, and deep OS affordances.
- **Learn:** explain what is active, why it is active, and which missing setup blocks it.
- **Avoid:** OEM-only behavior and opaque rules that cannot be exported, inspected, or reproduced.

### IFTTT

- **[Verified] Does well:** low-friction service connections; paid tiers demonstrate demand for multi-action flows, webhooks, queries/filtering, and multiple accounts.
- **Learn:** integrations and branching are valuable, but OpenTasker should expose them through local, explicit contracts.
- **Avoid:** cloud dependence, subscription gating, and transmitting automation data by default.

### Node-RED

- **[Verified] Does well:** node status, debug sidebar, subflows, deploy validation, reusable libraries, and import handling for unknown nodes.
- **Learn:** make graph validation, runtime status, and partial/unknown imports inspectable before execution.
- **Avoid:** embedding a network server or JavaScript package runtime in a privileged phone automation app.

### n8n and Activepieces

- **[Verified] Do well:** versioned workflows, retries, approval steps, execution observability, templates, and typed/versioned extension pieces.
- **Learn:** version action contracts, retain execution evidence, and add bounded recovery semantics.
- **Avoid:** multi-tenant RBAC, hosted execution, and arbitrary in-process third-party code; those solve a different product.

## Security, Privacy, and Reliability

### Bugs and risks found

- **[Verified, P0] Import can overwrite or declassify a secret variable.** `OpenTaskerBundle.kt:403-409` detects an existing name but still calls `VariableRepository.importVariable`; ordinary bundles omit secret values, so replace-by-name is not a safe default. `VariableSecretStorage.kt:194-199` confirms import writes the supplied secrecy state.
- **[Verified, P0] Imported task-internal references are not remapped.** Tasks are inserted unchanged at `OpenTaskerBundle.kt:396-400`; only profiles and scene elements use `taskIdMap` at `:411-432`. Numeric `task.run` and `notify.show` button task IDs can therefore break or target an unrelated local task.
- **[Verified, P0] Task deletion can leave executable dangling references.** `ActiveAutomationViewModel.kt:271-281` protects profile links only; action and scene reference fields already exist in `ActionMetadata.kt` and `SceneElement`.
- **[Verified, P0] Visible task summaries expose stored credentials.** `ActiveAutomationLists.kt:763` joins unredacted `action.args`; HTTP headers/authorization can consequently appear on screen, in screenshots, and in accessibility output despite runtime-trace redaction.
- **[Verified, P0] Capability status is not total or truthful.** `ActionCapabilities.kt:70-71` treats any known unlisted action as supported; `app.kill` always returns failure, and `screen.timeout` checks `Settings.System.canWrite` although the manifest/setup path does not establish Write Settings access.
- **[Verified, P1] External task execution violates the receiver lifetime contract.** `AutomationTargetReceiver.kt:50-95` holds `goAsync()` until arbitrary task completion; `flow.wait` supports durations far beyond Android's receiver window.
- **[Verified, P1] Parallel retriggers have no global or per-profile admission limit.** `AutomationService.kt:474-483` starts an untracked child for every accepted retrigger; default cooldown is zero. The queued mode's 50-item bound does not protect parallel mode.

### Existing controls worth preserving

- **[Verified]** Keystore/AES-GCM secret storage, provenance-aware runtime redaction, RE2/J regex evaluation, bounded import parsers, same-origin HTTP redirect policy, no-follow file writes, disabled-by-default imported profiles, WAL-safe backup publication, authenticated framed encrypted backups, dependency verification, CycloneDX SBOM, and distribution-specific manifest gates.
- **[Verified]** The 2026-07-29 resolved release-runtime graph scan covered 160 Maven coordinates and returned no OSV advisories. This is a point-in-time result, not a substitute for the existing release gate.
- **[Verified]** The JVM baseline is broad (735 tests), but registry-wide invariants and destructive cross-entity workflows remain the important missing layer.

### Missing guardrails

- A single reference index used by import, deletion, rename, search, risk analysis, and migrations.
- A total action contract joining runtime implementation, metadata, capability/setup requirements, distribution policy, sensitivity, and documentation.
- High-confidence direct-cycle/data-to-external warnings plus runtime concurrency/rate/circuit limits; warnings must explain the chain and allow an explicit override.
- Metadata-aware display redaction that fails closed for unknown sensitive keys and covers text, semantics, previews, exports, and error messages.

### Recovery and rollback needs

- Import review must enumerate every collision/reference rewrite and offer preserve, rename, or explicit replace before one atomic transaction.
- Staged restore needs schema/count metadata, a confirm/cancel state, and surfaced rollback recovery; keep the existing validated journal and pre-restore snapshot.
- Run-log retention changes need an affected-row preview and export/confirm step before irreversible pruning; every retained trace must remain expandable.

## Architecture Assessment

- **[Verified] Reference boundary:** IDs embedded in profiles, action argument maps, notification buttons, and scenes are interpreted independently. Add a typed `AutomationReferenceIndex`/rewriter in `core` and make import/deletion/migration/search consume it.
- **[Verified] Capability boundary:** `RuntimeRegistries.kt`, `ActionMetadata.kt`, `ActionCapabilities.kt`, `AutomationSensitivity.kt`, manifest permissions, and Setup duplicate one action contract. Introduce a registry invariant that fails if an action is missing implementation, metadata, sensitivity, distribution, setup, or honest capability classification.
- **[Verified] Observation boundary:** `ContextInspectorScreen.kt:133-165` starts every source collector in an activity-scoped ViewModel. Share demand-gated source observations with the engine; make Inspector a visible-lifecycle subscriber that reports Loading/Ready/Stale/Error and observation age.
- **[Verified] IPC boundary:** receivers should authenticate/validate and enqueue quickly; a foreground-service/job-owned execution broker should issue an execution ID and own query/cancel/result state. Keep the current signature permission for same-signer calls; a broader ecosystem API would require a bound Binder/AIDL service with revocable package/certificate grants.
- **[Verified] Diagnostics boundary:** `EngineHealthReader` and UI aggregation need one pure reason model with timestamps. Healthy must be impossible while loading, stale, or failed.
- **[Verified] Context model:** flat `invert` and `orGroup` already execute in `ContextSpec`/`ProfileMatcherImpl`; the remaining roadmap work is nested-tree authoring, migration, and explanations—not initial OR/NOT runtime support.
- **[Verified] Database modernization:** Room is schema 8, not schema 5. Adopt `@AutoMigration`/`@Upsert` only where schema JSON proves an additive change; retain explicit migrations for semantic rewrites.
- **[Verified] Documentation drift:** `README.md:39` says 59 registered actions, while `RuntimeRegistries.kt:78-139` registers 60. Generate the documented count/capability table from the authoritative registry or gate it in tests.
- **[Verified] Test gaps:** add golden bundle migrations/collision policies, reference-graph delete/import cases, capability-registry invariants, receiver-to-broker handoff, execution-storm limits, secret-summary semantics, restore cancellation, diagnostics freshness, and invalid/revoked scene-media cases. Preserve local-only release verification; hosted Actions were deliberately removed in commit `3d88f83`.
- **[Verified] Dependency posture:** core versions are current enough for feature work and no advisory forces an emergency upgrade. The existing synchronized batch should target Kotlin 2.4.10/KSP 2.3.10/Compose BOM 2026.06/Lifecycle 2.11/Coroutines 1.11 and replace pre-release `kotlinx.collections.immutable` 0.3.8 with stable 0.5.1, with call-site migration and one rollback commit.
- **[Verified] Accessibility and localization:** the existing hardcoded-English and bounded-accessibility roadmap rows remain valid; do not duplicate them. The new work is limited to secret-safe semantics, state explanations, and scene image descriptions/decorative state.
- **[Verified] Plugin and distribution posture:** existing Locale grant/fixture, Home Assistant, MQTT, UnifiedPush, F-Droid, and Play rows already cover the supported expansion paths. Keep plugins out of process; no new cloud or dynamic-code ecosystem item is justified.
- **[Verified] Scope decisions:** Android remains the only mobile platform; backup/restore and bundle migrations cover offline resilience and migration; the synchronized dependency batch covers upgrade strategy; multi-user/team work remains rejected below.

## Rejected Ideas

- **AI/VLM screen-driving automation — Rejected.** AutoDroid and VisionTasker show research potential, but nondeterministic UI control, sensitive-screen capture, and accessibility-service risk contradict OpenTasker's deterministic, explainable local engine.
- **Downloadable in-process action/module store — Rejected.** Dynamic code loading inside a high-privilege automation app creates a supply-chain boundary the current Locale IPC model intentionally avoids; prefer explicit, versioned out-of-process contracts.
- **Cloud sync, team RBAC, and multi-user collaboration — Rejected.** n8n/IFTTT capabilities solve hosted/team workflows; OpenTasker is a single-device local-first Android tool with explicit exports and encrypted backups.
- **Production AppFunctions integration — Under consideration, not roadmap-ready.** AndroidX AppFunctions was still alpha/private-preview on 2026-07-29; revisit at beta/public ecosystem availability.
- **Hosted CI restoration — Rejected.** The repository deliberately removed hosted Actions and maintains a comprehensive local release gate; re-adding it would contradict current project policy without new evidence.
- **Root/old-target companion APK or hidden-API bypasses — Rejected.** They create Play/F-Droid, maintenance, and trust liabilities. If elevated support advances, implement only the documented Shizuku UserService transport and keep every action fail-closed until verified.
- **Immediate Room 3 or Navigation 3 replatforming — Rejected.** Both add migration risk without fixing a present trust defect; evaluate after stable APIs and after the current data/authoring contracts are covered.
- **Cloud template marketplace — Rejected.** Local share preview, QR/import paths, provenance, and explicit trust review fit the product; hosting, moderation, accounts, and remote code do not.
- **Wear OS, iOS, or desktop clients — Rejected.** No repository or community evidence outweighs the Android-phone backlog; the existing companion-device roadmap item is the bounded cross-device path.

## Sources

### Direct and adjacent open-source projects

- https://github.com/Test-Mobile-Innovations/vFlow
- https://github.com/renyuneyun/Easer
- https://github.com/henrichg/PhoneProfilesPlus
- https://github.com/keymapperorg/KeyMapper
- https://github.com/termux/termux-tasker
- https://github.com/home-assistant/android
- https://github.com/node-red/node-red
- https://github.com/n8n-io/n8n
- https://github.com/activepieces/activepieces
- https://github.com/huginn/huginn

### Commercial products and documentation

- https://tasker.joaoapps.com/userguide/en/
- https://tasker.joaoapps.com/changes/changes6.6.html
- https://www.macrodroid.com/
- https://llamalab.com/automate/doc/flow.html
- https://llamalab.com/automate/doc/block/failure_catch.html
- https://ifttt.com/plans
- https://www.samsung.com/us/support/answer/ANS10002599/

### Community and discovery

- https://www.reddit.com/r/fossdroid/comments/1rkmp4m/has_anyone_tried_vflow_it_looks_very_promising_as/
- https://www.reddit.com/r/androidapps/comments/1s66fs7/whats_the_best_alternative_to_tasker_in_2026/
- https://news.ycombinator.com/item?id=42254433
- https://tasker.helprace.com/i956-better-handling-of-import-project-clash
- https://github.com/timschneeb/awesome-shizuku
- https://github.com/meirwah/awesome-workflow-engines
- https://github.com/guifelix/awesome-tasker

### Standards, platform APIs, security, and research

- https://developer.android.com/develop/background-work/background-tasks/broadcasts
- https://developer.android.com/reference/android/provider/Settings
- https://developer.android.com/training/permissions/requesting
- https://developer.android.com/guide/components/bound-services
- https://developer.android.com/privacy-and-security/security-tips
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/ai/appfunctions
- https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
- https://github.com/RikkaApps/Shizuku-API
- https://mas.owasp.org/MASTG/
- https://www.usenix.org/system/files/usenixsecurity25-zhang-shirley.pdf
- https://arxiv.org/abs/2102.01468
- https://www.usenix.org/conference/soups2023/presentation/mccall
- https://arxiv.org/abs/2308.15272
- https://arxiv.org/abs/2312.11190
- https://assets.temporal.io/durable-execution.pdf

### Dependencies and advisories

- https://developer.android.com/build/releases/gradle-plugin
- https://kotlinlang.org/docs/releases.html
- https://github.com/google/ksp/releases
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://developer.android.com/jetpack/androidx/releases/lifecycle
- https://developer.android.com/jetpack/androidx/releases/room
- https://github.com/Kotlin/kotlinx.coroutines/releases
- https://github.com/Kotlin/kotlinx.collections.immutable/releases
- https://google.github.io/osv.dev/api/
- https://source.android.com/docs/security/bulletin/2026-07-01

## Open Questions

- None.
