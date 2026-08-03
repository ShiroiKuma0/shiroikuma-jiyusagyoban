# OpenTasker Roadmap

**Current app version:** 0.2.81
**Last updated:** 2026-08-02

Only open work belongs here; git history and `CHANGELOG.md` are the release record.
Blocked items live in `Roadmap_Blocked.md`.

## Evaluations

- [ ] P3 — Evaluate Glance widget migration
  Why: As of 2026-07-29, widgets use RemoteViews XML; Glance could reduce widget/UI divergence but may add dependency and capability tradeoffs.
  Evidence: `app/src/main/java/com/opentasker/widget/TaskWidgetProvider.kt`; `app/src/main/res/layout/widget_task.xml`; https://developer.android.com/jetpack/androidx/releases/glance
  Touches: widget package, Gradle dependencies, widget tests.
  Acceptance: Recommendation records APK-size impact, missing Glance features, testability gains, and migration steps; no production rewrite happens before the decision is accepted.
  Complexity: S
  **2026-08-02 research note — the answer is "wait", and recording that closes this item.** Glance stable is still 1.1.1 (2024-10-16); 1.2.0 has sat at rc01 since 2025-12-03 with no stable release in eight months, and `glance-wear-tiles` is deprecated in favour of an unshipped `glance-wear-widgets`. Re-open when 1.2.0 is stable.

- [ ] P3 — Evaluate Navigation3 timing
  Why: The app uses Navigation Compose 2.9.8, while Navigation3 is still an alpha-track migration target and the UI split is a higher-priority risk.
  Evidence: `gradle/libs.versions.toml`; https://developer.android.com/jetpack/androidx/releases/navigation3
  Touches: navigation setup, route/state ownership, deep-link/editor-state handling.
  Acceptance: Recommendation states wait/migrate criteria, minimum stable version, expected code movement, and risks to editor state and deep links.
  Complexity: S
  **2026-08-02 research note — the minimum stable version is Navigation3 1.2.0.** Core has been stable since 1.0.0 (2025-11-19, now 1.1.5), but deep links only arrived in 1.2.0-alpha03 (`DeepLinkRequest`, `UriDeepLinkMatcher`) and those factory functions already broke at alpha05; result passing (`ResultEventBus`) is alpha-only. Both are hard requirements for this app's editor-state and deep-link behaviour.

