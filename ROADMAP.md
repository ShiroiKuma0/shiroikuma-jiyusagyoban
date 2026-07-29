# OpenTasker Roadmap

**Current app version:** 0.2.78
**Last updated:** 2026-07-29

Only open work belongs here; git history and `CHANGELOG.md` are the release record.

## Audit-Discovered Additions (2026-07-17 deep audit)

Findings surfaced by the 2026-07-17 audit that were verified but deferred (the audit's
direct fixes shipped in v0.2.76 across the engine, actions, context, UI, and theming layers).

### P2 — Reliability and product depth

- [ ] P2 — Share one hot calendar/sun ticker across EVENT contexts
  Why: Every EVENT context instantiates its own 1 Hz clock loop plus a per-minute CalendarProvider query, so N event contexts = N wake loops and N calendar reads per minute even for NFC-only profiles — contradicting the demand-gated monitor contract and reading calendar data profiles never asked for.
  Where: `app/src/main/java/com/opentasker/core/contexts/EventContextSourceImpl.kt`; `app/src/main/java/com/opentasker/core/contexts/CalendarSunContextEvents.kt`; `app/src/main/java/com/opentasker/core/contexts/StateContextSourceImpl.kt`; `app/src/main/java/com/opentasker/core/contexts/LocationContextSourceImpl.kt`
  Acceptance: One shared hot ticker (shareIn/SharedFlow) drives sun/calendar pulses; the CalendarProvider query runs only when at least one enabled profile references `event=calendar`.
  Research update (2026-07-29): Broaden the shared source into a demand-gated observation bus used by both the engine and Context Inspector; Inspector must subscribe only while visible and expose Loading/Ready/Stale/Error plus observation age instead of creating permanent duplicate collectors.

- [ ] P2 — Localize the remaining hardcoded English on secondary surfaces
  Why: The app shell nav/top-bar/retention picker, every ViewModel snackbar/toast, and the Context Inspector, Run Log, Diagnostics, Flow, and scene-toast strings are hardcoded English, and the localization guard's file list skips exactly those files — so localized builds show a mixed-language UI. Dead `nav_*`/`empty_profiles_*`/`workspace_*` resources already exist unused.
  Where: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt`; `ActiveAutomationViewModel.kt`; `ContextInspectorScreen.kt`; `RunLogScreenContent.kt`; `DiagnosticsScreen.kt`; `RunLogFilters.kt`; `AutomationFlowGraph.kt`; `app/src/test/java/com/opentasker/ui/LocalizationSourceTest.kt`
  Acceptance: These surfaces resolve through `R.string` (VM messages as resource IDs resolved at the collector); the guard covers the added files and its regex catches `body =`/`values =` argument strings.

### P3 — Correctness, a11y, and polish

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

- [ ] P2 — Add a Locale grant management UI (inspect and revoke individual grants)
  Why: Revocable Locale execution grants are issued/validated and auto-revoked on task deletion, but users cannot yet browse issued grants or revoke a specific one from the app.
  Evidence: `app/src/main/java/com/opentasker/core/plugins/locale/LocaleGrantStore.kt` (`grants()`, `revoke()`); `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt`
  Touches: Setup/Locale UI surface, `LocaleGrantStore` wiring, grant-row composables with task-name resolution, UI tests.
  Acceptance: Users can view each issued grant (task name/id) and revoke it; revoking blocks subsequent fires for that grant; the list updates live and shows an empty state when no grants exist.
  Complexity: S


### P1 -- Release evidence and device trust

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
  Research update (2026-07-29): The exporter now emits schema 2 and accepts schemas 1–2, so the original premise is stale; treat the remaining item as P0 data integrity. Import review must enumerate same-name variable conflicts with preserve/rename/explicit-replace choices, never declassify an existing secret implicitly, and remap every task reference inside profiles, scenes, `task.run`, and notification buttons before one atomic write.

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

- [ ] P1 — Add a live variable inspector for running and finished tasks
  Why: Run logs show per-expression template diagnostics but no snapshot of the resulting variable values, and there is no runtime global-variable view, so users cannot see what a task actually set; the Variables screen reads only the (currently unwritten) persisted vault.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/RunLogScreenContent.kt` (`ExpressionDebugger`); `app/src/main/java/com/opentasker/ui/screens/VariablesScreen.kt`; `app/src/main/java/com/opentasker/core/engine/VariableStore.kt`; CLAUDE.md "Later roadmap ideas" (variable inspector)
  Touches: task-run variable snapshot capture, run-log/inspector UI, global-variable runtime view, redaction of secret provenance, inspector tests.
  Acceptance: After a manual or triggered run, users can inspect task/event/global variable values with per-step deltas and redaction; a live global view reflects the durable global runtime; depends on the durable-globals P0.
  Complexity: M


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
  Research update (2026-07-29): Flat `ContextSpec.invert` and `orGroup` already execute and are explained by the Inspector; narrow this item to nested-tree authoring, a backward-compatible model/bundle migration, accessible group editing, and nested explanation tests.

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
  Research update (2026-07-29): Correct the stale baseline to Room schema 8; adopt these APIs only where exported schema JSON proves the change is additive, retaining explicit migrations for semantic rewrites.


