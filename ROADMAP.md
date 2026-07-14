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

### P0 -- Data integrity and runtime truth

- [ ] P2 — Add a Locale grant management UI (inspect and revoke individual grants)
  Why: Revocable Locale execution grants are issued/validated and auto-revoked on task deletion, but users cannot yet browse issued grants or revoke a specific one from the app.
  Evidence: `app/src/main/java/com/opentasker/core/plugins/locale/LocaleGrantStore.kt` (`grants()`, `revoke()`); `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt`
  Touches: Setup/Locale UI surface, `LocaleGrantStore` wiring, grant-row composables with task-name resolution, UI tests.
  Acceptance: Users can view each issued grant (task name/id) and revoke it; revoking blocks subsequent fires for that grant; the list updates live and shows an empty state when no grants exist.
  Complexity: S


### P1 -- Release evidence and device trust

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

- [ ] P1 — Add first-class secret variables and provenance-based redaction
  Why: Variable values are plaintext at rest, masking is name-based, and set/persist actions log the full value, so API tokens can leak through logs, traces, diagnostics, or ordinary exports.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt`; `app/src/main/java/com/opentasker/ui/screens/VariablesScreen.kt`; `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt`; https://developer.android.com/privacy-and-security/keystore
  Touches: variable Room migration/model/DAO, Keystore-backed secret codec, variable UI/action fields, expansion provenance, AppLogger/run-log/diagnostic redaction, bundle and backup policy, tests.
  Acceptance: Users can mark/create a secret; its Room value is authenticated ciphertext; UI reveal requires deliberate action; derived expansions remain redacted by provenance even in nonsensitive fields and exceptions; ordinary exports omit secret values; password backups either portably wrap them or clearly require re-entry; migration and key-loss recovery are tested.
  Complexity: L

- [ ] P1 — Classify sensitive automation capabilities and gate imported recipes
  Why: Implementation support metadata does not distinguish data access, external transmission, device control, or destructive actions, even though imported recipes run under app-wide permissions.
  Evidence: `app/src/main/java/com/opentasker/core/capabilities/ActionCapabilities.kt`; `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt`; https://www.usenix.org/system/files/usenixsecurity25-zhang-shirley.pdf
  Touches: action metadata/capability registry, bundle manifest and validator, import review, profile enable flow, run-log risk labels, registry coverage tests.
  Acceptance: Every registered action has a tested sensitivity classification; import review groups requested powers by task/profile and flags data-to-network chains; imported profiles remain disabled and require explicit acknowledgement before first enable; unknown actions fail closed.
  Complexity: M

- [ ] P1 — Start context monitors only when enabled profiles need them
  Why: Wi-Fi/connectivity, app-usage, shake, and camera/microphone sources start for the whole service lifetime even when no enabled profile references those context families.
  Evidence: `app/src/main/java/com/opentasker/core/engine/AutomationService.kt`; enabled profile contexts in `app/src/main/java/com/opentasker/core/storage/ProfileDao.kt`; https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban
  Touches: live profile registry, context-source lifecycle coordinator, monitor start/stop APIs, Context Inspector isolation, service tests and battery evidence harness.
  Acceptance: Each monitor has a reference-counted active-profile dependency; add/edit/disable/delete transitions start or stop it exactly once; Context Inspector cannot silently keep production sources alive; tests prove zero monitored sources with no dependent profiles and no missed first event after activation.
  Complexity: M

- [ ] P1 — Stream authenticated backup encryption and restore
  Why: `BackupEncryption` reads the entire bounded database and allocates a second plaintext/ciphertext array, creating avoidable low-memory failure risk near the 100 MB cap.
  Evidence: `app/src/main/java/com/opentasker/core/storage/BackupEncryption.kt`; `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt`; Android Keystore/AES-GCM guidance at https://developer.android.com/privacy-and-security/cryptography
  Touches: backup format version, streaming encrypt/decrypt pipeline, temporary-file publish/cleanup, restore journal, instrumentation and corruption tests.
  Acceptance: Encryption/decryption use bounded buffers; authentication is verified before a restored database is published; wrong-passphrase, truncation, tag failure, cancellation, low-space, and process-interruption leave the live DB untouched; format v1 remains restorable and peak-memory evidence is recorded near the cap.
  Complexity: M

- [ ] P1 — Expand release-truth contracts to all capability documents
  Why: Architecture and dependency docs still describe shipped scenes, visual flow, Shizuku, Termux, Locale configuration, and Compose versions as older baselines that the current README truth test does not cover.
  Evidence: `docs/ARCHITECTURE.md`; `docs/DEPENDENCY_MODERNIZATION.md`; `docs/LOCALE_PLUGIN_HOST.md`; `app/src/test/java/com/opentasker/docs/ReleaseTruthContractTest.kt`; `gradle/libs.versions.toml`
  Touches: stale docs, release-truth test, feature/version metadata source, `docs/FDROID_READINESS.md` release contract.
  Acceptance: Current stale claims are corrected; one deterministic test derives versions and shipped capability states from code/catalog metadata and checks README plus all feature docs; intentionally historical changelog passages are excluded; a failing example proves the gate.
  Complexity: S

- [ ] P1 — Add Android Developer Verification release evidence
  Why: OpenTasker distributes outside Play, and Android begins requiring registered packages from verified developers on certified devices in selected regions on September 30, 2026.
  Evidence: `app/build.gradle.kts`; `docs/FDROID_READINESS.md`; GitHub/F-Droid distribution in `README.md`; https://developer.android.com/developer-verification
  Touches: `docs/FDROID_READINESS.md`, signing/package evidence script, README distribution notes, release artifact verification.
  Acceptance: A local command prints and verifies application ID, version, signer SHA-256, and APK provenance for the release artifact; the release contract records the chosen full-registration or advanced-flow posture and its owner without claiming completion prematurely; package/signing drift fails the release gate.
  Complexity: S

- [ ] P1 — Replace split GET/POST actions with one production-grade HTTP request
  Why: Current HTTP actions lack method, header, query, auth, status, response-header, file-output, and consistent error semantics that Tasker and MacroDroid expose as table stakes.
  Evidence: `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt`; https://tasker.joaoapps.com/userguide/en/help/ah_http_request.html; https://macrodroidforum.com/wiki/index.php/Action%3A_HTTP_Request; https://square.github.io/okhttp/changelogs/changelog/
  Touches: version catalog/dependency verification, OkHttp 5.4.0 transport, action registry/metadata/editor, LAN policy, secret references/redaction, MockWebServer tests, GET/POST migration aliases.
  Acceptance: `http.request` supports GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, structured headers/query/body/file input, bounded body/file output, status and response-header variables, timeouts, and explicit same-origin/redirect policy; TLS verification cannot be disabled; deprecated GET/POST IDs preserve existing bundles; tests cover 2xx/4xx/5xx, cancellation, redirect, oversized data, secret redaction, and LAN denial.
  Complexity: L

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

- [ ] P2 — Add versioned OpenTasker bundle migrations and golden fixtures
  Why: Bundle schema 1 directly serializes domain models and rejects every other version, so the next metadata/entity change can strand exports or force unsafe leniency.
  Evidence: `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt`; `app/src/main/java/com/opentasker/core/storage/DatabaseMigrations.kt`; https://www.home-assistant.io/docs/blueprint/schema/
  Touches: version-specific transfer DTOs, migration chain, codec/validator, import review, fixture corpus, export docs/tests.
  Acceptance: Golden schema-1 exports import after a schema-2 implementation; migrations are ordered, deterministic, and lossy fields are reported; unsupported future schemas and failed migrations perform zero Room writes; round-trip and ID/reference remap tests cover tasks, profiles, variables, and scenes.
  Complexity: M

- [ ] P2 — Add a side-effect-free automation preflight runner
  Why: Manual Run executes real actions, while users need to validate contexts, variable expansion, branches, permissions, and targets before a profile can change device or external state.
  Evidence: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt`; `app/src/main/java/com/opentasker/ui/screens/ContextInspectorScreen.kt`; https://nodered.org/docs/user-guide/editor/sidebar/debug
  Touches: action dry-run contract, task runner, synthetic event/context inputs, capability checks, run-trace UI, flow/action tests.
  Acceptance: Users can preflight a task/profile with synthetic event variables; every step reports expanded inputs, branch decision, missing setup, and intended effect without network, file, setting, shell, notification, or scene side effects; unsupported preview implementations are explicit and registry coverage is complete.
  Complexity: L

