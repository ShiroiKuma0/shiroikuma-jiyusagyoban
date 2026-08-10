# OpenTasker Roadmap

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Actionable Items

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

- [ ] P1 — Add opt-in local rolling configuration snapshots with bounded restore
  Why: `DatabaseBackupManager` provides manual WAL-safe backups and reviewed restores, but the UI only creates them from an explicit Setup action; community requests and comparable tools show value in automatic on-save/periodic recovery, while OpenTasker can implement it locally without a sync service.
  Evidence: `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt`; `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt`; `gradle/libs.versions.toml` (`work = "2.11.2"`); https://www.reddit.com/r/tasker/comments/p5gwgp; https://github.com/Waboodoo/HTTP-Shortcuts/blob/develop/CHANGELOG.md; https://github.com/SuperMonster003/AutoJs6
  Touches: `DatabaseBackupManager.kt` or a validated OpenTasker configuration snapshot layer, a WorkManager worker, DataStore settings, Setup UI, retention/status strings, and backup/restore tests.
  Acceptance: Users can opt in to local-only snapshots, choose a bounded count/age policy, see the last successful/failed snapshot and storage use, and preview/cancel restore; snapshots are atomic, validated, redacted or secret-omitting by design, never upload, survive process restart, and never overwrite a pending restore or live database without the existing review gate.
  Complexity: M

- [ ] P2 — Add a JVM scenario harness for trigger-to-recovery execution semantics
  Why: The repository has extensive unit/contract coverage and a small Android-test surface, but no declarative suite that runs a complete trigger → admission → action → variable commit → run-log → restart scenario with deterministic time and platform outcomes; this is the layer needed to catch cross-module regressions without physical-device evidence.
  Evidence: `app/src/test` and `app/src/androidTest`; `core/engine/TaskExecutionHelper.kt`; `core/contexts`; `core/storage`; https://github.com/google-research/android_world; https://par.nsf.gov/biblio/10387467-helping-users-debug-trigger-action-programs; https://doi.org/10.1145/3411764.3445567
  Touches: reusable fake clock/platform/action adapters, scenario fixtures under `app/src/test`, engine/context/storage seams, coverage reporting, and the Gradle quality gate.
  Acceptance: Seeded scenarios cover time/state/event delivery, duplicate delivery, admission overflow, retry-safe versus non-retry-safe failure, secret redaction, cancellation, interrupted-run reconciliation, and Room migration; each asserts final state, run-log/journal entries, and side-effect ledger; scenarios run headlessly in the normal JVM gate with no real network or display.
  Complexity: L

- [ ] P2 — Gate OpenTasker bundle compatibility and migration fixtures in release truth
  Why: Runtime code and `tools/release-truth.json` use OpenTasker bundle schema v2 with an explicit v1→v2 migration, while `docs/OPEN_JSON_BUNDLE.md` still says v1; the current release contract checks the numeric constant but not migration behavior or supported-version semantics.
  Evidence: `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt`; `app/src/test/java/com/opentasker/core/transfer`; `tools/generate-release-truth.ps1`; `buildSrc/src/main/kotlin/com/opentasker/build/VerifyReleaseTruthTask.kt`; `docs/OPEN_JSON_BUNDLE.md`; https://www.home-assistant.io/docs/blueprint/schema/; https://github.com/Waboodoo/HTTP-Shortcuts/blob/develop/CHANGELOG.md
  Touches: bundle codec fixtures, v1/v2 migration tests, future-version rejection tests, `VerifyReleaseTruthTask`, `ReleaseTruthContractTest`, `tools/release-truth.json`, and the tracked README export contract.
  Acceptance: A checked-in fixture proves v1 imports to the documented v2 semantics, v2 round-trips deterministically, future versions are rejected before mutation, unknown keys/oversized values follow the stated policy, and the release gate fails when the supported-version set, generated truth, or tracked README claim disagrees with the codec.
  Complexity: M

- [ ] P2 — Refresh the pinned Gradle, KSP, and Compose tooling tuple under the release gate
  Why: Official release pages list Gradle 9.7.0, KSP 2.3.11, and Compose BOM 2026.06.01 as concrete candidates while the repository pins Gradle 9.6.1, KSP 2.3.10, and BOM 2026.06.00; AGP 9.3.1 already supports the API-37 target, so this should be a compatibility refresh rather than an AGP migration.
  Evidence: `gradle/wrapper/gradle-wrapper.properties`; `gradle/libs.versions.toml`; `app/build.gradle.kts`; `tools/verify-local-release.ps1`; `app/src/test/java/com/opentasker/docs/ReleaseTruthContractTest.kt`; https://docs.gradle.org/current/release-notes.html; https://developer.android.com/build/releases/gradle-plugin; https://github.com/google/ksp/releases; https://developer.android.com/develop/ui/compose/bom
  Touches: wrapper distribution URL/SHA and JAR, `libs.versions.toml`, dependency-verification metadata, expected bootstrap hashes and release-truth contract, Compose regression tests, and local-gate documentation if the observed tool tuple changes.
  Acceptance: The candidate versions are pinned only after the normal bootstrap, dependency-verification, JVM/Android test, lint, coverage, release-assembly, and F-Droid/Play policy gates pass; wrapper hashes and release truth agree; configuration-cache behavior is measured; no incubating `org.gradle.isolated-projects` setting is enabled; and the previous tuple remains an explicit rollback target.
  Complexity: M

- [ ] P3 — Prototype a capability-gated Android AppFunctions invocation surface
  Why: Android AppFunctions is an experimental API for exposing structured app capabilities to trusted callers, and OpenTasker has no AppFunctions implementation despite already having signature-gated external intents and launcher shortcuts; it may provide a safer future assistant/launcher surface without cloud execution.
  Evidence: `app/src/main/AndroidManifest.xml`; `app/src/main/java/com/opentasker/core/external`; `app/src/main/java/com/opentasker/widget/TaskShortcutHelper.kt`; no `AppFunctionService` or `appfunctions` source found; https://developer.android.com/ai/appfunctions; https://developer.android.com/ai/appfunctions/add-appfunctions; https://developer.android.com/jetpack/androidx/releases/appfunctions
  Touches: a small app-functions adapter/service, manifest and caller permission policy, safe-task capability metadata, `core/external` invocation mapping, API-level fallback, and contract tests; do not put it in the core engine.
  Acceptance: A disabled-by-default prototype exposes only explicitly approved, side-effect-classified tasks through a versioned schema; `EXECUTE_APP_FUNCTIONS`/trusted-caller checks are enforced, secrets never appear in schema or arguments without a user-mediated path, API < 36 has an honest unsupported result, and the feature adds no cloud, telemetry, or release-critical dependency.
  Complexity: L

## Audit Findings — 2026-08-10

Deep multi-pass audit (8 parallel code auditors + on-device run on API-36 emulator, both themes). Baseline recorded first: `:app:testDebugUnitTest` = **1126/1126 green**, `assembleDebug` + `lintDebug` clean — no pre-existing failures. Focus was the 34 commits since v0.2.82 (unreleased). Each item below is self-contained. IDs continue as A-NN.

### P1 — correctness / data-safety

- [ ] A-07 — Exported backups (including passphrase-protected `.otbackup`) are unrestorable after reinstall or on any other device
  Category: reliability
  Where: `app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt:51-55` (backup = raw copy of the SQLCipher-encrypted DB), `:98-120` (`exportEncryptedBackup` layers passphrase AES-GCM over the same ciphertext); key at `DatabaseKeyStore.kt:24-37` (random per-install, wrapped by a non-exportable AndroidKeyStore key in `database_security` prefs); restore validation `DatabaseSecurity.kt:32-61`; `res/xml/data_extraction_rules.xml` excludes the DB and prefs from cloud backup AND D2D transfer.
  Problem: Every backup artifact is SQLCipher ciphertext keyed to a key that dies with the install (Keystore master key + wrapped-key prefs are destroyed on uninstall and never migrate via D2D). After reinstall or on a new device, restore regenerates a fresh key, SQLCipher open fails, and restore fails closed — exactly the device-loss/reinstall/migration scenarios backups exist for. The passphrase protects bytes the user can never decrypt where it matters. (The restore side is already portable-ready: `DatabaseSecurity.isPlaintext` accepts plaintext SQLite and re-encrypts on next open — only the export side produces device-locked bytes.)
  Evidence: Verified (design trace).
  Fix: For exports, `sqlcipher_export` to a plaintext temp copy (or re-key to a passphrase-derived key) before the `.otbackup` AEAD layer; shred the temp.
  Acceptance: An `.otbackup` exported on one install restores on a fresh install of the same app with the correct passphrase; a test round-trips export→(new key)→restore and asserts the data opens.
  Confidence: Verified — Effort: M