## Audit-Discovered Additions

## Research-Driven Additions (2026-07-14 pass)

### P1 -- Variable-runtime correctness (root cause)

### P1 -- Platform-survival compliance

### P2 -- Exported-surface and intent hardening

### P2 -- Engine reliability

- [ ] P3 — Preserve event-pulse continuity across matcher reconcile
  Why: `pulseSequence` is seeded at 0 per matcher rebuild, and every `reloadProfiles` resets both the sequence and the compared baseline, so an event-context pulse in flight during an edit/reconcile can drop or a buffered replay double-fire.
  Evidence: `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt:41-59,137-147`
  Touches: pulse-sequence carry-over across rebuilds (or reconcile debounce away from pulse boundaries), matcher reconcile tests.
  Acceptance: An event fired during a reconcile is delivered exactly once; a test simulates edit-during-pulse and asserts no drop or duplicate.
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

## Research-Driven Additions

### P0 — Now: data integrity and shipped-runtime truth

### P1 — Next: trust, reliability, and complete authoring

- [ ] P1 — Add a searchable installed-app picker with inspector handoff
  Why: Application contexts and multiple actions/plugin fields require raw package strings, a documented onboarding failure in Easer and a usability gap compared with Automate's inspector.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/ContextEditorDialogs.kt:209-245`; `core/actions/ActionMetadata.kt:351,562`; https://github.com/renyuneyun/Easer/issues/486; https://news.ycombinator.com/item?id=42254433.
  Touches: package/app query repository, shared picker, Context Inspector handoff, application/notification/action/plugin editors, tests.
  Acceptance: Users can search by app label or package, see icon/label/package, choose from visible installed apps, use the latest observed value where safe, and fall back to validated manual entry without requesting `QUERY_ALL_PACKAGES`; all package-bearing editors share the component.
  Complexity: M

- [ ] P1 — Expose and reconcile the execution semantics already modeled by the engine
  Why: Profile exit tasks, action conditions, continue-on-error, and action ordering exist in models/runtime, while task collision mode is stored/displayed but not consumed; authoring cannot control them coherently.
  Evidence: `app/src/main/java/com/opentasker/core/model/Profile.kt`; `core/model/Task.kt`; `core/engine/TaskRunner.kt:89,265-271`; `core/engine/AutomationService.kt:278-339`; `ui/screens/EditorDialogs.kt`; `ActionEditorDialogs.kt`; https://tasker.joaoapps.com/userguide/en/activity_actionedit.html.
  Touches: profile/task/action dialogs, reorder mutation/snapshots, execution-mode policy and migrations, engine/editor tests.
  Acceptance: Users can select/clear an exit task, reorder actions atomically, edit condition and continue-on-error, and choose one documented collision policy; legacy task `collisionMode` is either enforced for manual/nested/external runs or migrated away without contradictory UI; round-trip and behavior tests cover each control.
  Complexity: M