- [ ] P2 — Generalize intent dispatch behind explicit safety bounds
  Why: `intent.launch` claims activity/service support but only starts an activity with package/action/category, while the closest fork and Tasker ecosystem rely on primitive extras, URI/MIME data, explicit components, and ordered broadcast results.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt`; `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt`; https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban
  Touches: intent action/runtime metadata, package/component picker, primitive-extra codec, activity/broadcast/service dispatch policy, output variables, sensitivity/import review, tests.
  Acceptance: A bounded action supports explicit activity and broadcast dispatch, URI/MIME, allowlisted flags, size-limited string/int/bool extras, and optional ordered-broadcast result capture; service dispatch follows current Android background rules; implicit sensitive broadcasts, parcelables/serialization, `file://`, and unapproved exported targets fail closed.
  Complexity: M

- [ ] P2 — Add first-class local projects as a workspace boundary
  Why: A direct upstream discussion and the active fork show demand for Tasker-style projects, while current profile groups cannot organize or scope tasks, profiles, scenes, variables, and exports together.
  Evidence: https://github.com/SysAdminDoc/OpenTasker/discussions/2; https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban; Tasker project model at https://tasker.joaoapps.com/userguide/en/activity_main.html
  Touches: Room project entity/migrations, foreign keys and default-project backfill, project filters across tabs, variable scope, bundle schema/migrations, import/export, deletion/reassignment UX, tests.
  Acceptance: Existing workspaces migrate atomically into a Default project; users can create/rename/reorder/delete projects and move/filter tasks, profiles, scenes, and project-scoped variables; cross-project references are validated; export/import preserves project membership without raw-ID coupling; deletion requires explicit reassignment or cascade review.
  Complexity: XL

## Research-Driven Additions

### P1 -- Background reliability, observability, and security

