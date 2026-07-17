# Research — OpenTasker
Date: 2026-07-14 — replaces all prior research.

> **Post-pass status (2026-07-17):** the "Verified" findings below drove the v0.2.76 hardening series and have shipped: fail-closed stored-payload boundary, entity-scoped history pruning, authored-geometry scene rendering, Shizuku fail-closed transport, structural import budgets, the deterministic local release gate, secret variables, Termux allowlist hardening, demand-gated context monitors, task-ID notification bindings, and the diagnostics surface. Findings text is preserved as written for the record; treat it as pre-fix state.

## Executive Summary

OpenTasker is a local-first, MIT-licensed Android automation app whose strongest current shape is a real Kotlin/Compose/Room execution stack: profiles across six context families, 58 registered actions plus flow control, visual flow and scene authoring, variables, run diagnostics, backup/import, Locale/Tasker compatibility, and Play/F-Droid build profiles. The highest-value direction is not another action-count race; it is making every execution, editor, privilege, recovery, and release surface tell the truth. The 2026-07-14 JVM report is strong (522 tests, zero failures), but repository inspection found fail-open persisted-record consumers, cross-entity history deletion, a scene renderer that ignores authored geometry, a non-privileged Shizuku runner advertised as elevated, and only partial local release gating.

Top opportunities, in priority order:

- **Verified — fail closed on corrupt persisted entities:** replace secondary `toDomain()` consumers with a shared decode-or-refuse boundary so corrupt tasks/profiles/scenes cannot run successfully, be overwritten, disappear from pickers, or export as empty valid records.
- **Verified — stop cross-entity undo-history loss:** fix `EditHistoryDao.pruneOld()` before expanding undo/redo; its outer delete is not scoped to the requested entity.
- **Verified — make scene runtime match scene authoring:** use authored bounds and absolute geometry, render real bounded image sources, and round-trip slider value semantics.
- **Verified — make Shizuku capability claims executable:** run allowlisted commands through a Shizuku user service/Binder path or keep them `Unsupported`; ordinary `ProcessBuilder` is not elevated execution.
- **Verified — budget untrusted imports structurally:** add object, action, context, scene-element, XML-node, token/depth, and string limits before Room writes, not only raw character caps.
- **Verified — create one deterministic local quality/release gate:** cover blocking lint, unit tests, Room schemas, instrumentation compilation, Play/F-Droid release builds, metadata identity, resolved dependencies, and advisory output without introducing hosted CI.
- **Verified — finish existing engine semantics in authoring:** expose exit tasks, action reorder, action conditions/failure policy, task collision behavior, typed choices, domain validation, and lossless unknown arguments.
- **Verified — improve operator control:** add installed-app/observed-value pickers, an active-execution view with cooperative cancellation, and pagination/export for every retained run log.
- **Verified — make onboarding and privilege states recoverable:** persist onboarding progress, handle permission results/permanent denial, and never equate manager installation with usable privilege.
- **Verified — close accessibility and localization source gaps:** repair control semantics/touch targets and resource-back dynamic metadata, widgets, overlays, plurals, and all visible non-Compose strings before translating.

## Product Map

- **Core workflows:** define tasks from registered actions and flow control; bind profile contexts to enter/exit tasks; manually or externally run tasks; author/show interactive scenes; inspect variables, context state, run traces, and setup health.
- **Data safety workflows:** Room-backed local storage, bounded run-log retention, edit snapshots, OpenTasker bundle and Tasker XML interchange, encrypted database backups, and diagnostic export.
- **User personas:** privacy-first Android power users; Tasker/MacroDroid/Automate migrants; FOSS users who need F-Droid; makers integrating Locale plugins, Termux, Shizuku, HTTP/LAN endpoints, tiles, widgets, and external intents.
- **Platforms and distribution:** Android API 26 minimum, compile/target API 37, JVM 17, Jetpack Compose Material 3, Room schema 5, debug/Play/F-Droid Gradle profiles; Android is the deliberate product boundary.
- **Key data flow:** context sources feed `ProfileMatcherImpl` and `AutomationService`; resolved tasks run through `TaskRunner` and action registries; variables and run outcomes flow back to Room and operator UI; import/export maps external data through validation and ID remapping.