- [ ] P1 — Make dynamic action forms typed, validated, and lossless
  Why: `FieldType.DROPDOWN` has no option model and renders as free text, number filtering accepts invalid syntax, validation is mostly nonblank, and editing reconstructs only known keys so forward-compatible arguments are dropped.
  Evidence: `app/src/main/java/com/opentasker/core/actions/ActionMetadata.kt:7-20`; `ui/screens/ActionEditorDialogs.kt:151-163,203-224,282-302`.
  Touches: action-field option/validator schema, typed input components, per-action validation, unknown-argument preservation, registry coverage tests.
  Acceptance: Every dropdown is a real selector with stable stored values; numbers enforce documented ranges/types; task/app/file fields use dedicated pickers; invalid configurations cannot save; editing and saving an action preserves unknown arguments byte-for-byte; registry tests require a renderer and validator for every field.
  Complexity: M

- [ ] P1 — Show active executions and support cooperative cancellation
  Why: `AutomationService` tracks running jobs privately while the UI exposes only completed logs, leaving users unable to identify or stop a runaway automation.
  Evidence: `app/src/main/java/com/opentasker/core/engine/AutomationService.kt:117-118,162-181`; `TaskRunner.kt`; `ui/screens/RunLogScreenContent.kt`; https://github.com/ChaoMixian/vFlow/issues/67.
  Touches: execution registry/state flow, task-runner step reporting, cancellation propagation through sub-tasks/network/scripts/delays, active-runs UI, run-log outcomes/tests.
  Acceptance: Active task/profile/source/start time/current step are visible; Cancel reaches nested and bounded blocking actions, runs cleanup, and records a terminal `Cancelled` outcome; completed jobs disappear without leaks; tests cover parallel, queued, nested, timeout, and cancel races without claiming pause or rewind.
  Complexity: M

### P2 — Later: observability, precision, and staged modernization

- [ ] P2 — Paginate and export every retained run log
  Why: Retention can keep far more than 100 rows, but `RunLogDao.getRecentFlow()` and the UI make every older retained record unreachable.
  Evidence: `app/src/main/java/com/opentasker/core/storage/RunLogDao.kt`; `ui/screens/ActiveAutomationViewModel.kt:146-153`; `RunLogScreenContent.kt`.
  Touches: keyset-paged DAO/query, database-backed filters, run-log UI, redacted JSON/CSV export, tests.
  Acceptance: Every retained row is reachable with stable timestamp/id keyset pagination; task/status/date/query filters run in SQL and survive refresh; JSON/CSV export includes the selected range with existing secret/redaction policy; concurrent inserts do not duplicate or skip rows.
  Complexity: M
  Research update (2026-07-29): Include a count/age preview plus export-or-confirm before a retention reduction prunes rows, and replace `diagnostics.traces.take(4)` with an accessible expand/collapse path for every retained trace.

- [ ] P2 — Add an optional foreground activity/component constraint
  Why: Package-only application contexts are too broad for apps with multiple activities, and `AppUsageMonitor` currently discards the observed `className`.
  Evidence: `app/src/main/java/com/opentasker/automation/app/AppUsageMonitor.kt:64-113`; `core/contexts/ApplicationContextEvents.kt`; https://github.com/keymapperorg/KeyMapper/issues/1343.
  Touches: application context schema/editor/migration, usage event payload and inspector, exact/glob matcher, OEM/device tests.
  Acceptance: A context can optionally match package plus exact/glob component; Context Inspector shows the observed component; missing OEM data is reported as `component unavailable` and never silently degrades to package-only matching; existing package-only contexts remain unchanged.
  Complexity: M