- [ ] P1 — Make time triggers Doze-safe and add a service watchdog
  Why: Time-window profiles are evaluated by an in-service `while(isActive){ delay(1000) }` poll that Doze/App-Standby suspends, while the AlarmManager wake path only logs and feeds nothing; a single dropped self-rescheduling alarm or a killed `START_STICKY` service silently stops all time delivery until process restart.
  Evidence: `app/src/main/java/com/opentasker/core/contexts/TimeContextSourceImpl.kt:27-43`; `app/src/main/java/com/opentasker/automation/receiver/TimeEventReceiver.kt:21`; `app/src/main/java/com/opentasker/automation/scheduler/TimeEventScheduler.kt`; `app/src/main/java/com/opentasker/core/engine/AutomationService.kt` (`onTimeout`→`stopSelf`); https://developer.android.com/about/versions/15/behavior-changes-15#fgs-timeout; https://developer.android.com/topic/libraries/architecture/workmanager
  Touches: time context source, `TimeEventReceiver`/`TimeEventScheduler`, a periodic WorkManager watchdog worker, `AutomationService` re-arm path, service/scheduler tests, battery-evidence harness.
  Acceptance: Time contexts are re-evaluated from the AlarmManager (or expedited-work) wake path, not solely the coroutine poll; a periodic WorkManager heartbeat verifies the service is alive and re-arms it after force-stop/timeout; a dropped single alarm cannot terminate ticks; tests prove a time window fires after a simulated Doze idle and after `onTimeout`.
  Complexity: M

- [ ] P1 — Surface crash logs and add an in-app diagnostics and engine-health panel
  Why: `CrashLogHandler` captures uncaught-exception traces to `filesDir/crash_logs` but `getLatestCrashLog` has no caller and the diagnostic export omits them, so crash data is unreachable; the app also exposes no live view of service/FGS/standby/exact-alarm/matcher health.
  Evidence: `app/src/main/java/com/opentasker/core/diagnostics/CrashLogHandler.kt:64`; `app/src/main/java/com/opentasker/core/logging/AppLogger.kt`; https://developer.android.com/topic/performance/vitals; https://developer.android.com/about/versions/16/behavior-changes-all#jobscheduler-quota-optimizations
  Touches: diagnostics screen, `AppLogger` bounded ring buffer, crash-log reader/export, WorkManager `WorkInfo.getStopReason()` surfacing, health-status model, tests.
  Acceptance: A diagnostics screen lists and shares captured crash logs and a bounded in-app log ring buffer; an engine-health panel shows service running, active FGS type, app-standby bucket, exact-alarm permission, last matcher error, and last worker stop reason; the diagnostic export includes crash logs with existing redaction.
  Complexity: M

