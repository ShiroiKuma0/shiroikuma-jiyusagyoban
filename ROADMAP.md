# OpenTasker Roadmap

**Current app version:** 0.2.75
**Last updated:** 2026-06-27

Only open work belongs here; git history and `CHANGELOG.md` are the release record.

## Research-Driven Additions

### P1 -- Maintainability, accessibility, and localization

### P2 -- Ecosystem integrations and observability

- [ ] P2 — Add a repeatable Locale plugin compatibility fixture
  Why: The current Locale host/target validation depends on whichever plugin is installed, so compatibility evidence is not repeatable.
  Evidence: `tools/validate-locale-plugin.ps1`; `docs/LOCALE_PLUGIN_HOST.md`; https://github.com/twofortyfouram/android-plugin-api-for-locale
  Touches: test fixture app/module or JVM contract tests, Locale discovery/config/fire/query code, validation harness summary output.
  Acceptance: A synthetic setting/condition plugin validates discovery, config parsing, fire/query/request-query, redaction, and result codes in a repeatable local or CI/device flow.
  Complexity: M

- [ ] P2 — Spike the first Home Assistant bridge protocol
  Why: Home Assistant, Node-RED, and n8n dominate local automation integrations, while OpenTasker has HTTP/LAN primitives but no native bridge.
  Evidence: https://github.com/home-assistant/android; https://companion.home-assistant.io/docs/core/sensors/; https://nodered.org/docs/; `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`
  Touches: integration package, action metadata, setup permissions, HTTP/local-network policy, tests.
  Acceptance: A tested proof of concept chooses outbound webhook, MQTT, or `mobile_app` registration as the first supported bridge and documents auth, payload limits, retry behavior, and privacy boundaries.
  Complexity: M

- [ ] P2 — Add an outbound MQTT publish action with an F-Droid-safe client decision
  Why: MQTT is the common local automation bus for Home Assistant and Node-RED, and existing HTTP actions do not support retained/QoS topic workflows.
  Evidence: https://github.com/hannesa2/paho.mqtt.android; `gradle/libs.versions.toml`; `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`
  Touches: Gradle dependency catalog, action metadata, network/local-network permission checks, run-log redaction, unit tests with a fake client.
  Acceptance: `mqtt.publish` supports host, port, TLS option, topic, payload, QoS, and retain; private hosts respect `ACCESS_LOCAL_NETWORK`; tests cover success, denial, timeout, and redaction.
  Complexity: M

- [ ] P2 — Design a UnifiedPush or ntfy event-trigger path
  Why: FOSS remote triggers need push without Google services or polling, and ntfy/UnifiedPush already serve the degoogled Android ecosystem.
  Evidence: https://github.com/binwiederhier/ntfy; https://github.com/UnifiedPush/android-connector; existing event contexts under `app/src/main/java/com/opentasker/automation` and `app/src/main/java/com/opentasker/core/contexts`
  Touches: event context model, setup screen, notification permission handling, payload validation, integration tests.
  Acceptance: A bounded `event=push` design or spike defines distributor/ntfy choice, auth model, payload size limits, retry behavior, redaction, and one tested trigger path.
  Complexity: M

### P3 -- Evaluations

- [ ] P3 — Evaluate Glance widget migration
  Why: Widgets use RemoteViews XML today; Glance could reduce widget/UI divergence but may add dependency and capability tradeoffs.
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

### P0 -- Backup trust

- [ ] P0 - Fix Create Local Backup failing required-table validation
  Why: A public user report says Create Local Backup fails with `Backup is missing required table(s): profiles, tasks, run_logs` even after creating profiles, tasks, and run logs.
  Evidence: https://github.com/SysAdminDoc/OpenTasker/issues/3; `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt`
  Touches: `DatabaseBackupManager`, `ActiveAutomationViewModel.createDatabaseBackup`, backup UI event handling, backup instrumented tests.
  Acceptance: Create Local Backup succeeds on a populated current-schema database; a regression test reproduces issue #3 or its root cause; failure messages distinguish missing DB, wrong DB name/path, WAL/race, and schema corruption.
  Complexity: M