- [ ] P2 — Upgrade the synchronized Kotlin/KSP/Compose runtime batch after trust fixes
  Why: Stable Kotlin 2.4.0, KSP 2.3.10, Compose BOM 2026.06.00, Lifecycle 2.11.0, and Coroutines 1.11.0 include compiler, AGP-9, incremental-cache, flow, and R8 fixes that should move together rather than drift independently.
  Evidence: `gradle/libs.versions.toml`; https://kotlinlang.org/docs/whatsnew24.html; https://github.com/google/ksp/releases/tag/2.3.10; https://developer.android.com/develop/ui/compose/bom/bom-mapping; https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0.
  Touches: version catalog/wrapper compatibility, generated Room/KSP output, Compose/Lifecycle/coroutine call sites, verification metadata, local gate.
  Acceptance: The synchronized versions resolve without dynamic artifacts; a clean local gate passes JVM tests, blocking lint, Room schema generation, Play/F-Droid release builds, and two configuration-cache runs; Gradle remains 9.4.1 unless a separate compatibility proof justifies a wrapper change; rollback is one catalog commit.
  Complexity: M
  Research update (2026-07-29): Target Kotlin 2.4.10, not 2.4.0, and include migration from pre-release `kotlinx.collections.immutable` 0.3.8 to stable 0.5.1; keep KSP 2.3.10, Compose BOM 2026.06, Lifecycle 2.11, and Coroutines 1.11 synchronized.

## Research-Driven Additions (2026-07-23 pass)

As of 2026-07-29, the app sets `targetSdk = 37`; the P1 items below are mandatory Android 17 behavior changes that are only partially handled (code-actionable now, no device required to migrate the APIs). Items already present in ROADMAP.md or Roadmap_Blocked.md (clipboard/contacts, share-target, companion-device, USB, screen-recording, app-archive, ProgressStyle, predictive-back, 16 KB alignment, MQTT/UnifiedPush/HA, dry-run preflight, media-active context, global search, AND/OR/NOT groups, live variable inspector, active-execution cancellation, automation-mode test coverage, dependency batch) are intentionally NOT repeated here.

### P1 — Android 17 (target SDK 37) platform survival

### P2 — Observability, debuggability, and reliability

- [ ] P2 — Add unit coverage for the context monitors and core Compose form state
  Why: The most correctness-sensitive untested code is the monitor lifecycle (WiFi/AppUsage/shake/scheduler) and the large Compose editors' validation/state; engine and context sources are well covered but these seams have <5 test files.
  Evidence: `app/src/main/java/com/opentasker/automation/network/WiFiNetworkMonitor.kt`; `app/src/main/java/com/opentasker/automation/app/AppUsageMonitor.kt`; `app/src/main/java/com/opentasker/automation/scheduler/TimeEventScheduler.kt`; `app/src/main/java/com/opentasker/ui/screens/EditorDialogs.kt`; existing thin `app/src/test/java/com/opentasker/ui/screens` set
  Touches: JVM/Robolectric or fake-driven tests for monitor register/unregister/permission-loss/re-register, and editor validation/state-restore tests.
  Acceptance: Each monitor has tests for start, permission-missing pause, and teardown-without-leak; representative editors have validation and rotation/state-restore tests; a regression in monitor teardown or editor validation is reproduced and locked.
  Complexity: M


### P3 — New platform triggers and actions no FOSS competitor exposes

- [ ] P3 — Author real system Zen rules with device effects (AutomaticZenRule + ZenDeviceEffects)
  Why: `dnd.set` only calls `setInterruptionFilter`, a transient global toggle; `AutomaticZenRule` lets a task create/own a named Zen rule and `ZenDeviceEffects` (grayscale, dim wallpaper, night mode) as first-class actions competitors mostly lack.
  Evidence: `app/src/main/java/com/opentasker/core/actions/SettingsActions.kt:222` (`setInterruptionFilter`); https://developer.android.com/about/versions/15/features; https://developer.android.com/reference/android/app/NotificationManager#addAutomaticZenRule(android.app.AutomaticZenRule)
  Touches: `zen.rule.set`/`zen.rule.clear` actions with SDK gating, `ZenDeviceEffects` options, notification-policy-access setup copy, action metadata, tests.
  Acceptance: On supported SDKs a task can create/update/own a named Zen rule with selected device effects and remove it, falling back to the existing `dnd.set` behavior below the floor; policy-access is checked and fails closed; tests cover rule create/clear and the low-SDK fallback.
  Complexity: M