- [ ] P1 — Add a live variable inspector for running and finished tasks
  Why: Run logs show per-expression template diagnostics but no snapshot of the resulting variable values, and there is no runtime global-variable view, so users cannot see what a task actually set; the Variables screen reads only the (currently unwritten) persisted vault.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/RunLogScreenContent.kt` (`ExpressionDebugger`); `app/src/main/java/com/opentasker/ui/screens/VariablesScreen.kt`; `app/src/main/java/com/opentasker/core/engine/VariableStore.kt`; CLAUDE.md "Later roadmap ideas" (variable inspector)
  Touches: task-run variable snapshot capture, run-log/inspector UI, global-variable runtime view, redaction of secret provenance, inspector tests.
  Acceptance: After a manual or triggered run, users can inspect task/event/global variable values with per-step deltas and redaction; a live global view reflects the durable global runtime; depends on the durable-globals P0.
  Complexity: M


- [ ] P1 — Add tapjacking protection to scene overlay controls that run tasks
  Why: The app both draws overlays (`SceneOverlayService`, `SYSTEM_ALERT_WINDOW`) and renders single-tap controls that execute tasks or toggle profiles, so a malicious overlay could proxy consequential taps.
  Evidence: `app/src/main/java/com/opentasker/core/scenes/SceneOverlayService.kt`; scene control bindings in `app/src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt`; https://developer.android.com/privacy-and-security/risks/tapjacking
  Touches: scene overlay control composables/views, obscured-touch filtering, scene UI tests.
  Acceptance: Scene controls that run a task or change a profile set `filterTouchesWhenObscured=true` (or the Compose equivalent) and drop taps received while obscured; a test verifies obscured taps do not trigger execution.
  Complexity: S

- [ ] P1 — Add behavioral test coverage for automation-mode dispatch, cooldown, and per-action execution
  Why: The most correctness-sensitive engine code — SINGLE/RESTART/QUEUED/PARALLEL dispatch, cooldown reservation, and queue-cap logic — plus concrete action implementations have only source-level or cross-cutting guard tests.
  Evidence: `app/src/main/java/com/opentasker/core/engine/AutomationService.kt` (`dispatchTask`); `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt`; `app/src/main/java/com/opentasker/core/engine/CooldownStore.kt`; existing `ActionGuardsTest`/`PermissionDenialTest`
  Touches: JVM/instrumented tests for automation modes, cooldown races, queue caps, and representative actions in `BuiltInActions`/`NetworkActions`/`SystemActions`.
  Acceptance: Tests cover each automation mode's concurrency behavior, cooldown reservation under re-trigger, queue-cap enforcement, and success/failure paths for a representative action per family; a race regression is reproduced and locked.
  Complexity: M

- [ ] P2 — Add HTML support to the data.read action
  Why: data.read parses JSON/CSV/XML on-device, but HTML extraction still needs a real parser (jsoup), which is a dependency + F-Droid-policy decision deferred from the original reader item.
  Evidence: `app/src/main/java/com/opentasker/core/data/StructuredDataReader.kt`; https://jsoup.org/
  Touches: Gradle dependency catalog (F-Droid review), StructuredDataReader HTML branch, data.read metadata, tests.
  Acceptance: data.read supports an html format with a CSS/element selector into variables; the parser dependency is F-Droid-clean or the html branch is documented as omitted from the F-Droid track; tests cover extraction and malformed input.
  Complexity: M

### P2 -- Authoring depth, capabilities, and hardening



- [ ] P2 — Add a Received-Share (ACTION_SEND) trigger
  Why: Registering OpenTasker as a share target lets shared text/URL/file/MIME route to a chosen task with filters — a high-value everyday primitive using a standard intent filter and no special permission.
  Evidence: `app/src/main/AndroidManifest.xml`; existing event contexts under `app/src/main/java/com/opentasker/core/contexts`; https://tasker.joaoapps.com/changes/changes6.5.html
  Touches: `ACTION_SEND`/`SEND_MULTIPLE` intent filter, a share-receiver activity, `event=share` context with MIME/text filters, sanitized extras, tests.
  Acceptance: A share from any app can trigger a filtered task with the shared text/URI/MIME exposed as sanitized variables; oversized/parcelable extras are rejected; tests cover text, URL, single-file, and multi-file shares.
  Complexity: S

- [ ] P2 — Add logical AND/OR/NOT grouping for profile contexts
  Why: Profiles currently combine contexts with implicit AND only; explicit boolean grouping is a standing FOSS-competitor request and a large expressiveness gain with no new permissions.
  Evidence: `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt`; `app/src/main/java/com/opentasker/core/contexts/ContextInspector.kt`; https://github.com/henrichg/PhoneProfilesPlus/issues/158; https://github.com/henrichg/PhoneProfilesPlus/issues/161
  Touches: profile/context data model + migration, matcher boolean evaluator, context editor grouping UI, Context Inspector explanations, bundle schema/migration, tests.
  Acceptance: A profile can express nested AND/OR/NOT groups over its contexts; existing implicit-AND profiles migrate unchanged; the matcher and Context Inspector evaluate and explain the tree; export/import preserves grouping; tests cover nested and inverted cases.
  Complexity: L

- [ ] P2 — Add a unified global search across profiles, tasks, actions, variables, and scenes
  Why: Only per-list filters exist; as automation count grows, finding which task/profile references an action or variable requires manual scanning, unlike Tasker/MacroDroid.
  Evidence: per-list filters in `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationLists.kt` and `VariablesScreen.kt`; no cross-entity search present
  Touches: a search index/query over Room entities, a global search UI surface, deep links to results, search tests.
  Acceptance: A single search returns matching profiles, tasks, actions, variables, and scenes (including references to a named variable/action) with deep links; results update live; tests cover reference lookups.
  Complexity: M

- [ ] P2 — Add clipboard and contacts actions
  Why: Clipboard get/set is one of the most-requested automation primitives and is entirely absent; contacts lookup is a common companion capability.
  Evidence: no clipboard/contacts action in `app/src/main/java/com/opentasker/core/RuntimeRegistries.kt` or `core/actions`; https://www.macrodroid.com/
  Touches: `clipboard.get`/`clipboard.set` and `contacts.lookup` actions + metadata/editor, `READ_CONTACTS` runtime-permission UX and F-Droid/Play policy note, redaction, tests.
  Acceptance: Clipboard read/write works without extra permission and redacts sensitive values in logs; contacts lookup resolves a name/number into variables behind an explicit runtime-permission prompt and fails closed when denied; tests cover both plus permission denial.
  Complexity: M

- [ ] P2 — Add dynamic, per-task Quick Settings tiles and a tile-state action
  Why: The app ships a single static generic QS tile; competitors expose per-task tiles as a primary manual-trigger surface, and `TileService` is a battery-friendly first-party trigger with no polling.
  Evidence: `app/src/main/java/com/opentasker/core/contexts/QuickSettingsTileService.kt`; `app/src/main/java/com/opentasker/core/contexts/QuickSettingsTileContextEvents.kt`; https://developer.android.com/reference/android/service/quicksettings/TileService; https://tasker.joaoapps.com/userguide/en/help/ah_set_quick_setting.html
  Touches: multiple `TileService` declarations or a bindable tile registry, per-tile task/label/icon/state config, a `tile.set` action, setup guidance, tests.
  Acceptance: Users can bind a specific task to each of N app-owned tiles with configurable label/subtitle/icon and a `tile.set` action to update state at runtime; short/long-click routing works; tests cover binding and state updates.
  Complexity: M

- [ ] P2 — Make edit history a durable multi-step undo/redo including scenes
  Why: Undo restores only the latest snapshot then deletes the whole 5-entry history, there is no redo, and scene edits take no snapshot at all, so the storage layer's recoverability is unusable.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:171-247,513-530`; `app/src/main/java/com/opentasker/core/storage/EditHistoryDao.kt`
  Touches: `EditHistoryDao` cursor/redo state, undo/redo view-model APIs, scene edit snapshots, undo/redo UI, tests.
  Acceptance: Users can step back and forward across up to five edits per task/profile/scene without wiping history; scene edits are snapshotted; a redo after undo restores the newer state; tests cover multi-step undo/redo and scene recovery.
  Complexity: S