- [ ] A-08 — F-Droid recipe & reproducibility harness expect `app-release-unsigned.apk`, which the release build can no longer produce
  Category: reliability
  Where: `app/build.gradle.kts:187-219` (absent env vars → `selfhost` signing fallback, so `release.signingConfig` is always set); `fdroid/metadata/com.opentasker.app.yml:34` (`output: …/app-release-unsigned.apk`); `tools/verify-fdroid-release.ps1:18,248-251` (hard-codes the unsigned path, throws "missing" even after `-BuildRelease`); guard `app/build.gradle.kts:968-970` (`verifyFdroidMetadata` only checks the yml TEXT contains the path string, never that the artifact exists).
  Problem: AGP names the output `app-release-unsigned.apk` only when the build type has no signing config. Since commit 03670e2 added the always-present selfhost fallback, the output is `app-release.apk` (verified on disk: only `app-release.apk`, 10.9M, exists). The F-Droid `output:` path fails on fdroidserver and the README-advertised reproducibility harness can never find its input.
  Evidence: Verified.
  Fix: Either drop the signing config for the fdroid distribution (`-PopenTaskerDistribution=fdroid` → no `signingConfig`) so the unsigned artifact returns, or update the yml + verifier to the signed name and F-Droid's expected flow; make `verifyFdroidMetadata` assert against the actual assembled output filename.
  Acceptance: `-BuildRelease` produces the artifact the yml `output:` names, and `verify-fdroid-release.ps1` passes without manual path edits; the metadata guard fails if the assembled filename and the yml disagree.
  Confidence: Verified — Effort: S

### P2 — reliability / security / UX blockers

- [ ] A-09 — Held run-log rows are exempt from all pruning and are never deleted (unbounded DB growth)
  Category: reliability
  Where: `app/src/main/java/com/opentasker/core/storage/RunLogDao.kt:113-127` (`pruneRetention … WHERE held = 0` — the only DELETE on run_logs); written on every admission rejection under the DEFAULT `overflowPolicy = LOG` at `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt:87-106,683` (each held row carries up to 16 KB `heldPayload`); replay (`:690`) never clears `held`/`heldPayload`.
  Problem: The exact scenario that produces held rows (burst storm / open circuit on a misfiring profile) produces them continuously — while a circuit is open every delivery is rejected → one unprunable 16 KB row each. A once-a-minute misconfigured trigger accumulates ~1,440 permanent rows/day forever, with no un-hold/dismiss/delete path anywhere.
  Evidence: Found by two auditors; verified against `pruneRetention` and the rejection path.
  Fix: Age-/count-bound held rows in `pruneRetention` (prune held rows older than the retention window unless starred) and clear `held`/`heldPayload` on the original row after a successful replay.
  Acceptance: Held rows are pruned by the retention policy; a test inserts held rows past the window and asserts they are deleted (unless starred), and that replaying a held row clears its held state.
  Confidence: Verified — Effort: S

- [ ] A-10 — `execution_journal` is pruned only at process start (unbounded growth under a long-lived service)
  Category: reliability
  Where: `app/src/main/java/com/opentasker/core/engine/ExecutionJournal.kt:129` (`pruneTerminal(256)` called only from `reconcileExecutionJournal`); sole caller `OpenTaskerApp_NoHilt.kt:94` (app start); `RunLogPruneWorker.kt` prunes only `run_logs`.
  Problem: `AutomationService` is START_STICKY and designed to run for weeks. Every execution inserts a journal row (`TaskExecutionHelper.kt:123`); terminal rows are trimmed to 256 only at the next process start. A once-a-minute profile produces ~10k rows/week between restarts, and the `state`/`updatedAtMs` indexes grow with it.
  Evidence: Found by two auditors; verified.
  Fix: Call `pruneTerminal` from the existing 6-hour `RunLogPruneWorker` (or after every N terminal writes).
  Acceptance: Journal size stays bounded without a process restart; a test drives the prune worker and asserts terminal rows are trimmed to the cap.
  Confidence: Verified — Effort: S

- [ ] A-11 — `flow.try` retry replays the whole body but safety-checks only the failing action — NEVER-safe actions get re-executed
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:415-427` (`retrySafetyFor(spec…)` checks only the failing spec; `nextPc = frame.tryIndex + 1` restarts the entire body).
  Problem: For a try body `[sms.send (NEVER, succeeds), http.get (IDEMPOTENT, fails)]` with `max_attempts=3`, the retry re-runs `sms.send` up to 2 extra times because only the failing action's classification is consulted. `TaskRunnerFailureRecoveryTest` uses only single-action bodies, so this passes untested.
  Evidence: Verified.
  Fix: Gate retry on the classification of every non-control action in the try body (or restart from the failing index for an idempotent-only prefix).
  Acceptance: A retry does not re-execute a preceding NEVER-classified action; a test with a mixed-safety body asserts the NEVER action runs exactly once.
  Confidence: Verified — Effort: M

- [ ] A-12 — Held-execution replay and manual "Run now" bypass the live admission controller and persisted circuit breaker
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt:696` (default `admissionController = ExecutionAdmissionController.Default`); callers pass none — `ActiveAutomationViewModel.kt:1409` (`runTaskNow`), `:1429-1433` (`replayHeldRun`); the service uses a distinct `ExecutionAdmissionController.persisted(this)` (`AutomationService.kt:173`); `ExecutionAdmissionRegistry.attach` exists to share the live controller but is not used here.
  Problem: A run held because its profile saturated the live controller replays against `Default` — a separate in-memory controller with zero counts and an in-memory circuit store — so it admits even while the profile is still saturated or its circuit is open. Check wired to the wrong data source.
  Evidence: Verified.
  Fix: Route replay/manual runs through `ExecutionAdmissionRegistry` (live controller, falling back to `persisted(context)`).
  Acceptance: Replaying/running a task while the profile is saturated is rejected by the same controller the engine uses; a test asserts replay respects a saturated live controller.
  Confidence: Verified — Effort: S

- [ ] A-13 — Held entries can be replayed repeatedly (no consumed marker → duplicate executions)
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt:690-735` (no state change on the held row); button shown whenever `entry.held` at `app/src/main/java/com/opentasker/ui/screens/RunLogScreenContent.kt:607-609`.
  Problem: Each replay mints a fresh `executionId` (`replayOf` = original), so the command ledger cannot dedupe; the row stays `held=1` forever and every tap (or double-tap) runs the task again with real side effects. `HeldExecutionInstrumentedTest` never replays twice.
  Evidence: Verified.
  Fix: Mark the original row consumed (clear `held`) inside the replay and/or dedupe by `replayOf` lookup; disable the button while a replay is in flight (see A-48).
  Acceptance: A held row can be replayed at most once; a test replays twice and asserts the second is a no-op.
  Confidence: Verified — Effort: S

- [ ] A-14 — Startup journal reconcile races live executions — a just-started run can be marked INTERRUPTED and double-logged
  Category: reliability
  Where: `app/src/main/java/com/opentasker/core/engine/ExecutionJournal.kt:100-127` (`dao.active()` with no `startedAtMs < processStart` filter); fire-and-forget launch at `OpenTaskerApp_NoHilt.kt:92-104`; unconditional success insert at `TaskExecutionHelper.kt:410`.
  Problem: Reconcile runs concurrently with service startup. A boot-triggered profile that reaches `ExecutionJournal.start` before `dao.active()` executes has its ACTIVE row flipped to INTERRUPTED; reconcile inserts an "Interrupted" recovery run-log (the real run hasn't logged yet, so the `getByExecutionId` guard passes), then the real run inserts its own success row — two contradictory rows for one execution, and the real terminal state is lost (`markTerminal` returns 0). The boot path is exactly the concurrent case.
  Evidence: Likely (both orderings possible; window small but boot-path realistic).
  Fix: Restrict reconcile to rows with `startedAtMs` before process start, or add a startup barrier so reconcile completes before the service may dispatch.
  Acceptance: A boot-triggered execution that starts during reconcile is not marked INTERRUPTED and produces exactly one run-log row; a test simulates the interleave.
  Confidence: Needs-repro — Effort: S

- [ ] A-15 — Hyphen is now a legacy variable-name character — `%var-suffix` text silently expands to empty string
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/variables/VariableExpander.kt:393` (`|| c == '-'` added by commit 7a9c93e), unknown names expand to `""` at `:131`.
  Problem: Previously `%count-1` expanded `%count` then kept `-1`; now the token is `count-1`, almost certainly undefined → the whole token becomes empty. Affects action args and conditions (`%battery-20 > 0` → `" > 0"`). Tasker itself disallows `-` in names, so imported Tasker text using `%Var-…` changes meaning. `VariableStoreTest:54` covers the new behavior; nothing covers the compatibility break.
  Evidence: Verified (behavior change); user impact Likely.
  Fix: Longest-match fallback — if the hyphenated name is undefined, retry the longest defined prefix ending before `-`.
  Acceptance: `%count-1` with `count=5` expands to `5-1`; a test covers hyphen-suffix compatibility.
  Confidence: Verified — Effort: S