- [ ] P3 — Add Bluetooth key-loss / encryption-change security triggers
  Why: Android 16 adds `ACTION_KEY_MISSING` (bond broken by remote) and `ACTION_ENCRYPTION_CHANGE` intents — purpose-built for security automations (e.g. lock/notify when a paired device loses its bond) that the current per-device connect/disconnect contexts cannot express.
  Evidence: `app/src/main/java/com/opentasker/core/contexts/BluetoothContextEvents.kt`; https://developer.android.com/about/versions/16/behavior-changes-16
  Touches: `event=bluetooth_key_missing`/`bluetooth_encryption_change` context mappings with SDK gating, receiver registration/teardown, editor options, tests.
  Acceptance: On SDK 36+ a task fires on bond-loss / encryption-change for a matching device with device attributes as sanitized variables; registration is torn down with the source; tests cover the state mapping and the low-SDK no-op.
  Complexity: S

- [ ] P3 — Turn Advanced Protection detection into a live callback and trigger
  Why: `AdvancedProtectionReader` reads APM state once via reflection for a static warning; `registerAdvancedProtectionCallback` gives live state changes, enabling an `event=advanced_protection` trigger and an accurate "N profiles degraded" banner instead of a stale poll — and it gates the roadmap's bounded-accessibility eval.
  Evidence: `app/src/main/java/com/opentasker/core/diagnostics/AdvancedProtectionReader.kt`; `app/src/main/java/com/opentasker/core/diagnostics/EngineHealthReader.kt`; https://developer.android.com/privacy-and-security/advanced-protection-mode
  Touches: reflection-safe callback registration/teardown, an `event=advanced_protection` context, Diagnostics/Setup live banner, pure mapping tests.
  Acceptance: On SDK 36+ APM enable/disable updates the UI live and can fire a task; registration fails closed and is torn down; below the floor it is a labeled no-op; the state mapping is unit-tested.
  Complexity: S

- [ ] P3 — Re-add an SMS/MMS-received trigger with Android 17 OTP-delay handling
  Why: The SMS-received trigger was removed as unsupported; it is a top everyday automation, but at API 37 standard-SMS OTP content is withheld for 3 hours from non-default-SMS apps, so it can only be re-added correctly with explicit delay/exemption handling.
  Evidence: CHANGELOG entry removing "unsupported SMS-received trigger advertising"; `app/src/main/AndroidManifest.xml` (SMS permission policy, Play build omits it); https://developer.android.com/about/versions/17/behavior-changes-17 (SMS OTP protection)
  Touches: `event=sms_received` context with sender/body filters (standard/F-Droid track only), sanitized extras, an in-UI note about the API-37 OTP delay and default-SMS/companion exemption, SDK-gated behavior, tests.
  Acceptance: On the SMS-permitted build a filtered SMS triggers a task with sanitized sender/body variables; the editor discloses the API-37 OTP-delay limitation; Play builds keep the trigger absent; tests cover filter matching and the permission-absent path.
  Complexity: M

- [ ] P3 — Assert read-only native libraries in the release gate (Android 17 System.load)
  Why: API 37 throws `UnsatisfiedLinkError` for any `.so` loaded via `System.load()` that is not read-only; if SQLCipher or a native MQTT client ever ships (both on the roadmap), a non-read-only `.so` would crash at load — cheap to gate alongside the existing 16 KB-alignment check.
  Evidence: `app/build.gradle.kts`; existing 16 KB-alignment roadmap item; https://developer.android.com/about/versions/17/behavior-changes-17 (Safer native DCL)
  Touches: the release-gate `.so` inspection (extend the 16 KB check), a pure-JVM trivial-pass path, gate reporting.
  Acceptance: The release gate reports whether any packaged `.so` is not read-only and fails on a regression; a pure-JVM build with no native libs passes trivially and is recorded as such.
  Complexity: S

## Research-Driven Additions

### P0 — Data integrity and runtime truth