- [ ] P2 — Surface app-standby bucket and prompt for battery-optimization exemption when throttled
  Why: A `RARE`/`RESTRICTED` standby bucket throttles the per-minute alarm and workers; a 2026 automation app should detect this and guide the user, distinct from the existing OEM battery-killer copy.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt` (OEM battery guidance); https://developer.android.com/topic/performance/appstandby
  Touches: `UsageStatsManager.getAppStandbyBucket()` read, health-status model, Setup/health warning + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent, tests.
  Acceptance: The app reads the current standby bucket, warns in Setup/health when it is `RARE`/`RESTRICTED`, and offers the battery-optimization exemption flow; the state is shown in the engine-health panel; tests cover bucket-to-warning mapping.
  Complexity: S



### P3 -- New triggers, actions, and modernization

- [ ] P3 — Add a Companion Device presence trigger
  Why: `CompanionDeviceManager` presence events are a purpose-built, low-power way to trigger on a paired device (car, watch, beacon) appearing or disappearing, far cheaper than a scanning loop.
  Evidence: existing Bluetooth contexts under `app/src/main/java/com/opentasker/core/contexts`; https://developer.android.com/reference/android/companion/CompanionDeviceService; https://developer.android.com/about/versions/16/features
  Touches: `CompanionDeviceService` association flow, `event=companion_presence` context, setup UX, tests.
  Acceptance: Users can associate a companion device and fire tasks on presence appear/disappear with low-power delivery; association and revocation are handled; tests cover the presence event mapping.
  Complexity: M

- [ ] P3 — Add a USB / input-device attach-detach trigger
  Why: A standing FOSS-competitor request (dock, keyboard, charger automations) using local `UsbManager` broadcasts with no cloud dependency.
  Evidence: existing event contexts under `app/src/main/java/com/opentasker/core/contexts`; https://github.com/renyuneyun/Easer/issues/476
  Touches: `UsbManager` attach/detach receivers, `event=usb` context with device filters, setup/permission handling, tests.
  Acceptance: Attaching or detaching a matching USB/input device fires a filtered task with device attributes as variables; tests cover attach, detach, and filter matching.
  Complexity: M

- [ ] P3 — Add a shortcut-publisher action to complement the shortcut trigger
  Why: The app has a shortcut trigger but no symmetric action to publish an app shortcut or request a pinned launcher shortcut that runs a chosen task.
  Evidence: existing shortcut trigger handling; https://developer.android.com/develop/ui/shortcuts; https://github.com/henrichg/PhoneProfilesPlus/issues/160
  Touches: `ShortcutManager` dynamic/pinned publish action + metadata, task binding, icon/label config, tests.
  Acceptance: A `shortcut.publish` action creates a dynamic or requests a pinned launcher shortcut bound to a task; the pinned request handles user decline; tests cover dynamic publish and the pinned request path.
  Complexity: M

- [ ] P3 — Add an Android 16 ProgressStyle live-update notification action
  Why: Long-running tasks lack premium feedback; Android 16 `Notification.ProgressStyle` / live-update chips give ordered-progress feedback with no extra permission.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt` (notify action); https://developer.android.com/about/versions/16/features; https://tasker.joaoapps.com/changes/changes6.6.html
  Touches: notification action metadata, `ProgressStyle` builder with SDK gating, run-log integration, tests.
  Acceptance: A task can post and update an ordered-progress notification on SDK 36+, falling back gracefully below it; tests cover the builder and the fallback.
  Complexity: M

- [ ] P3 — Adopt Room 2.8 @AutoMigration and @Upsert for additive schema and writes
  Why: The DB is at version 5 with hand-written migrations and manual insert-or-update; Room 2.8 auto-migrations (schemas already exported) and `@Upsert` remove a class of migration bugs and write races while keeping manual migrations only for drops/renames.
  Evidence: `app/src/main/java/com/opentasker/core/storage/AppDatabase.kt`; `app/src/main/java/com/opentasker/core/storage/DatabaseMigrations.kt`; https://developer.android.com/jetpack/androidx/releases/room
  Touches: `@Database` autoMigrations list + spec classes, DAO `@Upsert` conversions, schema-JSON verification, migration tests.
  Acceptance: Additive schema bumps use `@AutoMigration` with committed schema JSON; profile/variable/cooldown writes use `@Upsert`; existing manual migrations remain for non-additive changes; round-trip and migration tests pass.
  Complexity: M


## Audit-Discovered Additions

- [ ] P2 — Fix Tasker `Wait` import seconds-vs-milliseconds interpretation
  Why: `waitMillis` treats the same numeric field as milliseconds when the action has ≥2 Ints but as seconds (×1000) when it has exactly one Int ≤1000, so imported Tasker Wait durations are frequently off by 1000×. Needs confirmation of Tasker's fixed Seconds/Minutes/Hours/Days/MS field layout before changing.
  Where: `app/src/main/java/com/opentasker/core/transfer/TaskerXmlImport.kt` (`waitMillis`, ~298-312)

- [ ] P3 — QUEUED profiles consume cooldown at enqueue time, not run time
  Why: In QUEUED mode the cooldown reservation is applied before a trigger is enqueued, so a later distinct trigger during that window is dropped as "cooldown active" even though it would otherwise queue behind the running task — cooldown and queueing interact unintuitively.
  Where: `app/src/main/java/com/opentasker/core/engine/AutomationService.kt` (`dispatchTask` QUEUED branch)

## Research-Driven Additions (2026-07-14 pass)

### P1 -- Variable-runtime correctness (root cause)