- [ ] A-17 — Restore application and plaintext→SQLCipher migration run on the main thread (ANR risk at startup/boot)
  Category: reliability
  Where: `app/src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt:52-53,70-89` (`initializeAfterUnlock()` runs synchronously in `Application.onCreate` and from `BootReceiver.onReceive` — 10 s broadcast ANR budget); `DatabaseBackupManager.kt:398-425` → `validateDatabaseFile:485-530` (full `PRAGMA integrity_check`); `DatabaseSecurity.kt:100-143` (full `sqlcipher_export` re-encryption of legacy plaintext DB).
  Problem: On a multi-MB database, applying a pending restore (copy live → rollback, copy pending ≤100 MB, integrity_check) plus a possible plaintext re-encryption is seconds of main-thread I/O + crypto at startup; the app's own debug StrictMode flags this. Room init depends on it, so it can't move to a plain coroutine, but it can run on a background thread with the UI gated on completion.
  Evidence: Verified (code path; ANR magnitude needs a large DB to repro).
  Fix: Run the restore/migration on a background thread and gate first-frame/engine start on its completion.
  Acceptance: No main-thread disk/crypto during restore application; StrictMode does not flag startup on a large DB.
  Confidence: Needs-repro — Effort: M

- [ ] A-18 — MQTT cleartext gate is weaker than the HTTP gate (any-private + re-resolve → cleartext credentials to a public host)
  Category: security
  Where: `app/src/main/java/com/opentasker/core/actions/MqttPublishAction.kt:77-96` (`isPrivateOrLocalHost` uses `resolver(host).any(::isPrivateOrLocalAddress)`), `:224-227` (gate), `:107` (connect re-resolves via `InetSocketAddress(config.host, config.port)`). Compare `HttpRequestAction.kt:171-177` (`PRIVATE_ONLY_DNS` requires ALL addresses private and pins the resolved address). `mqtt.publish` is registered (`ActionCatalog.kt:101`) and capability `Supported` (`ActionCapabilities.kt:89`) — reachable.
  Problem: A hostname resolving to both a private and a public address (or an attacker-controlled DNS answer between check and connect) passes the "local only" gate, then the socket re-resolves and typically connects to the first A record — sending the cleartext CONNECT (username/password) and payload to the public address.
  Evidence: Verified against source; also confirms Roadmap_Blocked's "MQTT (RD34)" is stale — the action shipped (see A-65).
  Fix: Require ALL resolved addresses to be private and connect to the vetted `InetAddress`, mirroring `PRIVATE_ONLY_DNS`.
  Acceptance: A non-TLS MQTT publish to a hostname with any public A record fails closed; a test with a mixed-resolution resolver asserts rejection and address pinning.
  Confidence: Likely — Effort: M

- [ ] A-19 — Exported Locale condition receiver is an unauthenticated oracle for non-secret variable values
  Category: security
  Where: `app/src/main/AndroidManifest.xml:439-446` (`LocaleConditionQueryReceiver`, `exported="true"`, no `android:permission`); `app/src/main/java/com/opentasker/core/plugins/locale/LocaleConditionQueryReceiver.kt:75-88`; `LocaleConditionTarget.kt:169-186`.
  Problem: The `QUERY_CONDITION` receiver is exported without permission (Locale/Tasker contract). Its bundle is attacker-controlled: for `VARIABLE_COMPARE` it names an arbitrary variable and expected value and replies Satisfied/Unsatisfied via ordered-broadcast result code. Any zero-permission app can probe non-secret variable content and, with `STARTS_WITH`/`CONTAINS`/`EQUALS`, extract full values across repeated broadcasts. Secret variables are correctly refused (`snapshot.variableSecret -> Unknown`), so it is bounded to non-secret data — which routinely includes location strings, device names, and tokens the user didn't classify as secret. No rate limiting.
  Evidence: Likely (path fully reachable; severity threat-model-dependent; the secret refusal is a real mitigation).
  Fix: Gate variable-compare queries to variables the user explicitly exposed as a plugin condition (allowlist bound at configure time), or require a configure-time grant token like `LocaleGrantStore` already does for the fire receiver.
  Acceptance: A query for a variable the user never exposed as a condition returns Unknown/refused; a test asserts arbitrary-variable probing is blocked.
  Confidence: Likely — Effort: M

- [ ] A-20 — Tethering state sticks at `true` after tethering stops (pre-API-36 path)
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/contexts/StateSensorEvents.kt:543-547` (uncommitted/untracked file).
  Problem: The pre-36 path treats any delivery of `TETHER_STATE_CHANGED` as `tethering=true`. That broadcast fires on tethering STOP as well as start and is sticky (registration replays the last transition). Stopping USB/BT tethering emits `tethering=true` with nothing to reset it; turning Wi-Fi hotspot off can leave the final state `true`; a stale sticky broadcast on subscription flips state to `true` while tethering is off. A `tethering=true` profile then stays active forever.
  Evidence: Verified (source in the uncommitted context work).
  Fix: On both broadcasts, read the actual state (`WIFI_AP_STATE_CHANGED` extra for AP; `tetherArray`/`activeArray` extras or interface enumeration for the generic broadcast) instead of emitting a constant.
  Acceptance: Stopping tethering emits `tethering=false`; a test drives stop and asserts the value.
  Confidence: Verified — Effort: S

- [ ] A-21 — Device orientation classifier is inverted vs. Android sensor convention (exact sub-values swapped)
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/contexts/StateSensorEvents.kt:668-678` (uncommitted); test `app/src/test/java/com/opentasker/core/contexts/StateSensorEventsTest.kt:12-15` encodes the same wrong sign.
  Problem: `classify` maps `y <= 0 → "portrait"`, `y > 0 → "portrait_upside_down"`, but Android reports y = +9.81 when held upright — so a normally-held phone classifies as `portrait_upside_down` (and x-axis landscape_left/right are inverted the same way). Broad predicates (`orientation=portrait`) are masked because `orientationMatches` (`StateContextSourceImpl.kt:290-294`) accepts both sub-values, but exact predicates (`portrait_upside_down`, `landscape_left/right`) match the opposite physical orientation, and the Inspector shows the wrong live value on every device. The new JVM test asserts the inverted physics, so the suite is green.
  Evidence: Verified against the sensor coordinate convention (1-minute device repro with `adb emu rotate` closes it).
  Fix: Flip the y and x sign mappings (`y >= 0 → portrait`), correct the test to match physics, and verify once on a device/emulator.
  Acceptance: An upright device reports `portrait`; a landscape-left device reports `landscape_left`; the corrected test and an on-device check agree.
  Confidence: Verified — Effort: S