### P1 -- Release evidence and device trust

- [ ] P1 - Refresh F-Droid local build evidence and docs
  Why: The metadata is synced to `0.2.75`/`77`, but `docs/FDROID_READINESS.md` still cites `0.2.70`/`72` local evidence and stale fdroidserver notes.
  Evidence: `fdroid/metadata/com.opentasker.app.yml`; `docs/FDROID_READINESS.md`; `tools/verify-fdroid-release.ps1`; https://f-droid.org/en/docs/Reproducible_Builds/
  Touches: `docs/FDROID_READINESS.md`, `tools/verify-fdroid-release.ps1`, Gradle F-Droid tasks, local fdroidserver setup notes.
  Acceptance: `verifyFdroidReadiness`, `verifyFdroidMetadata`, `verify-fdroid-release.ps1 -BuildRelease`, `fdroid lint`, and one local fdroidserver build/lint result are current for `0.2.75`/`77` or document the exact blocker.
  Complexity: M

- [ ] P1 - Run an API 37 local-network and background-service evidence matrix
  Why: Android 17 enforces `ACCESS_LOCAL_NETWORK` for target SDK 37 local TCP/UDP traffic, and OpenTasker's core LAN actions plus future MQTT/home bridges depend on graceful denial/revocation behavior.
  Evidence: https://developer.android.com/privacy-and-security/local-network-permission; `app/src/main/AndroidManifest.xml`; `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`
  Touches: Setup permission flow, HTTP/Ping/WoL actions, `AutomationService`, local-network tests, device-evidence scripts/docs.
  Acceptance: Device evidence covers granted, denied, revoked, and re-requested local-network permission for HTTP, Ping, and WoL; background service, notifications, Bluetooth, overlay scene launch, and predictive back are smoke-tested on the same API 37 matrix.
  Complexity: L

- [ ] P1 - Capture rendered accessibility, font-scale, and large-screen evidence
  Why: Source gates exist, but TalkBack traversal, 200% font scale, split-screen, and large-screen layout behavior are not proven for the densest automation surfaces.
  Evidence: `app/src/test/java/com/opentasker/ui/AccessibilitySourceTest.kt`; `app/src/androidTest/java/com/opentasker/ui/screens/CriticalFlowComposeTest.kt`; https://developer.android.com/about/versions/17/behavior-changes-17
  Touches: Compose UI tests, screenshots/evidence harness, setup, editors, flow graph, scene canvas, import review, run log.
  Acceptance: Evidence artifacts or tests verify no clipped/overlapped controls and usable semantics for setup, profile/task/action/context editors, scene creation/editing, flow graph, import review, and run-log traces at high font scale and large widths.
  Complexity: M

- [ ] P1 - Convert setup and backup visible copy to string resources and seed one real locale
  Why: Locale skeletons exist but are empty, and `PermissionOnboardingScreen.kt` still contains setup/backup visible literals outside the current localization guard.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt`; `app/src/test/java/com/opentasker/ui/LocalizationSourceTest.kt`; `app/src/main/res/values-*/strings.xml`; https://hosted.weblate.org/
  Touches: `strings.xml`, locale resource directories, `PermissionOnboardingScreen.kt`, localization source tests.
  Acceptance: Setup, backup, and permission card text resolve through `R.string`; the guard covers this file; at least one locale directory has translated non-placeholder strings and Android resource validation passes.
  Complexity: M

- [ ] P1 - Split the scene editor into focused UI modules
  Why: `SceneLibraryScreen.kt` is now the largest UI file and owns list, dialogs, canvas projection, drag/resize, multi-select, alignment guides, validation, and overlay actions.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt`; `docs/SCENES.md`
  Touches: scene screen/card/dialog/canvas modules, scene UI tests, source-boundary tests.
  Acceptance: Scene list, scene card, element editor dialogs, canvas interactions, and overlay controls live in focused files; existing scene behavior and tests still pass; a source test prevents the shell from regrowing broad responsibilities.
  Complexity: M