## Competitive Landscape

- **Tasker:** does action-level conditions, continue-after-error, task search, labels, scenes, and a broad plugin ecosystem well. OpenTasker should expose its already-modeled condition/failure/exit-task semantics and task pickers; it should avoid opaque privilege behavior and plugin sprawl without trust boundaries.
- **MacroDroid:** makes trigger/action/constraint construction approachable and treats action blocks, typed variables, templates, and custom UI as first-class. OpenTasker should learn its typed pickers and reusable building blocks; it should avoid account-dependent sharing and unreviewed privacy-sensitive marketplace recipes.
- **Automate:** makes concurrency, wait/proceed behavior, failure paths, persistence, and flow state explicit, and its inspector reduces package/activity/intent guesswork. OpenTasker should copy that operational clarity while retaining its simpler list editor and avoiding graph-only authoring.
- **Easer:** preserves a clean FOSS Event/Condition/Operation model and narrow extension boundary. OpenTasker should preserve similarly explicit composition; Easer's raw package entry, unclear setup recovery, and long-lived reliability issues are warnings, not patterns.
- **vFlow:** demonstrates typed modules, declared permissions, subflows, execution context, and demand for live workflow control. OpenTasker should add active-run visibility and cancellation; it should avoid dynamic DEX or privileged-worker extensibility in the trusted core.
- **PhoneProfilesPlus:** is strong on profile ergonomics, OEM/device caveats, and temporary state behavior. OpenTasker should surface capability degradation per device; it should avoid freezing an old target SDK or depending on hidden APIs and companion APKs.
- **Samsung Modes and Routines:** excels at suggested routines, simple if/then construction, and showing when a routine is active. OpenTasker should learn its onboarding and active-state clarity; it should not trade away cross-device portability or advanced authoring.
- **IFTTT:** paywalls multi-action flows, webhooks, higher rate limits, queries, and conditional filter logic, confirming these are high-value capabilities. OpenTasker should keep local multi-action logic and diagnostics first-class; cloud accounts and multi-user service orchestration conflict with its philosophy.

## Security, Privacy, and Reliability

- **Verified — corrupt-record fail-open:** `core/storage/TaskDao.kt`, `ProfileDao.kt`, and `SceneDao.kt` make `toDomain()` return fallback empty payloads. `widget/TaskRunActivity.kt`, `core/actions/NotificationActionReceiver.kt`, `core/external/AutomationTargetReceiver.kt`, `core/transfer/OpenTaskerBundle.kt`, `ui/screens/ContextInspectorScreen.kt`, and `widget/TaskWidgetConfigActivity.kt` still consume those fallbacks. Effects include false-success empty runs, corrupt profiles rewritten with empty contexts, corrupt records hidden from selectors, and exports that destroy recovery evidence.
- **Verified — history pruning deletes unrelated entities:** `core/storage/EditHistoryDao.kt:26` selects retained IDs for one entity but applies the outer `DELETE` to the whole table. Editing one task/profile/scene can delete every other entity's snapshots. The existing durable undo/redo roadmap item must begin with this fix and a cross-entity regression test.
- **Verified — Shizuku readiness is overstated:** `core/capabilities/ActionCapabilities.kt` promotes elevated actions when Shizuku reports ready, but no production caller uses `ShizukuShellRunner`; its default factory invokes ordinary `ProcessBuilder` rather than a Shizuku Binder/user service. `ShizukuPowerBackend.killSwitchEnabled` is volatile and `requestPermission()` has no UI caller.
- **Verified — import limits are shallow:** `OpenTaskerBundleCodec.decode()` caps JSON at 16 MiB and `TaskerXmlImport` caps XML at 4 MiB and rejects DOCTYPE, but neither path caps entity counts, nested actions/contexts/elements, token/depth count, node count, or aggregate string volume before full deserialization.
- **Verified — notification actions are ambiguously bound:** `BuiltInActions.kt` stores a task name in each notification button and `NotificationActionReceiver.kt` resolves `TaskDao.getByName(... LIMIT 1)`. Duplicate names, rename, or name reuse can execute a different task; immutable task IDs and picker-backed configuration are required in addition to the existing PendingIntent-collision item.
- **Verified — recovery paths need transaction visibility:** backup restore is staged immediately after document selection; users cannot inspect pending source identity, cancel the pending restore, or explicitly select a rollback point. Extend the existing streaming authenticated-restore item rather than create a parallel backup system.
- **Verified — point-in-time advisory result:** an OSV batch query on 2026-07-14 returned no advisory for the inspected direct versions of Kotlin stdlib, Coroutines, Serialization, Room, WorkManager, DataStore, RE2J, or Shizuku. This is not a durable guarantee because `verifyFdroidReadiness` inspects declared rather than resolved runtime dependencies.
- **Missing guardrails:** source-to-sink sensitivity enforcement remains an existing roadmap item; add explicit structural import budgets, resolved-graph policy/advisory checks, task-ID notification bindings, and host-side permission-revocation tests because Android terminates the app process on revocation.