- [ ] A-22 — An unparseable STATE context spins up GPS, telephony, and all sensors (fail-open demand narrowing)
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/ProfileMatcherImpl.kt:75-76` (uncommitted); `app/src/main/java/com/opentasker/core/contexts/StateContextSourceImpl.kt:154-158` (`stateContextKey` returns null on no parseable predicate and no `key`); `StateSensorEvents.kt:67-98` (`requested == null` ⇒ every physical key wanted).
  Problem: `null` means both "Inspector wants everything" and "couldn't determine the key". A STATE spec with a malformed predicate (reachable via JSON/Tasker import) yields `stateContextKey == null`, and the matcher subscription then starts continuous GPS (15 s / 5 m), phone-state receivers, and three sensor listeners for the profile's lifetime — even though `stateMatches` fails closed and the context can never match. Before this uncommitted change the same spec started nothing. Battery/privacy fail-open on exactly the input that should do the least.
  Evidence: Verified.
  Fix: In the matcher path, treat an unresolvable key as "no physical sensors" (a sentinel non-physical key); reserve `null` for the Inspector's explicit everything-mode.
  Acceptance: A malformed STATE spec starts no location/sensor/telephony listeners; a test asserts the subscription set is empty for an unparseable predicate.
  Confidence: Verified — Effort: S

- [ ] A-23 — Play distribution: roaming/call-state contexts point to a Setup step that cannot exist (issue-#4 class)
  Category: correctness
  Where: `app/build.gradle.kts:141,148,177` (`play` distribution swaps `READ_PHONE_STATE` for `ACCESS_NETWORK_STATE` — phone-state undeclared); new phone-state Setup row gated on `BuildConfig.SMS_ACTION_AVAILABLE` (false on play) at `PermissionOnboardingScreen.kt` (~:1287, uncommitted); `ContextEditorDialogs.kt` `StateContextPresets` offers "Roaming"/"Incoming call" unconditionally (uncommitted); emit copy `StateSensorEvents.kt:407-409,465-467` ("Open Setup and grant Phone permission…"); `SetupRequirementResolver` still emits `PHONE_STATE` on play with no row.
  Problem: On a play build a user can one-tap create a roaming/call-state context; the engine fails closed and the Inspector says "Open Setup and grant Phone permission" — but there is no such row and the permission is undeclared, so it can never be granted. This is the exact trap documented in the repo Learned notes for DND access (issue #4), reintroduced in the uncommitted work.
  Evidence: Verified.
  Fix: Gate the roaming/call presets and normalizations on the same build flag (or declare `READ_PHONE_STATE` on all distributions), and add the SetupRequirement→declared-permission contract test the Learned note prescribes (extend `DndAccessManifestContractTest` to all rows).
  Acceptance: On a play build the roaming/call presets are absent (or the permission is declared and a Setup row exists); a contract test asserts every SetupRequirement maps to a declared permission per distribution.
  Confidence: Verified — Effort: S

- [ ] A-24 — "Simulate" buttons destroy unsaved editor state; profile-editor simulation shows stale data
  Category: ux
  Where: profile path `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt:1203-1206` + `EditorDialogs.kt:619` (`onSimulate(profile)` passes the original saved object); context path `ActiveAutomationUi.kt:1259-1276` + `ContextEditorDialogs.kt:242-256`.
  Problem: Two contradictory lossy behaviors. (a) Profile editor: "Simulate trigger" calls `clearProfileDialog()` then simulates the ORIGINAL saved profile — pending edits (cooldown, priority, limits) are discarded and the simulation reports values that don't match the screen. (b) Context dialog: "Test synthetic event" builds a profile copy including the pending context edit, then closes the editor; dismissing the simulation does not reopen it and the fully configured context (e.g. location lat/lon/radius/dwell) is lost — while the simulation just showed it as applied.
  Evidence: Found by two auditors; verified.
  Fix: Keep the editor open behind the simulation dialog (both are just state); the profile path should simulate a `profile.copy(...)` built from the current field state (the same values passed to `onSave`).
  Acceptance: Editing a profile/context and tapping Simulate simulates the edited values and preserves the edits after the simulation closes.
  Confidence: Verified — Effort: M

- [ ] A-25 — Out-of-range profile Priority / Grace-period silently disables Save with no error shown
  Category: ux
  Where: `app/src/main/java/com/opentasker/ui/screens/EditorDialogs.kt:456-490` (fields accept 3–4 digits; `isError`/supportingText check only `parsed == null`) vs `:701-724` (`profileEditorCanSave` enforces `-100..100` and `0..3600`).
  Problem: Entering priority `500` or grace `5000` parses fine and shows normal helper text, but `canSave` fails the range check, so Save goes dead with no visible or announced reason. Clearing either field entirely also blocks Save silently (unlike cooldown, which allows blank). The sibling `maxActiveExecutions`/`burstLimit` fields DO range-check in `isError` — an inconsistency within the same dialog.
  Evidence: Found by two auditors; verified.
  Fix: Add the range (and blank) checks to the priority/grace `isError`/supportingText, using the existing `profile_priority_invalid`/`profile_grace_period_invalid` strings.
  Acceptance: Out-of-range priority/grace shows an error state naming the valid range; a test asserts `isError` for `500`/`5000`.
  Confidence: Verified — Effort: S

- [ ] A-26 — Semantic-diff "highlighted in Flow" feature is unreachable; the dialog copy promises something the user can never see
  Category: ux
  Where: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationUi.kt:819,1210`; `SemanticDiffDialogs.kt:76-81`; `strings.xml:851` (`semantic_diff_flow_note` = "%1$d flow node(s) are highlighted in Flow."); source `ActiveAutomationViewModel.kt:1672,1686-1688`.
  Problem: `changedNodeKeys` fed to `AutomationFlowScreen` comes from `semanticDiffReview`, which is non-null only while the modal SemanticDiffDialog is open (set at :1672, cleared on both dismiss paths). The dialog's scrim covers the Flow tab, and closing it — the only way to reach Flow — clears the keys. The changed-node border/pill rendering in `AutomationFlowScreen.kt` (`node.changed`, `flow_changed_node`) can never be observed.
  Evidence: Verified (grep-confirmed single writer).
  Fix: Decouple highlight lifetime from the dialog (clear on next edit, or a timed/`viewed` flag), or drop the note.
  Acceptance: After reviewing a diff, the changed nodes are visibly highlighted when the user opens the Flow tab — or the misleading note is removed.
  Confidence: Verified — Effort: S

- [ ] A-27 — Localization & accessibility source gates exclude the new dialog files (and one ships hardcoded English)
  Category: testing
  Where: `app/src/test/java/com/opentasker/ui/LocalizationSourceTest.kt:20-44` (hand-written `localizedFiles`) and `app/src/test/java/com/opentasker/ui/AccessibilitySourceTest.kt:110-171` (hand-written critical-flow map); uncovered new files: `SemanticDiffDialogs.kt`, `SyntheticTriggerSimulationDialog.kt`, `RunLogRetentionPreviewDialog.kt` (the last MOVED out of the covered `ActiveAutomationUi.kt`), plus `DiagnosticsScreen.kt`, `ContextInspectorScreen.kt`, `VariablesScreen.kt`, `ImportReviewDialogs.kt` for a11y. Hardcoded strings already present: `SyntheticTriggerSimulationDialog.kt:48,51,60` ("No cooldown is currently blocking this profile.", "Cooldown has $seconds second(s) remaining.", "Admission rejected this run.").
  Problem: A gate scoped by a hand-written list certifies whatever is not in it (the a11y toggle-row rule scans only `ActiveAutomationUi.kt`, which is how A-29 shipped). New surfaces are unguarded and one already contains the exact hardcoded copy the gate exists to forbid.
  Evidence: Found by two auditors; verified.
  Fix: Enumerate `ui/screens/*.kt` programmatically (or assert list completeness against the directory), move the three reason strings to resources (plural for the cooldown one), and add rule classes for touch-target height and severity-not-color-only.
  Acceptance: The gates fail when a new `ui/screens` file is added without coverage; the three simulation strings resolve from resources.
  Confidence: Verified — Effort: S

- [ ] A-28 — AlertDialogs and DropdownMenus render on M3 baseline (purple-tinted) surfaces instead of the app palette
  Category: visual
  Where: `app/src/main/java/com/opentasker/ui/theme/Theme.kt:34-100` (Amoled/Light/HighContrast schemes never override the `surfaceContainer*` family); `grep surfaceContainer|AlertDialogDefaults|MenuDefaults` → zero matches.
  Problem: M3 `AlertDialog` defaults to `surfaceContainerHigh` and `DropdownMenu` to `surfaceContainer`; unoverridden, they fall back to M3 baseline tokens. In light theme every editor dialog and the new duplicate/simulation/semantic-diff dialogs sit on a lavender surface against the cream palette; in dark they are purple-gray instead of graphite; in HighContrast they are `#2B2930` instead of black. Confirmed on-device this audit: the "Manage projects" dialog renders visibly purple-graphite over the AMOLED-black app. The app is dialog-heavy, so this affects most surfaces.
  Evidence: Verified (code + M3 token defaults + on-device screenshot).
  Fix: Set `surfaceContainerLowest/Low/…/Highest` (and `surfaceBright/surfaceDim`) in all three schemes.
  Acceptance: Dialogs and dropdown menus use the app's graphite/cream surfaces in all three themes; a screenshot check shows no purple tint.
  Confidence: Verified — Effort: S

- [ ] A-29 — New Setup toggles have no accessible name for TalkBack
  Category: a11y
  Where: `app/src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt` ~:659-706 (`DirectBootSetupCard`, new since v0.2.82) — `Switch(... modifier = Modifier.semantics { stateDescription = … })` with the title/body as sibling non-merged Texts.
  Problem: TalkBack focuses the bare switch and announces only "On/Off" + state, never "Direct boot". Every other toggle uses the `toggleable(role = Role.Switch)` + merged-label pattern the a11y gate enforces — but that test scans only `ActiveAutomationUi.kt`, so this new file's deviation passes (see A-27).
  Evidence: Verified.
  Fix: Make the card Row `toggleable(role = Role.Switch)` with merged semantics, or give the Switch a contentDescription of the card title.
  Acceptance: TalkBack announces the toggle's name + state; the a11y gate (once broadened) covers this file.
  Confidence: Verified — Effort: S

- [ ] A-30 — Bundle import surfaces a raw `kotlinx.serialization` exception string to the user
  Category: ux
  Where: "Paste / scan JSON" flow → import review; error rendered from the caught decode exception message.
  Problem: Observed on-device this audit: pasting invalid text into the "Paste or scan an OpenTasker bundle" dialog and tapping "Review bundle" shows an inline error reading "Error: Unexpected JSON token at offset 0: Expected start of the object '{', but had 'n' instead at path: $  JSON input: not-valid-json" — a raw serializer exception that also echoes the user's raw input back. Robotic, technical, and inconsistent with the app's otherwise calm microcopy.
  Evidence: Verified on-device (screenshot captured).
  Fix: Catch the decode failure at the import boundary and present a friendly message ("This doesn't look like an OpenTasker bundle. Paste the JSON exported from OpenTasker, or scan its QR code."), logging the technical detail rather than showing it; do not echo raw input.
  Acceptance: Invalid paste input shows a plain-language error with no serializer internals or echoed input.
  Confidence: Verified — Effort: S

