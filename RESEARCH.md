# Research — OpenTasker
Date: 2026-08-10 — replaces all prior research.

## Executive Summary

Verified on 2026-08-10: OpenTasker is a local-first, FOSS Android automation app for users migrating from Tasker-like tools who need profiles, context triggers, no-code tasks, device actions, integrations, run evidence, and recoverable local data. Its strongest shape is already trust-oriented: one typed action catalogue, capability and power manifests, bounded imports, encrypted SQLCipher/Keystore storage, staged restore review, held execution replay, process-exit and scheduler diagnostics, redacted run exports, and explicit unsupported states (`README.md`, `app/src/main/java/com/opentasker/core/actions/ActionCatalog.kt`, `core/transfer/OpenTaskerBundle.kt`, `core/engine/TaskExecutionHelper.kt`, `core/storage/DatabaseBackupManager.kt`, `core/diagnostics/DiagnosticExport.kt`). The highest-value direction is to make those trust claims durable across process death, hostile or malformed inputs, recovery, and release reporting rather than prioritizing another broad action list.

Priority opportunities:

1. P0 — derive quality-gate test metrics and release prose from one source; the checked-in gate enforces a 1,049-test floor while `tools/verify-local-release.ps1` reports a 522-test minimum.
2. P1 — persist an execution journal so process death produces an explicit interrupted run with its last known step instead of relying on the in-memory registry and a process-level diagnostic.
3. P1 — add a deterministic compatibility corpus for OpenTasker JSON, Tasker XML, exported intents, Locale, and Termux boundaries.
4. P1 — replace regex-only export redaction with the existing field/sensitivity metadata as an end-to-end secret contract.
5. P1 — add opt-in local rolling configuration snapshots with bounded retention and restore preview.
6. P2 — add a JVM scenario harness for trigger → admission → execution → persistence → restart outcomes.
7. P2 — formalize OpenTasker bundle v1→v2 compatibility as a release-gated contract; checked-in code is v2 while an ignored bundle document still says v1.
8. P2 — refresh the pinned Gradle/KSP/Compose tooling tuple under the existing release gate; official release pages expose concrete patch candidates while the app is already on the current AGP line.
9. P3 — prototype a capability-gated Android AppFunctions surface only after the experimental platform API is judged stable enough for a local-only integration.

The roadmap already contains the appropriate P3 items for blueprint templates, module separation, an out-of-process plugin SDK, Home Assistant vocabulary, F-Droid listing, WorkManager metrics, and opt-in update checks. Device-only evidence and owner/credential-gated work remains in `Roadmap_Blocked.md`; it is not repeated below.

## Product Map