## Architecture Assessment

- **Persisted-domain boundary:** make `StorageDecodeResult` the only execution/mutation/export boundary. Compatibility fallback may remain for non-destructive display only if the issue is surfaced alongside raw recovery/export; never silently write the fallback back.
- **Scene boundary:** extract a shared scene projection/layout plan consumed by `SceneLibraryScreen.kt` and `SceneOverlayService.kt`. The runtime currently uses a vertical `LinearLayout`, `WRAP_CONTENT`, ignores `xDp/yDp/widthDp/heightDp`, renders IMAGE as a placeholder, and reads slider `progress` while the editor writes `value`.
- **Scene editor correctness:** `SceneLibraryScreen.kt:427-435` computes one updated batch then sends multiple edits based on the stale scene, so last-write-wins can move only one selected element; resize at `:705-707` uses the width scale for both axes. Persist one atomic scene and use independent X/Y scales.
- **Action/editor schema:** `ActionMetadata.kt` labels fields as `DROPDOWN` but supplies no option model, and `ActionEditorDialogs.kt` renders dropdowns as unrestricted text, validates only required/nonblank, preserves existing condition/failure values without editing them, and reconstructs only known arguments. Add option/value types, validators, task/app/package selectors, and unknown-argument preservation.
- **Context-source lifecycle:** the existing demand-start monitor item should also share one hot source per context family. `LocalePluginConditionContextSource.events()` creates a poll loop per collector, then each loop queries all subscriptions; N plugin contexts can cause N-squared polling. Reference counting alone is insufficient without `shareIn`/a coordinator and bounded query concurrency.
- **Observability:** `RunLogDao.getRecentFlow()` hard-limits the UI to 100 rows although retention supports larger histories. Add keyset pagination and redacted export; add active executions and terminal `Cancelled` outcomes before attempting pause/rewind.
- **Testing and release gate:** the stored 2026-07-14 JVM report shows 522/522 passing. The stored API-37 connected report from 2026-07-13 shows 2/2 failures and an instrumentation process crash; it is evidence of an unfinished host/device harness, not device support. Lint is non-blocking (`abortOnError=false`) and broadly disables two permission checks. One local aggregate command should run blocking lint, JVM tests, Room schema checks, `androidTest` compilation, Play/F-Droid release builds and metadata checks, resolved dependency/advisory policy, and configuration-cache verification.
- **Release identity:** F-Droid metadata pins `0.2.75`/77 to `a802265c95d006ae3a829577ec37ea5d207a5c84`, while HEAD remains `0.2.75`/77 after later source changes and has no matching tag on its lineage. Extend the existing Developer Verification/release-truth items with monotonic version, exact tag, source commit, package, signer, and metadata alignment.
- **Documentation drift:** `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/LOCALE_PLUGIN_HOST.md`, `docs/DEPENDENCY_MODERNIZATION.md`, `docs/FDROID_READINESS.md`, and duplicate `CHANGELOG.md` Unreleased sections contradict live capabilities or versions. The existing release-truth item already owns this; do not add another docs row.
- **Existing-roadmap corrections:** `ACCESS_LOCAL_NETWORK` declaration/setup/precheck and predictive-back opt-in already exist; flat OR groups already ship through `ContextSpec.orGroup`; Navigation3 is stable rather than alpha; official Advanced Protection documentation does not support the current claim that it revokes accessibility solely because an app lacks an “Accessibility Tool” declaration. Treat those rows as revalidation/correction work, not greenfield implementation.
- **Category audit:** security, accessibility, i18n, observability, testing, distribution, and upgrade strategy receive new or existing actionable work. Docs, offline resilience, plugin lifecycle, migrations, and backup recovery are already covered and are refined above. Android remains the mobile scope; cloud multi-user/team collaboration and cross-platform clients are intentionally rejected.