- [ ] A-31 — Re-delivered external execution ids evicted from the ledger report FAILED instead of an idempotent ack
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt:211-229` (dedupe only via `ExternalExecutions.get`; ledger cap 64 at `ExternalExecutionLedger.kt:135`); journal insert `check(inserted != -1L)` at `ExecutionJournal.kt:56` / `TaskExecutionHelper.kt:123`; failure caught at `AutomationService.kt:878`.
  Problem: A caller re-sending `EXTRA_EXECUTION_ID` used >64 external commands ago is treated as new → `runExternalTask` → journal insert conflicts on the PK → `IllegalStateException` → the external record is set FAILED ("was already journaled") and the in-process ledger record is stuck RUNNING. The contract says "re-delivery of a command with the same id is an acknowledgement, not a second run."
  Evidence: Verified path.
  Fix: In `executeAndLogTask`, treat a journal insert conflict as DUPLICATE_DELIVERY (return skipped/ack) instead of `check`-throwing.
  Acceptance: Re-delivering an old execution id returns an idempotent ack, not FAILED; a test drives a conflicting id and asserts skipped.
  Confidence: Verified — Effort: S

- [ ] A-32 — `%FLOW_ERROR_CAUGHT` is never "true"; the documented flow.catch variable always reads "false"
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:375` (unreachable `phase == CATCH` branch), `:442` (sets "false" before entering the handler), `:445` (`nextPc = catchIndex + 1` skips the CATCH marker); documented at `TaskFailure.kt:42`.
  Problem: `recoverFailure` jumps past the CATCH marker, so the only way pc lands on it is sequential fall-through from a successful body (phase == BODY). The `phase == CATCH` assignment can never run, and inside every `flow.catch` handler the documented `%FLOW_ERROR_CAUGHT` reads "false". No test references it.
  Evidence: Verified (grep: zero test references).
  Fix: Set CAUGHT="true" in `recoverFailure`'s catch branch (or jump to `catchIndex` so the marker executes); add a test.
  Acceptance: Inside a `flow.catch` handler, `%FLOW_ERROR_CAUGHT` is "true"; a test asserts it.
  Confidence: Verified — Effort: S

- [ ] A-33 — `verify-fdroid-release.ps1` tag-vs-metadata check can never pass under the documented release flow
  Category: reliability
  Where: `tools/verify-fdroid-release.ps1:207-214` (requires `git rev-list -n1 v<version>` == yml `commit:`).
  Problem: The documented bump flow points the yml `commit:` at the BUILD commit, then adds a follow-up "sync release artifact metadata" commit, and the tag is placed on the SYNC commit. Verified: v0.2.82 tag → `bd01eeb`, yml → `03670e2` (its parent); v0.2.79 tag → `a9a82c3`, yml → `a802265`. The script throws for every release unless `-SkipTagCheck` is passed, so the tag gate is de-facto permanently bypassed.
  Evidence: Verified.
  Fix: Tag the build commit, or make the check accept `tag == a descendant of the metadata commit that only touches yml/truth`; then wire the tag check into a gate that actually runs.
  Acceptance: The tag check passes on a normal release without `-SkipTagCheck`.
  Confidence: Verified — Effort: S

- [ ] A-34 — README signing claims are stale after the selfhost-keystore change
  Category: docs
  Where: `README.md:149` ("Environment-driven release signing"), `README.md:192` ("Release build (unsigned without keystore env vars)"); actual behavior `app/build.gradle.kts:201-206`.
  Problem: Since the selfhost fallback, a release build WITHOUT env vars is signed with the checked-in `dev_keystore.jks` (the actual published identity), not unsigned. Both README lines say the opposite and neither mentions the repo-owned fallback key.
  Evidence: Verified.
  Fix: Update both lines to describe env-var signing with the repo-owned fallback identity; optionally add the phrase to a `DocumentTruthRule` forbidden list to force future sync.
  Acceptance: README accurately describes the signing fallback; the doc-truth gate covers the claim.
  Confidence: Verified — Effort: S

- [ ] A-35 — ROADMAP.md / RESEARCH.md are tracked and public, contradicting the "local-only" protocol
  Category: docs
  Where: `.gitignore:47-48` (`*.md` / `!README.md`); protocol text in repo CLAUDE.md ("Both files are gitignored (only README.md is committed)"); `git ls-tree origin/master` shows `ROADMAP.md`, `RESEARCH.md`, `CHANGELOG.md` tracked while `Roadmap_Blocked.md` is untracked.
  Problem: `.gitignore` cannot untrack already-tracked files, so half the roadmap protocol is public and half local; the currently-modified `ROADMAP.md` will be pushed on the next commit sweep, contrary to the stated protocol. `CHANGELOG.md` is tracked with no `!CHANGELOG.md` negation, so a delete/re-add would be silently ignored.
  Evidence: Verified.
  Fix: Decide the intended state — either `git rm --cached ROADMAP.md RESEARCH.md` (if local-only is intended) or correct the protocol text and add explicit `!CHANGELOG.md` (and `!ROADMAP.md`/`!RESEARCH.md`) negations. (Note: this session makes NO commits; this is a finding for the implementer.)
  Acceptance: The tracked/ignored state of the four docs matches the documented protocol.
  Confidence: Verified — Effort: S

### P3 — perf / a11y / maintainability / polish

- [ ] A-36 — New-feature copy generated in `core` is untranslatable English on primary surfaces
  Category: maintainability
  Where: `core/capabilities/AutomationLint.kt` (finding title/detail/suggestedFix literals, ~:75-131) shown in `ContextInspectorScreen.kt:545-548`, `AutomationFlowScreen.kt`, `ImportedProfileRiskDialog.kt:52-59`, and VM snackbars; `core/diff/AutomationSemanticDiff.kt:77-128` field labels and raw enum values ("UNTIL_DATE", "SILENT") shown in `SemanticDiffDialogs.kt:119`; `ProfileLifecyclePolicy.kt:5-41` suppression reasons; `ExecutionAdmissionController.kt` reason strings; `AutomationDuplicator.kt:13,17` ("Untitled", " (copy)"); status words interpolated into `ui_message_run_status`.
  Problem: These are user-visible in localized screens but can never be translated; the app already solved this class with `AutomationFlowStrings.from(resources)` (gate-enforced), so the new features regress the pattern. Mitigating: all 13 `values-*` dirs are currently empty, so the app is English-only today and the locale-completeness test passes vacuously.
  Evidence: Found by two auditors; verified.
  Fix: Move copy to resource IDs resolved at the collector (`UiMessage.resolve(context)` pattern) or structured enums mapped via `stringResource`.
  Acceptance: Lint/diff/suppression/status copy resolves from resources; a translation would localize them.
  Confidence: Verified — Effort: M