- [ ] P0 — Make the action capability contract total and executable
  Why: Known-but-unlisted actions default to `Supported`; `app.kill` always fails, `screen.timeout` needs unexposed Write Settings access, and implementation/metadata/setup/distribution truth can drift independently.
  Evidence: `app/src/main/java/com/opentasker/core/capabilities/ActionCapabilities.kt:33-71`; `app/src/main/java/com/opentasker/core/actions/AppActions.kt:46-54`; `app/src/main/java/com/opentasker/core/actions/SettingsActions.kt:101-124,398-420`; `app/src/main/AndroidManifest.xml`; Android `Settings.ACTION_MANAGE_WRITE_SETTINGS`.
  Touches: `RuntimeRegistries.kt`, `ActionMetadata.kt`, `ActionCapabilities.kt`, `AutomationSensitivity.kt`, manifest variants, `PermissionOnboardingScreen.kt`, README truth tests.
  Acceptance: Every runtime or engine-handled action has exactly one metadata/capability/sensitivity/distribution/setup contract; `Supported` actions can succeed under a satisfiable fixture, permanent stubs are `Unsupported`, and special-access actions expose a working grant/deep-link and revoked-state failure; the registry-derived action count cannot drift from README.
  Complexity: M

### P1 — Reliability, recovery, and explainability

- [ ] P1 — Add execution admission, feedback-loop warnings, and a storm circuit breaker
  Why: `PARALLEL` retriggers launch without a concurrency ceiling, and direct task/context feedback chains can repeatedly exercise powerful actions; automation-security studies find these interactions both common and difficult for users to diagnose.
  Evidence: `app/src/main/java/com/opentasker/core/engine/AutomationService.kt:474-483`; `app/src/main/java/com/opentasker/core/capabilities/AutomationSensitivity.kt`; USENIX Security 2025 automation-app abusability study; TAPInspector; SOUPS 2023 conflict study.
  Touches: engine admission controller, profile/task dependency graph, risk analyzer, run-log skip reasons, Diagnostics, profile-enable/import review, engine tests.
  Acceptance: Configured global and per-profile concurrency/burst limits bound every mode; repeated direct cycles trip a persisted cooldown/circuit state and log the causal chain; the analyzer warns only on explainable high-confidence feedback/data-to-power paths with an explicit acknowledgement; ordinary independent profiles remain unaffected.
  Complexity: L

- [ ] P1 — Broker external task runs outside the broadcast lifetime
  Why: `AutomationTargetReceiver` holds `goAsync()` until the entire task finishes, but Android expects broadcast work to complete in roughly 10 seconds and tasks may wait for 30 minutes.
  Evidence: `app/src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt:50-95`; `app/src/main/java/com/opentasker/core/actions/NotificationActionReceiver.kt`; `app/src/main/java/com/opentasker/core/actions/BuiltInActions.kt:304-327`; https://developer.android.com/develop/background-work/background-tasks/broadcasts.
  Touches: versioned external contract, `AutomationTargetReceiver`, `AutomationService`, execution IDs/status storage (reuse the active-execution registry item), Locale fire bridge, manifest, IPC/service tests.
  Acceptance: A versioned asynchronous run request authenticates and validates, enqueues to service-owned execution, returns `accepted` plus an execution ID, and finishes promptly without claiming terminal success; callers can query terminal status by ID and the normal redacted Run Log records it; legacy callers get an explicit protocol response; a task waiting longer than 10 seconds survives receiver completion; unknown/unauthorized callers remain denied.
  Complexity: M