- [ ] P1 — Unify the uppercase=global variable-naming convention across every write path
  Why: `PersistVariableAction` writes an explicit lowercase `global_name` to the transient local scope (never persisted) yet returns Success, and the Variables editor hard-codes `isGlobal=true` for any name, so persisted "globals" that a task later writes never round-trip — three call sites disagree on the rule.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt:197-201`; `app/src/main/java/com/opentasker/core/engine/VariableStore.kt:43-44`; `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:570-580`
  Touches: a shared name-normalizer, `PersistVariableAction`, `VariableStore.set`, Variables editor validation, variable-store tests.
  Acceptance: One normalizer enforces the convention; `var.persist` either promotes or rejects a non-global target instead of silently dropping it; the editor stores/labels scope consistently with the engine; a test proves a lowercase-targeted persist round-trips or fails loudly.
  Complexity: S

- [ ] P1 — Make concurrent global write-back transactional
  Why: Each run builds its own `VariableStore`, hydrates a baseline, and writes back only its own diff, so under PARALLEL mode or manual/profile overlap two runs read the same baseline and clobber each other's global mutations with no merge or row lock.
  Evidence: `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt:31-45`; `app/src/main/java/com/opentasker/core/engine/AutomationService.kt` (`dispatchTask` PARALLEL branch)
  Touches: global hydrate/persist path, `VariableDao` write, per-variable serialization or a read→run→write transaction, concurrency tests.
  Acceptance: Two concurrent runs touching disjoint globals both persist; a run reading a global a sibling changed sees a defined, tested result; a race regression is reproduced and locked.
  Complexity: M

### P1 -- Platform-survival compliance

- [ ] P1 — Add Advanced Protection Mode awareness and graceful degradation
  Why: Android 16 `AdvancedProtectionManager` revokes Accessibility from apps not declaring the "Accessibility Tool" use, degrading automation-class apps, and OpenTasker has no detection or fallback path.
  Evidence: no `AdvancedProtectionManager`/`QUERY_ADVANCED_PROTECTION_MODE` usage in `app/src/main`; https://developer.android.com/privacy-and-security/advanced-protection-mode
  Touches: an APM status reader, Setup/engine-health surfacing, feature gating for APM-affected capabilities, SDK-gated tests.
  Acceptance: When APM is on the app detects it, warns in Setup/health, and disables/relabels affected features with a documented manual fallback instead of failing opaquely; below API 36 the check is a no-op; tests cover on/off mapping.
  Complexity: M

- [ ] P1 — Implement the ACCESS_LOCAL_NETWORK runtime request and graceful-denial path
  Why: Once enforced for target-37 LAN traffic, HTTP-to-private-IP, Ping, WoL, and future MQTT/HA bridges `EPERM` without the permission; only the device-evidence matrix is tracked (blocked) — the request/fallback code is not implemented.
  Evidence: `app/src/main/java/com/opentasker/core/actions/NetworkActions.kt` (`enforceHttpPolicy`); `app/src/main/AndroidManifest.xml`; https://developer.android.com/privacy-and-security/local-network-permission
  Touches: manifest permission, Setup permission row, LAN action pre-checks, denial/revocation UX, JVM policy tests.
  Acceptance: LAN actions request `ACCESS_LOCAL_NETWORK` when targeting a private host, fail closed with a clear message when denied/revoked, and re-request cleanly; non-LAN actions are unaffected; tests cover granted/denied/revoked mapping (device evidence remains separately blocked).
  Complexity: M

### P2 -- Exported-surface and intent hardening

- [ ] P2 — Sweep PendingIntent immutability, explicit components, and unique request codes
  Why: `NotifyAction` derives notification-button request codes from `notifId.hashCode()*31+i` over a user-controllable `Int` id, so with `FLAG_UPDATE_CURRENT` a colliding code lets a newer notification overwrite an older button intent and fire the wrong task; a full immutability/explicit-target sweep is not tracked.
  Evidence: `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt:74-83`; https://mas.owasp.org/MASTG/tests/android/MASVS-PLATFORM/MASTG-TEST-0030/
  Touches: notification/tile/alarm PendingIntent construction, an atomic request-code source, a source guard for mutable/implicit PendingIntents, tests.
  Acceptance: Every PendingIntent is immutable with an explicit component and a collision-free request code; a test proves two notifications with adjacent ids keep independent button intents; a source guard fails on a mutable/implicit PendingIntent.
  Complexity: S

- [ ] P2 — Adopt Safer-Intents and unsafe-intent-launch detection for the external-trigger surface
  Why: The engine unparcels and re-dispatches intents from triggers; API 36 `intentMatchingFlags="enforceIntentFilter"` and StrictMode `detectUnsafeIntentLaunch()` close the classic redirection sink before intent dispatch is generalized.
  Evidence: exported receivers in `app/src/main/AndroidManifest.xml`; existing debug StrictMode in `app/src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt`; https://developer.android.com/privacy-and-security/risks/intent-redirection
  Touches: exported-receiver manifest attributes, debug StrictMode VmPolicy, forwarded-intent sanitization, contract tests.
  Acceptance: Exported receivers enforce their intent filters where supported, debug builds add `detectUnsafeIntentLaunch()`, forwarded extras are validated before dispatch, and tests cover a rejected non-matching/redirected intent.
  Complexity: S

- [ ] P2 — Harden FileActions against symlink TOCTOU
  Why: `safeUserFile` canonicalizes and containment-checks the path, but `file.write`/`file.append` then `mkdirs()` and stream through components that can be swapped for symlinks between check and I/O.
  Evidence: `app/src/main/java/com/opentasker/core/actions/FileActions.kt:183-190`
  Touches: file open path (`NOFOLLOW_LINKS`/`O_NOFOLLOW`), post-open canonical re-verification, path-escape tests.
  Acceptance: File I/O opens with no-follow semantics and re-verifies containment immediately before read/write; a test proves a symlink inside the sandbox pointing outside it is refused.
  Complexity: S

### P2 -- Engine reliability

- [ ] P2 — Bound the Termux dispatch rate-limit map
  Why: `lastDispatchTimes` keeps a process-lifetime entry per template-expanded (user-controlled) executable string and never prunes, leaking memory across varying script paths.
  Evidence: `app/src/main/java/com/opentasker/core/actions/ScriptActions.kt:78-90`
  Touches: the rate-limit map (LRU cap or age eviction), a bound test.
  Acceptance: The map is capped or evicts entries older than the dispatch interval; a test drives many distinct executables and asserts a bounded size.
  Complexity: S

- [ ] P2 — Wire scene SLIDER controls to fire a task and surface the value
  Why: `SceneOverlayService` builds the slider `SeekBar` with no change listener, so a scene slider drives no task and exposes no value, unlike BUTTON tap/long-press.
  Evidence: `app/src/main/java/com/opentasker/core/scenes/SceneOverlayService.kt:186-193`
  Touches: slider element binding, task-fire-with-value path, scene element metadata/docs, overlay tests.
  Acceptance: Dragging a scene slider fires its bound task with the value as a variable (or the element is explicitly documented as display-only and rendered as such); tests cover the value-to-task mapping.
  Complexity: S

- [ ] P3 — Preserve event-pulse continuity across matcher reconcile
  Why: `pulseSequence` is seeded at 0 per matcher rebuild, and every `reloadProfiles` resets both the sequence and the compared baseline, so an event-context pulse in flight during an edit/reconcile can drop or a buffered replay double-fire.
  Evidence: `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt:41-59,137-147`
  Touches: pulse-sequence carry-over across rebuilds (or reconcile debounce away from pulse boundaries), matcher reconcile tests.
  Acceptance: An event fired during a reconcile is delivered exactly once; a test simulates edit-during-pulse and asserts no drop or duplicate.
  Complexity: S

- [ ] P3 — Scope sub-task input variables to the child task
  Why: `runSubTask` sets extra args into the parent's current scope before the child scope is pushed, so lowercase inputs remain in the parent task's later actions after the sub-task returns.
  Evidence: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:250-255`
  Touches: sub-task scope ordering, sub-task variable tests.
  Acceptance: Sub-task inputs are visible to the child and cleaned up when its scope pops; a test proves the parent's later actions do not see the child's input names.
  Complexity: S