- [ ] A-37 — Starred run-log rows show the action verb "Unkeep" as a non-interactive status pill
  Category: ux
  Where: `app/src/main/java/com/opentasker/ui/screens/RunLogScreenContent.kt:591-594` (`StatusPill(stringResource(R.string.run_log_unstar))`); `strings.xml:475-476` (`run_log_unstar` = "Unkeep").
  Problem: A kept entry shows a state pill labeled "Unkeep" (the toggle's action verb, and not a word) directly above the real "Unkeep" TextButton — duplicated text, one inert. Screen readers encounter both.
  Evidence: Found by two auditors; verified.
  Fix: Add a `run_log_kept` state string ("Kept") for the pill.
  Acceptance: A kept entry's pill reads "Kept"; the action button still reads "Unkeep".
  Confidence: Verified — Effort: S

- [ ] A-38 — Simulation dialog reads SharedPreferences on the main thread during composition
  Category: perf
  Where: `app/src/main/java/com/opentasker/ui/screens/SyntheticTriggerSimulationDialog.kt:44-68` — `remember(profile) { CooldownStore(context).remaining(...); ExecutionAdmissionRegistry.preview(...) }` constructs SharedPreferences-backed stores and reads them synchronously on first composition; `preview` can fall back to `ExecutionAdmissionController.persisted(context)` + circuit-store load.
  Problem: First-load of each prefs file blocks the UI thread at dialog open. The VM's own `snapshot()` correctly uses `Dispatchers.IO`.
  Evidence: Found by two auditors; verified (jank magnitude needs repro).
  Fix: Hoist into a `LaunchedEffect`/ViewModel with a loading state.
  Acceptance: No main-thread prefs I/O when opening the simulation dialog.
  Confidence: Verified — Effort: M

- [ ] A-39 — Raw epoch milliseconds shown to users in expiry suppression text
  Category: ux
  Where: `app/src/main/java/com/opentasker/core/model/ProfileLifecyclePolicy.kt:11` ("This profile expired at ${profile.expiresAtMs}.") → rendered via `ContextInspectorScreen.kt` `InspectorNotice`.
  Problem: An expired UNTIL_DATE profile shows "This profile expired at 1770693599999." in the Inspector.
  Evidence: Verified (source read this audit).
  Fix: Format via the existing `formatProfileExpiryDate()` (in EditorDialogs.kt) or pass structured data to the UI layer for formatting.
  Acceptance: The message shows a human-readable date.
  Confidence: Verified — Effort: S

- [ ] A-40 — Trigger-lint severity is conveyed by color alone in two of three surfaces
  Category: a11y
  Where: `ContextInspectorScreen.kt:545-556` and `AutomationFlowScreen.kt:~276-297` (BLOCKING vs advisory expressed only as `error` vs `secondary` color; body text carries no severity word). `ImportedProfileRiskDialog.kt:52-59` does it correctly with `automation_lint_blocked_prefix`/`warning_prefix`.
  Problem: Color-blind users cannot distinguish blocking from advisory; mixed-severity Inspector lists collapse to one color.
  Evidence: Verified.
  Fix: Reuse the blocked/warning prefix strings in both surfaces.
  Acceptance: Each lint finding names its severity in text.
  Confidence: Verified — Effort: S

- [ ] A-41 — Flow-canvas "changed" node indicator is border-color-only and near-invisible in dark; a11y label omits changed/outputs
  Category: a11y
  Where: `AutomationFlowScreen.kt:449-452` (`FlowCanvasNode` marks changed nodes only with a 2dp `tertiary` border vs 1dp default) and `:442,512` (clickable nodes set `contentDescription = node.accessibilityLabel()`, superseding merged child text); `AutomationFlowGraph.kt:71-74` (`accessibilityLabel()` omits `changed` and `outputs`).
  Problem: Dark-theme tertiary (#9CB7B0) vs sage (#B7C7B0) are almost indistinguishable; the list variant got a text pill (`flow_changed_node`) but the canvas did not; screen readers never hear "changed" or the output-variable list on tappable nodes.
  Evidence: Verified (code; color claim from hex values).
  Fix: Add the pill/icon to `FlowCanvasNode`; extend `nodeAccessibility` with changed/outputs.
  Acceptance: Changed canvas nodes are distinguishable without color and announce "changed"/outputs.
  Confidence: Verified — Effort: S

- [ ] A-42 — Profile editor's radio-style option groups lack selection semantics
  Category: a11y
  Where: `EditorDialogs.kt:540-557` (overflow-policy, lifetime, retrigger groups) via `SelectableOption` (`:630-664`), a plain `OutlinedButton` with no `Role.RadioButton`/`selected()`/`stateDescription`.
  Problem: Selection is announced only if TalkBack reads the "Selected" pill; unselected options carry no "not selected" state and no radio-group context. The Setup theme selector does this correctly (gate-enforced); these three groups do not, and the v0.2.82+ lifecycle work doubled their usage.
  Evidence: Verified.
  Fix: Add `Modifier.semantics { role = Role.RadioButton; selected = … }` (or `selectable`) to `SelectableOption`.
  Acceptance: TalkBack announces each option's selected/not-selected state and radio-group membership.
  Confidence: Verified — Effort: S

- [ ] A-43 — Semantic-diff and import-review render all entries eagerly (no virtualization)
  Category: perf
  Where: `SemanticDiffDialogs.kt:36-58` (`SemanticDiffDetails` is a plain Column inside a single LazyColumn `item`, `entries.forEach`); same pattern in `ImportReviewDialogs.kt:139+`.
  Problem: A large bundle import (hundreds of changes) composes every entry inside an AlertDialog with no recycling.
  Evidence: Verified structure (impact needs a large diff to repro).
  Fix: Use `items(document.entries)` directly in the LazyColumn.
  Acceptance: A large diff composes only visible rows.
  Confidence: Verified — Effort: S

- [ ] A-44 — Home-screen widget uses the retired Catppuccin Mocha palette, disconnected from the app theme
  Category: visual
  Where: `app/src/main/res/values/colors.xml:3-8` (`widget_primary #CBA6F7`, `widget_background #11111B`, …) used by `res/layout/widget_task.xml`; no `values-night` variant.
  Problem: The app shell was rebranded to sage/graphite, but the widget still ships Mocha lavender-on-navy and is permanently dark regardless of theme/wallpaper — the one surface users see without opening the app.
  Evidence: Verified colors ("unintentional" is Likely — may be a deliberate static-dark exception).
  Fix: Align widget colors with the graphite/sage palette (or use DayNight resources).
  Acceptance: The widget matches the app brand in light and dark.
  Confidence: Likely — Effort: S

- [ ] A-45 — Diagnostics admission card exposes raw Room profile IDs instead of names
  Category: ux
  Where: `app/src/main/java/com/opentasker/ui/screens/DiagnosticsScreen.kt:347,381`; `strings.xml` `diagnostics_admission_profile_active` ("Profile %1$d active executions").
  Problem: Numeric IDs on a user-visible surface where every other new surface (inspector, run log) resolves names.
  Evidence: Verified.
  Fix: Resolve and show profile names (fall back to id only when the profile is gone).
  Acceptance: The admission card shows profile names.
  Confidence: Verified — Effort: S

- [ ] A-46 — Terminology drift in new strings
  Category: docs
  Where: `strings.xml:201` "Simulate trigger" vs `:202` "Test synthetic event" (two names for the same dialog); `profile_lifetime_label` = "Automation lifetime" among "Profile priority/fallback/grace" in the same dialog; widespread "(s)" pluralization (`semantic_diff_summary`, `ui_profile_lint_warnings`, `diagnostics_admission_open`, `synthetic_trigger_*`) while the gate mandates `plurals`.
  Problem: Inconsistent naming (profile vs automation, two labels for one feature) and manual "(s)" pluralization that won't localize.
  Evidence: Verified.
  Fix: Pick one name for the simulation feature; align "profile"/"automation" usage within a dialog; convert "(s)" strings to `plurals`.
  Acceptance: Consistent terminology; pluralized strings use `plurals`.
  Confidence: Verified — Effort: S

- [ ] A-47 — Global fallback-task rewrite persisted outside the delete transaction
  Category: correctness
  Where: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:640-644` (in `deleteTask`, `fallbackTaskSettings.saveTaskId(...)` runs after `db.withTransaction` commits; `loadTaskId` has no existence check).
  Problem: Process death between the transaction commit and the settings write leaves `FallbackTaskSettings` pointing at a deleted task id.
  Evidence: Verified (crash-window edge case).
  Fix: Move the fallback-settings update inside the transaction, or validate existence on load.
  Acceptance: Deleting the global fallback task never leaves a dangling fallback id after a crash.
  Confidence: Verified — Effort: S

- [ ] A-48 — Replay / duplicate / run-now buttons have no busy or debounce state
  Category: ux
  Where: `RunLogScreenContent.kt:607-612` (Replay), `ActiveAutomationViewModel.kt:1426-1443` (`replayHeldRun`), `:755` (`duplicateProfile`).
  Problem: Buttons stay enabled during the async launch; a double-tap replays a held execution twice (real side effects — see A-13) or creates two copies. Admission limits bound but don't prevent it.
  Evidence: Verified.
  Fix: Disable the control / show a busy state until the operation completes (or debounce).
  Acceptance: Double-tapping Replay/Duplicate performs the action once.
  Confidence: Verified — Effort: S

- [ ] A-49 — Simulation dialog state uses plain `remember` and is lost on rotation
  Category: correctness
  Where: `ActiveAutomationUi.kt:294` and `ContextInspectorScreen.kt:245` (`var simulationProfile by remember { mutableStateOf<Profile?>(null) }`); sibling dialog IDs use `rememberSaveable`.
  Problem: A configuration change dismisses the simulation dialog (and, with A-24, loses the already-discarded edit). Profile isn't Parcelable, so the fix is holding it in the ViewModel like `semanticDiffReview`.
  Evidence: Verified.
  Fix: Hoist the simulation target into the ViewModel.
  Acceptance: Rotating during a simulation keeps the dialog open.
  Confidence: Verified — Effort: S

- [ ] A-50 — New broadcast receivers registered `RECEIVER_EXPORTED` against codebase convention
  Category: security
  Where: `app/src/main/java/com/opentasker/core/contexts/StateSensorEvents.kt:428,497,559` (uncommitted) — roaming, call-state, tethering receivers use `RECEIVER_EXPORTED`.
  Problem: All three actions are protected broadcasts (spoofing blocked at send time), but every other dynamic receiver in the codebase uses `RECEIVER_NOT_EXPORTED` (which still receives system broadcasts on API 33+). The exported flag is an unnecessary widening and an unexplained convention break in unreviewed code.
  Evidence: Verified.
  Fix: Switch all three to `RECEIVER_NOT_EXPORTED`.
  Acceptance: The three receivers register NOT_EXPORTED and still receive their system broadcasts.
  Confidence: Verified — Effort: S

- [ ] A-51 — Pre-API-36 tethering "ready" marker claims readiness with no value, so `tethering=false` never matches
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/contexts/StateSensorEvents.kt:561-563` (`emitReady(emit, "tethering")` with no `tethering` value; roaming/call/speed all perform an initial read).
  Problem: Until the first tethering transition, a `tethering=false` predicate silently never matches (absent key → fail-closed) while the Inspector reports the source ready with no explanation. Combined with A-20, the pre-36 tethering key is effectively unusable.
  Evidence: Verified.
  Fix: Perform an initial tethering-state read on registration and emit the value (see A-20's state-read fix).
  Acceptance: A `tethering=false` predicate matches immediately when tethering is off.
  Confidence: Verified — Effort: S

- [ ] A-52 — Step-detector path emits a context event and full profile re-evaluation on every step
  Category: perf
  Where: `app/src/main/java/com/opentasker/core/contexts/StateSensorEvents.kt:219-231,267-271` — with a step detector, `emitActivity` always passes non-null `stepsPerMinute`, bypassing the `activity == lastActivity` dedupe; `activity_steps_per_minute` changes almost every step.
  Problem: Each step yields a state patch, a merged ContextEvent (value changed, so the dedupe can't help), and a `combine`+expression re-evaluation of the whole profile — ~2 evaluations/second while walking.
  Evidence: Verified.
  Fix: Emit only when the classified activity or a bucketed rate changes (rate-limit the cadence metadata).
  Acceptance: Walking does not trigger per-step profile re-evaluation; a test asserts events are throttled.
  Confidence: Verified — Effort: S

- [ ] A-53 — `url.open` accepts scheme-less URIs (allowlist not fail-closed for the null case)
  Category: security
  Where: `app/src/main/java/com/opentasker/core/actions/AppActions.kt:99-102` — `if (scheme != null && scheme !in ALLOWED_SCHEMES)`; a scheme-less URI (`scheme == null`, e.g. `//host/path`) bypasses the allowlist and is dispatched via `ACTION_VIEW`.
  Problem: Low impact (Android usually finds no handler), but the scheme allowlist is not fail-closed for null.
  Evidence: Verified.
  Fix: Reject or normalize scheme-less input before dispatch.
  Acceptance: A scheme-less `url.open` argument is rejected; a test asserts it.
  Confidence: Verified — Effort: S

- [ ] A-54 — `ActionRegistry` category/retry-safety drift guards became tautological after the centralization refactor
  Category: maintainability
  Where: `app/src/main/java/com/opentasker/core/engine/Action.kt:88-95` (`register` asserts `declaration.category == action.category` and `declaration.retrySafety == action.retrySafety`), but `DeclaredAction` (`:113-122`) sources both sides from the same `ActionDefinition` after commit b3a704b.
  Problem: The asserts compare the definition's fields to themselves and can no longer detect the drift they were written to catch (a check in the same trust domain). The pre-refactor actions hardcoded these per class, so the comparison used to be meaningful.
  Evidence: Verified.
  Fix: Drop the now-tautological asserts, or move the invariant to where category/retry-safety are declared independently (metadata vs. catalog).
  Acceptance: The guard either verifies an independent source of truth or is removed; a comment explains the invariant.
  Confidence: Verified — Effort: S

- [ ] A-55 — Secret-variable AEAD binds the variable name but not the project (same-name ciphertexts swappable across projects)
  Category: security
  Where: `app/src/main/java/com/opentasker/core/storage/VariableSecretStorage.kt:50,73` (`cipher.updateAAD(variableName…)` only) vs the v9 composite key `(projectId, name)` (`VariableDao.kt:16`).
  Problem: The codec's stated guarantee ("ciphertext cannot be copied to a different variable and still decrypt") no longer holds across projects: two secrets named `apikey` in projects A and B share identical AAD, so an envelope moved between rows decrypts cleanly. Requires DB write access (SQLCipher-protected), so impact is low, but the projectId should join the AAD (with a versioned envelope, e.g. `otsec:v2`).
  Evidence: Verified.
  Fix: Include projectId in the AAD and version the envelope.
  Acceptance: An envelope moved between same-named secrets in different projects fails to decrypt; a test asserts it.
  Confidence: Verified — Effort: S

- [ ] A-56 — `TaskerXmlExporter` is unreachable dead code while CHANGELOG claims it shipped
  Category: maintainability
  Where: `app/src/main/java/com/opentasker/core/transfer/TaskerXmlExport.kt:29,59` (`object TaskerXmlExporter.export` — no production caller; every reference is in tests); CHANGELOG.md:306 states "Tasker XML export … Exports Time, Day, Application, State, and Event contexts" as shipped.
  Problem: The feature is inaccessible to users despite the changelog, and the redaction-unification work for this file (commit 458a3b2, the `secretValues`-aware `ExportRedactionPolicy.Context`) is never exercised in production, so a regression there ships silently.
  Evidence: Verified (exhaustive symbol search).
  Fix: Wire the exporter into the UI/ViewModel export flow, or remove it and correct the changelog.
  Acceptance: Either a user can export Tasker XML (with the redaction path exercised by a test), or the dead code and the changelog claim are removed.
  Confidence: Verified — Effort: S

- [ ] A-57 — Import boundary corpus does not cover several reachable paths (including the internal-subset XXE branch)
  Category: testing
  Where: `app/src/test/java/com/opentasker/core/scripting/ImportBoundaryCorpusTest.kt`; the untested logic is the internal-subset bracket scanner in `ImportResourceGuard.sanitizeTaskerXml` (`ImportResourceBudget.kt:58-85`).
  Problem: The corpus covers JSON/XML/external-intent/Locale/Termux boundaries but not: (a) DOCTYPE with an internal-subset ENTITY declaration (`<!DOCTYPE x [ <!ENTITY xxe "…"> ]>`) — the actual XXE-defense bracket-matching branch, only an external SYSTEM DTD is tested (caught earlier by the preflight); (b) `ShareContextEvents.parseInput` (Parcelable/CharSequence rejection, `MAX_URIS`, control chars); (c) the `LocaleConditionQueryReceiver` secret-variable refusal (A-19's mitigation).
  Evidence: Verified.
  Fix: Add corpus cases for the internal-subset ENTITY DOCTYPE, the share-intent boundary, and the secret-refusal path.
  Acceptance: The corpus fails if the internal-subset DOCTYPE scanner is reverted; all three paths are covered.
  Confidence: Verified — Effort: S

- [ ] A-58 — Journal `recordStep` issues one DB UPDATE per executed action (up to 100k writes/run)
  Category: perf
  Where: `app/src/main/java/com/opentasker/core/engine/TaskExecutionHelper.kt:188-190` (`onStepCompleted` → `ExecutionJournal.recordStep`); `MAX_FLOW_STEPS = 100_000` (`TaskRunner.kt:795`); sub-task steps report their own indices, making `lastStepIndex` ambiguous across nesting.
  Problem: A long flow run generates one row UPDATE per action.
  Evidence: Verified.
  Fix: Throttle step journaling (time- or count-based).
  Acceptance: A long flow run performs far fewer journal writes than actions; a test asserts throttling.
  Confidence: Verified — Effort: S

- [ ] A-59 — `runTaskNow` lacks the `runCatching` its sibling `replayHeldRun` has (crash path)
  Category: reliability
  Where: `app/src/main/java/com/opentasker/ui/screens/ActiveAutomationViewModel.kt:1407-1424`.
  Problem: `executeAndLogTask` rethrows non-action exceptions (`TaskExecutionHelper.kt:290`, e.g. a Room I/O failure in `ExecutionJournal.start`, which is not `runCatching`-wrapped) → uncaught in `viewModelScope.launch` → app crash instead of the toast the sibling produces.
  Evidence: Verified path (rare trigger).
  Fix: Wrap `runTaskNow` in `runCatching` with a user-facing error, matching `replayHeldRun`.
  Acceptance: A DB failure during "Run now" shows an error toast, not a crash.
  Confidence: Verified — Effort: S

- [ ] A-60 — WAIT-mode cross-recursive `task.run` can deadlock and bypasses the per-action timeout
  Category: correctness
  Where: `app/src/main/java/com/opentasker/core/engine/TaskRunner.kt:534` (task.run returns before the `withTimeout` wrapper), `:626` + `TaskCollisionCoordinator.kt:37` (`waitMutex.withLock` around the whole run).
  Problem: Task A (WAIT) sub-runs B (WAIT) while a concurrent execution of B sub-runs A → AB-BA mutex deadlock; `executionChain` only guards one chain. No timeout applies (task.run short-circuits before `withTimeout`), and each hung run holds an admission lease until the global cap (8) wedges the engine (user can still cancel via the active-executions UI). Mostly pre-existing, but new admission leases raise the blast radius.
  Evidence: Likely (needs two concurrent cross-recursive WAIT tasks).
  Fix: Ordered lock acquisition, or a bounded `withTimeout` around WAIT acquisition.
  Acceptance: Two cross-recursive WAIT tasks do not deadlock; a test drives the interleave with a timeout.
  Confidence: Needs-repro — Effort: M

- [ ] A-61 — `VerifyDocumentationTruthTask` declares CHANGELOG/release-truth as inputs but never checks them; `verifyFdroidReadiness` has an always-pass check
  Category: maintainability
  Where: `app/build.gradle.kts:54-56,846-850` (`currentDocumentation` includes `CHANGELOG.md` + `tools/release-truth.json`) vs `:77-117` (action reads only `readmeFile` + `historicalDocumentation`); `:916` (`check(selectedDistribution in allowedDistributions)` — the same predicate is already `require`d at configuration time on `:145`).
  Problem: The "currentDocumentation" inputs invalidate caching but are never verified — CHANGELOG claims are unguarded despite the task description ("Checks current release claims"). The fdroid-readiness `check` can never fail.
  Evidence: Verified.
  Fix: Verify something in those files (e.g. CHANGELOG contains a `## v$appVersionName` heading) or drop them from the input list; delete the tautological `check`.
  Acceptance: The task fails when CHANGELOG lacks the current version heading; the always-pass check is gone.
  Confidence: Verified — Effort: S

- [ ] A-62 — Stale early-scheme tags v0.3.0 / v0.4.1 / v0.4.2 are also published GitHub Releases and sort above v0.2.82
  Category: docs
  Where: git tags + `gh release list --repo SysAdminDoc/OpenTasker` (releases dated 2026-05-04).
  Problem: The tags sort above v0.2.82 in semver order. Mitigations verified: GitHub marks v0.2.82 "Latest", and F-Droid `UpdateCheckMode: Tags` reads versionCode from the build files (at those tags `versionCode = 1`/`versionName "0.1.0"`, so 84 wins). Residual risk: "highest semver tag" tooling (`git describe`, third-party update checkers) resolves to v0.4.2, and the `/releases` page linked from the yml/README shows v0.4.x above the 0.2.x entries.
  Evidence: Verified.
  Fix: Delete the three tags and their GitHub releases (or retag as `archive/…`); they carry no artifacts.
  Acceptance: `git tag` and the GitHub releases list show no v0.3.x/v0.4.x entries above v0.2.82. (Blocked-item note: deleting published GitHub releases is a remote mutation — implementer/owner decision; this audit makes no changes.)
  Confidence: Verified — Effort: S

- [ ] A-63 — PowerShell `-match` lowercase-SHA validations are case-insensitive, contradicting their error text
  Category: maintainability
  Where: `tools/generate-release-truth.ps1:33` and `tools/verify-fdroid-release.ps1:195` — `-notmatch "^[0-9a-f]{40}$"` with message "must be a full lowercase SHA".
  Problem: `-match` is case-insensitive, so an uppercase SHA passes the "lowercase" validation (the Kotlin gate later catches it case-sensitively, so impact is a confusing delayed failure).
  Evidence: Verified.
  Fix: Use `-cnotmatch`.
  Acceptance: An uppercase SHA fails the PowerShell validation immediately.
  Confidence: Verified — Effort: S

- [ ] A-64 — Dead trust entries left in dependency-verification metadata
  Category: maintainability
  Where: `gradle/verification-metadata.xml:17` (`trusted-key … group="io.netty"`) and `:132` (`org.jsoup version="1.22.2"` alongside the current 1.23.1).
  Problem: Commit 0275a57 removed all netty/grpc-netty components (matching the "Netty absent" policy) and bumped jsoup to 1.23.1 but left the netty signing key and the jsoup 1.22.2 version-scoped trust behind, so the trust set over-approximates the graph. Related honesty nit: `tools/verify-dependency-verification.ps1:122-128` (`Test-PgpSignatureFile`) only checks the `.asc` looks like a signature — the origin label "published .asc + artifact SHA-256" records signature presence, not verification (Gradle does the real PGP check via `verify-signatures`).
  Evidence: Verified.
  Fix: Remove the netty trusted-key and the jsoup 1.22.2 trust; relabel the PS origin string to reflect presence-not-verification.
  Acceptance: The trust set contains no netty/1.22.2-jsoup entries; the origin label is accurate.
  Confidence: Verified — Effort: S

- [ ] A-65 — Roadmap_Blocked entries are stale: MQTT (RD34) has shipped
  Category: docs
  Where: `Roadmap_Blocked.md` "P3 - MQTT publish action (RD34) … Blocked on client library decision"; actual: `mqtt.publish` is registered (`ActionCatalog.kt:101`), capability `Supported` (`ActionCapabilities.kt:89`), implemented in `MqttPublishAction.kt` (10.5K).
  Problem: The blocked-roadmap item describes MQTT as a not-yet-decided spike, but the action is built, registered, and user-reachable (see A-18). Blocked/roadmap docs disagree with the code. (Also re-check the other Roadmap_Blocked items — e.g. UnifiedPush RD32 — against the current tree for the same drift.)
  Evidence: Verified for MQTT.
  Fix: Remove the shipped MQTT item from Roadmap_Blocked (git/CHANGELOG are the record); audit the rest of Roadmap_Blocked for shipped features.
  Acceptance: Roadmap_Blocked contains no items that are already implemented in the tree.
  Confidence: Verified — Effort: S

- [ ] A-66 — Local repo CLAUDE.md carries several provably false machine facts
  Category: docs
  Where: repo `CLAUDE.md` — header "Room schema: v10" (actual `AppDatabase.kt` = 15, release-truth = 15); Key Files lists `.github/workflows/build-release.yml` (no `.github/` directory exists — correct per the no-CI policy, but the reference is dead); architecture diagram "43 registered definitions" (actual 74); gotcha #6 "requires `abortOnError = false`" (build sets `abortOnError = true`, `app/build.gradle.kts:229`).
  Problem: The living working-notes file misstates schema version, action count, lint config, and a nonexistent CI workflow. Low priority (gitignored, warn-only), but it misleads future sessions.
  Evidence: Verified.
  Fix: One doc sweep to correct schema (15), action count (74), lint (`abortOnError = true`), and remove the dead CI reference.
  Acceptance: CLAUDE.md matches the current tree.
  Confidence: Verified — Effort: S

- [ ] A-67 — JSON bundle export cannot redact literal (inline) secret values, unlike the (unreachable) XML exporter
  Category: security
  Where: `app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt:605-632` (`exportBundle` builds `ExportRedactionPolicy.Context(secretNames = …)` with empty `secretValues`, `:209`) vs `TaskerXmlExport.kt:68-71` (passes both).
  Problem: The live JSON export redacts args that reference a secret by name and applies generic URL/token patterns, but cannot redact an arg containing a literal copy of a secret's plaintext (`ordinaryExport()` never exposes secret plaintext). A user who pasted a secret's plaintext into a non-sensitively-named arg gets it exported in cleartext unless a generic pattern catches it. Largely inherent to crypto-at-rest (the JSON path has no plaintext to match), so this is a documentation/known-limitation item rather than a resurrection bug — omitted secret variables are filtered on export with a warning (`:159`).
  Evidence: Needs-repro (requires the user to have hand-entered secret plaintext into an action arg).
  Fix: Document the limitation in `docs/OPEN_JSON_BUNDLE.md` and warn on export when an arg literally contains a known secret name's value; a true fix requires plumbing decrypted secrets into export, which contradicts the storage model — decide explicitly.
  Acceptance: The export limitation is documented and the user is warned; or a deliberate decision is recorded.
  Confidence: Needs-repro — Effort: S

### Unaudited — needs a future pass
- [ ] A-68 — Areas not covered this pass (log so a later audit closes them)
  Category: docs
  Where: N/A (coverage note).
  Problem: Not audited in depth this pass: Scene editor/canvas/overlay (`SceneEditor*.kt`, `SceneOverlayControls.kt`, scene runtime), the pre-existing Location/Calendar/Sun/NFC/Boot context sources (no fresh commits, several prior audits), `GlobalSearchDialog`/`ContextGroupingDialog`/`ProfileShareReviewDialog`, the widget/quick-settings-tile config activities beyond theming, and any behavior requiring physical-device/API-37 evidence (already tracked in Roadmap_Blocked). On-device UI driving covered the primary + secondary screens and the import-error path in both themes, but did not exercise every editor field or destructive-confirmation flow.
  Fix: Schedule a follow-up pass over Scenes and the share/search dialogs; treat the Roadmap_Blocked device-evidence items as the on-device checklist.
  Acceptance: A later audit records findings or an explicit clean result for each area above.
  Confidence: Verified — Effort: M