- [ ] P3 — Evaluate bounded accessibility automation as an opt-in advanced module
  Why: Tasker/AutoInput, MacroDroid, and AutoJs6 show demand for tap/read automation, but unbounded accessibility scripting is a high policy and trust risk.
  Evidence: https://github.com/SuperMonster003/AutoJs6; https://support.google.com/googleplay/android-developer/answer/10964491; no `AccessibilityService` entry in `app/src/main/AndroidManifest.xml`
  Touches: manifest/service design, action metadata, setup disclosure, permission UX, policy documentation.
  Acceptance: Threat model and policy review define whether bounded tap/swipe/read actions can ship, what data is never collected, and why arbitrary scripting is excluded.
  Complexity: M
  **2026-08-02 research note — new maintenance-cost evidence for the "no" side, to record in the threat model.** Klick'r issue #599 (Android 15 gesture execution stops randomly) has been labeled `blocked` since 2025-01-16; Key Mapper's Shizuku-backed System Bridge breaks on every wireless-debugging re-prompt (#2026, #2071); AutoJs6 issue #372 is a 7 GB RAM blowup and #407 is the maintainer's indefinite project-suspension notice (2025-05-30).

## Research-Driven Additions (2026-08-02)

Reconciled against the current tree, `Roadmap_Blocked.md`, and all prior passes. Items already tracked elsewhere — execution envelope, API 37 device evidence, background audio, BAL `IntentSender`, `RangingManager` proximity, Health Connect, Weblate, F-Droid reproducibility, TalkBack sweep, Shizuku device evidence, Developer Verification posture — are intentionally not repeated.

### P0 — Data integrity and gate truth

- [ ] P0 — Fail closed when an undo/redo snapshot will not decode
  Why: The restore path writes an undecodable or wrong-entity JSON blob straight into the entity's payload column and reports success, contradicting the fail-closed doctrine every other stored-payload path follows (`e2f8786`).
  Evidence: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:1346-1351,1364-1368,1381-1386` — `decodeFromString<Task>(targetJson).takeIf { it.id == entityId }?.toEntity()` falls back to `current.copy(actionsJson = targetJson)`; `app/src/main/java/com/opentasker/core/storage/TaskDao.kt:43` requires that column to decode as `List<ActionSpec>`, so the ID-mismatch branch writes a whole-Task object into it.
  Touches: `ActiveAutomationViewModel` undo/redo transaction, `EditHistoryDao`, storage decode-result helpers, a new JVM test per entity type.
  Acceptance: An undo/redo whose snapshot fails to decode, or whose decoded id differs from the target, aborts the transaction, leaves the entity untouched, surfaces an error, and is covered by a test for Task, Profile, and Scene; no path writes an unvalidated payload string.
  Complexity: S

- [ ] P0 — Verify every field of `release-truth.json`, and stop the generator regressing it
  Why: The `dependencies` block is generated but never asserted and is already stale, and the generator hardcodes a value the verifier and README disagree with — a release contract with unenforced fields is worse than no contract.
  Evidence: `VerifyReleaseTruthTask.verify()` in `app/build.gradle.kts:633-667` asserts only `application`/`android`/`capabilities` keys; `tools/release-truth.json` claims `kotlin 2.3.21`, `ksp 2.3.7`, `composeBom 2026.05.00` against `gradle/libs.versions.toml`'s `2.4.10`/`2.3.10`/`2026.06.00`; `tools/generate-release-truth.ps1` hardcodes `engineHandledActions = 7` while the truth file and README say 10.
  Touches: `app/build.gradle.kts` (`VerifyReleaseTruthTask`), `tools/generate-release-truth.ps1`, `tools/release-truth.json`, `ReleaseTruthContractTest`.
  Acceptance: Every key in the truth manifest is asserted against a derived source value; `engineHandledActions` is derived from `FlowControl` plus `SUB_TASK_ACTION_ID` rather than hardcoded; regenerating the manifest on a clean tree produces a byte-identical file and the gate passes; mutating any single value fails the gate.
  Complexity: S

### P1 — Honest capabilities, supply chain, and self-diagnosis

- [ ] P1 — Make `flow.try` retry honest across all 74 actions
  Why: The editor offers `max_attempts` (1-5) and `backoff_ms` on every try block, but retry is gated on `retrySafety == IDEMPOTENT` and only 1 of 74 actions declares it — a user who sets 3 attempts around `ping`, `download`, `mqtt.publish`, `wol`, or `data.read` gets one attempt and no explanation.
  Evidence: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:358-360`; `app/src/main/java/com/opentasker/core/engine/Action.kt:51` (`NEVER` default); `app/src/main/java/com/opentasker/core/actions/HttpRequestAction.kt:184` is the only override.
  Touches: every action file, `Action.kt`, `ActionMetadata.kt` (try-block field copy), `TaskRunner`, action capability tests.
  Acceptance: Each of the 74 actions carries a reviewed `retrySafety`; the try-block editor states which enclosed actions will actually retry; a non-retryable failure inside a try records that reason in the run log rather than silently skipping the retry branch; a source guard fails when a new action omits the classification.
  Complexity: M

- [ ] P1 — Replace trust-on-first-fetch dependency verification with independent verification
  Why: All 1396 checksums carry `origin="Generated by Gradle"` and `<verify-signatures>false</verify-signatures>`, so the metadata pins whatever was downloaded rather than what upstream published — a first-fetch compromise would be recorded as trusted forever.
  Evidence: `gradle/verification-metadata.xml:3-5` and every `<sha256 … origin="Generated by Gradle"/>` entry across 824 components; https://docs.gradle.org/current/userguide/dependency_verification.html
  Touches: `gradle/verification-metadata.xml`, `tools/verify-local-release.ps1`, release documentation.
  Acceptance: `verify-signatures` is `true` with an explicit `<trusted-keys>` set for the signed subset; checksum-only entries are cross-checked against upstream-published `.sha256`/`.asc` files and their `origin` records that provenance; the gate fails if any entry reverts to a Gradle-generated origin; no blanket `trusted-artifacts` entry is introduced.
  Complexity: M

- [ ] P1 — Detect and name inter-profile causal loops
  Why: `MAX_SUBTASK_DEPTH` only guards nested `task.run`; a profile whose action satisfies another profile's context starts a fresh depth-0 run, so an A→B→A cycle appears only as an unexplained admission-controller storm with no named cause. CHI 2019 classifies this as the Infinite Loop bug; Node-RED solves it with a hard, uncatchable loop counter.
  Evidence: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:475`; `app/src/main/java/com/opentasker/core/engine/ExecutionAdmissionController.kt:29-35`; https://dl.acm.org/doi/10.1145/3290605.3300782; https://nodered.org/docs/user-guide/handling-errors
  Touches: `ExecutionEnvelope` (causal parent and depth), `ProfileMatcher`, `AutomationService` dispatch, run-log terminal reasons, `ContextInspector` explanation, engine tests.
  Acceptance: An execution carries a causal parent and depth; exceeding a fixed depth aborts with a terminal reason naming the participating profiles; the Run Log and Diagnostics show the cycle rather than a generic rate-limit skip; a test drives a two-profile cycle and asserts it is named and stopped.
  Complexity: M

- [ ] P1 — Correlate engine downtime with `ApplicationExitInfo`
  Why: `EngineHeartbeatStore` records *that* the engine stopped but nothing records *why*; OEM process killing is the most-cited failure in the category and is endorsed as such by Tasker's own author. There are zero occurrences of `ApplicationExitInfo` in the tree.
  Evidence: `app/src/main/java/com/opentasker/core/diagnostics/EngineHealthReader.kt`; https://developer.android.com/reference/android/app/ApplicationExitInfo; https://dontkillmyapp.com/; Android 17 adds a `"MemoryLimiter:AnonSwap"` description (https://developer.android.com/about/versions/17/behavior-changes-all)
  Touches: `EngineHealthReader`, `EngineHeartbeatStore`, `DiagnosticsScreen`, diagnostic export, JVM tests with a fake exit-reason source.
  Acceptance: On startup the engine pairs each heartbeat gap with a historical exit record (API 30+ guarded) and Diagnostics shows a first-class row naming the reason, timestamp, and gap duration; below API 30 the row states the platform cannot report it rather than implying health.
  Complexity: M

- [ ] P1 — Reconcile missed triggers against expected fire times
  Why: `EngineWatchdogWorker` checks liveness but nothing compares expected next-fire times against actual fires, so a Doze or OEM-kill gap leaves no evidence and the user learns nothing. This is the observable half of the OEM problem no FOSS competitor answers.
  Evidence: `app/src/main/java/com/opentasker/core/engine/EngineWatchdogWorker.kt`; `app/src/main/java/com/opentasker/automation/scheduler/TimeEventScheduler.kt`; https://developer.android.com/topic/performance/power/power-details
  Touches: a persisted expected-fire ledger, `TimeEventScheduler`, `EngineWatchdogWorker`, Run Log, Setup remediation copy, scheduler tests.
  Acceptance: Every scheduled trigger persists its expected next fire; the watchdog records each overdue trigger as a first-class missed-trigger entry with the elapsed delay and the standby bucket at the time, then points at the specific setting that would have prevented it; a test simulates a gap and asserts the miss is recorded exactly once.
  Complexity: M

- [ ] P1 — Extend reference safety to variables
  Why: `AutomationReferenceIndex.OwnerKind` covers `PROFILE, TASK, SCENE` but not `VARIABLE`, so renaming or deleting a variable silently breaks every `%var` and `{{ }}` reference to it. This is the fourth-most-requested Tasker feature (41 supporters) and the machinery already exists.
  Evidence: `app/src/main/java/com/opentasker/core/references/AutomationReferenceIndex.kt:24`; `app/src/main/java/com/opentasker/core/references/AutomationReferenceRewriter.kt`; https://tasker.helprace.com/s1-general/ideas/top
  Touches: `AutomationReferenceIndex`, `AutomationReferenceRewriter`, `VariablesScreen`, `VariableNamePolicy`, template and legacy expansion scanning, reference tests.
  Acceptance: Renaming a variable rewrites every reference in action arguments, conditions, scene bindings, and templates atomically; deleting one that is still referenced is blocked with the referencing sites listed; local/global scope normalisation is preserved; tests cover both expansion syntaxes.
  Complexity: M

- [ ] P1 — Publish `docs/`, unpublish the raw research transcript
  Why: `.gitignore`'s blanket `*.md` leaves the entire `docs/` tree untracked, so README's `docs/EXTERNAL_INTENTS.md` link 404s on GitHub and fifteen useful docs are invisible to contributors and F-Droid reviewers — while `docs/research/raw-research-output.txt` (60,688 bytes) *is* committed and opens with a raw agent transcript header, violating the no-AI-references-in-committed-content rule.
  Evidence: `git ls-files docs` returns only `docs/archive/ui-snapshots/README.md` and `docs/research/raw-research-output.txt`; `README.md:112`; `.gitignore` `*.md` / `!README.md`; `app/build.gradle.kts:755` names `CLAUDE.md` in committed source.
  Touches: `.gitignore` (a `!docs/**/*.md` negation), `git rm --cached docs/research/raw-research-output.txt`, `docs/research/iter-1-roadmap-recommendations.md`, `app/build.gradle.kts` `verifyDocumentationTruth` inputs, `docs/IMPROVEMENT_PLAN.md` (a 9-line stub).
  Acceptance: Every `docs/*.md` referenced by README resolves on GitHub; no tracked file contains an AI-tool reference or agent transcript; a link check over README's relative paths runs in the gate; the historical-doc scan no longer depends on gitignored inputs that are absent on a clean checkout.
  Complexity: S

- [ ] P1 — Single-source the capability, schema, and version literals
  Why: The same four facts are hardcoded in four places that must be edited in lockstep, and the Room schema claim is scraped from a build-script literal rather than the source constant — so a `@Database` bump with a stale literal passes every gate.
  Evidence: `app/build.gradle.kts:641` (`val currentVersion` regex), `:691` (`val currentVersion = 10`), `:763-765` (`actionCount.set(74)`, `contextFamilyCount.set(7)`, `schemaVersion.set(10)`), against `app/src/main/java/com/opentasker/core/storage/AppDatabase.kt:7` (`OPEN_TASKER_DATABASE_SCHEMA_VERSION = 10`).
  Touches: `app/build.gradle.kts` or an extracted `buildSrc` convention plugin, `verifyRoomSchema`, `verifyDocumentationTruth`, `verifyReleaseTruth`, `tools/generate-release-truth.ps1`.
  Acceptance: Action count, context-family count, and Room schema version are each derived from exactly one source location and consumed by every gate; changing `OPEN_TASKER_DATABASE_SCHEMA_VERSION` without exporting the schema fails the build; no verification task contains a hardcoded capability literal.
  Complexity: S

- [ ] P1 — Ratchet the test floor and add coverage measurement
  Why: `verifyJvmTestCount` enforces 522 against 979 actual `@Test` methods, so nearly half the suite could be deleted without tripping the gate, and nothing measures which code those tests reach. Four packages have zero tests.
  Evidence: `app/build.gradle.kts:567-573` (`minimumTests.set(522)`); no test package for `core/scheduling`, `core/resilience`, `automation/receiver`, or `ui/utils`; no Kover or JaCoCo anywhere in the build.
  Touches: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `tools/verify-local-release.ps1`, new tests for the four uncovered packages.
  Acceptance: The floor tracks the current count with a documented ratchet step; a coverage report is produced by the gate with a per-area floor that fails on regression; `ExactAlarmSupport`, `GracefulDegradation`, and `TimeEventReceiver` have tests.
  Complexity: S

- [ ] P1 — Stop shipping empty locale resources and enable per-app language selection
  Why: Twelve of thirteen locale directories contain only an empty `<resources/>`, `values-es` covers 34 of 1,789 strings (1.9%), and with no `localeConfig` the Android 13+ per-app language picker is unavailable — so the APK advertises thirteen languages and delivers one.
  Evidence: `app/src/main/res/values-{ar,de,fr,hi,it,ja,ko,pl,pt-rBR,ru,tr,zh-rCN}/strings.xml` are empty; `app/src/main/res/values-es/strings.xml` has 34 strings; `app/src/main/res/values/{strings,action_catalog_strings,dynamic_surface_strings}.xml` total 1,789; no `android:localeConfig` in `app/src/main/AndroidManifest.xml`.
  Touches: `AndroidManifest.xml`, `app/build.gradle.kts` (`androidResources { generateLocaleConfig = true }`), locale resource directories, the README translation section, a source guard.
  Acceptance: Only locales above a stated completion threshold ship and appear in the system per-app language picker; empty locale directories are removed or excluded from the release APK; a gate fails when a locale directory exists below the threshold; README's translation instructions match the enforced rule.
  Complexity: S

### P2 — Debuggability, execution semantics, and authoring depth

- [ ] P2 — Park rejected executions as HELD and allow manual replay
  Why: An admission-capacity rejection or circuit trip discards the triggering event entirely, so the app's best safety feature is also silent data loss. Zapier's rule is the right one: retry automatically where safe, but never auto-replay held work.
  Evidence: `app/src/main/java/com/opentasker/core/engine/ExecutionAdmissionController.kt`; https://zapier.com/help/autoreplay/; https://docs.n8n.io/hosting/scaling/execution-data/
  Touches: `ExecutionEnvelope`, `ExecutionAdmissionController`, run-log schema (new terminal state plus stored redacted trigger payload), `RunLogPruneWorker` carve-outs, Run Log UI, Room migration.
  Acceptance: A rejected execution is recorded as `HELD` with its redacted trigger payload and the rejecting policy; the user can replay it, producing a new execution with a `replayOf` link; `RunLogPruneWorker` never prunes held or user-starred runs; replay is covered by a test.
  Complexity: M

- [ ] P2 — Simulate a trigger with a pinned synthetic event
  Why: Manual run and `PreflightRunner` exercise the task, but nothing exercises the *matcher* — context predicates, group logic, cooldown, dwell, and admission — which is where the trigger-misfire bug class actually lives (five separate fixes in history).
  Evidence: `app/src/main/java/com/opentasker/core/engine/PreflightRunner.kt`; `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt`; https://docs.n8n.io/data/data-pinning/
  Touches: an editor-only execution mode flag on the envelope, `ProfileMatcher` synthetic-event injection, context editors, `ContextInspectorScreen`, matcher tests.
  Acceptance: From a profile editor the user can inject a fabricated event of the right shape and see which predicates passed, which blocked, and whether admission would have accepted the run; simulated runs never write production run-log state or fire side effects; every context family supplies a synthetic-event template.
  Complexity: M

- [ ] P2 — Add a per-task debug mode with breakpoints, stepping, and a variable watch panel
  Why: 129 of 190 analysed automation forum threads are debugging and existing tools fix none of them; Tasker has 38 votes and no developer response, Apple Shortcuts has no debugger, Node-RED has none in core. Nobody has shipped one on a phone, and OpenTasker already has per-step traces and a live in-flight view.
  Evidence: https://arxiv.org/abs/2408.04755; https://tasker.helprace.com/i22-improved-debugging; https://www.macstories.net/reviews/logger-for-shortcuts/; the existing per-step inspector in `RunLogScreenContent.kt` and live-execution view in `ActiveAutomationViewModel.kt`
  Touches: `TaskRunner` (pause/step/resume), `ExecutionEnvelope` (debug flag), a debug surface in `ui/screens`, `VariableStore` snapshot exposure, redaction policy, tests.
  Acceptance: Debug mode is opt-in per task, visibly indicated while active, and expires; the user can set a breakpoint on an action, step, and read task/event/global variable values with their source scope and setting step; unredacted values appear only in this elevated state and are never persisted to the run log or diagnostic export; leaving debug mode clears the capture. Resolve the debug-capture boundary question in `RESEARCH.md` before building.
  Complexity: L

- [ ] P2 — Lint automations against the trigger-action bug taxonomy
  Why: CHI 2019 measured ten TAP bug classes, eight of which impaired user prediction; four are statically decidable against structures already in the tree, and no automation product ships this.
  Evidence: https://dl.acm.org/doi/10.1145/3290605.3300782; `app/src/main/java/com/opentasker/core/flow/AutomationFlowGraph.kt`; `app/src/main/java/com/opentasker/core/contexts/ContextInspector.kt`
  Touches: a pure lint module, `AutomationFlowGraph`, profile save validation, `ContextInspectorScreen`, import review, lint tests.
  Acceptance: Saving or importing a profile reports Missing Reversal (a persistent-setting enter task with no exit task), Repeated Triggering (a state trigger with no cooldown, dwell, or idempotency guard), Priority Conflict (simultaneously-satisfiable profiles writing the same setting), and inter-profile Infinite Loop; each finding explains the risk and offers the concrete fix; findings are warnings except Priority Conflict at equal priority, which blocks.
  Complexity: M

- [ ] P2 — Add profile priority, activation grace, and automation lifetime
  Why: Three small entity fields close three separately-evidenced gaps: no priority means simultaneous matches resolve nondeterministically (the worst-accuracy TAP bug class), no grace period means state contexts flap (27 supporters on Tasker, marked Planned), and no expiry means "run this until Friday" is impossible (Samsung shipped it in One UI 8.5).
  Evidence: `app/src/main/java/com/opentasker/core/model/Profile.kt:11-24` has none of the three; https://github.com/henrichg/PhoneProfilesPlus/blob/master/docs/ppp_features.md; https://tasker.helprace.com/s1-general/ideas/top; https://www.androidauthority.com/one-ui-8-5-routine-scheduling-3648715/
  Touches: `Profile` entity, Room migration and schema export, `ProfileMatcher`, profile editor, bundle schema and Tasker import mapping, `ContextInspector` copy, tests.
  Acceptance: A profile can declare a priority, a symmetric activate/deactivate grace period, and an expiry (never / on a date / once); the matcher honours all three; the Inspector explains a suppression caused by any of them; bundle export and import round-trip the fields and older bundles migrate to safe defaults.
  Complexity: M

- [ ] P2 — Expose per-profile concurrency limits and make overflow visible
  Why: Admission limits are module constants with no per-profile override and no user-facing surface, so a user hitting the 2-active or 8-burst cap sees a skip they will report as a bug. Home Assistant solves exactly this with per-automation `max` plus `max_exceeded`.
  Evidence: `app/src/main/java/com/opentasker/core/engine/ExecutionAdmissionController.kt:10-16,29-35`; https://www.home-assistant.io/docs/automation/modes/
  Touches: `Profile` entity, Room migration, `ExecutionAdmissionController` wiring, profile editor, run-log terminal reasons, Diagnostics.
  Acceptance: A profile can override its active and burst limits within bounded ranges and choose whether overflow is logged or silent; every rejection records which limit fired and the counts at the time; Diagnostics shows circuit-breaker state with the trip reason and remaining open time.
  Complexity: S

- [ ] P2 — Add a fallback failure task and a structured error object
  Why: `flow.catch` is lexical only, so a failure outside a try block ends the task with nothing else running, and catch handlers receive flat `FLOW_ERROR_*` strings that are hard to compose or test. Node-RED and n8n both prove the scoped-catch plus global-error-handler shape.
  Evidence: `app/src/main/java/com/opentasker/core/engine/FlowStructure.kt`; `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:345-375`; https://nodered.org/docs/user-guide/handling-errors; https://docs.n8n.io/flow-logic/error-handling/
  Touches: `Profile` and global settings (a fallback task reference), `TaskRunner`, `VariableStore` error scope, run-log terminal reasons, the reference index (a fallback task must be delete-safe), tests.
  Acceptance: An unhandled task failure runs an optional per-profile then global fallback task, receiving a structured error with failing action id, index, type, message, attempt count, and originating profile; a successful retry reports success and clears the retrying flag (do not reproduce n8n issue #10763); fallback failures cannot recurse.
  Complexity: M

- [ ] P2 — Offer typed step outputs as inline variable chips in the action editor
  Why: Chaining currently requires the user to know and hand-type a variable name on a phone keyboard — the single largest authoring-UX gap in the survey. vFlow's typed `OutputDefinition`→`InputDefinition` picker and Apple's Magic Variables both solve it, and the change is editor-only: the chip serialises to the existing `{{ }}` text.
  Evidence: `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt`; `app/src/main/java/com/opentasker/core/expressions/TemplateExpressionEngine.kt`; https://github.com/ChaoMixian/vFlow; https://support.apple.com/guide/shortcuts/use-variables-apdd02c2780c/ios
  Touches: `ActionMetadata` (declared outputs with types), `ActionEditorDialogs`, template serialisation, `AutomationFlowScreen` node rendering, metadata tests.
  Acceptance: Each action declares its output variables and their types; a text field offers only type-compatible outputs from earlier steps plus event and global scopes; selecting one inserts a chip that serialises to existing template text and round-trips through storage and bundle export unchanged; hand-typed templates keep working.
  Complexity: L

- [ ] P2 — Render each action as a one-line parameter summary
  Why: An action row currently shows a type and a form preview; Apple treats a grammatical summary sentence as mandatory for every intent because it is what makes multi-step authoring legible on a small screen. The copy is resource-backed already, so it localises for free.
  Evidence: `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt`; `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationLists.kt`; https://developer.apple.com/videos/play/wwdc2024/10210/
  Touches: `ActionMetadata` (a summary string resource with field placeholders), action list rows, `AutomationFlowScreen` nodes, `PreflightReviewDialog`, the localisation source guard, redaction policy for summarised arguments.
  Acceptance: Every action declares a summary template that stays grammatical for any parameter value including empty; task rows, flow nodes, and preflight all render it; sensitive arguments remain masked inside the summary; a source guard fails when an action ships without one.
  Complexity: M

- [ ] P2 — Show a running task as a promoted ongoing notification
  Why: The app already builds `Notification.ProgressStyle` but never requests promotion, so a running automation has no status-bar presence. This is an API-36 platform capability with no FOSS competitor using it.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt:151-155` uses `ProgressStyle` but no `setRequestPromotedOngoing`; no `POST_PROMOTED_NOTIFICATIONS` in `app/src/main/AndroidManifest.xml`; https://developer.android.com/develop/ui/views/notifications/live-update
  Touches: `AndroidManifest.xml`, the `AutomationService` foreground notification, `notify.progress`, capability metadata, a Setup row for `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`.
  Acceptance: On API 36+ a running task can appear as a promoted ongoing chip with short critical text once the user has granted it; eligibility rules (ongoing, content title set, no custom RemoteViews, channel importance) are enforced in code; ineligibility and denial degrade to the current notification with an honest capability state, and below API 36 nothing changes.
  Complexity: S

- [ ] P2 — Deepen scheduling self-diagnosis with API 36/37 signals
  Why: `EngineHealthReader` uses the API 34 singular `getPendingJobReason`; API 36/37 add history and aggregate stats, and API 37 adds profiling triggers for OOM, ANR, and excessive-CPU kills. This is the cheapest available upgrade to "why didn't my profile fire".
  Evidence: `app/src/main/java/com/opentasker/core/diagnostics/EngineHealthReader.kt:166-175`; https://developer.android.com/about/versions/17/features; https://developer.android.com/about/versions/16/behavior-changes-16
  Touches: `EngineHealthReader`, `DiagnosticsScreen`, diagnostic export, the health-signal model, tests with a fake scheduler.
  Acceptance: Where available, Diagnostics reports pending-job reason history and aggregate stats, `WorkInfo.getStopReason()` values including `STOP_REASON_TIMEOUT_ABANDONED`, and the standby bucket expressed as a consequence ("RARE — time triggers may be delayed") rather than an enum; every reader is API-guarded and states unavailability rather than implying health.
  Complexity: M

- [ ] P2 — Arm triggers before first unlock using Direct Boot storage
  Why: Nothing in the tree uses device-protected storage, so after a reboot no automation can arm until the user unlocks — the widely-reported post-reboot dead zone. DataStore has supported this since 1.2.0 stable.
  Evidence: no `createInDeviceProtectedStorage`, `createDeviceProtectedStorageContext`, or `directBootAware` anywhere in `app/src/main`; https://developer.android.com/jetpack/androidx/releases/datastore
  Touches: a device-protected DataStore for a minimal armed-trigger set, `BootReceiver` (`directBootAware`), `AutomationService` startup, `AndroidManifest.xml`, Setup disclosure, tests.
  Acceptance: A bounded, explicitly opt-in set of non-secret triggers arms in the direct-boot phase and fires or queues before first unlock; nothing secret, encrypted, or Room-backed is read before user unlock; Setup states exactly which triggers gain pre-unlock behaviour and which cannot.
  Complexity: M

- [ ] P2 — Duplicate a profile, task, or scene
  Why: Every surveyed competitor has it and it is the fastest way to author a variant; today the only path is export, edit, and re-import.
  Evidence: no clone or duplicate entry point in `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationLists.kt` or `ActiveAutomationViewModel.kt`; present in PhoneProfilesPlus and MacroDroid.
  Touches: `ActiveAutomationViewModel`, list row overflow menus, name-collision handling, the reference index, `EditHistoryDao`, tests.
  Acceptance: Duplicating produces a disabled copy with a distinct name, fresh ids, and no shared mutable references; scene element and task bindings are remapped to the copy; the operation is undoable.
  Complexity: S

- [ ] P2 — Show a semantic diff when an edit is undone, restored, or re-imported
  Why: `EditHistoryEntity` already stores `previousJson` and `nextJson`, so the data for a review UI exists and is unused; re-importing an updated bundle currently overwrites without showing what changes in the user's own profiles, which is Home Assistant's known blueprint defect.
  Evidence: `app/src/main/java/com/opentasker/core/storage/EditHistoryDao.kt:16-18` (`previousJson`, `nextJson`, `isUndone`); https://dl.acm.org/doi/10.1145/3411764.3445567; https://github.com/home-assistant/core/issues/123025
  Touches: a pure diff module over decoded entities, the edit-history UI, `ImportReviewDialogs`, `AutomationFlowGraph` changed-node highlighting, diff tests.
  Acceptance: Undo/redo and bundle re-import present a structured diff of added, removed, and changed contexts and actions with sensitive values masked; the flow graph marks nodes whose reachability changed; the diff is derived from decoded entities, never raw JSON text.
  Complexity: M

- [ ] P2 — Become a Locale/Tasker *condition* plugin, not just a setting plugin
  Why: `LocaleSettingEditActivity` and `LocaleSettingFireReceiver` are exported so other hosts can already run OpenTasker tasks, but there is no `EDIT_CONDITION`/`QUERY_CONDITION` component, so Tasker and MacroDroid cannot use OpenTasker's contexts, variables, or profile states as conditions. It is the missing half of an interop surface already half-built.
  Evidence: `app/src/main/AndroidManifest.xml:384-401` (setting plugin present); `EDIT_CONDITION` and `QUERY_CONDITION` appear only inside `<queries>`; https://github.com/twofortyfouram/android-plugin-api-for-locale
  Touches: a new exported edit activity and query receiver under `core/plugins/locale`, `AndroidManifest.xml` (`enforceIntentFilter`), bundle validation and redaction, `docs/LOCALE_PLUGIN_HOST.md`, contract tests.
  Acceptance: A third-party host can configure and query an OpenTasker condition (profile active, variable comparison, context satisfied) and receives satisfied/unsatisfied/unknown correctly; bundles are validated and bounded on the way in; secret variables are never exposed; malformed or oversized bundles fail closed. Note that Android 16 no longer guarantees cross-app ordered-broadcast priority — do not depend on it.
  Complexity: M

- [ ] P2 — Detect implicit URI permission grants before Android 18 enforces them
  Why: StrictMode already catches unsafe intent launches but not the new implicit-URI-grant detector, and the app both receives `ACTION_SEND`/`SEND_MULTIPLE` content URIs and dispatches intents with `GRANT_READ_URI`. Android 17 ships the detector; Android 18 enforces.
  Evidence: `app/src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt:102-114`; the `ShareReceiverActivity` filters in `app/src/main/AndroidManifest.xml`; `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt:516`; https://developer.android.com/about/versions/17/behavior-changes-all
  Touches: `OpenTaskerApp_NoHilt` StrictMode VmPolicy, `IntentDispatch`, share-receiver handling, a source guard.
  Acceptance: Debug builds enable `detectImplicitUriPermissionGrant()` on API 37+; every outbound intent carrying a URI sets the grant flag explicitly; a source guard fails on a URI-bearing intent built without one; the share receiver states in-app when an incoming URI is not readable rather than failing opaquely.
  Complexity: S

- [ ] P2 — Collapse action registration to one declaration site
  Why: Adding one action touches nine files across two unlinked registries, which the commit history shows is the dominant change shape in the repo; metadata key drift between them is a documented past bug class.
  Evidence: `app/src/main/java/com/opentasker/core/RuntimeRegistries.kt` (74 hand-maintained entries) versus `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt` (1267 lines, same string keys, no compile-time link); the v0.2.62 CHANGELOG entry on metadata key drift.
  Touches: the `Action` interface, `ActionRegistry`, `ActionMetadata`, `ActionCapabilities`, `ActionArgumentSensitivity`, `RuntimeRegistries`, the release-truth action-count derivation, all action files.
  Acceptance: An action declares its id, category, fields, capability, retry safety, argument sensitivity, and summary in one place; the runtime registry, editor metadata, and capability contract are derived from it; the action count is computed from that single source; a missing declaration is a compile error rather than a drift bug.
  Complexity: L

- [ ] P2 — Toolchain hygiene and the Kotlin build-cache advisory
  Why: Kotlin 2.4.10 is affected by CVE-2026-53914 (unsafe deserialization in build-cache metadata, fixed in 2.4.20, currently Beta2), and Gradle, AGP, and the Compose BOM are each behind with no blocking constraint recorded. `CLAUDE.md` gotcha #9 still documents an AGP 8.9.1 / Compose BOM 2025.07.00 ceiling that predates two upgrades and names Hilt, which is no longer in the tree.
  Evidence: `gradle/libs.versions.toml`; https://github.com/advisories/GHSA-r937-wjx7-w2jp; Gradle 9.6.1 and AGP 9.3.1 are current as of 2026-08-02; Netty 4.1.93/4.1.110 appear in `gradle/verification-metadata.xml` with 2026 high-severity advisories, confirmed absent from the shipped APK.
  Touches: `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` plus both pinned hashes and `ReleaseTruthContractTest`, `gradle/verification-metadata.xml`, `docs/DEPENDENCY_MODERNIZATION.md`, `CLAUDE.md` gotcha #9.
  Acceptance: The advisory is recorded with its mitigation (no untrusted remote build caches) and an upgrade trigger on Kotlin 2.4.20 stable; Gradle, AGP, and the Compose BOM are current or carry a recorded blocking reason; Netty is excluded or pinned out of the build classpath so OSV output stays clean; gotcha #9 is corrected or deleted.
  Complexity: S

### P3 — Breadth, ecosystem, and distribution

- [ ] P3 — Add the six low-privilege sensor triggers the actively-released FOSS competitor has
  Why: `com.jens.automation2` 1.8.7 ships `deviceOrientation`, `proximity`, `activityDetection`, `speed`, `roaming`, and `tethering`, plus phone-call state; these are the entire trigger-breadth gap against it and none needs a privileged or always-on-microphone grant.
  Evidence: https://f-droid.org/en/packages/com.jens.automation2/; `app/src/main/java/com/opentasker/core/contexts/StateContextSourceImpl.kt` covers none of them. The ambient-noise trigger from that same project is deliberately excluded — see Rejected Ideas in `RESEARCH.md`.
  Touches: `StateContextSourceImpl` or new event sources, `ContextSpec` config, context editors, capability and setup gating, `AndroidManifest.xml` (`ACTIVITY_RECOGNITION`, `READ_PHONE_STATE`), Inspector copy, tests.
  Acceptance: Each trigger is registered with a real runtime source, declares its permission and setup requirement, fails closed with a Setup pointer when ungranted, and appears in the Inspector with live values; each ships with JVM coverage for its matching predicate.
  Complexity: M

- [ ] P3 — Convert guided templates into data-driven blueprints with typed selectors
  Why: Templates are Kotlin code today, so each new one needs code and no user can author one. Home Assistant's blueprint model (metadata plus typed `input` entries whose `selector` both constrains the value and *is* the widget, grouped into collapsible sections) gives an ecosystem shape with no server, and CHI 2016 showed users overwhelmingly duplicate each other's automations rather than author from scratch.
  Evidence: `app/src/main/java/com/opentasker/core/templates/ProfileTemplates.kt`; https://www.home-assistant.io/docs/blueprint/schema/; https://www.home-assistant.io/docs/blueprint/selectors/; https://dl.acm.org/doi/10.1145/2858036.2858556
  Touches: bundle schema (a blueprint block with versioned inputs), a selector-driven editor, `ProfileTemplates`, share manifests, import review, schema migration and tests.
  Acceptance: A blueprint is a bundle with typed inputs (app, Wi-Fi SSID, location, task reference, variable, duration) rendered by selector without per-template UI code; instantiating one creates a disabled profile for review; importing a newer version of an installed blueprint shows the diff against the user's instantiated profiles and never overwrites in place.
  Complexity: L

- [ ] P3 — Split the build into `core/*` and `feature/*` modules
  Why: `ui/screens` holds the four largest files in the repo and roughly 6300 lines of one logical screen split by line count rather than responsibility; it has been split twice and is churning again. Klick'r demonstrates the `core/{common,…}` plus `feature/{backup,notifications,smart-debugging,tutorial}` layout at comparable size, and module boundaries would enforce the layering `docs/ARCHITECTURE.md` describes.
  Evidence: `ActiveAutomationViewModel.kt` 1535 lines, `ActiveAutomationUi.kt` 1496, `PermissionOnboardingScreen.kt` 1301, `ActiveAutomationLists.kt` 1013; 58 combined changes to the top two in the last 200 commits; https://github.com/Nain57/Smart-AutoClicker
  Touches: `settings.gradle.kts`, a convention plugin, package moves, the baseline-profile assertion on `OpenTaskerApp_NoHilt` at `app/build.gradle.kts:723`, all verification tasks.
  Acceptance: Engine, storage, and UI are separate modules with dependency direction enforced by the build; no source file exceeds an agreed line budget for a reason other than responsibility; the full local gate, Room schema export, and both distribution builds pass unchanged; APK content is byte-comparable before and after the split.
  Complexity: XL

- [ ] P3 — Ship an out-of-process plugin SDK with a local plugin index
  Why: It is the only way to reach Tasker-scale breadth without growing the core's permission set or adding a cloud marketplace. Easer proves the AIDL separate-APK model and Smartspacer proves the SDK-plus-curated-index distribution shape. It also makes OpenTasker a fully free Locale host, which is what would let Termux:Tasker's `NonFreeDep` anti-feature be dissolved for that ecosystem.
  Evidence: https://github.com/renyuneyun/EaserOperationPluginExample; https://github.com/KieronQuinn/Smartspacer; https://github.com/KieronQuinn/SmartspacerPluginRepository; Termux:Tasker's fdroiddata entry carries `AntiFeatures: NonFreeDep`
  Touches: a published SDK artifact, a signature- and permission-scoped AIDL or ContentProvider boundary, plugin discovery and capability manifest, import-review-style trust UI, docs, contract tests with a reference plugin.
  Acceptance: A separately-installed plugin APK can register actions and conditions that appear in the pickers with their declared capabilities; the plugin runs in its own process and cannot borrow the host's permissions; the user reviews and pins a plugin's signature before first use; a malformed, oversized, or unresponsive plugin fails closed without affecting the engine; the API is versioned with a stated compatibility policy. Resolve the plugin trust-model question in `RESEARCH.md` first.
  Complexity: XL

- [ ] P3 — Adopt the Home Assistant command vocabulary and ntfy extras naming verbatim
  Why: Both are de-facto standards in the two largest FOSS automation communities, so matching their names means zero adapters. The HA companion app documents 25 notification commands and 15 sensor categories that read as a ready-made spec for a remote-control surface.
  Evidence: https://companion.home-assistant.io/docs/notifications/notification-commands; https://companion.home-assistant.io/docs/core/sensors; https://docs.ntfy.sh/subscribe/phone/; the existing `integration.home_assistant.webhook` action and `event=push` bridge.
  Touches: `AutomationTargetContract` external command names, `PushEventReceiver` extras parsing, `HomeAssistantWebhookAction` payload fields, `docs/EXTERNAL_INTENTS.md`, contract tests.
  Acceptance: OpenTasker's external command names and push extras match the documented HA and ntfy names where semantics align, with every divergence listed and justified in the docs; an ntfy `broadcast` action reaches an OpenTasker trigger with no adapter; existing protocol v2 callers keep working.
  Complexity: M

- [ ] P3 — Supply F-Droid store listing metadata in-repo
  Why: The root `metadata/` directory is empty and there is no `fastlane/` tree, so the F-Droid listing carries no maintained description, screenshots, or per-version changelog — and the community survey found discovery, not features, to be the binding constraint for FOSS automation apps.
  Evidence: `metadata/` is empty and untracked; there is no `fastlane/` directory; `fdroid/metadata/com.opentasker.app.yml` carries only a build recipe and an inline summary; https://f-droid.org/en/docs/Build_Metadata_Reference/
  Touches: `fastlane/metadata/android/en-US/` (title, short and full description, changelogs, screenshots), the screenshot capture process, `fdroid/metadata/com.opentasker.app.yml`, the release checklist.
  Acceptance: A versioned listing with description, feature graphic, and at least four current screenshots is committed and picked up by the F-Droid metadata; a per-version changelog file is produced by the release step; the release gate fails when screenshots predate the last UI-affecting release.
  Complexity: S

- [ ] P3 — Evaluate WorkManager 2.12 execution metrics
  Why: 2.12.0-beta01 adds experimental `WorkMetricsInfo` (worker duration, total runtime, stop-reason counts, explicit retry count, enqueue/start/finish timestamps, configurable retention) plus `setWorkExecutionEventListener` — precisely the observability the run log and watchdog want, for free.
  Evidence: `gradle/libs.versions.toml` pins `work = "2.11.2"`; https://developer.android.com/jetpack/androidx/releases/work
  Touches: a prototype branch only — `libs.versions.toml`, `EngineWatchdogWorker`, `RunLogPruneWorker`, `EngineHealthReader`, Diagnostics.
  Acceptance: The recommendation records what the metrics API adds over the existing heartbeat and health reader, the cost of depending on an experimental API, and a stable-version trigger; the release build stays on 2.11.2 until that trigger is met.
  Complexity: S

- [ ] P3 — Offer an opt-in update check for non-F-Droid installs
  Why: F-Droid installs get updates from F-Droid, but GitHub-release users have no way to learn a release exists; there is no update-check code in the tree.
  Evidence: no `UpdateChecker` or release-feed poll anywhere in `app/src/main`; `README.md` distributes via GitHub Releases and F-Droid.
  Touches: an opt-in setting, a bounded release-feed check reusing the existing HTTP policy, Setup disclosure, `BuildConfig.DISTRIBUTION` gating, tests.
  Acceptance: The check is off by default, absent entirely from the F-Droid build, performs one bounded HTTPS request on an explicit schedule, sends no identifying data, and reports only that a newer version exists with a link — never downloading or installing anything.
  Complexity: S