## Rejected Ideas

- **AI/natural-language automation generation — AndroidWorld/mobile-agent research:** current agents remain brittle under real-world variation, while generated high-privilege workflows create review and privacy risks; retain deterministic user-authored automation.
- **Cloud accounts, team workspaces, and a hosted recipe marketplace — IFTTT/MacroDroid:** conflicts with the local-first, no-telemetry product boundary; local reviewed sharing is already on the roadmap.
- **Pause, rewind, or step-back for live workflows — vFlow issue signal:** external side effects are not reversible; cooperative cancel plus explicit terminal logging is the safe first control.
- **Dynamic DEX/plugin workers or root/hidden-API companions — vFlow/PhoneProfilesPlus:** expands the trusted-code and distribution attack surface and undermines target-37 portability; keep the Locale/Termux/Shizuku boundaries narrow and explicit.
- **Implementation-first AccessibilityService recorder — automation competitors:** Google Play requires declaration, disclosure, consent, and deterministic user-authored behavior; retain only the existing policy-first bounded evaluation.
- **Background clipboard surveillance, call-log/call-state automation, voice wake words, Wear OS, TV, or iOS — competitor/community requests:** permission/privacy or platform-expansion cost is not supported by verified OpenTasker demand.
- **Target-SDK freeze or immediate unforced framework migration — PhoneProfilesPlus/AndroidX:** freezing contradicts modern Android support; Room 3/Navigation3/Gradle churn should land only for a measured defect or capability, not novelty.

## Sources

### Direct and commercial competitors

- https://tasker.joaoapps.com/userguide/en/activity_actionedit.html
- https://www.macrodroid.com/
- https://www.macrodroidforum.com/wiki/index.php/Action_Blocks
- https://llamalab.com/automate/doc/flow.html
- https://github.com/renyuneyun/Easer
- https://github.com/renyuneyun/Easer/issues/486
- https://github.com/ChaoMixian/vFlow
- https://github.com/ChaoMixian/vFlow/issues/67
- https://github.com/keymapperorg/KeyMapper/issues/1343
- https://github.com/henrichg/PhoneProfilesPlus
- https://ifttt.com/plans
- https://www.samsung.com/uk/support/mobile-devices/how-to-use-routines-on-your-samsung-galaxy-device/

### Ecosystem, community, and research

- https://news.ycombinator.com/item?id=42254433
- https://research.google/pubs/androidworld-an-open-world-for-autonomous-agents/

### Platform, security, and distribution

- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/training/permissions/requesting
- https://developer.android.com/guide/topics/ui/accessibility/principles
- https://developer.android.com/guide/topics/resources/localization
- https://developer.android.com/privacy-and-security/risks/unsafe-deserialization
- https://f-droid.org/en/docs/Reproducible_Builds/
- https://github.com/RikkaApps/Shizuku-API
- https://developer.android.com/developer-verification
- https://support.google.com/googleplay/android-developer/answer/10964491

### Dependency releases and advisories

- https://kotlinlang.org/docs/whatsnew24.html
- https://github.com/google/ksp/releases/tag/2.3.10
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0
- https://google.github.io/osv.dev/api/

## Open Questions

- Which version lineage is authoritative for the next public release: the current `0.2.75` source line or the off-lineage `v0.3.0`/`v0.4.x` tags? Maintainer intent is required before the release-identity gate can choose the next monotonic version/tag.
- Which elevated actions and OEM/API-37 devices are in the supported Shizuku contract? Source inspection can prove fail-closed wiring, but per-action privilege and reboot/screenshot behavior still require the already-blocked physical-device matrix.
