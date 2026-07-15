# Changelog — 白い熊 自由作業盤

Fork-specific changes layered on top of [OpenTasker](https://github.com/SysAdminDoc/OpenTasker).
This lists what the fork adds; upstream's own history lives in the OpenTasker repository.

## Upstream v0.2.76 merged on the 0.2.76/78 resync (2026-07-20)

### Deep audit fixes (2026-07-17)

- **Engine**: exit tasks now run on their own job slot and never consume the profile cooldown, so a cooldown, SINGLE-mode in-flight enter task, or RESTART can no longer silently drop the exit task. Closed a QUEUED lost-task race where a retrigger could be enqueued into a queue whose consumer had already decided to exit.
- **Engine**: plugin conditions no longer flap — the shared Locale plugin poll source multiplexes every subscription, so the matcher now holds state for results addressed to a different plugin/bundle instead of driving every plugin context true→false→true each 30 s cycle. The internal `sun_tick` minute pulse can no longer satisfy a generic/blank-filter EVENT context (previously firing imported event profiles every minute); blank-event/blank-filter specs fail closed.
- **Contexts**: all-day calendar events match on the local day instead of the raw midnight-UTC bounds (they were shifted by the zone offset). The Context Inspector is now read-only and no longer resets the engine's persisted location dwell timers, and its match explanations honor OR groups like the engine. Serialized the two-thread state-source merge and synchronized camera/mic AppOps start/stop against a watcher leak.
- **Data**: added indexes on `run_logs(timestamp)` and `edit_history(entityType, entityId)` (schema v8, migrated + instrumented).
- **Actions**: `download` runs on OkHttp with a policy-DNS hook so the cleartext private-LAN rule and the API 37 `ACCESS_LOCAL_NETWORK` gate are enforced against the addresses actually connected to (closing a DNS-rebinding TOCTOU), fails on non-2xx instead of saving a redirect stub over a good file, and fsyncs before the atomic rename. `tile.set` fails honestly and is capability-gated Unsupported instead of reporting a no-op Success. `screen.timeout` rejects the "0 = never" value that actually turns the screen off immediately. `flow.wait` at its 30-minute maximum no longer always times out; `sound.play`/`tts.speak` get a 10-minute budget and TTS queue failures fail fast. `datetime.*` zone typos fail closed; `data.read` CSV supports RFC 4180 quoted fields. Added missing editor fields for ping, download, sound.play, and media.mute.
- **UI**: fixed a Diagnostics crash from duplicate log keys (now keyed on a monotonic sequence) and stopped its polling while backgrounded. The one-time NFC write is disarmed on dialog close, expires after 60 s, and runs its tag I/O off the main thread. `deleteVariable` reports real success/failure instead of an optimistic toast; undo no longer reports false success; `updateTask`/`updateProfile` are transactional. Editing an unknown action type shows a message instead of a dead tap. The context editor blocks saving garbled TIME windows and out-of-range coordinates. New profiles default to disabled, the enter-task selection no longer resets mid-edit, and backup state loads off the main thread.
- **Theming**: scene warning text follows the applied theme's luminance (was near-invisible in Light-app-on-dark-system), the Locale plugin edit activity honors the persisted theme, run-log detail lines no longer render twice, and the scene overlay is clamped on screen so it can't be dragged fully offscreen. Removed dead duplicate helpers.

## Upstream unreleased work merged on the 0.2.75/77 resync (2026-07-15)

- **Diagnostics**: added a secondary Diagnostics destination with live engine heartbeat, active foreground-service types, app-standby bucket, exact-alarm delivery, last matcher failure, WorkManager watchdog stop reason, bounded process logs, and redacted crash previews. Shared diagnostic reports now include that health snapshot, up to 100 ring-buffer entries, and bounded crash excerpts; Authorization/Bearer credentials are redacted in addition to existing secret patterns.
- **Background reliability**: time/day contexts now consume AlarmManager wake pulses in addition to an aligned in-process minute clock, so a Doze wake reaches the matcher instead of only producing a log line. Inexact fallback alarms use `setAndAllowWhileIdle`; a persisted service heartbeat and 15-minute WorkManager watchdog re-arm dropped ticks, and foreground-service timeout leaves a recovery alarm armed before shutdown.
- **Variable reliability**: global write-back now compares each run against its hydrated snapshot under a process-wide mutation coordinator and publishes accepted rows as one Room batch. Concurrent runs merge disjoint globals without loss; stale same-global writers preserve the first committed value and add an explicit conflict note to the run log instead of silently clobbering it.
- **Variables**: unified variable-name normalization across runtime writes, `var.persist`, the Variable vault, durable storage, and Tasker XML imports. Any uppercase letter now consistently identifies a global; all-lowercase names stay task-local, explicit lowercase persistence targets are promoted instead of silently disappearing, invalid targets fail visibly, and root-local event values cannot leak into durable snapshots.
- **Networking**: replaced the split HTTP GET/POST editor actions with one cancellable `http.request` transport on OkHttp 5.4.0. It supports GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, bounded structured query/header/auth input, inline or file bodies, status/header/body variables, atomic response files, per-stage timeouts, and explicit no-redirect/same-origin redirect policy. TLS bypasses and cross-origin redirects fail closed, cleartext remains private-LAN-only, header traces redact credentials, and stored GET/POST IDs remain hidden compatibility aliases.
- **Release/docs**: expanded the release-truth contract from README-only checks to source-derived capability and version checks across architecture, dependency, F-Droid, scenes, visual flow, Shizuku, Termux, and Locale documentation. The gate excludes explicitly historical dependency logs and includes a deterministic stale-document failure example.
- **Backup reliability**: encrypted `.otbackup` exports now use chunked format v2, authenticating each bounded 64 KiB frame plus an explicit terminal frame before validated restore staging is atomically published; v1 backups remain restorable. Wrong passphrases, corruption, truncation, cancellation, write failures, and interrupted staging clean temporary plaintext without replacing an existing pending restore, while startup restore keeps its pending journal through same-directory atomic database replacement.
- **Battery/reliability**: production Wi-Fi, connectivity, app-usage, shake, camera/mic, package, and Bluetooth context monitors now start only while an enabled profile depends on them and stop after the final dependent profile is disabled or deleted. Profile edits reconcile reference counts without duplicate registrations, and an explicit subscription barrier prevents a newly activated pulse source from firing before its matcher is listening. Camera/mic AppOps pulses are now also wired into event-context matching.
- **Security**: every built-in action now has an explicit data-access, external-transmission, device-control, destructive, or local-only classification. Bundle schema v2 carries a computed task/profile power manifest and flags potential data-to-external chains; unknown actions fail before import or task side effects. Imported profiles persist a review-required state, stay outside the engine registry, and require an in-app acknowledgement before their first enable.
- **Security**: global variables can now be explicitly stored as Android Keystore-backed AES-256-GCM secrets. Secret provenance survives legacy/template expansion and derived writes, redacts nonsensitive argument fields, action logs, traces, and failures, and keeps values out of ordinary OpenTasker/Tasker exports. Cross-device or key-loss restores fail closed with a deliberate re-entry flow.
- **Maintainability**: split the scene library's list/cards, interactive canvas, element dialogs, and overlay controls into focused Compose modules; the public screen is now a 160-line coordinator protected by source-boundary, localization, accessibility, and scene behavior tests.
- **Security**: Termux scripts now require a Setup-managed SHA-256 allowlist and a matching Termux-side preflight hash before every run. The app declares and requests `RUN_COMMAND`, requires Termux 0.109+ result support, receives results through a non-exported one-shot callback, bounds arguments/stdin/timeouts/stdout/stderr/pending commands/rate-limit state, redacts captured content from logs, and can map stdout, stderr, exit code, and original lengths to variables.
- **Data safety**: completed the fail-closed stored-payload boundary across manual, widget, notification, external-intent, export, inspector, and widget-configuration paths; corrupt rows stay untouched for database recovery and skipped runs now record the reason. Edit-history pruning is also entity-scoped, so trimming one task can no longer delete another task, profile, or scene history.
- **Scenes**: overlay rendering now uses the editor's authored scene projection and exact element bounds, supports overlapping elements and bounded local-image decoding from persistable content URIs, and reads both legacy slider `progress` and current `value` deterministically.
- **Reliability**: notification action buttons now bind to immutable task IDs through a task picker. Renames preserve bindings, deleted tasks fail visibly, and legacy name bindings migrate only when the name is unique; duplicates never select an arbitrary task.
- **Scenes**: multi-selected elements now move as one rigid, edge-clamped mutation with one transactional undo snapshot; resize gestures use independent horizontal and vertical canvas scales and stay within scene bounds.
- **Onboarding**: first-run template onboarding now completes only after an explicit skip or successful install and resumes after dismissal or recreation. Runtime permission results update Setup immediately, repeated denial routes to app settings, and grant/revocation resets recovery state.
- **Accessibility**: scene overlays now use a 48dp close target, expose screen-reader move actions, and retain a proper touch click path. Profile switches, task/profile actions, nested action/context controls, run-log filters, and expression details now expose specific names and authored state without duplicate decorative icon announcements.
- **Platform**: Android 17 audio hardening is now eligibility-aware instead of disabling every audio action. Visible task launches and while-in-use-eligible automation services attempt sound, TTS, volume, ringer, mute, and media-key operations; boot/background runs fail before side effects with recovery guidance, while exact-alarm access is honored only for alarm-stream changes.
- **Security**: Shizuku permission can no longer promote elevated capabilities or route commands through an ordinary app-UID `ProcessBuilder`. The kill switch is persisted and defaults on, Setup distinguishes stopped/permission/unavailable/disabled states, and every elevated action remains `Unsupported` until a privileged user-service transport exists.
- **Security**: OpenTasker JSON and Tasker XML imports now enforce shared entity/action/context/scene/string budgets, plus streaming token/node and nesting preflights before model or DOM allocation. Named violations fail before the Room transaction.
- **Release**: added one local quality/release gate covering blocking lint, the JVM test floor, Room schema drift, Android-test compilation, resolved dependency/repository/checksum policy, a CycloneDX SBOM with OSV advisory results, configuration-cache reuse, and Play/F-Droid release assemblies. Enabling permission lint also caught and fixed the missing manifest permission for the shipped vibrate action.
- **i18n**: moved action/context catalogs, setup and backup copy, capability diagnostics, widget plurals, and scene-overlay labels to Android resources; seeded Spanish setup translations, enabled `en-XA`/`ar-XB` debug pseudolocales, and expanded localization guards.
- **Reliability**: Wake-on-LAN now rejects MAC addresses with mixed `:`/`-` separators (e.g. `AA:BB-CC:...`); a consistent separator is required.
- **Actions**: added a text/regex action pack — **Match Text** (`text.match`, captures become an array), **Replace Text** (`text.replace`, `$1` group refs), **Split Text** (`text.split`, literal or regex), **Join Text** (`text.join`), and **Substring** (`text.substring`). Regex uses the linear-time RE2 engine with bounded pattern/input sizes, so patterns can't hang the runner.
- **Actions**: added date-time actions — **Format Date/Time** (`datetime.format`), **Parse Date/Time** (`datetime.parse`), and **Add to Date/Time** (`datetime.add`). Convert between epoch milliseconds and formatted strings with optional time zones, and do calendar-aware date arithmetic (seconds through years), all deterministic and offline. Fixed units are exact zone-independent deltas; months/years honor calendar length.
- **Actions**: added a **Read Data** action (`data.read`) that parses JSON, CSV, or XML into variables entirely on-device — ideal for turning HTTP responses and file contents into usable automation data. Supports JSON path selectors (`items[0].name`), CSV column/cell selection, and XML element paths (`root/item/name`), sets an array plus a `%var_count`, is size-bounded, hardened against XML external entities, and fails closed on malformed input or an unresolved selector.
- **Security**: the external-automation broadcast target now bounds the number of supplied variable extras (64) in addition to the existing per-value length cap, name validation, and signature permission.
- **Interoperability**: OpenTasker bundle import now tolerates hand-edited JSON — `//` comments, trailing commas, and case-insensitive enum values decode cleanly, while unknown keys and oversized bundles are still rejected. Export output is unchanged.
- **Reliability**: task execution now runs off the main thread. Every run path (manual, profile trigger, widget/shortcut, notification action, Locale/external) executes actions on `Dispatchers.IO`, and the automation service's matching/dispatch runs on `Dispatchers.Default`. Previously blocking actions (HTTP GET/POST, download, ping, Wake-on-LAN, file I/O) launched from the main thread threw `NetworkOnMainThreadException` and failed silently. Debug builds now install StrictMode to flag any accidental main-thread disk/network I/O.
- **Privacy**: SMS recipient numbers are now masked in run logs (e.g. `***6789`) instead of stored in full — run-log redaction does not otherwise scrub phone numbers.
- **Reliability**: hardened smaller action/import edge cases — the Termux script action no longer passes a spurious empty argument when `arguments` is blank or double-spaced; `file.list` reports a clean "invalid file name pattern" failure instead of leaking a raw Java exception for a bad glob; and OpenTasker bundle import no longer counts updated variables as newly inserted.
- **Reliability**: hardened the variable engine. A `var.set` targeting a huge array index (e.g. `%X[2000000000]`, reachable from an imported/shared profile) no longer tries to grow a multi-billion-entry list — out-of-range writes fail closed. Array storage now evicts the genuinely least-recently-used array at its cap instead of an arbitrary one, and is synchronized for concurrent tasks. Ternary conditions whose test contains parentheses (e.g. `(%A(+1) > 5) ? a : b`) are now parsed correctly instead of silently falling through.
- **Reliability**: event/notification text matching with `regex=true` now uses the linear-time RE2 engine (as variable regex already does) instead of the JDK backtracking engine, so a pathological user pattern can no longer hang the matcher on an incoming event.
- **Correctness**: battery-level triggers now normalize `EXTRA_LEVEL` against `EXTRA_SCALE`. On devices that report a non-100 scale (some report 255), `battery_level` thresholds previously never/always matched.
- **Security**: the exported Locale fire receiver now requires a revocable execution grant. Any app could previously broadcast a chosen task id to the receiver and have OpenTasker run it. Configuring the plugin now issues a high-entropy token bound to the selected task; the receiver dispatches only when the incoming bundle carries a token that is still stored and bound to that exact task, so forged, missing, mutated, revoked, and deleted-task grants are rejected without dispatch. Grants are revoked automatically when their task is deleted.
- **Networking**: cleartext HTTP to LAN/private hosts now actually works. The network-security config previously listed private ranges as `<domain>` hostnames (Android has no CIDR support there), which silently blocked every literal LAN IP. Cleartext is now gated solely by the runtime policy — HTTPS stays the default, `allow_http` is an explicit opt-in, and any host not resolving to a loopback/link-local/site-local/IPv6-ULA address is rejected before a connection opens. IPv6 Unique Local Addresses (`fc00::/7`), previously misclassified as public, are now recognized.
- **Variables**: global (`%UPPERCASE`) variables and `var.persist` values are now genuinely durable. Every execution path (manual run, profile trigger, widget/shortcut, notification action, Locale/external intent) hydrates persisted globals before running and commits any globals changed during the run to the database before reporting success, so they survive across runs and process restarts. Local (lowercase) variables still never escape their invocation, and the Variables vault now reflects real global state.
- **Reliability**: the running automation engine now reconciles itself from the profiles table. Creating, editing, enabling, disabling, or deleting a profile rebuilds matchers and plugin subscriptions live, without needing a service restart, while leaving any in-flight task run untouched. Purely cosmetic edits (name, group) no longer thrash the engine.
- **Data safety**: corrupt stored automation payloads now fail closed. Task, profile, and scene rows whose JSON no longer decodes are surfaced with the exact record and field, cannot be executed (profiles skip them with a run-log note and `task.run` refuses corrupt sub-tasks), and cannot be overwritten by the normal editors (the raw bytes are preserved for undo/backup recovery). Scene edits now also snapshot to edit history, and stored payloads decode through a shared codec that tolerates unknown additive fields.
- **Release**: refreshed the draft F-Droid metadata pin and local fdroidserver lint/build evidence for `0.2.75`/`77`.
- **Release**: added the Kotlin/Gradle dependency verification hashes needed by clean fdroidserver source checkouts.
- **Reliability**: hardened database backup creation so local backups wait for a complete WAL checkpoint, publish only schema-validated copies, clean up failed temporary files, and keep backup UI state from getting stuck after failures.
- **Testing**: added Compose instrumentation coverage for setup onboarding, task/profile editor validation, action/context required-field validation, scene creation, and incompatible import review states.
- **Accessibility**: added repeatable source gates for setup, profile/task editors, action/context editors, scenes, destructive dialogs, and run-log states; converted remaining app-shell and setup semantic labels to string resources.
- **i18n**: completed the core active automation, editor, flow, scene, and premium-state string-resource extraction pass; added a JVM source guard for hardcoded Compose strings and valid Weblate locale targets.
- **Reliability**: routed remaining direct platform log calls through `AppLogger` and added a source-level regression guard so `android.util.Log` stays isolated to the logging wrapper.
- **Maintainability**: finished the active-automation shell split into owned view-model, list, editor, action, and context modules while keeping `ActiveAutomationUi.kt` under 1,500 lines.
- **Release**: synced draft F-Droid metadata and the PowerShell release verifier with the current `0.2.75`/`77` Gradle release contract.
- **Docs**: added a release-truth contract test so README release values and shipped-feature claims stay aligned with Gradle metadata and current backend docs.

## v0.2.75 - 2026-06-19

Scene editor finishing pass and visual flow editor authoring.

- **Feature**: scene overlay launch via `SYSTEM_ALERT_WINDOW` — each scene card shows a "Show" button (when overlay permission is granted) that displays the scene as a draggable floating window with dark-themed element views and tap-to-run-task bindings.
- **Feature**: scene element multi-select — drag-starting an element selects it (highlighted border); when multiple elements are selected, dragging one applies the delta to all selected elements as a group.
- **Feature**: alignment guides on scene canvas — elements snap to canvas edges, center lines, and other element edges/centers during drag. Dashed guide lines render during the gesture with a 6dp threshold.
- **Feature**: flow canvas pinch-zoom (0.5x-2.5x) and pan gestures for the lane overview.
- **Feature**: flow edge routing — vertical connectors between lanes and horizontal connectors between nodes drawn as Canvas lines with endpoint dots.
- **Feature**: branch and subflow markers — action nodes with sub-task references show a Subflow pill; conditional actions show a Branch pill with the if-condition text.

## v0.2.74 - 2026-06-19

i18n bootstrap, engine v3, dependency upgrade, encrypted backup, Shizuku/Termux backends, and Locale interop.

- **i18n**: expanded `strings.xml` from 49 to 170+ string resources covering all major UI surfaces. Converted ImportReviewDialogs, VariablesScreen, RunLogScreenContent, ContextInspectorScreen, and SceneLibraryScreen to use `stringResource()`. Created locale skeleton directories for 13 languages. Added contributor translation workflow docs to README.
- **Feature**: `var.set` now supports dotted and bracketed path syntax (`config.theme`, `items[0]`, `Data.user.profile.name`) for nested JSON writes via `VariableStore.setAtPath()`. Array indices auto-grow with empty-string padding.
- **Feature**: Run-Log expression traces now render in an expandable debugger surface with per-expression arg name, scope source, monospace expression→value mapping, and warning highlights.
- **Feature**: encrypted database backup/restore using AES-256-GCM with PBKDF2-derived keys (600k iterations). `.otbackup` file format with 4-byte magic, salt, IV, and authenticated ciphertext.
- **Feature**: Shizuku elevated backend with real API 13.1.5 integration. Checks Shizuku service state (ping, permission), exposes Ready/PermissionNeeded/Disabled/ManagerInstalled states. ShizukuShellRunner validates commands against a strict allowlist. Kill-switch toggle. ActionCapabilities dynamically promotes elevated actions when Shizuku is active.
- **Feature**: Termux RUN_COMMAND dispatch with executable path, arguments, working directory, and background execution. SHA-256 script hash pinning for allowlist verification. 1-second per-script frequency cap. Output-to-variable mapping via capture prefix.
- **Feature**: Tasker XML export for the mappable action subset (notify, wait, log, var.set). Exports Time, Day, Application, State, and Event contexts. Reports skipped actions and unmappable contexts.
- **Feature**: Locale plugin target bridge — OpenTasker now appears as a Locale-compatible setting plugin for Tasker/MacroDroid. Edit activity shows task picker; fire receiver dispatches tasks through the existing automation pipeline.
- **Feature**: scene element resize handles on the canvas preview. Drag the bottom-right handle to resize elements within scene bounds.
- **Dependencies**: upgraded Compose BOM from 2026.04.01 to 2026.05.00 with updated dependency verification checksums.
- **Style**: adopted DesignSystem spacing and radius tokens across 5 major UI screen files.

## v0.2.73 - 2026-06-17

Hardening, test coverage, and expression engine improvements.

- **Security**: applied Android 17+ `ACCESS_LOCAL_NETWORK` permission guard to Ping and Wake-on-LAN actions; all LAN socket actions now enforce the same gate.
- **Security**: extended the Android 17+ local-network guard to HTTPS requests targeting private, loopback, or link-local hosts so URL-backed LAN actions cannot bypass Setup permission state.
- **Reliability**: added Room schema v5 drift gate — CI now fails if any schema version file is missing; added migration tests for 2→3, 4→5, and full 1→5 path.
- **Supply chain**: enabled Gradle dependency verification with SHA-256 checksums for all resolved artifacts.
- **Feature**: added `var.persist` action to promote local variables to global scope across task invocations.
- **Testing**: broadened action guard coverage for file, settings, app, and notification-channel operations; expanded retention policy boundary tests.
- **i18n**: centralized common UI strings (navigation, dialogs, setup, empty states) in `strings.xml`.
- **Docs**: updated Setup permission copy to list all guarded network actions.
- **Safety**: `AutomationService.onDestroy()` now snapshots job collections before cancelling to prevent `ConcurrentModificationException` during service teardown.
- **Safety**: `reloadProfiles()` cleans up stale queued tasks for deleted/disabled profiles, preventing memory accumulation.
- **Safety**: `ArrayStore` now caps at 500 entries to prevent unbounded growth from `%var(split:...)` operations within a single task run.
- **Safety**: `OpenTaskerBundleCodec.decode()` now rejects JSON payloads larger than 16 MB to prevent OOM from malicious imports.
- **Safety**: capped `file.write` and `file.append` payloads at the existing 1 MB file-action boundary and fail before creating or expanding files beyond that limit.
- **Safety**: bounded imported database restore staging to 100 MB and remove temporary files if the import stream fails or exceeds the limit.
- **Safety**: `WiFiNetworkMonitor` and `ConnectivityMonitor` handle null `ConnectivityManager` gracefully instead of crashing on devices where the system service is unavailable.
- **Reliability**: serialized persisted Location dwell-state read/modify/write operations so concurrent matchers cannot lose inside-since updates.
- **Reliability**: cleaned up LocationManager listener registration on provider-set changes and partial registration failures to avoid duplicate callback chains.
- **Safety**: surfaced corrupted profile/task JSON as visible UI warnings while keeping safe fallback domain objects.
- **Safety**: hardened database backup validation with current schema-shape checks, required-table row-count reads, and a consumed WAL checkpoint before copy.
- **Maintainability**: split run-log rendering and import-review dialogs out of `ActiveAutomationUi.kt`, with source tests guarding the screen ownership boundary.
- **Reliability**: made `flow.wait`, `vibrate`, and `screen.timeout` fail clearly on missing, malformed, or out-of-range durations instead of silently defaulting or clamping.
- **Fix**: implemented deterministic `file.list` filename glob filtering and added the missing action editor field for `pattern`.
- **Security**: tightened import validation by rejecting oversized/DOCTYPE Tasker XML before parsing and blocking OpenTasker bundles with duplicate task IDs or variable names.
- **UX**: bounded long Tasker/OpenTasker import review dialogs so warnings and mapped-action lists stay scrollable on compact screens.
- **Reliability**: replaced API 33-only service receiver registration with AndroidX compatibility calls and gated camera/mic active watchers to Android 11+ APIs.
- **Safety**: made downloads write to a temporary file and replace the destination only after a complete bounded copy succeeds.
- **Performance**: reduced scene-canvas drag recomposition overhead by using primitive float state and lambda offsets.
- **UX**: polished first-run onboarding, labeled create actions, the widget task picker, and the home-screen widget treatment so setup and secondary flows feel more guided and intentional.
- **Reliability**: made widget and launcher-shortcut task runs close with clear feedback even when task execution throws, avoiding stranded translucent runner activities.
- **Reliability**: guaranteed external automation broadcast pending-results finish even if ordered-result publication fails.
- **Cleanup**: removed duplicate `ArrayStore.joinWith` method (identical to `join`).

## v0.2.72 - 2026-06-16

Setup and secondary-state polish pass.

- **Setup**: replaced the theme button grid with an accessible radio-style selector that exposes selected/not-selected state and avoids no-op selected buttons.
- **Backup**: tightened backup/restore copy, added a calm state banner, and changed secondary backup actions into compact side-by-side controls so the card scans better on compact screens.
- **Permissions**: normalized setup action button shape for a more consistent control language.
- **Flow/Scenes/Inspector**: upgraded sparse empty states into framed, explanatory surfaces with status cues and clearer next-step copy.
- **Docs**: bumped app metadata and README/roadmap state for v0.2.72.

## v0.2.71 - 2026-06-16

Premium UX polish pass.

- **Navigation**: promoted Run Log into the primary bottom navigation, clarified destination labels, and tightened selected-state geometry for more stable compact-screen behavior.
- **Theme**: synced the navigation bar color with the AMOLED/light/high-contrast theme selection so edge-to-edge chrome feels intentional.
- **Profiles/Tasks**: made status and secondary action rows horizontally safe on compact screens, added filtered no-match notices, and kept long mode/group/collision labels from crowding primary content.
- **Run Log**: moved outcome and duration chips below the run header so diagnostics keep readable width with long task names and trace detail.
- **Variables**: upgraded the Variables tab into a summary-driven variable vault with metrics, clear search, polished empty states, consistent cards, and explicit sensitive-value masking labels.
- **Design system**: added reusable screen spacing and opacity tokens to reduce hardcoded visual decisions across Compose surfaces.

## v0.2.69 - 2026-06-16

Locale condition plugin context UX (N7).

- **Feature**: added `ContextType.PLUGIN` for Locale/Tasker condition plugins as first-class profile context predicates; users can pick a condition plugin, configure it, and have profiles activate/deactivate based on the plugin's satisfied/unsatisfied state.
- **Feature**: added `LocalePluginConditionContextSource` that polls subscribed condition plugins every 30 seconds with last-known-state caching through the existing `LocalePluginConditionStateCache`.
- **Feature**: added Plugin context row in the context picker with package, config JSON, description, and timeout fields.
- **Feature**: Context Inspector shows plugin condition source health, config summary (package + blurb), and match state.
- **Engine**: `AutomationService` registers plugin subscriptions when enabled profiles are loaded and clears them on destroy.
- **Tests**: added evaluator tests for plugin matching, package/bundle validation, inversion, and inspector config summary.

## v0.2.68 - 2026-06-16

Safety and correctness patch.

- **Safety**: replaced legacy Java/Kotlin regex worker threads in variable `%regex` and `%replace` operators with RE2/J linear-time matching, eliminating leaked `regex-eval` threads from pathological user-authored patterns.
- **Safety**: unsupported advanced regex syntax now fails closed for variable regex operations instead of attempting cancellable backtracking.
- **Correctness**: fixed `torch.set` toggle semantics by reading the current torch state through `CameraManager.TorchCallback`; if Android cannot report the state, toggle now fails honestly and tells users to use explicit `on`/`off`.
- **Correctness**: torch actions now select a camera that actually reports flash availability instead of using the first camera id.

## v0.2.67 - 2026-06-15

Deep engineering, security, and UX audit pass.

- **Thread safety**: made `ArrayStore` concurrent-safe with `ConcurrentHashMap` to prevent `ConcurrentModificationException` when tasks run in parallel automation mode.
- **Thread safety**: upgraded `VariableStore` local scope maps to `ConcurrentHashMap` to prevent race conditions between concurrent coroutines reading/writing the same scope.
- **Thread safety**: marked `WiFiNetworkMonitor.lastState` and `ConnectivityMonitor.lastState` as `@Volatile` since `NetworkCallback` methods fire on binder threads.
- **Thread safety**: marked `CameraMicContextEvents` camera/mic callback fields as `@Volatile` to prevent races between `start()` and `stop()` on different threads.
- **Resource leak**: added `CameraMicContextEvents.stop()` call in `AutomationService.onDestroy()` to unregister `AppOpsManager` watchers that were previously leaked.
- **Data corruption**: fixed HTTP response `readBounded` to collect bytes into `ByteArrayOutputStream` before UTF-8 decode, preventing multi-byte character corruption when a character straddles an 8KB read boundary.
- **Correctness**: fixed `BrightnessAction` auto mode to set `SCREEN_BRIGHTNESS_MODE` to automatic instead of writing `-1` to the brightness value. Manual brightness values now explicitly set the mode to manual first.
- **Correctness**: fixed `ScreenTimeoutAction` to clamp the timeout value to 0–30 minutes, preventing `Long`-to-`Int` truncation on large values.
- **Correctness**: fixed `SunEventCalculator` DST offset to use the offset at the approximate event time instead of noon, preventing sunrise/sunset times from being off by 1 hour on DST transition days.
- **Correctness**: seeded `battery_level` and `charging` in `StateContextSourceImpl.seedInitialState()` from the sticky `ACTION_BATTERY_CHANGED` broadcast so battery-based profile conditions evaluate correctly immediately after service start.
- **Crash fix**: `FlowGraphCard` now uses `firstOrNull()` instead of `first()` for the profile node, preventing `NoSuchElementException` if graph data is corrupted.
- **Crash fix**: TTS `SayAction` now guards continuation resume with `AtomicBoolean` to prevent double-resume if TTS callbacks race.
- **Safety**: capped vibration duration to 10 seconds to prevent extended uncontrolled vibration.
- **Safety**: capped queued task depth per profile to 50 in QUEUED automation mode, preventing unbounded memory growth from rapid triggers.
- **Safety**: changed database backup WAL checkpoint from `FULL` to `TRUNCATE` for safer backup consistency.
- **Safety**: fixed notification button `PendingIntent` request codes to use hash-based IDs instead of `notifId * 10 + i`, preventing integer overflow for large notification IDs.
- **Memory**: `ShakeDetector` now uses `applicationContext` to prevent potential `Service`/`Activity` context leak.
- **UX**: fixed `disabledAlpha` modifier to use `Modifier.alpha()` instead of a semi-transparent black overlay, which broke disabled element appearance in light theme.
- **UX**: warning color in scene validation now uses warm amber/peach instead of green (tertiary), which was confusing since green implies success.
- **UX**: added `contentDescription` to navigation bar icons for screen reader accessibility.
- **Design system**: added `Radii.xxl` (18dp) token and `SemanticColor.warningDark`/`warningLight` to the design system. Replaced ~11 hardcoded `RoundedCornerShape(18.dp)` instances across all screens with the design token.

## v0.2.63 - 2026-06-15

Release-polish pass.

- Added IME padding to the main Compose scaffold so focused forms have safer keyboard behavior.
- Reduced bottom-navigation crowding by showing labels only for the selected destination.
- Added confirmation before deleting global variables and preserved variable search/edit/delete dialog state across recreation.
- Made widget task rows explicit button-role targets with minimum row height and long-text ellipsis.
- Added button roles to clickable flow-graph nodes.
- Preserved task/profile/action editor drafts with saveable state across configuration changes.

## v0.2.62 - 2026-06-15

Action editor compatibility and UI polish.

- Aligned dynamic action form metadata with runtime argument keys for brightness, screenshots, file read/write/append/list, and HTTP GET/POST actions.
- Kept legacy saved-action keys working (`level`, `filename`, `variable`, `content`, and `body`) so older automations still prefill and execute correctly after the metadata correction.
- Replaced full-round badge geometry with bounded 8dp corners and removed the unused full-round radius token.
- Changed action/template/context picker lists from fixed heights to adaptive max-height constraints for better small landscape and split-screen behavior.
- Made checkbox action fields full-row switch targets with explicit switch role and on/off state descriptions.
- Added regression coverage for metadata field keys and legacy HTTP POST body handling.

## v0.2.61 - 2026-06-14

Security hardening, platform readiness, and new actions/functions.

- **Target SDK 36**: raised `targetSdk` from 35 to 36 for Android 16 platform compliance.
- **HTTP POST body bound**: POST bodies are now capped at 1 MB and use fixed-length streaming mode before the network connection opens.
- **Regex match timeout**: user-authored regex operations in variable expansion now have a 2-second wall-clock timeout to prevent ReDoS.
- **Network Security Config**: added platform-level scoping that blocks public-host cleartext while permitting LAN/private-range HTTP (forward-compat with Android 17 `usesCleartextTraffic` deprecation).
- **android:allowBackup=false**: explicitly declared for privacy-first posture.
- **Android 17 audio gating**: `sound.play` and `tts.speak` now fail honestly on Android 17+ when background audio requires a media FGS type the engine does not hold; capability registry updated.
- **Hilt shrinker cleanup**: removed stale `Hilt_OpenTaskerApp` and `dagger.hilt.android.HiltAndroidApp` keep rules from proguard-rules.pro.
- **Theme toggle**: added DataStore-backed System/Dark/Light theme preference with a toggle card in the Setup screen; wired into MainActivity and widget config.
- **Wake-on-LAN action** (`wol`): sends a magic packet to wake devices on the local network with MAC validation, configurable broadcast IP/port, and unit tests.
- **Date template function**: added `{{ value | date:'pattern' }}` for epoch-millis formatting with bounded patterns, Locale.ROOT output, and fail-closed rejection of invalid patterns or non-numeric input.
- **Registry-metadata parity test**: bidirectional contract test ensuring every runtime action has UI metadata and vice versa.
- **Action guard tests**: new `ActionGuardsTest` covering POST body cap, URI scheme allowlist, wait duration cap, HTTP policy, ping host validation, missing-argument failures, and WoL packet construction.

## Unreleased

- Fixed State context matching so battery, charging, headphones, and screen facts persist across partial broadcasts instead of replacing one another.
- Added State context aliases and fail-closed numeric predicate handling for malformed thresholds.
- Added `lintDebug` to the normal GitHub Actions build workflow.
- Fixed Event context matching so repeated identical one-shot events can retrigger profiles while level contexts keep activation/deactivation semantics.
- Fixed boot Event context truthfulness by routing manifest boot starts through `AutomationService` into a replay-safe `event=boot_completed` pulse, and removed unsupported SMS-received trigger advertising from the active event source.
- Removed the legacy parallel automation engine, second `automation.db` Room database, legacy Hilt provider module, dead minimal activity, shell-capable legacy action, and dead battery/geofence manifest receivers. Active app, WiFi, and time monitors now publish into core context bridges; rebuilt APKs shrank from 22,321,836 to 21,799,321 bytes (debug) and 2,107,361 to 2,041,684 bytes (release unsigned).
- Added configurable Run Log retention with short, standard, and extended presets. The standard default keeps 30 days or 1,000 entries, prunes on service/UI startup and hourly after inserts, and includes DAO pruning coverage.
- Added Setup-tab database backup and restore controls. Backups checkpoint and export the active Room database through Android's document picker; imported backups are validated, staged for the next startup, applied before Room opens, and roll back to the previous database if restore fails.
- Added Profiles-tab OpenTasker JSON bundle export/import. Exports use Android's document picker, imports preview schema/version/counts/warnings/capability requirements before confirmation, and imported profiles are always disabled for review.
- Added a Play distribution manifest policy gate that omits SMS and phone-state permissions, hides SMS setup, and marks the SMS action unsupported while keeping standard/F-Droid SMS behavior intact.

## v0.2.59 - 2026-05-05

Dependency modernization, visual flow, scene editor, and navigation polish.

- Added typed graph-node targets to the pure automation flow model so profile, context, task, action, and missing-reference nodes can route back to existing editors.
- Made Flow tab nodes selectable and wired them into the current profile/task/action/context edit dialogs, with stale-target feedback if the underlying Room data changes.
- Added first-class conditional action metadata to the flow graph so conditional steps render with `if ...` edge labels and compact conditional markers instead of being hidden inside generic action details.
- Added a compact, horizontally scrollable Flow lane overview for profile/context/enter/exit lanes as the first read-only canvas interaction before drag/drop editing.
- Added deterministic Flow graph accessibility summaries and node labels, then wired them into Compose semantics for screen readers and UI automation.
- Added Flow-tab mutation shortcuts for adding contexts to a graph profile and adding steps to enter/exit task lanes through the existing context and action pickers.
- Added Scene-tab element creation/editing for button, text, slider, and image controls, with tap and long-press task binding pickers plus removable element rows.
- Replaced the Scene card text-only preview with a scaled canvas projection that renders element positions and sizes against the scene dimensions.
- Added drag-to-move editing on the scaled Scene canvas, converting preview offsets back to bounded scene dp coordinates before updating Room.
- Shortened bottom navigation labels from `Inspector` to `Inspect` and `Run Log` to `Log` so compact navigation items align consistently.
- Upgraded Hilt/Dagger from `2.46` to the intermediate `2.52` line while leaving Kotlin, KSP, AGP, Room, and runtime startup wiring unchanged.
- Verified the Hilt batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and the F-Droid release profile.
- Upgraded Room from `2.6.1` to `2.8.4` on the existing `androidx.room` artifact line after the Kotlin/KSP/compiler batch; Room 3.0 remains a separate future migration because it uses the new `androidx.room3` group.
- Verified the Room batch with connected migration instrumentation tests on `SM-S938B`.
- Upgraded WorkManager from `2.9.1` to `2.11.2`; no active workers are registered yet, so this batch is dependency/build compatibility only and passed the standard dependency gate.
- Upgraded the stable Compose/AndroidX UI dependency set within the current API 35 / AGP 8.7 constraints: Compose BOM `2025.07.00` and Activity Compose `1.10.1`; newer Activity/Navigation lines are deferred because they require API 36 and AGP 8.9.1, while Compose BOM `2025.08.01+`, Hilt Navigation Compose `1.3.0`, and Lifecycle `2.9.x+` are deferred because they resolve Lifecycle lint checks that need a newer AGP/Kotlin analysis stack.
- Upgraded the runtime-support dependency subset to Core KTX `1.18.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.11.0`, and Gson `2.14.0`.
- Upgraded the compiler alignment set to Kotlin/Compose plugin `2.3.21` and KSP `2.3.7`, migrating Gradle configuration from deprecated `kotlinOptions` to `compilerOptions`.
- Resolved the earlier Kotlin `2.3.21`/KSP `2.3.7` blocker by moving Hilt/Dagger from `2.52` to `2.59.2` after the AGP 9 batch.
- Upgraded the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, while keeping target SDK `35`.
- Verified the AGP/API 36 batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`; the previous release R8 Kotlin metadata warnings are gone.
- Upgraded the API 36-unblocked AndroidX stable dependency set: Core KTX `1.18.0`, Compose BOM `2026.04.01`, Activity Compose `1.13.0`, Lifecycle `2.10.0`, Navigation Compose `2.9.8`, and Hilt Navigation Compose `1.3.0`.
- Verified the AndroidX follow-up with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Upgraded the AGP 9 compatibility stack to Gradle wrapper `9.4.1`, AGP `9.2.1`, Hilt/Dagger `2.59.2`, Kotlin/Compose plugin `2.3.21`, KSP `2.3.7`, and Kotlinx Serialization JSON `1.11.0`.
- Added temporary AGP 9 compatibility flags for the explicit Kotlin plugin path: `android.builtInKotlin=false` and `android.newDsl=false`; these keep the build green now but must be removed before AGP 10 by migrating to built-in Kotlin and Android Components/new DSL APIs.
- Verified the AGP 9 stack with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Migrated AGP 9 to built-in Kotlin and the new DSL by removing the explicit `org.jetbrains.kotlin.android` plugin, deleting the temporary AGP 9 opt-out flags, and replacing the deprecated androidTest asset source-set mutation.
- Verified the built-in Kotlin/new DSL migration with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Reviewed Room 3 and deferred migration because the new `androidx.room3:room3-*` artifact group is alpha-only (`3.0.0-alpha03`) and would touch both persisted databases plus migration tests.

## v0.2.58 - 2026-05-05

Tasker XML import UI and F-Droid release verification.

- Added a user-facing Tasker XML import flow to the Profiles screen using Android's document picker.
- The preview reads selected XML with a bounded 4 MB limit, parses it through the existing secure Tasker importer, and shows source counts, import counts, mapped/unsupported actions, migration warnings, and capability review notes.
- Confirmed imports now reuse the existing Room-backed OpenTasker bundle repository and create imported Tasker profiles disabled by default for review.
- Added a pure `TaskerImportPlanner` for preview summaries and disabled-by-default confirmed bundles with JVM coverage.
- Synced the draft F-Droid metadata to version `0.2.58` / code `60` and pinned it to release source commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`.
- Added `:app:verifyFdroidMetadata` plus CI/release workflow coverage so F-Droid metadata version fields, commit pinning, Gradle properties, preassemble hooks, changelog URL, and unsigned APK output stay in sync.
- Added `tools/verify-fdroid-release.ps1` for release-tag checks, F-Droid lint/build execution, and signature-agnostic APK payload comparison against a signed upstream APK.
- Verified local `fdroid lint` and WSL fdroidserver 2.4.4 `fdroid build --no-tarball com.opentasker.app:60` with Java 17 and Android SDK 35.

## 0.2.75+189 — 2026-07-12

The **cool-running release**: the 音楽端灯 meteors move from a WebView canvas to a **native METEOR
scene element** — same dance, roughly **a third of the CPU** during music playback (~195 % → ~60 %,
measured) — plus a headless **adb workspace-transfer bridge** (broadcast-driven export/import) that
powers a fully automated build-test cycle, physically **rounded screen corners** with their own knob,
and a 電池線 fix that stops the charging flame at 100 % and turns the line blue.

### 音楽端灯 — native METEOR element (the heat fix)
- **Why:** the phone heated up badly while playing music. Systematic measurement (live `top`
  sampling per variant, thread-level breakdown) traced the load to **WebView's per-frame canvas
  machinery, not the meteor math**:
  - the real meteor page cost **~185–195 % CPU** sustained (app process + WebView renderer);
  - a bare full-screen canvas drawing ONE line at 60 fps already cost **~60 %**;
  - four edge-strip canvases cost **more** (~110 %) — per-layer commit overhead, not pixels;
  - an rAF loop with **no** canvas drawing cost ~0 % — the commit path itself was the furnace.
- **New scene element `METEOR`** (`scenes/EdgeMeteors.kt` + a `SceneActivity` renderer branch):
  a 1:1 native port of the meteor page — perimeter ribbons in a rounded-rect band, layered glow,
  comet-taper core, white-hot head star, per-ribbon twinkle, hue drift, and the full **音楽反応 v3
  tempo-locked** behaviour (beat-grid pump, auto-gain-normalised dynamics, onset fallback) — drawn
  by the app's **own RenderThread** like the 電池線 charging fire, reading
  **`MusicPulseSource.Bridge` natively** (no JS bridge, no WebView renderer process at all).
- **Three rendering pathologies found and eliminated on the way** (each measured, each ~190 %+):
  - `BlurMaskFilter` Gaussian glow — CPU-rasterized per blurred path on a hardware canvas (~225 %);
  - wide anti-aliased **stroked paths** (even 3-point ones) — HWUI software-rasterizes every stroke
    into a mask texture on its `hwuiTask` threads each frame;
  - the band's **even-odd ring `clipPath`** — a full-window coverage mask rasterized every frame;
    this was the invariant cost across ALL variants, the original WebView page included.
- **Final architecture — GPU-native primitives only**: every ribbon is split at the screen corners
  it crosses into 1–3 **axis-aligned capsules** (`drawRoundRect`); the glow is concentric widening
  capsules with a Gaussian-ish alpha falloff; the core taper is a per-run axis-aligned
  `LinearGradient`; the band's inner hole is punched by **one `PorterDuff.CLEAR` round-rect**
  instead of a clip. Result: `hwuiTask` raster threads at **0.0 %**, RenderThread ~21 %,
  **~60 % total at 60 fps / ~50 % at 45 fps** (vs ~195 %), dance visually intact.
- **Physically rounded screen corners** (白い熊's design): four **opaque-black corner masks** drawn
  over the band — ribbons run into the corner squarely underneath and emerge from behind the curve.
  The mask path is static and HWUI-cached (rebuilt only on size/radius change), so it is free.
  Its radius is an independent live knob **`%Ongaku_Corner`** (default 18; `0` = square corners,
  `32` = the band's own rounding) in `音楽端灯の設定 [01]`.
- **All knobs are now %var-live**: the METEOR config maps every `%Ongaku_*` variable through the
  scene engine's live expansion, so palette, speeds, glow, fps, reactive tuning — everything —
  applies **instantly without re-showing the scene** (the WebView read them once at page load).
- **The FPS cap became a true linear heat dial**: a capped-out frame is skipped before the sim
  step — no state write, no recompose, no draw, no commit — so `%Ongaku_Maxfps` now scales cost
  almost proportionally. Default retuned **60 → 45** (visually smooth, measurably cooler);
  the dead interim `Ongaku_Glowres` knob was removed from 設定.
- Invisible ribbons (fade-in/fade-out ends of life) skip all drawing; `METEOR` is editable in the
  scene editor (element-type list, defaults, size).
- Screen-off gating as before: the element leaves composition when the display sleeps — the frame
  loop stops dead, nothing computes in the dark.

### Workspace-transfer bridge — headless export/import over adb
- **New `WorkspaceTransferReceiver`** (`core/transfer/`), an exported ordered-broadcast bridge
  gated by the shared protocol extra (same convention as the widget bridges):
  - **`shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE`** — writes a full workspace export
    (tasks, profiles, scenes, variables, templates, projects, groups, item metadata) as an
    OpenTaskerBundle JSON to `/sdcard/tmp/白い熊 自由作業盤.<yyyy-MM-dd_HH-mm-ss>.json` (or an
    explicit `extra.PATH`), answering with the written path and item counts;
  - **`shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE`** — imports the bundle JSON at `extra.PATH`
    (a bare filename resolves against `/sdcard/tmp`) with the standard strategies (merge projects,
    overwrite same-name items in place), answering with a human-readable import summary and any
    validation warnings; failures return the error message in the broadcast result.
- Powers the new **fully automated dev cycle**: baseline export → mirror sync → build →
  `adb install -r` → push bundles → headless import → test → final export → archive — no manual
  file picking anywhere.

### 電池線 — stop the charging flame at 100 %, full-charge line goes blue (workspace-side)
- `denchi.update` gained a gate: at **100 % battery `%Charging` is forced to `false`**, so the
  charging fire stops while the plug stays in (it previously burned forever on a full battery,
  since EXTRA_PLUGGED-based charging detection stays true).
- **`%Denchi_Full`** (the 100 % line colour) changed green → **blue `#0000FF`**; label updated.
- Both tasks re-shipped fully literate (labels on every action + task notes).

### Packaging & docs
- Dev-workflow overhaul recorded in `CLAUDE.md` and the repo skills (`build-apk`,
  `workspace-mirror`): wireless adb (direct or via the `skhw` ssh tunnel), automatic
  `adb install -r`, `/sdcard/tmp` keeps only the current APK, end-of-cycle archives the final
  export and APK to the on-phone backup tree.
- Version tail: builds `+182` – `+189`; `versionCode = 770189`.

## 0.2.75+181 — 2026-07-11

The **music-reactive release**: the 音楽端灯 edge-light meteors now **dance to the actual music** —
tempo-locked to a beat grid the engine derives live from the device's output mix — plus a
narrow-screen (folded-cover) layout mode, a fix for the 物理鍵 grabber silently dead since the
variable-demotion campaign, and a Visualizer feasibility diagnostic.

### 音楽端灯 — 音楽反応: tempo-locked, audio-reactive meteors
- New engine component **`MusicPulseSource`** (`core/media`): taps the device **output mix** via
  `Visualizer` on audio session 0 with the **push capture listener** at the device max rate
  (~20 Hz) and distills it into small live signals:
  - **level** — smoothed loudness 0..1 (fast attack, slow release);
  - **beat** — a decaying 0..1 impulse on bass onsets (low-FFT-bin flux over a ~2 s running
    average, with a **180 ms refractory** so one drum hit is one onset);
  - a **beat grid** — tempo + phase + confidence, from inter-onset-interval clustering **folded
    into the 60–180 BPM band** (half/double-time hits agree), modal cluster ±8 %, gentle period
    tracking, and **PLL-style anchor nudging** toward on-beat onsets; confidence fades out ~2 s
    after onsets stop (quiet outros, pauses).
- **Nothing is recorded** — the Visualizer yields transient 8-bit visualization snapshots only.
- The bridge is injected into WEB scene elements as **`window.OngakuPulse`**
  (`level()/beat()/bpm()/beatPhase()/tempoConf()`), polled from the page's rAF loop (R8 keep rule
  for the `@JavascriptInterface` methods).
- **Ref-counted lifecycle**: the Visualizer exists only while a reactive scene element is visible
  AND the screen is on AND the knob is on — the scene's `musicPulse` config is live-%var-expanded
  (`%Ongaku_Reactive`), so flipping the knob to `0` releases the capture completely.
- The meteor canvas gained a reactive branch (scene bundle):
  - with a confident tempo, the whole flow **pumps precisely ON each grid beat** —
    `exp(-beatPhase × sharpness)`: sharp attack at the beat instant, decaying to the next
    (metronomic, immune to missed/extra onsets); ribbons **flash** and head stars **swell** on
    the same grid;
  - the **baseline speed follows the music's dynamics, auto-gain normalised** against the track's
    own rolling min/max (~8 s window) — mastering compression holds raw RMS nearly constant, which
    froze speed in the first iteration; normalisation restores the full slow↔fast swing;
  - low tempo confidence falls back to per-onset surges with a gentle drift; `Ongaku_Reactive=0`
    reverts to the original random walk exactly.
- **Five knobs, recipe-documented** in `音楽端灯の設定 [01]`: `%Ongaku_Reactive` (master),
  `%Ongaku_Reactgain` (dynamics→speed sensitivity), `%Ongaku_Reactpulse` (beat flash),
  `%Ongaku_Reactkick` (pump depth), `%Ongaku_Reactsharp` (pump shape: 2 = swell … 10 = jab). All
  nine tuning labels (incl. `Speedmin/Speedmax/Twinkle/Headglow`) were rewritten as **cross-linked
  recipes** — every label states its reactive-mode role and names partner knobs with concrete
  setting ideas (EDM jab, ballad breathing, flash-only, no-stall floor …).

### `music.viz.test` — Visualizer feasibility diagnostic
- New Media action: taps `Visualizer(0)` for N seconds (play music!) and reports frames received,
  live-frame share, peak RMS and peak bass energy, ending in a clear ✅/🔴 verdict — this decided
  the reactive pipeline was buildable on the Mate XT before any of it was written.
- Handles two verified EMUI quirks: a fresh `Visualizer(0)` can arrive **already enabled** (resize
  then throws "wrong state 2" — disable → resize best-effort → re-enable), and **polled**
  `getWaveForm()` is throttled to ~4 Hz (hence the push listener in the real pipeline).
- Wired into the capability pre-flight (Microphone, blocking); manifest gains
  `MODIFY_AUDIO_SETTINGS`; ships with a `視覚化試験` task in the 音楽端灯 project.

### 物理鍵 — grabber dead since the variable demotion (fix)
- The 2026-07-05 demotion campaign renamed the `%PKEY_*` super-globals to project-scoped
  `%Pkey_*` and rewrote every task — but the engine's `ShizukuKeyEventListener` still read the
  deleted ALL-CAPS names from the super-global bucket, so `enabled()` was permanently false and
  the volume-key grabber (double-press → camera etc.) had been silently dead since that day.
  It now resolves the MixedCase project-globals via `snapshotAll()` (the listener runs outside any
  task, so it can't know the owning project's id), keeping the legacy ALL-CAPS names as fallback.
- Same class of fix for the edge-bar long-swipe threshold: `SceneActivity` read the demoted
  `%LONGSWIPE_DP` and silently fell back to the default — now `%Longswipe_Dp` first.
- A full audit of all 40 demoted names found no other live code readers; stale prose mentions in
  18 item notes were refreshed on-device via a minimal notes bundle.

### Narrow screen — compact layout on the folded cover panel
- Under **480 dp** window width (the folded Mate XT cover, ~336 dp; semi/unfolded stay regular),
  the list layouts switch to a compact mode:
  - the per-level **group indent shrinks 56 → 14 dp** (the old indent ate ~17 % of the panel);
  - list side gutters 16 → 6 dp; task-card inner padding 16 → 10 dp;
  - action rows **reflow: each argument on its own full-width line** — the key pill plus a value
    that takes the whole rest of the row and **wraps to 2 lines** before ellipsising, so
    `%Ongaku_*`-length names read in full instead of "Ong…".

### Workspace content (bundles, not APK)
- **Battery 割 display**: the short battery form (`%ST_BattShort` — the `batt-fold` widget and the
  相撲字時計 overlays) now renders round tens as 九割/八割/…/一割 instead of 九〇/八〇 (non-round
  values like 八五 and 100 = 全 unchanged; same character width, so no layout shifts).
- The 18 pre-demotion variable names still mentioned in task/scene **notes** were freshened to the
  current `%MixedCase` names (and the 画面操作 01 note's "super-global" claim corrected to
  project-global).

## 0.2.75+175 — 2026-07-09

The **living-overlay release**: a native **charging-fire animation** on the battery line, a rewritten
**buttery-smooth music edge-light** canvas, strict **screen-off gating** so no overlay ever computes
behind a dark screen, a full set of **documented tuning variables** in the projects' `[01]` settings
tasks, a **task-target bridge** for sister launchers, and an action-row fix so variable names always
render in full.

### 電池線 — charging fire (native scene renderer)
- While charging (and only with the screen on), **two fire-comets glide in from both ends of the
  battery line, meet in the middle** with a red-orange collision bloom, and slide back out — a
  seamless, breathing loop (cosine-eased turnarounds, so it never jumps or disappears).
- Each comet has a **blood-red → hot orange-red gradient body** (soft-blurred capsule) and a red head
  glow — no white anywhere in the flame.
- **Red star-cross glints** twinkle at each flame tip: tiny `+`-shaped strokes flashing in and out on
  fast per-glint cycles — a genuine red sparkle instead of a solid dot.
- An **ember burst** sprays red sparks in all directions from each tip (bright red-orange at birth,
  cooling to deep crimson), arcing down under gravity into the scene's below-line head-room.
- A decaying **heat field** tints the bar deep red where a comet just passed — a lingering
  "residual fire" trail (~1.3 s time-constant) that cools back to the line's own colour.
- The visible bar itself stays a thin strip (`barThickness` config, default 3 dp) at the top of the
  now-taller scene; the line keeps its state colours (base / low-battery red / full green) at all
  times, and the whole overlay stays fully tap-through.
- **Fully variable-tunable** via the scene config → `電池線の設定 [01]`: `%Denchi_Cycle` (seconds per
  converge-and-return breath), `%Denchi_Hibana` (ember count), `%Denchi_Kirameki` (glint count),
  `%Denchi_Nokoribi` (heat-trail linger seconds; `0` disables the trail loop entirely).
- The effect exists in composition **only while `charging && screen-on`** — unplugging or blanking
  the screen stops the animation clock dead (zero off-screen CPU).

### 音楽端灯 — smooth, rich, heat-controlled edge-light (scene canvas)
- The WebView canvas hot loop was rewritten **allocation-free**: no per-frame arrays (the perimeter
  mapper writes globals), per-ribbon colours resolved once at spawn, in-place particle compaction,
  and the ribbon core drawn as **one gradient stroke** instead of 24 per-segment strokes — roughly
  **9× fewer draw calls per frame**. This eliminated the GC-pause stutter ("choppy, interrupted").
- Runs at the **full display refresh rate** with a settable cap: `%Ongaku_Maxfps`
  (`0` = uncapped, `60` = default, `30` = power-saver) — the overheating control.
- **New eye-candy knobs**, all injected as canvas variables: `%Ongaku_Headglow` (a near-white head
  star melting into the ribbon colour — shooting-star tips), `%Ongaku_Twinkle` (per-ribbon shimmer
  depth), `%Ongaku_Huedrift` (deg/s — a ribbon's colour slowly walks the colour wheel as it orbits).
- Palette reworked: the original bright multicolour set **plus blood-reds** (`#ff0000`, `#c00000`,
  `#ff2a00`) interleaved, so ~1 in 4 meteors runs red among the bright ones.

### Screen-off gating (engine)
- New opt-in WEB-element config **`pauseWhenScreenOff`**: when the display turns off, the native
  renderer calls `WebView.onPause()` *and* `window.__scenePlay(false)` (a JS hook the page defines),
  freezing both the compositor and the rAF loop; both resume on screen-on. **Opt-in by design** so
  通知明滅's over-lockscreen wakedance scenes — which must draw while the screen is off — are
  untouched.
- The battery-line comet effect leaves composition entirely on screen-off (same guarantee, native).

### Settings-task workflow
- Both projects' `[01]` settings tasks now carry the **complete knob set with a documentation label
  on every action** — the task doubles as the manual. Re-running the project's `⇨ 起動 [71]` task
  (which runs `[01]`) idempotently applies any settings change; a live scene reloads with the new
  values automatically.

### Task-target bridge (sister-launcher integration)
- New **`GET_TASK_TARGET_PACKAGE`** ordered-broadcast receiver: a sister launcher holding one of our
  run-task shortcuts can ask *which app the task ultimately opens* (by task name or id) and point its
  "app info" / "uninstall" menu entries at that app instead of at us. Newer shortcuts additionally
  bake the target package into the shortcut intent's extras.

### UI fixes
- **Action rows: variable names always render in full.** The arg renderer's hard 160 dp cap on
  non-last values (the `var.set` *name*) truncated most real-world names on a wide screen; names now
  take their natural width, with a 3:1 weight backstop so the value keeps ≥~25% of the row and a
  pathological name ellipsises at ~75% instead of pushing the value off.

## 0.2.75+164 — 2026-07-05

A large feature release over 0.2.75+127: the **相撲字時計** fold-aware over-lockscreen clock, a **task & action UI overhaul**, a full **Variables-tab redesign** with an in-app **dead-globals analyzer** and hard guards against scope leaks, a new **Edit Action** action, tap-through **permission deep-links**, and a switch to **event-local** notification/broadcast variables.

### 相撲字時計 — fold-aware overlay clock
- A new over-lockscreen overlay clock rendered in **相撲字 (sumo-script)** style, ported from the Tasker 時間と日付 project and driven entirely by the app. Three layouts — **folded / semi-folded / unfolded** — swap automatically with the device's fold state.
- **`%FOLD` via the HALL sensor**: a `fold` event context reads the hinge/HALL sensor and publishes the fold state, so the clock (and any task) can branch on `%FOLD`.
- The wide (semi/unfolded) layout **centres the time itself** on screen, with 午前/午後 and the weekday placed relative to it (center-anchored scene positioning, `xc`).
- Scenes are **touch-through** — the overlay passes taps to whatever is beneath it.
- An **app-multiselect picker** dialog chooses which apps hide the clock; the selection is committed to the blacklist variable *and* written back into the 設定 task so it survives the next startup.
- Bundles are **id-free / name-based** (zero ids; everything referenced by unique name), so a re-import can't dangle a profile→task link.

### Task & action UI overhaul
- Redesigned **action rows** with UI-settable styling.
- **Per-task and per-action menus** with **clipboards** — copy/cut/paste actions between tasks and duplicate tasks.
- Removed the olive accent throughout (**olive-free theme**).

### Variables tab — redesign
- Variable **name in blue, value bold**, both at action-view sizes — colours and sizes independently **UI-settable** (defaulting to parity with the action editor). Name and value share **one line**; the folded view is a single line.
- **Row padding** defaults to 2px and is settable.
- A yellow **magnifying-glass** icon sits to the right of the search bar.
- **Foldable scope sections** (Global, Project-global, …) with larger underlined headings, a fold triangle to the right of each heading, slight indentation, and a **live count** in parentheses.
- **Project-filter pills** in the top bar scope the list (and the project-global counts) to the selected project; a pill expands with a wrap-around rounded border around its members when unfolded.

### Dead-globals analyzer + scope guards
- A **"Clean up dead globals"** analyzer folds into the Variables tab. It classifies every persisted global and lists — per category, expandable — exactly what will be deleted and where:
  - **Shadow-copies** — a super-global duplicating a live project-global (with the twin project named).
  - **Orphans** — globals referenced by no task, profile, scene, or widget template.
  - **Dangling project-globals** — rows whose `projectId` points to a deleted/re-created project (previously invisible to both the analyzer and the Var tab).
- Deletion is **cache-consistent** (the in-memory global cache updates in lockstep with the DB).
- **Hard guards against the root cause** of scope leaks:
  - Deleting a project now **cascades to its variables** (they can't re-home to a dead project id).
  - Startup **sweeps dangling** project-globals before warming the cache.
  - `set()` redirects a MixedCase name written at projectId 0 to **task-local**, and import **skips** MixedCase-at-0 — so a project-scoped name can never land in the super-global bucket.

### New action
- **Edit Action** (`task.editaction`) — programmatically edit another task's action: locate it by index or by `matchType`/`matchName`, set one arg, and persist. Used by the clock's blacklist picker to keep its 設定 task in sync.

### Engine & permissions
- **Tap-through permission deep-links**: the permission block/warning dialog shows an **"Open &lt;permission&gt; settings →"** pill per missing permission, deep-linking straight to the correct System settings page (via `CapabilityState.settingsIntent`), on the shared dialog host and the task pre-flight block.
- **Event-local notification/broadcast variables**: `%NOTIF_*` (notification listener) and `%INTENT_*` (broadcast receiver — e.g. Poweramp's ~30 `TRACK_CHANGED` extras) are **no longer persisted as super-globals**. They're threaded per-invocation to the triggered enter task via the event's `vars`, and a startup sweep clears any stale copies — keeping the global namespace to genuine app/engine state.

## 0.2.75+127 — 2026-07-03

A point release over 0.2.75+122 — one targeted fix.

### Fixes
- **Huawei Mate XT foldable**: restored the folded-portrait wakedance fold-compensation the upstream reconcile had dropped. On the folded cover panel held in portrait, EMUI reserves a 105px top system-bar strip and confines the over-lockscreen wakedance Activity below it, clipping the 通知明滅 black mask + edge-light. The fix pulls that window up 105px so the blink covers the full panel again — applied **only** in that one state (folded landscape, semi-folded, unfolded, and the screen-on overlay blink are all untouched).

## 0.2.75+122 — 2026-07-03

A large update over 0.2.75+36: cross-app **protected contacts**, a **drag-reorder** pass across the app, a **Review Import** overhaul, **critical data-loss hardening**, task-card & group-header **styling controls**, a **Termux keyboard** trick, and a resync onto the latest OpenTasker.

### Protected contacts (cross-app privacy)
- Companion feature with the sister apps (白い熊 GNU Jami, 白い熊 Arcane Chat): for a marked contact, the messenger posts a **content-free notification** (a fixed “着信あり：新着伝言。” body) instead of the sender name / message text, so lock-screen and Android-Auto read-outs stay private. A per-package marker keeps it opt-in per messenger.
- 自由作業盤 pushes the protected-contact list to each messenger over an ordered broadcast, and can **read it back** (a `GET_PROTECTED_CONTACTS` query channel) to verify state.
- A **保護試験** test group (per-messenger checks + a cumulative check) confirms each messenger's stored list with a 🟢/🔴 dialog.

### Review Import
- Importing a JSON bundle now opens a near-full-screen **Review import**: per-category counts (“Tasks: N”, with “N exists” flagged), a **folder tree** (project → type → group → items) showing exactly where each item lands (and marking projects/groups that will be created), a **global conflict strategy** with per-item overrides (Overwrite / Overwrite + backup / Keep both), and Cancel/Import.
- Review typography (per-text sizes, a readable sky-blue conflict colour, row padding) is UI-settable.
- **An import never downgrades a Manual-sorted tab** — a partial import no longer silently re-sorts untouched groups.

### Drag-reorder & projects
- **Tasks, the Projects tab, and the top project-tabs** all support drag-to-reorder (the Projects tab's up/down arrows are gone; a long-press on a project tab gives a grab haptic).
- **Projects tab**: tap a project to make it active; the current project is clearly highlighted (accent border + tint).

### Task list & group-header styling
- Icon-less task cards stay **compact** and honour the padding settings; the empty “add icon” placeholder is small and visually subdued.
- Shrunk the ⋮ menu buttons so they no longer inflate rows/headers — **“Padding between cards”** and **“Padding inside group headers”** are now actually respected.
- **Group-header background** is a settable ARGB, with a settable **header border** (thin yellow by default).
- The **task-icon-size** slider now works; the **advanced full-screen, category-foldable action picker** is restored and default-on; an **app-picker** (choose from installed apps) is on app.kill / app.launch / intent.launch / intent.send / notify.dismiss.


### Reliability & engine
- **Critical data-loss fix**: hardened task-action persistence against a strict-decode landmine that could blank a task's whole action list after an app update (tolerant storage codec + an overwrite guard + write logging).
- **Battery**: removed the engine's permanent partial wake-lock, gated the shake / orientation / app-usage sensors to run only when a profile needs them, and stopped eager high-accuracy GPS.
- Generic **Send Intent** gained ordered-broadcast **result capture** (`result_var`).

### More
- **Termux keyboard**: an edge-bar up-swipe re-focuses Termux to bring its IME back (for mosh/emacs sessions where a screen tap won't).
- Themed black/yellow **snackbar & flash** everywhere; **oval-bar borders** (settable width + colour) on the volume/brightness panels; the **Variables** tab's folded rows now show each variable's value.
- The open tab + active project **persist across app exit**.

### Upstream
- Resynced onto **OpenTasker 0.2.75 / code 77**, including upstream's hardened database-backup publishing (WAL-checkpointed, schema-validated backups).

## 0.2.75+36 — 2026-06-27

**Pure-black, yellow-framed popups everywhere**, a task **icon from a song's album art**, and a **redesigned launcher shortcut picker** — now a tall floating dialog whose tasks are organised into bordered folder-boxes, with its own UI-customization controls.

### Menus & dialogs
- **All popup menus and dialogs are now true black with a yellow border**, matching the cards and search box — no more the lifted, brownish Material surface. The theme's `surfaceContainer*` roles are pinned to pure black, and a shared `ThemedDropdownMenu` (black container + 1.5 dp yellow border) backs **every** dropdown: the `+` FAB menu, the project switcher, the scene / widget / sort / font menus, and the action-clipboard menu. Standalone dialogs (task icon, etc.) carry the same yellow rounded frame.

### Task icons
- **New "Audio" icon source** in the task icon picker (App / Picture / Emoji / **Audio** / Clear): pick an **mp3 / ogg / flac / m4a** and the task takes its **embedded album art** as the icon — extracted with `MediaMetadataRetriever`, centre-cropped and snapshotted to a PNG like the other sources. A toast tells you if the file has no embedded artwork.

### Launcher shortcut picker (add-to-home-screen → 白い熊 自由作業盤)
- **Now a floating dialog**, not a fullscreen page: a tall + wide (94 % × 90 %) card with a **yellow rounded frame** over a dimmed scrim. Dismiss by tapping outside or the bottom **Cancel** button (black background, yellow rounded border).
- **Tasks are organised by group.** Each project, unfolded, shows its groups as **bordered rounded folder-boxes** — a folded group is visibly a closed box, so its siblings below can no longer be mistaken for its contents — then the project's ungrouped tasks. Groups **nest**, order is preserved, expanded group contents are indented deeper, and the old misleading play-arrow on task rows is gone.
- **New UI-customization → Shortcut picker section:** font size, row spacing, indent per level, group-box roundness, group-box border, and font — all applied live.

## 0.2.75+31 — 2026-06-26

**Move actions around freely** — long-press to multi-select and **clone / copy / cut / delete / paste** actions within and **between** tasks — plus a workspace-wide shift to **name-based linking**: scenes, scene-element task links, and `task.run` all resolve by **name** (not fragile ids), imports **overwrite in place**, and item names are now **unique within a project**. And `sound.play` can read tones anywhere in shared storage.

### Task editor — action multi-select & clipboard
- **Long-press an action** in a task to select it (multi-select by long-pressing / tapping more rows; selected rows are highlighted with a ✓). The long-press menu acts on the **whole selection**: **Clone** (duplicate in place), **Copy**, **Cut**, **Delete**.
- **Paste before / Paste after** the long-pressed action (shown once something has been copied/cut). An app-wide **`ActionClipboard`** holds the copied/cut actions, so you can **move actions between tasks**, not just within one.
- The drag handle keeps its own long-press reorder; a plain tap still expands a row (or toggles its selection while selecting).

### Link everything by name (no more broken links on re-import)
- **`scene.show` / `scene.hide`** resolve a scene by **`(project, name)`** — the scene in the calling task's project wins, then any-project by name (deterministic by position then id), then a numeric id only as a legacy fallback. `VariableStore.projectId` is exposed so the action knows the caller's project.
- **Scene element task links** — tap, long-press, and the edge-gesture handlers — now carry a **task name** (`tapTaskName` / `longPressTaskName`, JSON-only, no migration) and resolve **name-first** at run time *and* on import. A re-imported or recreated task no longer silently drops a slider/button's action: the editor stores the name on pick, **export back-fills** names from ids, and **import re-binds** by name → the bundle id map → the raw id.
- **`task.run`** resolves **name-first** (exact, then case-insensitive), with the numeric id only as a legacy fallback — matching the scene/profile resolvers.

### Import — overwrite in place
- **Profiles and scenes now overwrite *in place*** on import (reuse the existing row id, matched by name), just like tasks already did — so a re-import **keeps each item's id, group membership, and notes** instead of deleting + re-inserting and orphaning them. A profile overwrite preserves its enabled state.
- The **default conflict strategy is now Overwrite** (in place), and the conflict dialog leads with it. The "missing tap task" import warning no longer misfires when a scene element carries a name to re-bind against.

### Name uniqueness
- The **task / profile / scene editors block a duplicate name within the same project**, and the **project editor blocks a duplicate project name** — Save is disabled with an inline error (widget templates already did this).
- Enforced at the DB level by **UNIQUE indices** on `(projectId, name)` for tasks/profiles/scenes and `(name)` for projects (**DB schema v16 → v17**, `MIGRATION_16_17`). The migration **self-heals** first — any pre-existing collision is renamed `"<name> (<id>)"` so the index build can never fail; SQLite treats Unfiled (null-project) rows as distinct, so the editor's UI check covers those.

### Sound
- **`sound.play` — all-files access.** A new **All-files capability** (`Environment.isExternalStorageManager()` / `MANAGE_EXTERNAL_STORAGE` on API 30+, `READ_EXTERNAL_STORAGE` below) lets `sound.play` read custom tones **anywhere in shared storage** (e.g. the 通知明滅 Jami notification tone), surfaced as a capability pill on the action and a new **"All files access"** row on the Setup tab with a deep-link to grant it.

## 0.2.75+25 — 2026-06-25

**Freeze bubbles** — a native port of the Tasker 凍結 融解 re-freeze workflow — plus a tiled app picker, app-icon launcher tasks, an inline freeze toggle + tappable task icon, fully styleable bubbles, and every numeric UI-customization setting converted to a slider.

### Freeze bubbles
- **Per-task "Freeze bubble" flag** (DB **v16**, `MIGRATION_15_16`). Running a flagged task queues a re-freeze bubble for the app it launches/unfreezes (package read from its `app.launch` / `app.unfreeze` action). Toggle it in the task editor **or inline on the expanded task card**; **Make Launcher Tasks enables it by default**.
- **Desktop-gated overlay** — bubbles render as draggable `TYPE_APPLICATION_OVERLAY` windows shown **only while the default home launcher is foreground** (auto-detected), hidden everywhere else. Each shows the app's icon + a ❄ badge + label.
- **Tap a bubble → freeze the app** (`app.freeze` via Shizuku) and remove it; **long-tap → dismiss only**. Bubbles are **draggable**, **persist across reboots** (`FreezeBubbleStore`), de-dupe per app, and **re-clamp on rotation / fold** keeping their position relative to the top-right edge.
- **Fully styleable** under *UI customization → Freeze bubbles*: icon size, icon roundness, label size, label weight, and label font — with a **live preview**.
- The Setup tab's **Overlay access** row now notes freeze bubbles.

### Launcher tasks & icons
- The **Make Launcher Tasks** app picker is now a **yellow-bordered grid of app-icon tiles** (icon + name, multi-select with a check badge), replacing the plain text list — shared with every app-package field.
- Generated unfreeze-then-launch tasks now **default their icon to the selected app's icon**.
- A task's **icon is tappable in the list** — opens the icon picker (App / Picture / Emoji / Clear) without opening the editor; tasks with no icon show an "add icon" affordance when expanded. The picker is a shared component (`TaskIconEditorRow` / `TaskIconPickerDialog`) used by both the card and the editor.

### UI customization
- **Every numeric setting is now a slider** (was +/− steppers): Borders → Border width; Typography → Text size; Flash / toast → Border width, Corner radius, Text size; plus the freeze-bubble sizes. The flash and bubble sections keep their live previews.

### Infrastructure
- New `FreezeBubbleStore` (SharedPreferences) + `FreezeBubbleOverlayManager`, started from `AutomationService`; bubble enqueue hooked into `executeAndLogTask` (covers every run path). `TaskIconStore` gains a context-free `saveFromApp(pkg)` for non-UI callers.

## 0.2.75+18 — 2026-06-23

**Home-screen shortcuts that run a task directly**, each with a **persisted custom icon** (from an app, a picture, or an emoji), a **launcher shortcut picker**, a global **icon-size** control, and **cross-device icon transfer** in exports.

### Task shortcuts
- **Launcher "create shortcut" picker** — a new `CreateTaskShortcutActivity` registers for the system `CREATE_SHORTCUT` flow, so long-pressing the home screen → *Shortcuts* → **白い熊 自由作業盤** opens a **foldable projects → tasks picker** (all projects folded by default); choosing a task drops a home-screen shortcut that runs it.
- The in-app **Pin to home screen** path now uses the task's custom icon (previously always the app icon).
- **`TaskRunActivity` is now exported**, so a third-party launcher (e.g. 白い熊 雷起動盤 / raikidoban) that fires the raw shortcut intent itself can start the task — fixes the launcher's "this shortcut isn't associated with a valid app". Shortcuts carry the task **id + name**, so they survive a task re-import.

### Per-task icon
- Assign a task's icon in its editor from three sources: an **installed app's icon**, a **picture** (photo picker), or an **emoji / glyph** (new `EmojiPickerDialog` — type or tap a quick-pick, with a live preview). The chosen icon is **snapshotted to a PNG** in app storage (`TaskIconStore`) at pick time, so it keeps displaying even if the source picture is deleted or the source app is frozen, and is **baked as a bitmap** into shortcuts so it survives in the launcher regardless of the app's state.
- The icon shows in the **editor preview**, next to the task in the **task list** (folded and unfolded), and on its **shortcut**; no icon set → the app's launcher icon.
- **Global task-icon size** — a new **slider with a live preview** under *UI customization → Task list* (`ThemePrefs.taskIconSizeDp`, 16–96 dp) sizes every task's icon on its card.
- The **app picker** dialog was rebuilt as a **yellow-bordered grid of app-icon tiles** (icon + name), replacing the plain text list — used here and by every app-package field.

### Import / export
- Task icons now **travel across devices**: an export embeds each icon as base64 (`Task.iconData`); import **re-materializes** it into local storage (reusing the existing local file on a same-device re-import). The bundle schema stays **v4** — the field is additive, so older builds ignore it — and `iconData` is never written to the database.

### Schema
- **DB v15** — new `tasks.iconPath` column (the saved icon's path) with a `14 → 15` migration; existing data is untouched on update.

## 0.2.75+11 — 2026-06-23

Re-based the **entire fork onto upstream OpenTasker 0.2.75**, and added **Turn Screen Off**, **Freeze / Unfreeze App** + a **multi-select launcher-task generator**, a **capability-aware action editor**, and the screen-off **通知明滅 wakedance**.

### Re-synced onto upstream 0.2.75
- Rebased the whole fork delta from upstream **0.2.68 → 0.2.75**, keeping every customization (~31 file overlaps resolved by hand). The full pre-resync history is preserved in `backup/custom-0.2.68-pre-resync`.
- Inherited upstream's intervening work: **Locale plugin** interop (both *condition* contexts and a *setting* target), **encrypted DB backup** (AES-256-GCM), a real **Shizuku** elevated backend + **Termux** script dispatch, scene **multi-select / alignment guides / resize**, a **visual flow editor** (zoom, edge routing, branch & subflow markers), `var.persist`, dotted/bracketed `var.set` JSON paths, **RE2/J** linear-time regex, a **Run-Log expression debugger**, and a large batch of concurrency / teardown / schema-drift hardening, plus i18n scaffolding.
- Our **DB schema stays at v14** — no migration when you update; existing data is untouched. Upstream's redundant per-item `group` tag is dropped in favour of our richer project grouping. Gradle dependency-verification is disabled (it only fights local builds).

### New actions
- **Turn Screen Off** (`screen.off`) — accessibility `GLOBAL_ACTION_LOCK_SCREEN` first (no Shizuku needed), Shizuku `KEYCODE_SLEEP` fallback; no longer greyed out.
- **Freeze App** (`app.freeze`) / **Unfreeze App** (`app.unfreeze`) — disable/enable any app through Shizuku (`pm disable-user` / `pm enable`).
- **Make Launcher Tasks** (`tasks.launchers`) — a **multi-select app picker** (all installed user apps, *including frozen ones*, searchable) that writes one **unfreeze-then-launch** task per chosen app into a named project group, **re-sorted alphabetically on every run**, skipping duplicates; the group is auto-created beneath the generator task.

### The 通知明滅 screen-off wakedance
- When a notification arrives **screen-off**, the device now **wakes over the lockscreen** and rotates through every unread app (colour + sender + preview) before sleeping, repeating on a sub-minute timer. Beats EMUI's ~2 s teardown via a `SCREEN_BRIGHT` wakelock, draw-before-wake, an **opaque show-when-locked `WakedanceActivity`**, and a clean self-sleep — no lockscreen or wallpaper flash.
- New engine primitives: a **`sec_tick`** sub-minute event trigger; `state.get screen=on/off`; `wake` / `screen.off` via Shizuku key events.

### Capability-aware action editor
- Each action now shows a **live status pill** — **red** with a one-tap **deep-link to the exact Settings screen** when its permission/service isn't set up, **yellow** (FYI) when it is — evaluated against the **same checks the Setup tab uses** (accessibility, Shizuku, modify-settings, overlay, Do-Not-Disturb, notifications). Consistent across the action picker, the in-task list, and the config dialog.
- New **`APP_PACKAGE`** field type — type a package name / `%variable`, or pick from an installed-apps list.

### Battery, scenes & recomposition
- **Charging detection = `EXTRA_PLUGGED` only** — Huawei lingers `isCharging`/status after unplug, so charging state now follows the plug (covers wireless, drops instantly). The **電池線** battery line turns **solid red while charging**.
- **Per-variable scene recomposition** (`derivedStateOf`) — a scene element re-renders only when *its own* expanded values change, not on every global write, cutting idle overlay CPU.

### Profiles & engine
- Profiles now link their **enter/exit task by NAME** (DB **v14** + migration) with the id as fallback, so re-importing a task — which re-ids it — no longer orphans the profile (“Missing task #N”).
- The **Monitor** tab aggregates engine task-activity and a widget-pull log.

### Docs
- **README** fully rewritten: a two-line title (白い熊 自由作業盤 / ShiroiKuma Jiyūsagyōban), the *Jiyūsagyōban* gloss, the full **Triggers** + **115-action** tables, and fork-vs-upstream feature sections.

## 0.2.68+107 — 2026-06-21

The **通知明滅 notification edge-light** port, a **self-healing always-on engine** with a live **Monitor tab**, new **notification / broadcast / orientation / app-foreground triggers**, the **music edge-light** & a full **edge-gesture** system, **item grouping** across every tab, and reliability fixes for OEM battery management.

### 通知明滅 — notification edge-lights (new project port)
- A notification from a configured app **frames the whole screen in that app's colour** as a permanent edge light; several lit apps share **one frame that cycles** through their colours and titles (~2 s each). Built entirely from tasks + a full-screen tap-through WebView scene, with per-app colours and an `%TSUCHI_*` slot model rebuilt into the cycle list.
- **Three off-paths:** a persistent **“all-off”** control notification (tap → clear every light, keep the apps' own notifications); and **entering an app** (via its notification or the launcher) → that app's light off and its notification dismissed, while the others keep glowing. Ongoing/persistent notifications never light; a notification arriving while you're already in the app doesn't light it; per-app gates (e.g. blink only on missed calls).

### Triggers (new)
- **Notification trigger** — an `EVENT` context (`event=notification`, optional `package` allowlist) fires a profile when a matching app posts a notification.
- **Broadcast (Intent Received) trigger** (`7c7c343`) — fire on any system/app broadcast action, with **typed intent extras** parsed into variables; profiles now **reload live** as you edit them.
- **Device-orientation trigger** (`8060ddc`) — an `EVENT` source for portrait / landscape / reverse changes, exposed as `%DEVICE_ORIENTATION`; orientation is named by the **on-screen** orientation, not the device-natural angle (`4e17243`), fixing foldables.
- **App-to-foreground trigger** (`8060ddc`) — fires when an app comes to the foreground (`%APP_PACKAGE`), fed from the accessibility service so it works where OEM UsageStats is dead (`3bcec99`). Powers the **Previous/Next App** switcher (`bd45920`, `9037117`).

### Notifications
- **`%NOTIF_*` super-globals** (`8bc12c7`) — a posting notification's package, title and body, plus its **ongoing flag** (`%NOTIF_ONGOING`, `4001fb3`), exposed for tasks to read.
- **Per-invocation event vars** (`3a3715e`) — each event now carries its own `%NOTIF_*` snapshot, threaded through the matcher and the **task queue** to the fired task and injected as locals that shadow the shared globals, so a **burst from different apps never mixes up** colours/titles under a QUEUED profile.
- **`notify.show tap_task`** (`c7737de`) — run a task when a notification body is tapped (works while collapsed).
- **Dismiss Notifications** (`notify.dismiss`, `7e3ead6`) — cancel another app's clearable notifications **by package**, via the notification-listener service.
- **`scene.show` expands `%vars`** in element configs at show time (`23f4611`) — so an overlay reflects live globals (the edge-light colour/title).

### Engine & reliability
- **Survives OEM battery management** (`d695587`) — the foreground service holds a partial **wakelock** and a Doze-proof **minute alarm resurrects** it if the process is reaped.
- **Survives coroutine death** (`d181881`) — the engine scope uses a `SupervisorJob` so one failed trigger can't cascade and freeze the rest; a **heartbeat** stamps the per-minute tick and **re-arms** the engine within ~2.5 min if it ever stalls.
- **Auto-run on start** (`d181881`) — pick tasks (e.g. a master “start everything”) to run automatically on every fresh engine start, so overlays/state return after an app update or reboot without manual intervention.

### Monitor tab (new)
- A left-most **Monitor** tab (`fff63ed`) showing engine status / uptime / seconds-since-last-tick, the **overlays actually on screen**, each enabled profile's **real activity** (a trigger firing ≠ its overlay being drawn), and a **history** of every start / re-arm / resurrect — refreshing every second.
- The **“Run on start”** picker (`fff63ed`, `5921666`) — a bordered dialog that groups tasks by project, folds per project, and keeps each project's **manual task order**; every monitor section folds.

### Scenes & edge overlays
- **Music edge-light** (`ec036a2`) — a WebView scene element, a full-screen overlay mode, edge HUDs and custom fonts.
- **Edge-gesture system** — fraction-height edge strips and invisible swipe-only sliders (`291f8f4`); a full edge-bar gesture set with **per-third placement** (`a01678d`); **short/long swipe** + a bottom edge bar, headings honouring fonts (`21959fd`); a **bottom edge bar via an accessibility overlay** that captures the flush gesture-nav area (`ebde11f`); edge-swipe **direction detection** + task-id remap on import (`eb4d91b`).
- **Live sliders, edge-centred panels, tap-outside-close, drag-to-keep-alive** (`45512c4`); a **font picker** in element style (`956db2b`).
- **Battery line charge sweep** now **ping-pongs** (left↔right) instead of snapping back (`d0831ae`).

### Actions
- **Take Screenshot** (`nav.screenshot`, `a2a489d`) — system screenshot via accessibility.
- **Previous App / Next App** (`bd45920`) — switch using the accessibility foreground history.
- **Percent volume / brightness** + **editable dropdown** fields (`446a5da`).
- **Hybrid Back / Recents** (`1380db3`) — accessibility first, Shizuku fallback, with an accessibility-setup row.

### Items, grouping & navigation
- **Grouping on all five tabs** (`c2fcbaa`) with direct **New group / New subgroup**, **nested subgroups** and **foldable per-item notes** (`a5431af`); **drag** rows into groups (`acce065`) or **out** to an *Ungrouped* zone (`e1257d9`); a new group **inherits the item's project** (`326e873`).
- **Project sort toggle, group-delete cascade, collapsed-task quick-run** (`901ee01`); fixed folded-nav covering the screen, **last-tab memory**, and **swipe between tabs** (`e30a42a`); a **Settings link stays** on already-granted permissions (`a68f269`).

### Import / export
- **Overwrite-in-place** (`b0d69cb`) — re-importing a task keeps its id, so profiles and scenes stay linked (no more “Missing task”).
- **Export everything** from the project menu + **timestamped** default filenames (`1340cde`).

## 0.2.68+16 — 2026-06-17

A **battery line** (電池線): a thin bar over the status bar showing charge, built from a new scene element and a full-width overlay mode.

### Scenes
- **Progress-bar element** (`PROGRESS`) — a new scene element type: a horizontal fill bar whose `value` (0–100), `fillColor` and `trackColor` are variable-bound and **re-render live**. A truthy `charging` flag draws a **red sweeping glow** along the fill — advanced by a delay-driven state loop so it animates inside the system-overlay window (where an infinite-transition frame clock doesn't reliably tick) — over a static red tint. It's a first-class element in the editor (palette entry, default size 220 × 12).
- **Full-width overlay** — `scene.show fullWidth=true` shows a non-modal overlay that spans the whole screen width and lays out **over the status bar**, flush to the top edge (`FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`). Scene-card elements sized `widthDp`/`heightDp ≤ 0` fill the card, so a single element can span the entire bar.

### Actions
- **Get Device State** (`state.get`) — charging detection hardened: it now also consults the live `BatteryManager.isCharging`, not only the sticky battery intent's `plugged` / `status`, so charge state is reliable across OEMs.

### 電池線 (battery line)
- These compose into a battery line: a full-width **3 dp** bar at the very top whose length tracks battery %, coloured **amber** normally, **red** ≤ 20 %, **green** at 100 %, with the red charging glow while plugged in — refreshed every minute and updated **instantly** on plug-in/out via a `charging=true` state trigger.

### Docs
- Recorded 白い熊's version-controlled Tasker reference projects directory in `CLAUDE.md` (the porting source for the kanji clock, battery line, etc.).

## 0.2.68+10 — 2026-06-17

Live home-screen widgets and widget UX, on top of the 0.2.68+3 widget/clock system.

### Actions
- **Get Device State** (`state.get`) — read battery % (zero-padded), charging, WiFi-enabled and airplane-mode into variables; no permissions needed. Drives the live status widgets.
- **Toggle Airplane Mode** (`airplane.toggle`) — fixed: the `AIRPLANE_MODE` broadcast is system-only and fails from the Shizuku shell, which previously failed the whole action even though the setting applied. The broadcast is now best-effort, so success tracks the setting write (and dependent widgets update).

### Home-screen widgets & the kanji clock
- **Live status widgets** — WiFi (無線 / 無線無し), Airplane (機内 / 機内無し) and Battery (八割三分 / 全, with a charging line) read real device state every minute; tapping the WiFi or Airplane widget toggles it through Shizuku and the kanji flips instantly.
- **Tap task bound by name** — a widget's tap task is stored by **name** and resolved at tap time, so it survives bundle re-imports (no re-pointing). The widget config now offers a **task picker** dropdown instead of a typed task name.
- **Themed tap feedback** — widget taps give an immediate **vibration** plus a black-and-yellow **Flash** confirmation anchored at the bottom of the screen (a system toast can't be recoloured on a modern targetSdk); failures still surface.
- **Legible preview thumbnails** — template thumbnails render at a canvas scaled to the template's largest font, with `%vars` expanded against the live globals, then scaled down — so big-screen clock templates read as mini widgets instead of a narrow wrapped strip.
- **Wider one-line widgets** — the rendered-bitmap cap was raised 1440 → 2880 px and the one-line time templates set `maxLines = 1`, so a wide one-line widget renders at full width instead of wrapping.

### Theme
- **Serif / Minchō font** — `font: "serif"` (also `明朝` / `mincho`) renders CJK in the built-in serif family (= 明朝 / Minchō), so widgets can use Minchō without importing a font; `"sans"` / `"gothic"` fall back to sans-serif.

### Infrastructure
- Declared the **VIBRATE** permission (widget-tap haptics).

## 0.2.68+3 — 2026-06-16

Rebased onto **OpenTasker 0.2.68** (up from 0.2.60), with a large round of new fork features on top.

### Scenes — a floating-overlay UI builder
- Build interactive overlays from elements and show them with **Show Scene** (`scene.show`) / **Hide Scene** (`scene.hide`) — as a foreground panel or, with “display over other apps”, a **system-wide overlay** that floats over any app and works from background triggers.
- **Element types**: Text, Button, Edit Text, Slider (horizontal & vertical), **Number picker** (− / + stepper), Checkbox, Toggle, Spinner, Image, Rectangle, Oval.
- **Inputs write variables**: sliders, steppers, checkboxes, toggles, spinners and text fields set a `%var` (case-scoped) and run a per-element tap task; shown scenes **re-render live** when a bound variable changes.
- **Styling**: per-element text/label colour, size, bold, alignment, background and border; **panel styling** (background, corner radius, modal scrim, border) with black/yellow theme defaults.
- **`scene.show` options**: position (top/center/bottom), modal vs tap-through HUD, auto-dismiss timeout, dismiss-on-outside — plus **per-scene defaults** so a scene remembers how it likes to show.
- **Editor**: drag-to-move and **drag-to-resize** on a live canvas, a **live styling preview**, element **duplicate** and **z-order** (bring forward / send back), and a project-aware library.

### Home-screen widgets & a template library
- **Set Widget** (`widget.set`) renders a styled bitmap widget from a layout (text / columns / rows, fonts, colours, padding) with a visual **layout editor** — RGBA colour pickers, ± number steppers, per-field sliders, a resizable preview, and **Tasker Widget V2 import**.
- A **Template Library** (new “Widgets” tab) of named layouts referenced by name — edit once, every widget using it updates. File & clipboard import/export; templates also travel inside JSON bundles.
- **Pull / placeholder model**: bind a placed widget to a template, and **Refresh Widgets** (`widget.refresh`) re-renders them all from current variables — no per-widget wiring. Plus a `SET_WIDGET_NAME` broadcast receiver.
- **Fonts**: import `.ttf` / `.otf`, delete from the picker, UTF-8 names preserved.

### Kanji clock (時間と日付)
- A modular, fully app-driven port of the Tasker 勘亭流 kanji clock/date: calc tasks compose spoken-kanji time and date into `%DT_*` variables, two widget templates (clock & date), and a per-minute refresh.
- New **every-minute “clock tick”** EVENT trigger (`event=minute`) to drive it.

### Variables
- **Persistent, project-scoped globals** with case-based scoping: `%ALLCAPS` → app-wide super-global, `%MixedCase` → project-global, `%lowercase` → task-local. Globals now **survive across runs and reboots**. The Vars tab shows super-globals plus the selected project’s globals.

### UI & navigation
- **Uniform top bar** — the project selector now appears on every tab.
- **Name search** on every list tab (Profiles, Tasks, Scenes, Vars, Widgets): a pinned, case-insensitive filter.
- **Multi-select** with batch delete / move-to-project across Profiles, Tasks, Scenes, Vars and Widgets.
- **Help tab** — concepts, variable scoping, the bundle schema and an auto-generated action reference, in collapsible sections.
- **Unified import/export** — one JSON-bundle engine with a per-tab “+” menu (New / Import / Export), a persistent import-result dialog and clearer conflict prompts.
- Foldable cards on every list tab; a horizontally-scrollable bottom navigation.

### Infrastructure
- **Room DB v10**; export **bundle schema v4** (widget templates; per-scene styling & defaults; project-scoped variables).
- CI build workflows disabled (local builds only).

## 0.2.60+35 — 2026-06-15

The action catalogue grew from a few dozen built-ins to **~100**. New built-in actions, grouped:

### Actions — Variables & Arrays (pure logic, no permissions)
- **Variable Clear** (`var.clear`) — unset a variable (and any array of the same name).
- **Variable Split** (`var.split`) — split a value into an array (custom delimiter; empty = per character).
- **Variable Join** (`var.join`) — join an array back into a single value.
- **Variable Search Replace** (`var.replace`) — regex replace; optionally capture matches to an array; ignore-case / multi-line.
- **Variable Convert** (`var.convert`) — upper / lower / trim / length / reverse / capitalize / URL-encode/decode / Base64-encode/decode / MD5 / SHA-1 / SHA-256.
- **Variable Add** (`var.add`) — add a number to a numeric variable, with wrap-around and round.
- **Parse/Format DateTime** (`datetime`) — now / epoch-seconds / epoch-millis / formatted input → a formatted output string.
- **Array Set / Push / Pop / Clear** (`array.set` / `array.push` / `array.pop` / `array.clear`).
- **Array Process** (`array.process`) — sort / sort-desc / numeric / reverse / shuffle / unique / squash.
- **Arrays Merge** (`array.merge`) — concatenate several arrays into one.

### Actions — Dialogs
- **Input Dialog** (`dialog.input`), **List Dialog** (`dialog.list`), **Text Dialog** (`dialog.text`).
- Backed by a transparent host activity + a `CompletableDeferred` result bridge, so the task **suspends until the user answers**; themed black-and-yellow, with an optional close-after timeout, and they cancel cleanly (the task never hangs).

### Actions — Interface gestures (new opt-in `AccessibilityService`)
- **Back** (`nav.back`), **Recents** (`nav.recents`), **Notifications Panel** (`panel.notifications`), **Quick Settings** (`panel.quicksettings`), **Power Dialog** (`nav.power`), **Lock Screen** (`screen.lock`, Android 9+). Global-action only — no screen content is read.

### Actions — Platform
- **Flash** (`flash`) — reworked into a styled overlay window: per-flash text / background / border colours, nine anchor positions with X/Y dp offsets, an **HTML** toggle, and defaults from the UI; falls back to a plain toast without overlay permission.
- **Comment** (`flow.comment`) — a labelled no-op for documenting a task.
- **Set Clipboard** (`clipboard.set`), **Get Clipboard** (`clipboard.get`).
- **Compose Email** (`email.compose`), **Set Wallpaper** (`wallpaper.set`).
- **Open File** (`file.open`) — via a new `FileProvider` over the app sandbox.
- **Move File** (`file.move`), **Create Directory** (`file.mkdir`).
- **List Apps** (`apps.list`), **Keyboard Picker** (`ime.pick`), **Wi-Fi Settings** (`wifi.settings`).
- **Place Call** (`call.place`) — `CALL_PHONE`, else opens the dialer.
- **Profile Status** (`profile.toggle`) — enable / disable / toggle a profile by name.
- **Get Setting** (`setting.get`), **Set Setting** (`setting.put`) — read any of System/Secure/Global; write System via Write-Settings (Secure/Global via Shizuku).
- **Auto Brightness** (`brightness.auto`).
- **Set Volume** (`volume.set`) — per-stream (music / ring / alarm / notification / call / system).

### Actions — Elevated tier (via Shizuku)
- **Run Shell** (`shell.run`) — runs `sh -c <cmd>` with ADB/root privileges; stores `%stdout` / `%stderr` / `%exit`; optional ignore-exit-code.
- Rerouted through Shizuku (now functional, previously stubbed): **Toggle Wi-Fi** (Android 10+), **Toggle Airplane Mode**, **Toggle Mobile Data**, **Take Screenshot** (`screencap`), **Secure/Global Set Setting**.
- New: **Location Mode** (`location.mode`), **Set Keyboard** (`ime.set`).

### Actions — Flagship & flow
- **Send Intent** (`intent.send`) — the reason the fork exists: fire an arbitrary intent (action, category, MIME, data, three `key:value` extras, target package/class, broadcast / activity / service).
- **Named task parameters** — Run Task passes `param:<name>`; sub-task reads `{{ param.name }}` / `%@name`.
- **Return Values** (`task.return`) — return named values to the caller (`%prefix_name`, `%prefix_ok`, `%prefix_error`).
- **Fail** (`flow.fail`) — signal a task error.

### Features
- **Projects** — Tasker-style grouping of profiles/tasks/scenes: data model, DB migration, top-bar switcher, management UI, move-between-projects, per-project filtering, Unfiled catch-all.
- **Per-tab sorting** — Alphabetical or Manual per tab (Profiles / Tasks / Scenes), with long-press **drag-and-drop** reorder; persisted and round-tripped through export/import.
- **白い熊 自由作業盤 UI customization page** — colours (background / text / accent / surface / border), border width, font (incl. imported `.ttf`/`.otf`) and text scale, live; plus a configurable **Flash / toast** section (colours, border width, corner radius, text size, weight) with a live preview.
- **JSON export/import** — versioned bundles (**schema v3**): export all / a project / hand-picked items; per-item, per-project and selective export; conflict prompt (overwrite vs keep-both) and unique-name handling on import; preserves each item's manual **position** and the tab's **sort method**.
- **Editor & workflow** — advanced full-screen, searchable, category-folded **action picker**; **foldable task list**; **full-screen action editor**; **RGBA colour-picker** fields (4 sliders + preview); **drag-to-reorder actions**; task-name picker for Run Task; continue-on-error toggle.

### Changed
- Renamed all user-facing **“OpenTasker” → “白い熊 自由作業盤”** (top bar, export/import dialogs, the home-screen widget, permission prompts, OEM battery-guidance, notifications/channels, NFC/file/backup messages, Shizuku/Termux setup text). Code identifiers, logcat tags, notification-channel IDs and the upstream URL were left intact.
- **Black-and-yellow** as the default theme; pure `#FFFF00` throughout; a yellow border on every dialog; fixed the Material3 1.4 button-label colour so labels render full-strength.
- Capability badges updated: the elevated actions moved from **Unsupported → Requires setup (Shizuku)**.

### Infrastructure
- `applicationId` `shiroikuma.jiyusagyoban`; black-yellow launcher icon; signed release; **side-by-side** install with upstream.
- Added: an `AccessibilityService`, a `FileProvider`, and **Shizuku** (`dev.rikka.shizuku:api` + `:provider`).
- **Room DB v6** — `position` columns on profiles/tasks/scenes; **export bundle schema v3**.