- [ ] P1 - Harden Termux script allowlist and bounded output capture
  Why: Termux dispatch is active, but docs state hash checks are not user-facing and stdout/stderr/exit-code capture is not a completed output pipeline.
  Evidence: `docs/TERMUX_SCRIPTING.md`; `app/src/main/java/com/opentasker/core/actions/ScriptActions.kt`; https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
  Touches: `TermuxScriptAction`, setup/script UI, action metadata, variable output handling, run-log redaction, device validation harness.
  Acceptance: Users can manage allowed scripts with displayed SHA-256 hashes; stdout, stderr, and exit code are size-bounded, redacted in logs, and optionally mapped to variables; tests cover permission denial, hash mismatch, rate limit, timeout, and oversized output.
  Complexity: L

- [ ] P1 - Add Shizuku per-action risk copy and device evidence
  Why: Elevated actions are allowlisted and kill-switch gated, but each command variant needs device-backed evidence and explicit user-facing risk text before broad release claims.
  Evidence: `docs/SHIZUKU.md`; `app/src/main/java/com/opentasker/core/power/ShizukuShellRunner.kt`; https://github.com/RikkaApps/Shizuku-API
  Touches: Shizuku setup row, action capability copy, run-log diagnostics, Shizuku tests/evidence harness.
  Acceptance: Each allowlisted action has risk copy, setup state, failure behavior, and device evidence for ready/denied/kill-switch states; release docs distinguish normal Android and Shizuku-backed behavior.
  Complexity: M

### P2 -- Migration, sharing, and authoring depth

- [ ] P2 - Expand Tasker XML import/export mappings for the next safe action families
  Why: Tasker migration is a core adoption path, but import/export still maps only a small safe subset and preserves most actions as unsupported placeholders.
  Evidence: `docs/TASKER_XML_IMPORT.md`; `app/src/main/java/com/opentasker/core/transfer/TaskerXmlImport.kt`; `app/src/main/java/com/opentasker/core/transfer/TaskerXmlExport.kt`; https://tasker.joaoapps.com/userguide/en/
  Touches: Tasker XML parser/exporter, action mapping table, migration report UI, capability warnings, import/export tests.
  Acceptance: A documented mapping batch adds the next safest settings, media, notification, variable, and flow actions; unsupported actions remain explicit; preview/export reports list mapped, skipped, and lossy fields deterministically.
  Complexity: L

- [ ] P2 - Add local community-share preview and import review
  Why: MacroDroid and Automate win on shared templates/flows, while OpenTasker already has offline share manifests but no user-facing preview/import surface.
  Evidence: `docs/PROFILE_SHARING.md`; `app/src/main/java/com/opentasker/core/sharing/ProfileShareLibrary.kt`; https://www.macrodroid.com/; https://llamalab.com/automate/
  Touches: profile sharing UI, OpenTasker bundle review, screenshot attachment preview, safety finding rendering, import repository tests.
  Acceptance: A local share manifest can be previewed with screenshots, safety findings, capability warnings, and bundle import plan before any Room write; unverified shares cannot bypass existing validation.
  Complexity: M

- [ ] P2 - Validate complex flow graphs before direct graph persistence
  Why: Flow authoring now has zoom/pan, deep links, add commands, branches, and subflow markers, but docs still defer direct graph persistence until picker-backed edits are stable.
  Evidence: `docs/VISUAL_FLOW.md`; `app/src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt`; `app/src/main/java/com/opentasker/core/flow/AutomationFlowGraph.kt`
  Touches: flow graph builder, flow screen, graph UI tests, accessibility summaries, action/context picker deep links.
  Acceptance: A complex real-world graph fixture validates branch labels, subflow markers, missing-reference repair, add commands, zoom/pan framing, and screen-reader summaries; a decision note defines when direct drag/drop persistence can be implemented.
  Complexity: M