### P2 -- Local-first authoring primitives

- [ ] P2 — Add a temporary / auto-revert state action
  Why: Apply-a-state-then-restore-the-previous-state-after-a-duration is a top PhoneProfilesPlus request and reuses the app's snapshot/undo infrastructure without any cloud surface.
  Evidence: existing state actions in `app/src/main/java/com/opentasker/core/actions/SystemActions.kt`; edit-history snapshot infra in `app/src/main/java/com/opentasker/core/storage/EditHistoryDao.kt`; https://github.com/henrichg/PhoneProfilesPlus/discussions/62
  Touches: a `state.temporary`/auto-revert action, a durable revert timer (WorkManager/alarm) surviving process death, action metadata, tests.
  Acceptance: A task can set a state for a bounded duration and reliably restore the prior value after it, even across a process restart; the pending revert is inspectable; tests cover apply, timed revert, and restart survival.
  Complexity: M

- [ ] P2 — Add a Switch IME / Set-Keyboard action with get-keyboard-info
  Why: Tasker 6.5 ships keyboard switching and info; "work vs personal keyboard" is a common local automation with no cloud dependency.
  Evidence: no IME action in `app/src/main/java/com/opentasker/core/RuntimeRegistries.kt`; `InputMethodManager`; https://tasker.joaoapps.com/changes/changes6.5.html
  Touches: an `ime.set`/`ime.info` action, enabled-IME enumeration, permission/limitation notes, redaction, tests.
  Acceptance: The action switches to a chosen enabled IME (within platform limits) and exposes current-keyboard info as variables, failing closed when the target IME is unavailable; tests cover selection and the unavailable path.
  Complexity: S

- [ ] P2 — Add a media/audio-playback-active context
  Why: "any app is actively playing audio" is requested in both PhoneProfilesPlus and Easer and enables pause/resume and focus automations from a local media API.
  Evidence: existing event contexts under `app/src/main/java/com/opentasker/core/contexts`; `MediaSessionManager`/`AudioManager`; https://github.com/henrichg/PhoneProfilesPlus/discussions/62
  Touches: an `event=media_active` (or level) context, notification-listener/media permission handling, active-session filters, tests.
  Acceptance: A profile can match while audio is actively playing (optionally filtered by package) and deactivate on stop; permission is requested/handled and fails closed; tests cover start/stop and filter matching.
  Complexity: M

- [ ] P3 — Add offline template import from clipboard / QR text
  Why: A local-first answer to MacroDroid's cloud Template Store — one-tap import of a shared task from clipboard or scanned text, preserving the no-server ethos.
  Evidence: `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt`; existing import review in `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt`; https://tasker.joaoapps.com/changes/changes6.6.html
  Touches: clipboard/QR-text bundle decode, existing import-review pipeline reuse, size/format validation, import tests.
  Acceptance: A valid bundle pasted from the clipboard (or scanned) routes through the existing disabled-by-default import review before any Room write; malformed/oversized input is rejected; tests cover paste import and rejection.
  Complexity: M