- [ ] P1 — Derive Setup requirements from enabled automations
  Why: Setup presents a broad platform checklist even for an empty workspace, obscuring the difference between a blocker for an enabled profile, an optional integration, and a reliability optimization.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt`; `app/src/main/java/com/opentasker/core/capabilities/ActionCapabilities.kt`; registered context-source requirements; Android in-context permission guidance.
  Touches: the P0 capability contract, capability/context requirement resolver, Setup sections/progress, action/context editor prompts, profile-enable preflight, permission-revocation observer, UI tests.
  Acceptance: Setup separates Engine baseline, Needed by enabled automations, Optional integrations, and Reliability; each row names the dependent profiles/actions and deep-links to a working grant path; an empty workspace has no automation-specific blockers; add/enable/run and revocation update requirements immediately.
  Complexity: M

- [ ] P1 — Make Diagnostics health freshness- and failure-aware
  Why: A prior observation can leave a source looking active, and aggregate health can hide loading, stale, failed, or retrying subsystems.
  Evidence: `app/src/main/java/com/opentasker/core/diagnostics/EngineHealthReader.kt`; `app/src/main/java/com/opentasker/ui/screens/DiagnosticsScreen.kt`; `app/src/main/java/com/opentasker/ui/screens/ContextInspectorScreen.kt`; engine heartbeat and monitor state stores.
  Touches: the existing demand-gated observation-bus item, pure health-reason model, timestamped source states, heartbeat/monitor readers, Diagnostics and Inspector status UI, retry/deep-link actions, active-execution state, tests.
  Acceptance: Every subsystem reports Loading/Ready/Stale/Error with observation age and a concrete reason; aggregate Healthy is impossible while required evidence is loading, stale, or failed; exact-alarm fallback, standby bucket, foreground-service state, Advanced Protection, permissions, pending executions, and monitor errors are represented; refresh/retry and setup links are testable.
  Complexity: M

- [ ] P1 — Add review and cancellation to staged database restore
  Why: Selecting a database immediately replaces the pending restart journal, so users cannot inspect the candidate, cancel it, or distinguish it from a prior staged restore.
  Evidence: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:589-615`; `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt:123-201,267-330`.
  Touches: backup inspector/summary DTO, Setup restore review dialog, pending-journal cancellation/replacement, rollback surfacing, backup tests and UI tests.
  Acceptance: Selection shows source, schema version, compatibility, and entity counts before an explicit Stage action; an existing pending restore must be kept, replaced with confirmation, or canceled; cancellation removes only the validated pending journal; apply retains the current atomic replacement, pre-restore snapshot, and failed-restore recovery evidence.
  Complexity: M

### P2 — Authoring and resilience depth

- [ ] P2 — Add bounded failure-catch and retry/backoff flow blocks
  Why: `continueOnError` can suppress a failure but cannot recover a span, retry transient work, or expose structured failure details, while Automate and mature workflow engines make that control flow explicit.
  Evidence: engine flow markers in `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt`; https://llamalab.com/automate/doc/block/failure_catch.html; https://assets.temporal.io/durable-execution.pdf; Node-RED, n8n, and Activepieces execution semantics.
  Touches: versioned flow metadata/markers, validator, task runner, action retry-safety metadata, action editor and graph, trace/failure variables, bundle/Tasker migration, tests.
  Acceptance: A bounded try/catch span can expose sanitized failure type/message/step variables; retry supports fixed maximum attempts, exponential backoff cap, and optional jitter, but defaults to actions classified idempotent or failures proven to occur before a side effect; non-idempotent retries require an explicit warning/acknowledgement and an idempotency mechanism where supported; cancellation and engine timeout are never swallowed; invalid nesting is rejected before save/import; traces show each attempt without secrets.
  Complexity: L

- [ ] P2 — Make scene images and value controls valid and accessible by construction
  Why: Image drafts start with unsupported source `"Image"` and can save without a decodable persistent URI or accessible description; slider/bounds validation is split across editor and runtime.
  Evidence: `app/src/main/java/com/opentasker/core/scenes/SceneElementDrafts.kt:34-43`; `app/src/main/java/com/opentasker/core/scenes/SceneImageLoader.kt:12-47`; `app/src/main/java/com/opentasker/ui/screens/SceneEditorDialogs.kt`; `app/src/main/java/com/opentasker/core/scenes/SceneOverlayService.kt`.
  Touches: scene draft defaults/validator, document picker and persisted URI grants, image preview/error state, description/decorative metadata, slider and bounds validation, overlay semantics, tests.
  Acceptance: Image drafts start empty and cannot save until a supported decodable URI is retained or the element is removed; revoked/invalid sources show a repair action; every image is decorative or has a description used by semantics; slider min/value/max and element bounds validate identically in editor, import, and runtime.
  Complexity: M