- Core workflows: create projects, tasks, profiles, scenes, variables, and seven context families; compose flow markers and actions in the Compose editor; inspect/search/duplicate/import/share automation; then enable profiles for foreground-service matching and execution (`README.md`, `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt`, `core/model/ContextSpec.kt`).
- Execution workflow: context sources feed `AutomationService`; admission, collision, lifecycle, retry-safety, causal-chain, fallback, and held-execution policies surround `TaskRunner`; `RunLogEntity` records completed, skipped, held, cancelled, and failed outcomes (`core/engine/AutomationService.kt`, `TaskExecutionHelper.kt`, `TaskRunner.kt`, `ExecutionAdmissionController.kt`).
- Action workflow: 74 registered actions plus 10 engine-handled markers cover variables, text/data, apps/intents, settings, media, files, HTTP/MQTT/Home Assistant, notifications, DND, Locale/Tasker, Termux, and supported/non-supported power paths (`ActionCatalog.kt`, `ActionMetadata.kt`, `README.md`).
- Trust workflow: capability/preflight review, imported-profile acknowledgement, semantic diffs, typed output chips, run-log filtering/export/replay, diagnostics, encrypted database backup, and staged restore review provide visibility and recovery without a cloud service (`core/transfer/OpenTaskerBundle.kt`, `core/capabilities`, `core/diff`, `core/diagnostics`, `core/storage/DatabaseBackupManager.kt`).
- Personas: privacy-focused Android/F-Droid users; Tasker users importing XML or using plugin contracts; power users building local device/network automations; maintainers who need deterministic release and migration guarantees. This is a verified product-fit inference from `README.md`, the manifest, import/export surfaces, and distribution profiles.
- Platforms and distribution: one `:app` module plus `:baselineprofile`, Android API 26 minimum and API 37 compile/target, Play and F-Droid dependency/manifest profiles, local unsigned release assembly, and no active `.github` workflow after the 2026-06-26 local-build decision (`settings.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `README.md`, `git show 3d88f83`).
- Data flows: Room/SQLCipher stores projects, profiles, tasks, scenes, variables, run logs, and edit history; DataStore/SharedPreferences hold settings and small ledgers; Android receivers, WorkManager, alarms, and the foreground service deliver events; JSON v2, Tasker XML, share manifests, Locale, Termux, external intents, HA webhooks, MQTT, UnifiedPush, and Shizuku are bounded integration edges (`core/storage/AppDatabase.kt`, `core/engine`, `core/transfer`, `core/plugins`, `core/scripting`, `core/external`, `AndroidManifest.xml`).

## Competitive Landscape

- Tasker — does breadth, flow/scenes/variables, run history, encryption, intents, and a documented plugin protocol well. OpenTasker should keep typed, capability-scoped plugin contracts and explicit security/error status; it should avoid inheriting Tasker’s unrestricted Java/JavaScript power because OpenTasker’s stated fit is bounded no-code, fail-closed automation (`https://tasker.joaoapps.com/userguide/en/`, `https://tasker.joaoapps.com/plugins.html`, `README.md`).
- MacroDroid — makes a very broad trigger/action/constraint catalogue approachable and uses community templates/marketplace discovery. OpenTasker should improve reusable template selection and preview, which is already represented by the existing blueprint roadmap item; it should avoid a proprietary marketplace dependency (`https://macrodroid.com/`, `ROADMAP.md`).
- Automate — combines a large block library, flowchart composition, expressions, plugins, community sharing, safe mode, and recovery documentation. OpenTasker should learn from visible execution/recovery affordances and keep its existing capability/preflight model; it should avoid making screen scraping and simulated input the core abstraction (`https://llamalab.com/automate/`, `https://llamalab.com/automate/doc/index.html`, `https://llamalab.com/automate/doc/premium.html`).
- Automation — demonstrates table-stakes breadth across location, time, device state, connectivity, notifications, scripts, and settings, while its F-Droid maintainer explicitly notes that Android/OEM changes make reliable support difficult. OpenTasker should invest in capability health, interrupted-run evidence, and user-submitted diagnostics before adding equivalent breadth; it should avoid an unbounded catalogue without maintenance evidence (`https://f-droid.org/packages/com.jens.automation2/`, `https://git.server47.de/jens/Automation`).
- Easer — provides an event-driven Android model and a standalone-app plugin direction, but its README calls out UI and test gaps and its plugin interface is not stable. OpenTasker should preserve the existing stable, permission-scoped integration approach and require contract tests before expanding its SDK; it should avoid an unstable remote-plugin ABI (`https://github.com/renyuneyun/Easer`, `https://github.com/renyuneyun/Easer/issues/489`).
- HTTP Shortcuts — is a strong adjacent example of local request history, certificate controls, variables, import/export, integrations, recovery of edits, and explicit handling of migration/import failures. OpenTasker should apply those reliability and secret-handling lessons to its own exports; it should not copy arbitrary JavaScript extensibility because that conflicts with its no-code and fail-closed posture (`https://github.com/Waboodoo/HTTP-Shortcuts`, `https://github.com/Waboodoo/HTTP-Shortcuts/blob/develop/CHANGELOG.md`).
- Home Assistant and Node-RED — make reusable blueprints, selector validation, execution modes, trace inspection, caught errors, and status messages first-class. OpenTasker should use those concepts to sharpen blueprint/template contracts and durable traces; it should avoid a remote hub/cloud dependency and not duplicate the existing blueprint roadmap work (`https://www.home-assistant.io/docs/automation/`, `https://www.home-assistant.io/docs/automation/modes/`, `https://www.home-assistant.io/docs/automation/troubleshooting/`, `https://nodered.org/docs/user-guide/handling-errors`).
- Termux:Tasker and Shizuku — show how Android extensions can use explicit permissions, bounded result channels, and typed Binder/API contracts instead of parsing shell text. OpenTasker should keep those boundaries for its existing Termux/Locale/Tasker work and its planned plugin SDK; it should not enable unrestricted shell/root fallback (`https://github.com/termux/termux-tasker`, `https://github.com/RikkaApps/Shizuku`, `app/src/main/java/com/opentasker/core/power/ShizukuShellRunner.kt`).

## Security, Privacy, and Reliability

Current strengths (Verified):

- The manifest uses a custom signature permission for the exported automation receiver, avoids `QUERY_ALL_PACKAGES`, disables ordinary Android backup, and limits exported integrations; untrusted imports use resource budgets, unknown-action rejection, staged review, and fail-closed decoders (`app/src/main/AndroidManifest.xml`, `core/transfer`, `core/external`, `core/storage/ImportResourceBudget.kt`).
- Secret variables are Keystore-backed/ciphertext in Room and omitted from portable bundles; action metadata, template evaluation, run traces, diagnostic logs, and CSV exports already carry several sensitivity controls (`core/storage/VariableSecretStorage.kt`, `core/actions/ActionMetadata.kt`, `core/expressions/TemplateExpressionEngine.kt`, `core/diagnostics`, `core/engine/TaskRunner.kt`).
- Admission limits, per-profile circuits, lifecycle policies, causal-chain stopping, held execution replay, fallback tasks, process-exit correlation, missed-trigger accounting, and scheduler diagnostics address failure modes that older research had incorrectly described as missing (`core/engine`, `core/diagnostics`, `CHANGELOG.md`, recent history through 2026-08-10).
- The build pins Gradle bootstrap hashes, dependency verification, SBOM generation, OSV queries, Room schemas, release truth, lint, test/coverage floors, and Play/F-Droid policy checks (`tools/verify-local-release.ps1`, `tools/verify-dependency-verification.ps1`, `app/build.gradle.kts`, `buildSrc/src/main/kotlin/com/opentasker/build/VerifyReleaseTruthTask.kt`). The Kotlin build-cache advisory is already documented and the repository uses local cache only (`https://github.com/advisories/GHSA-r937-wjx7-w2jp`).

Gaps and risks (Verified unless marked otherwise):

- P0 release-truth inconsistency: `VerifyJvmTestCountTask` enforces the 1,049 floor in `app/build.gradle.kts`, `README.md` calls it part of the full gate and says the floor is the current passing count, while `tools/verify-local-release.ps1` writes `minimumJvmTests = 522` into `local-release-gate.json`. The gate checks `tests >= floor`, not that the report and prose use the same derived semantics. This undermines a release contract even when the build passes.
- Process-death observability gap: `ActiveExecutionRegistry.kt` is process-memory state; `TaskExecutionHelper.kt` inserts a completed `RunLogEntity` only after `TaskRunner.run()` returns, with a special cancellation write. `EngineHealthReader.kt` correlates an `ApplicationExitInfo` to a process/heartbeat, but no persisted record identifies the interrupted task, execution ID, or last `onStep` value. A kill during a side-effecting action can therefore leave no task-level terminal record. This is distinct from the already-shipped held-execution ledger and should not retry work automatically.
- Recovery gap: `DatabaseBackupManager.kt` and `ActiveAutomationViewModel.kt` expose manual local backups, export/import, retention deletion, and reviewed staged restore. No periodic or edit-triggered rolling configuration snapshot was found. Full database backup also includes runtime history, whereas a portable configuration snapshot can omit secrets and logs by construction.
- Export privacy gap: `DiagnosticExport.redactSensitive()` is regex-based and `RunLogExporter` applies it to selected string fields, while action metadata and template evaluation already know which fields/values are sensitive. The current tests cover representative passwords, tokens, authorization, cards, and source guards, but no end-to-end contract proves that secret-derived values, URL credentials/query values, file paths, crash text, Tasker XML, and every future export surface remain absent. A historical Tasker community report documents an export leak, and HTTP Shortcuts’ release history shows secret-variable and recovery fixes (`app/src/main/java/com/opentasker/core/diagnostics/DiagnosticExport.kt`, `core/diagnostics/RunLogExporter.kt`, `app/src/test/java/com/opentasker/core/diagnostics/DiagnosticExportTest.kt`, `https://www.reddit.com/r/tasker/comments/c0mzkd`, `https://github.com/Waboodoo/HTTP-Shortcuts/blob/develop/CHANGELOG.md`).
- Boundary testing is broad but fragmented: malformed/oversized cases exist for JSON, XML, HTML, Tasker XML, bundles, intents, Locale, Termux, backups, and actions, but the repository scan found no deterministic property/fuzz corpus that exercises all external decoders together. The jsoup advisory shows why parser changes can become security issues even after targeted tests (`app/src/test`, `https://github.com/advisories/GHSA-pmhh-3w7g-xqp8`, `https://github.com/jhy/jsoup/releases`).
- Documentation truth drift is visible in ignored material: `docs/OPEN_JSON_BUNDLE.md` says schema v1 while `OpenTaskerBundle.kt`, `tools/release-truth.json`, and migration code use v2; `docs/DEPENDENCY_MODERNIZATION.md` says immutable collections 0.3.8 while `gradle/libs.versions.toml` and `CHANGELOG.md` use 0.5.1. The build reports these as historical-document warnings, and publishing the ignored documentation tree is already owner-gated; this is documented as a risk, not duplicated as an active docs item.
- Android 17 needs live validation for local-network permission, background audio, foreground-service/background-start behavior, and OEM backup/device-to-device behavior. The app targets API 37 and declares relevant permissions, but `Roadmap_Blocked.md` already records the required device matrices (`https://developer.android.com/about/versions/17/behavior-changes-17`, `https://developer.android.com/privacy-and-security/local-network-permission`, `https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`, `https://developer.android.com/privacy-and-security/risks/backup-best-practices`). Confidence: Needs live validation.

Required guardrails for the new work: never auto-retry an interrupted side effect without the action’s existing retry classification; keep snapshots local and bounded by default; preserve the existing secret re-entry rule on device migration; validate all candidate data before Room mutation; make diagnostic/export redaction opt-out impossible; and keep external invocation capability- and permission-gated.

## Architecture Assessment

- The most valuable boundary already exists in `ActionCatalog.kt`: one declaration now owns runtime factory, category, retry safety, capability resolution, metadata binding, and release counts. Do not reintroduce a second action registry; the existing P3 module split should preserve this single source across `core` and `feature` modules.
- Dependency review on 2026-08-10 found a bounded tooling refresh rather than a broad upgrade campaign: the wrapper is 9.6.1 while official Gradle release notes list 9.7.0; KSP’s release page lists 2.3.11 above the catalog’s 2.3.10; and the official Compose BOM guide uses 2026.06.01 while the catalog pins 2026.06.00. AGP 9.3.1 remains the project’s current API-37-compatible line, so the candidate must be gated as one compatibility tuple and must not enable incubating isolated-projects behavior (`gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `https://docs.gradle.org/current/release-notes.html`, `https://developer.android.com/build/releases/gradle-plugin`, `https://github.com/google/ksp/releases`, `https://developer.android.com/develop/ui/compose/bom`).
- The next missing boundary is a durable execution journal between `TaskExecutionHelper.kt`/`TaskRunner.kt` and `RunLogDao`. It should be a small, append/update state machine rather than a second full run-log format: start, last step, terminal outcome, source, execution lineage, redacted reason, and bounded retention.
- `OpenTaskerBundle.kt` is a good central portability boundary, but its v1 migration, v2 computed capability/power manifest, Tasker XML importer/exporter, and share manifest need one compatibility fixture policy. The current schema mismatch in ignored docs is evidence that a source-derived runtime contract alone does not keep human-facing claims coherent.
- `DatabaseBackupManager.kt` has the right atomic/WAL-safe restore primitives. Add snapshot scheduling and retention around those primitives instead of bypassing validation or creating a second unencrypted storage path.
- Refactor candidates are already captured by the existing P3 module item: `ui/screens/ActiveAutomationViewModel.kt`, `ui/screens/ActiveAutomationUi.kt`, `ui/screens/PermissionOnboardingScreen.kt`, `core/actions/ActionMetadata.kt`, `core/engine/AutomationService.kt`, `core/engine/TaskRunner.kt`, and `core/transfer/OpenTaskerBundle.kt` are unusually large. The refactor should follow dependency direction and not be treated as a prerequisite for the P0/P1 reliability work.
- Test posture is strong at the JVM/unit level and includes focused Android tests for storage/import paths, but it lacks a single scenario fixture layer and public headless verification. There is no active `.github` workflow because commit `3d88f83` deliberately removed GitHub Actions and `README.md` says hosted CI is not required. Reintroducing non-signing CI is Under Consideration, not an active roadmap item, until the maintainer chooses between local-owner verification and public checks.
- Category decisions: security, reliability, migration, observability, and testing receive active additions; accessibility/adaptive layouts and API37/device matrices remain in `Roadmap_Blocked.md`; i18n/l10n remains en-US-first with alternate-locale/Weblate work gated in `Roadmap_Blocked.md`; distribution remains Play/F-Droid with metadata/update work already on `ROADMAP.md`; plugin ecosystem work is already represented by the out-of-process SDK item; mobile means Android by project definition; offline operation is intentionally local-first and does not justify a cloud queue; multi-user/cross-profile automation has no product or permission contract and is rejected; upgrade strategy remains the pinned Gradle/dependency/release-truth gate plus Room/bundle migrations.

## Rejected Ideas

- Cloud sync, remote execution, or a Temporal/Zapier-style hosted orchestration backend — conflicts with the local-first/F-Droid posture, adds credentials and data residency risk, and is not needed for the core workflows (`README.md`, `https://docs.temporal.io/workflows`, `https://zapier.com/pricing`).
- Arbitrary JavaScript, accessibility-driven GUI macros, root shell fallback, or an AI agent that taps arbitrary apps — AutoJs6 and vFlow demonstrate the power, but also introduce unrestricted code/UI fragility and permission risk; community reports describe disabled accessibility services and brittle background automations. OpenTasker’s no-code, capability-gated, fail-closed contract is the better fit (`https://github.com/SuperMonster003/AutoJs6`, `https://github.com/ChaoMixian/vFlow`, `https://github.com/Nain57/Smart-AutoClicker/issues/599`, `https://www.reddit.com/r/tasker/comments/1u8nl4a`, `https://www.reddit.com/r/macrodroid/comments/1tnaziyu`).
- A signed online marketplace or profile-sharing backend — public provenance/signing requires owner credentials and policy decisions already recorded as blocked; the local share manifest, import review, and existing blueprint/plugin roadmap are safer incremental steps (`core/sharing`, `docs/PROFILE_SHARING.md`, `Roadmap_Blocked.md`, `https://www.home-assistant.io/docs/automation/using_blueprints/`).
- iOS support, cross-device/multi-user execution, or a general workflow cloud — the manifest, storage model, supported platform, and privacy philosophy are Android/local scoped; no repository or ecosystem evidence makes this a fit (`app/src/main/AndroidManifest.xml`, `README.md`).
- A generic increase in action count as the primary strategy — direct competitors already have breadth; OpenTasker’s current differentiators are evidence, safety, portability, and explicit unsupported states, while the canonical catalogue and existing P3 template/plugin work provide a controlled extension path (`ActionCatalog.kt`, `README.md`, `ROADMAP.md`).
- Full interactive per-task debugger with breakpoints, variable watch, pause, and step — valuable and supported by community evidence, but it requires a product judgment about sensitive variable display and is already operator-gated in `Roadmap_Blocked.md`; the durable interrupted-run journal is the lower-risk prerequisite (`https://www.reddit.com/r/AutomateUser/comments/1d9u1ec/`, `Roadmap_Blocked.md`).
- Android AppFunctions as a release-critical feature now — the API is an experimental preview, available only on newer Android versions, and must be caller-permissioned; keep it as a P3 prototype behind a capability gate rather than making it a platform dependency (`https://developer.android.com/ai/appfunctions`, `https://developer.android.com/jetpack/androidx/releases/appfunctions`).

## Sources

### Direct OSS and Android automation

- https://f-droid.org/packages/com.jens.automation2/
- https://git.server47.de/jens/Automation
- https://github.com/renyuneyun/Easer
- https://github.com/renyuneyun/Easer/issues/489
- https://github.com/ChaoMixian/vFlow
- https://github.com/Nain57/Smart-AutoClicker
- https://github.com/Nain57/Smart-AutoClicker/issues/599
- https://github.com/Waboodoo/HTTP-Shortcuts
- https://github.com/Waboodoo/HTTP-Shortcuts/blob/develop/CHANGELOG.md
- https://github.com/SuperMonster003/AutoJs6
- https://github.com/KieronQuinn/Smartspacer
- https://github.com/termux/termux-tasker
- https://github.com/RikkaApps/Shizuku
- https://github.com/home-assistant/android

### Commercial and adjacent workflow products

- https://tasker.joaoapps.com/userguide/en/
- https://tasker.joaoapps.com/plugins.html
- https://macrodroid.com/
- https://llamalab.com/automate/
- https://llamalab.com/automate/doc/index.html
- https://llamalab.com/automate/doc/premium.html
- https://zapier.com/pricing
- https://www.home-assistant.io/docs/automation/
- https://www.home-assistant.io/docs/automation/modes/
- https://www.home-assistant.io/docs/automation/troubleshooting/
- https://www.home-assistant.io/docs/automation/using_blueprints/
- https://www.home-assistant.io/docs/blueprint/schema/
- https://nodered.org/docs/user-guide/handling-errors
- https://docs.temporal.io/workflows

### Ecosystem lists and benchmarks

- https://github.com/guifelix/awesome-tasker
- https://github.com/dariubs/awesome-workflow-automation
- https://github.com/google-research/android_world

### Android, distribution, and platform standards

- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/privacy-and-security/risks/backup-best-practices
- https://docs.gradle.org/current/release-notes.html
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/jetpack/androidx/releases/work?authuser=9
- https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes?hl=en
- https://developer.android.com/develop/ui/compose/accessibility/testing
- https://developer.android.com/ai/appfunctions
- https://developer.android.com/ai/appfunctions/add-appfunctions
- https://developer.android.com/jetpack/androidx/releases/appfunctions
- https://f-droid.org/en/docs/Reproducible_Builds/
- https://f-droid.org/en/docs/Inclusion_Policy/
- https://f-droid.org/en/docs/Anti-Features/

### Security and dependency advisories

- https://github.com/advisories/GHSA-r937-wjx7-w2jp
- https://github.com/advisories/GHSA-pmhh-3w7g-xqp8
- https://github.com/jhy/jsoup/releases
- https://github.com/google/ksp/releases
- https://developer.android.com/develop/ui/compose/bom

### Community and academic evidence

- https://www.reddit.com/r/AutomateUser/comments/1q2wlqr/suggestions/
- https://www.reddit.com/r/AutomateUser/comments/1d9u1ec/
- https://www.reddit.com/r/tasker/comments/p5gwgp
- https://www.reddit.com/r/tasker/comments/c0mzkd
- https://www.reddit.com/r/tasker/comments/1u8nl4a
- https://www.reddit.com/r/macrodroid/comments/1tnaziyu
- https://par.nsf.gov/biblio/10387467-helping-users-debug-trigger-action-programs
- https://hcrlab.cs.washington.edu/publications/huang2015ubicomp/
- https://doi.org/10.1145/3411764.3445567

## Open Questions

- Whether maintainers want public, non-signing CI is the only unresolved prioritization choice that affects an otherwise actionable recommendation: the 2026-06-26 history and `README.md` explicitly choose local-only verification, while public OSS peers expose shared automation checks. Keep CI Under Consideration until that policy is decided.
- No open question blocks the P0/P1 items. The recommended snapshot policy is local, opt-in, configuration-only by default, bounded retention, and no network upload; it can be implemented without credentials or a device matrix.