- [ ] P3 — Add an inverted / outside-geofence location condition
  Why: "fire when NOT at a place" needs geofence-exit semantics distinct from generic AND/OR/NOT grouping and is a recurring FOSS request.
  Evidence: `app/src/main/java/com/opentasker/core/contexts/FossGeofenceEvaluator.kt`; `app/src/main/java/com/opentasker/core/location/LocationDwellState.kt`; https://github.com/henrichg/PhoneProfilesPlus/discussions/62
  Touches: Location context inversion flag, matcher/dwell handling for outside state, editor toggle, inspector copy, tests.
  Acceptance: A Location context can match while outside its radius (with dwell), migrates existing inside-only configs unchanged, and is explained in the Context Inspector; tests cover inside/outside/dwell transitions.
  Complexity: S

- [ ] P3 — Add an "all Bluetooth devices disconnected" state
  Why: Firing only when the final BT device drops (not per-device) is a small, well-scoped Easer request the current per-device contexts cannot express.
  Evidence: existing Bluetooth contexts under `app/src/main/java/com/opentasker/core/contexts`; https://github.com/renyuneyun/Easer/issues
  Touches: a last-device-left Bluetooth state, connection-count tracking, editor option, tests.
  Acceptance: The context activates only when zero BT devices remain connected and deactivates on the first reconnect; tests cover multi-device connect/disconnect sequences.
  Complexity: S

### P2 -- Data-at-rest and forward-compat

- [ ] P2 — Encrypt the whole automation database at rest (SQLCipher, F-Droid-compatible)
  Why: Task logic, variables, and logs are plaintext SQLite; whole-DB encryption is distinct from the tracked per-value secret variables and materially strengthens the local-privacy story.
  Evidence: `app/src/main/java/com/opentasker/core/storage/AppDatabase.kt`; `app/src/main/java/com/opentasker/core/storage/BackupEncryption.kt`; https://mas.owasp.org/MASVS/06-MASVS-CRYPTO/
  Touches: SupportFactory/SQLCipher wiring, key derivation/storage (Keystore), F-Droid build/16 KB-alignment decision, migration from plaintext, backup/restore interplay, tests.
  Acceptance: The database opens only with the derived key; existing plaintext DBs migrate once; wrong-key fails closed; the F-Droid track either ships an aligned build or documents the encrypted DB as an off-F-Droid variant; migration and open/close are tested.
  Complexity: L

- [ ] P2 — Audit 16 KB page-size alignment for native libraries
  Why: Any bundled `.so` (SQLCipher, future MQTT/native deps) must be 16 KB-aligned for current/near-future devices; a one-time build audit prevents load crashes.
  Evidence: `app/build.gradle.kts`; `gradle/libs.versions.toml`; https://developer.android.com/guide/practices/page-sizes
  Touches: AGP/NDK build config, dependency `.so` inspection, a release-gate check.
  Acceptance: The release gate reports whether any packaged `.so` is not 16 KB-aligned and fails on a regression; a pure-JVM build with no native libs passes trivially and is recorded as such.
  Complexity: S

- [ ] P2 — Migrate to predictive-back (OnBackInvokedCallback) for target 36
  Why: `onBackPressed()` is no longer invoked at target 36 and back animations are on by default, affecting scene/dialog dismissal.
  Evidence: back handling in `app/src/main/java/com/opentasker/app/MainActivity.kt` and scene/dialog dismissal in `app/src/main/java/com/opentasker/ui/screens`; https://developer.android.com/about/versions/16/behavior-changes-16
  Touches: `OnBackInvokedCallback`/Compose predictive-back APIs, scene/dialog dismissal, manifest `enableOnBackInvokedCallback`, UI tests.
  Acceptance: Back navigation and scene/dialog dismissal work under predictive back with no lost `onBackPressed` behavior; tests or a documented device-rehearsal note cover gesture and 3-button modes.
  Complexity: S

### P3 -- New platform-signal triggers and actions

- [ ] P3 — Add a screen-recording-detected trigger
  Why: API 35 `DETECT_SCREEN_RECORDING` lets a task react when recording of this app/screen starts or stops (e.g. hide sensitive scenes), from a purely on-device signal.
  Evidence: existing event contexts under `app/src/main/java/com/opentasker/core/contexts`; https://developer.android.com/about/versions/15/features
  Touches: an `event=screen_recording` context with SDK gating, callback registration/teardown, setup copy, tests.
  Acceptance: On SDK 35+ a task fires on recording start/stop with graceful no-op below it; registration is torn down with the source; tests cover the state mapping and the low-SDK fallback.
  Complexity: S

- [ ] P3 — Add an app archive / unarchive action
  Why: API 35 `PackageInstaller.requestArchive`/`requestUnarchive` frees storage while keeping user data — a scriptable local cleanup primitive with no store dependency.
  Evidence: no package-archive action in `app/src/main/java/com/opentasker/core/actions`; https://developer.android.com/about/versions/15/features
  Touches: an `app.archive`/`app.unarchive` action, package targeting/validation, SDK gating and result handling, tests.
  Acceptance: On SDK 35+ the action archives/unarchives a named package and reports the outcome, failing closed below it or when denied; tests cover the request and the unsupported path.
  Complexity: M

