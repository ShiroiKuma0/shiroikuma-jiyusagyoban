# Changelog

## Unreleased

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

## v0.2.57 - 2026-05-05

Calendar and sun device smoke evidence.

- Added `tools/collect-calendar-sun-evidence.ps1` to capture adb calendar/sun smoke evidence.
- The harness launches OpenTasker, optionally grants Calendar access, captures package/service/provider evidence, and can require Calendar permission, CalendarProvider access, and foreground `AutomationService` state.
- Verified the debug app on API 36 device `SM-S938B` with evidence `build/device-evidence/calendar-sun/20260505-152622`.
- The smoke run confirmed `READ_CALENDAR` was granted, CalendarProvider calendar and instance queries succeeded, and `AutomationService` was foreground after app launch.
- Patched the new adb evidence scripts for Windows PowerShell 5.1 process-argument compatibility.

## v0.2.56 - 2026-05-05

Calendar and sun context presets.

- Added reusable Event context presets for during-meeting, before-meeting, all-day busy, at sunrise/sunset, and offset sunrise/sunset windows.
- Added preset controls to the Event context editor when `event=calendar`, `event=sunrise`, or `event=sunset` is selected.
- Presets preserve unrelated filters such as calendar allowlists while replacing the state/window fields they own.
- Added JVM coverage for calendar preset coverage, sun offset windows, and preset application behavior.

## v0.2.55 - 2026-05-05

NFC write-helper flow.

- Added an NFC tag write session that arms a one-time NDEF text-record write and consumes the next scanned tag while armed.
- Supports writable and formattable NDEF tags with size/read-only failure messages surfaced through the write session.
- Added an NFC write helper card to the Event context editor when `event=nfc` is selected.
- MainActivity now gives armed writes priority over normal NFC trigger publication.
- Added JVM coverage for NFC write-label normalization and payload-size estimation.

## v0.2.54 - 2026-05-05

Locale plugin validation harness.

- Added `tools/validate-locale-plugin.ps1` to capture adb evidence for an installed Locale/Tasker-compatible plugin package.
- The harness records package path, `dumpsys package`, resolver command output, contract-action checks, and a structured `summary.json`.
- Supports required setting/condition contract checks and an optional synthetic `REQUEST_QUERY` broadcast to OpenTasker.
- Documented the harness as the repeatable sample-plugin validation path for X3 follow-up testing.

## v0.2.53 - 2026-05-05

Locale request-query event handling.

- Added a foreground-runtime listener for Locale `ACTION_REQUEST_QUERY` broadcasts from condition plugins.
- Emits sanitized `event=locale_request_query` context events with the requested condition activity class and deterministic bundle JSON.
- Rejects blank or malformed activity class names and reuses primitive-only bundle sanitization for request-query payloads.
- Added package visibility for `REQUEST_QUERY` and JVM coverage for request-query event construction.

## v0.2.52 - 2026-05-05

Locale plugin configuration result handling.

- Added explicit edit-setting and edit-condition intent resolution for Locale-compatible plugin configuration activities.
- Fails closed when a plugin package exposes no matching configuration activity or multiple ambiguous activities.
- Added guarded configuration result parsing that accepts only primitive bundle values and emits deterministic JSON plus bounded blurb text.
- Reused the same string-only bundle safety policy for plugin-returned configuration data, rejecting null, nested, parcelable, and arbitrary object values.
- Added JVM coverage for deterministic bundle JSON encoding and primitive-only configuration result sanitization.

## v0.2.51 - 2026-05-05

Locale condition unknown-state handling.

- Added a bounded in-memory last-known-state cache for Locale condition plugin query results.
- Resolves `RESULT_CONDITION_UNKNOWN` to the last known state for the same plugin package and guarded bundle.
- Treats unknown condition results without history as unsatisfied instead of exposing an ambiguous success path.
- Added JVM coverage for last-known fallback, no-history behavior, and bundle-scoped cache keys.

## v0.2.50 - 2026-05-05

Locale condition plugin query baseline.

- Added `plugin.locale.query` to issue explicit `QUERY_CONDITION` ordered broadcasts to Locale/Tasker-compatible condition plugin receivers.
- Added guarded parsing for Locale condition result codes: satisfied, unsatisfied, unknown, and unrecognized-result fail-closed handling.
- Hardened Locale setting execution to resolve a single explicit receiver component before dispatch instead of broadcasting to an entire package.
- Extended Locale plugin discovery metadata with setting/condition receiver permissions for future disclosure UI.
- Added package-visibility queries for Locale execution receivers and JVM coverage for condition result-code mapping.

## v0.2.49 - 2026-05-05

Day schedule polish.

- Added a shared `DaySchedule` parser for day contexts with canonical weekday order, weekday/weekend/daily aliases, numeric day tokens, and inclusive day ranges such as `MON-FRI`.
- Updated Day context matching to use the shared parser so imported, typed, and UI-created schedules evaluate consistently.
- Replaced raw Day context editing with quick presets, individual day toggles, canonical save output, and validation that blocks invalid day schedules before saving.
- Improved profile and inspector summaries so Day contexts show human-readable labels such as `Weekdays`, `Weekends`, or `Every day`.
- Added JVM coverage for day aliases, wrapped ranges, numeric tokens, and ContextMatchEvaluator day matching.

## v0.2.48 - 2026-05-05

Post-reconnect unplugged evidence checks.

- Extended `tools/collect-location-evidence.ps1` with `-RequireRecentUnpluggedHistory`, `-MinimumUnpluggedHistorySeconds`, and `-MaximumUnpluggedHistoryAgeMinutes` for workflows where USB ADB is unavailable while the phone is unplugged.
- Added recent unplugged interval parsing from `dumpsys battery` power and battery-change history so post-reconnect runs can fail closed on duration.
- Captured post-reconnect API 36 evidence `build/device-evidence/location/20260505-125057`; the device history showed a recent unplugged interval from `2026-05-05T12:48:23.598` to `2026-05-05T12:50:14.389`, about 111 seconds, which was below the 600-second roadmap threshold.
- Captured follow-up API 36 evidence `build/device-evidence/location/20260505-143254`; the recent unplugged interval from `2026-05-05T14:21:53.052` to `2026-05-05T14:32:08.107` lasted 615.055 seconds and satisfied the 600-second post-reconnect history gate with GPS/network provider cadence evidence present.

## v0.2.47 - 2026-05-05

Location durability evidence gates.

- Extended `tools/collect-location-evidence.ps1` with structured battery parsing for plug state, charge counter, current, voltage, and sample deltas.
- Added `-RequireUnpluggedSample` so future battery evidence fails closed if the device is connected to USB/AC/wireless/dock power before or after the sample.
- Added `-RequireProviderCadenceEvidence` so Location evidence can assert that `dumpsys location` contains expected OpenTasker GPS/network cadence registrations or historical aggregates.
- Verified the new collector gates on connected API 36 device `SM-S938B` with evidence `build/device-evidence/location/20260505-120448`; the run correctly detected USB power and GPS/network cadence, so it is tooling evidence only, not an unplugged battery reliability claim.

## v0.2.46 - 2026-05-05

Background Location delivery evidence.

- Verified the installed/enabled `Location evidence log` template on connected API 36 device `SM-S938B` with the app sent home.
- Used a shell-owned GPS test provider to deliver the template coordinates while `AutomationService` stayed foreground with `specialUse|location`.
- Captured Room evidence under `build/device-evidence/location/20260505-085413` showing a successful `Location evidence log Task` run log after evidence collection started.
- Extended `tools/collect-location-evidence.ps1` so `-RequireRunLogMessagePattern` can match the recent run-log message, task name, or the triggered task's action JSON.

## v0.2.45 - 2026-05-05

Location event evidence assertions.

- Extended `tools/collect-location-evidence.ps1` to snapshot the debug app's Room database through `run-as`.
- Writes `room-summary.json` with profile, task, and recent run-log counts/details when local Python/SQLite support is available.
- Added optional `-RequireRunLogMessagePattern` and `-RequireLogcatPattern` checks so a background Location run can fail closed unless execution evidence is present.
- Kept database capture non-fatal for non-debug or non-`run-as` builds while preserving foreground-service validation.

## v0.2.44 - 2026-05-05

Location evidence template.

- Added a disabled-by-default `Location evidence log` profile template for configuring a test radius with latitude, longitude, radius, max-accuracy, and dwell slots.
- The template installs as a normal Location context plus a log action, so future device smoke work can verify actual Location event delivery without manual context construction.
- Kept the template setup-required with explicit foreground/background location and device Location prerequisites.
- Added JVM coverage for the template catalog entry and generated Location context config.

## v0.2.43 - 2026-05-05

Location device evidence harness.

- Added `tools/collect-location-evidence.ps1` to collect adb-backed foreground-service, permission, location, logcat, and battery snapshots for Location/geofence verification.
- The harness writes timestamped JSON summaries and raw evidence files under ignored `build/device-evidence/location/`.
- Supports optional permission grants and an app-to-home sample to verify the foreground automation service remains active while the app is backgrounded.
- Verified the harness against connected API 36 device `SM-S938B`; a 10-second home/background sample kept `AutomationService` foreground with `specialUse|location` and recorded battery snapshots.

## v0.2.42 - 2026-05-05

Foreground service launch repair.

- Started `AutomationService` from `MainActivity` using `ContextCompat.startForegroundService`.
- Kept boot receiver startup intact while ensuring app launch also activates the automation engine.
- Logged foreground-service startup failures from the activity path.
- Added a JVM source contract test for the activity-to-service startup path.
- Verified on a connected API 36 device that app launch starts the foreground service with the `specialUse|location` type after foreground/background location permissions and device location are enabled.

## v0.2.41 - 2026-05-05

Location policy disclosures.

- Added shared Android location policy disclosure copy for Setup and Context Inspector.
- Explains that Android 11+ background location is granted from app settings instead of the foreground permission dialog.
- Explains that approximate foreground access limits background precision.
- Adds Android 14+ foreground-service location gating copy when foreground and background location prerequisites are ready.
- Added JVM coverage for the location disclosure policy text.

## v0.2.40 - 2026-05-05

Geofence cadence tuning.

- Added a balanced location provider request policy for the FOSS `LocationManager` source.
- Requests GPS updates less aggressively than network updates to reduce baseline location polling pressure.
- Added cadence metadata to the waiting-for-location setup event for inspector/debug visibility.
- Extended location setup rechecks from 30 seconds to 60 seconds.
- Added JVM coverage for cadence defaults and validation.

## v0.2.39 - 2026-05-05

Geofence dwell cleanup.

- Added profile-scoped persisted dwell-state cleanup for deleted profiles.
- Cleared a profile's persisted Location dwell keys when its context list changes, preventing removed or reindexed geofences from retaining stale timers.
- Routed the active automation view model through the application context so profile edits can maintain location dwell storage.
- Kept enable/disable and profile metadata edits from resetting dwell timers when contexts are unchanged.

## v0.2.38 - 2026-05-05

Context Inspector dwell detail.

- Added per-profile Location observation enrichment in the Context Inspector using the same persisted dwell state as runtime matching.
- Added location check rows that show inside, outside, accuracy-blocked, or unknown dwell status with elapsed time against configured dwell duration.
- Kept source cards raw while profile check rows display geofence-specific dwell metadata for the selected profile/context.
- Added regression coverage for transformed Location observations during profile inspection.

## v0.2.37 - 2026-05-05

Persisted geofence dwell state.

- Added profile/context-scoped Location dwell keys with config hashes so edited geofences do not reuse stale inside-since state.
- Added a pure dwell-state tracker that preserves `insideSinceEpochMs` across accurate inside samples and clears it when a sample leaves the radius.
- Persisted dwell state in app-local preferences so dwell timers can survive process restarts.
- Wired ProfileMatcher to enrich Location context events with persisted dwell metadata before FOSS geofence evaluation.
- Added regression coverage for first-entry persistence, dwell carry-forward, outside clearing, low-accuracy preservation, and stable key hashing.

## v0.2.36 - 2026-05-05

Live FOSS location source baseline.

- Added a registered `location` context source backed by Android `LocationManager`, with GPS/network providers and last-known-fix seeding.
- Added fail-closed source events for missing permissions, disabled providers, unavailable services, and source errors.
- Declared the Android 14+ location foreground-service contract while keeping background geofence reliability gated behind background location and device verification.
- Updated Setup and Context Inspector copy for foreground, approximate, precise, and background location states.
- Added regression coverage for location event metadata, runtime source registration, and manifest foreground-service location declarations.

## v0.2.35 - 2026-05-05

Template regex policy.

- Made regex-like template functions (`match`, `matches`, `regex`, and `replace`) explicitly unsupported.
- Preserved fail-closed behavior by keeping the original template token when regex-like functions are used.
- Kept existing bounded legacy `%var(regex:...)` behavior separate from the new template engine.
- Added regression coverage for explicit regex-template rejection.

## v0.2.34 - 2026-05-05

Template condition expansion.

- Added bounded `{{ ... }}` expansion to action conditions before legacy predicate evaluation.
- Preserved legacy `%var` condition behavior and applied template expansion only when a condition contains template tokens.
- Made template condition warnings fail closed by skipping the action instead of running on an unsafe or unknown expression.
- Added regression coverage for template conditions, JSON path conditions, and warning-based condition skips.

## v0.2.33 - 2026-05-05

Per-expression template diagnostics.

- Persisted bounded per-expression template trace lines beneath action trace summaries.
- Parsed template trace lines back into structured run-log diagnostics with argument name, source scope, expression, value, and optional warning.
- Rendered individual template expressions in Run Log trace rows, including source scope and redacted values for sensitive arguments.
- Added regression coverage for persisted template trace lines, sensitive expression redaction, and structured parsing.

## v0.2.32 - 2026-05-05

Template run-log diagnostics.

- Parsed template expansion details out of action trace messages into structured run-log diagnostics.
- Added per-step expanded argument summaries and template warning counts to the Run Log UI.
- Preserved ordinary parenthesized failure messages while recognizing generated template detail suffixes.
- Added regression coverage for parsing expanded argument details, warning counts, and normal parenthesized messages.

## v0.2.31 - 2026-05-05

Runtime template argument expansion.

- Wired action argument expansion through the bounded `TemplateExpressionEngine` after legacy `%var` expansion.
- Added `VariableStore` template snapshots for task-local, event, global, and array scopes.
- Added sanitized expanded-argument summaries, template warnings, and per-argument expansion traces to `ActionExecutionTrace`.
- Redacted sensitive argument names such as tokens, keys, secrets, cookies, and passwords from run-log summaries.
- Added regression coverage for runtime template expansion, event scope lookup, array lookup, warning propagation, and summary redaction.

## v0.2.30 - 2026-05-05

Template expression engine baseline.

- Added a pure `TemplateExpressionEngine` for bounded `{{ ... }}` template expansion.
- Added task/event/global scope precedence, explicit scope prefixes, array indexing/count/join support, and JSON path reads from scoped values.
- Added safe string and math pipe functions with traces and warnings for debugging expansion behavior.
- Added fail-closed limits for template length, expression count, function chains, resolved value size, output size, and unknown functions.
- Documented the template expression baseline and added regression coverage for scope, defaults, string/math transforms, JSON paths, arrays, and expansion limits.

## v0.2.29 - 2026-05-05

FOSS geofence evaluator baseline.

- Added a pure `FossGeofenceEvaluator` with Haversine distance, radius checks, optional max accuracy, and dwell-time evaluation.
- Wired active Location context matching through the FOSS evaluator without adding Play Services dependencies.
- Added Location editor fields for max accuracy and dwell seconds.
- Reused the same evaluator for the older geofence trigger distance path and added regression coverage for radius, accuracy, dwell, and active context matching.

## v0.2.28 - 2026-05-05

Profile sharing manifest baseline.

- Added a pure profile-share manifest model for OpenTasker bundles with stable slugs, counts, trust state, and submission metadata.
- Added safety findings for unsupported/setup-required actions, schema warnings, lossy import warnings, and missing screenshots.
- Added GitHub Discussions submission markdown generation without adding network publishing or verified-template claims.
- Documented the sharing baseline and added unit coverage for manifest counts, blockers, slug validation, and submission text.

## v0.2.27 - 2026-05-05

Termux script readiness baseline.

- Added a gated `script.termux.run` action with metadata and a runtime failure path that does not execute scripts.
- Added Termux and Termux:Tasker package visibility and optional setup status detection.
- Added Setup checklist copy for the Termux script bridge while excluding it from required readiness progress.
- Documented the non-executing scripting baseline and added tests for package constants, manifest queries, and capability gating.

## v0.2.26 - 2026-05-05

Shizuku readiness baseline.

- Added package visibility and runtime status detection for the Shizuku manager without linking the Shizuku API.
- Added an optional Setup checklist row for Shizuku power mode that is excluded from required readiness progress.
- Added elevated-action hints for Shizuku candidates while keeping restricted actions blocked.
- Documented the safe readiness scope and added tests for status, action hints, and manifest package visibility.

## v0.2.25 - 2026-05-05

Scene library baseline.

- Added a Room-backed Scenes tab that lists persisted scenes and supports safe scene creation/deletion.
- Added scene validation for positive dimensions, empty scenes, element bounds, and missing tap/long-press task bindings.
- Added scene cards with canvas summaries, element/binding previews, overlay-permission readiness status, and validation messages.
- Documented the scene baseline and updated roadmap/version metadata for L2.
- Added unit coverage for scene validation warnings, geometry errors, missing task references, and valid bounded elements.

## v0.2.24 - 2026-05-05

Visual flow baseline.

- Added a pure automation flow graph model that maps profiles to contexts, enter/exit tasks, actions, edges, and warnings.
- Added an optional Flow tab that renders read-only per-profile graphs from the active Room data without replacing the list/form editor.
- Added graph warnings for missing tasks, empty contexts, and empty task lanes.
- Documented the visual flow baseline and updated roadmap/version metadata for L1.
- Added unit coverage for enter chains, exit chains, missing task references, and empty-context warnings.

## v0.2.23 - 2026-05-05

Dependency modernization baseline.

- Added a Gradle version catalog for Android, Kotlin, Compose, Room, WorkManager, Coroutines, Hilt, Gson, and test dependency versions.
- Converted root and app Gradle plugin/dependency declarations to catalog aliases without changing dependency versions.
- Documented the staged dependency modernization order, risk rules, and verification gates for future upgrade batches.
- Updated F-Droid draft metadata and version metadata for the centralized dependency baseline.

## v0.2.22 - 2026-05-05

F-Droid readiness baseline.

- Added an `openTaskerDistribution=fdroid` Gradle profile without changing existing Android variant names.
- Pinned Android build tools to `35.0.0` and exposed `BuildConfig.DISTRIBUTION`.
- Added `verifyFdroidReadiness` to block common proprietary dependency families from the F-Droid profile.
- Added CI coverage for the F-Droid release profile.
- Added F-Droid readiness docs and a draft fdroiddata metadata file for `com.opentasker.app`.

## v0.2.21 - 2026-05-05

Tasker XML import baseline.

- Added a secure Tasker XML parser that converts common task/profile/variable structures into an OpenTasker JSON bundle.
- Added a migration report model with mapped actions, unsupported Tasker action placeholders, skipped profile/context warnings, variable counts, and scene exclusions.
- Added an explicit unsupported imported Tasker action runtime failure path and capability metadata.
- Documented the supported import surface and updated roadmap/README/version metadata for X10.
- Added regression tests for action mapping, unsupported action preservation, profile skipping, variable import, scene warnings, and Wait conversion.

## v0.2.20 - 2026-05-05

Calendar and sun trigger baseline.

- Added a local CalendarProvider event bridge that emits redacted `event=calendar` metadata for busy current or upcoming events.
- Added sunrise/sunset matching with user-provided latitude/longitude, offset minutes, and bounded trigger windows.
- Added Calendar access onboarding, Event context editor fields for calendar/sun filters, and Inspector setup copy.
- Promoted the meeting-mode calendar template from planned to setup-required installation.
- Updated roadmap/docs/version metadata and regression tests for calendar filtering, sun calculations, and template installation.

## v0.2.19 - 2026-05-05

NFC tag trigger baseline.

- Added an NFC event bridge that accepts tag/tech/NDEF discovery intents and emits `event=nfc` context events with normalized tag IDs.
- Routed cold-start and foreground NFC intents through `MainActivity` into the existing Event context source.
- Added NFC tag ID filtering to Event contexts and exposed an NFC tag ID field in the context editor.
- Promoted the nightstand NFC sleep template from planned to setup-required installation.
- Updated inspector/setup copy, roadmap/docs/version metadata, and regression tests for NFC matching and template installation.

## v0.2.18 - 2026-05-05

Notification listener trigger baseline.

- Added a `NotificationListenerService` event bridge that emits `event=notification` context events without logging notification text.
- Merged notification events into the existing Event context source for profile matching and context inspection.
- Added package allowlists, title/body filters, bounded regex matching, and fail-closed invalid-regex behavior for Event contexts.
- Expanded the context editor for notification event filters and updated docs/version metadata for the X7 baseline.

## v0.2.17 - 2026-05-05

Context inspector baseline.

- Added an Inspector tab with live registered context-source health, latest observed values, setup status, and source errors.
- Added per-profile match explanations that show whether enabled profiles currently match and which context blocks activation.
- Added a reusable context-inspection model with tests for source health, missing events, all-context matching, and inverted contexts.
- Updated roadmap, project notes, README metadata, and app version metadata for the X6 baseline.

## v0.2.16 — 2026-05-04

Automation mode baseline.

- Added per-profile automation modes: single, restart, queued, and parallel.
- Added a Room v1-to-v2 migration that persists `automationMode` on profiles.
- Added profile editor mode selection and profile cards showing the current mode.
- Updated `AutomationService` dispatch so re-triggers can be skipped, restarted, queued, or run in parallel.
- Added unit coverage for profile entity automation-mode round trips and legacy fallback.

## v0.2.15 — 2026-05-04

External automation target baseline.

- Added a permission-scoped exported receiver for documented external automation intents.
- Added external actions to run tasks, enable/disable profiles, query automation status, and pass task variables.
- Persisted external task runs to the Room run log with action trace summaries.
- Added manifest permission strings and security documentation for external callers.
- Added unit coverage for external variable-name validation and documented variable extra names.

## v0.2.14 — 2026-05-04

Locale plugin host baseline.

- Added Locale/Tasker-compatible setting plugin dispatch through a new `plugin.locale.fire` action.
- Added explicit package validation, string-only JSON bundle decoding, bundle size limits, blurb handling, and timeout wrapping.
- Added plugin discovery metadata for Locale edit-setting/edit-condition packages and requested permission disclosure.
- Added manifest package visibility queries for Locale-compatible plugin discovery.
- Documented the supported plugin host surface and added parser/trust-boundary unit tests.

## v0.2.13 — 2026-05-04

Open JSON bundle baseline.

- Added schema-versioned OpenTasker JSON bundle models for profiles, tasks, actions, contexts, variables, scenes, and metadata.
- Added deterministic export ordering and capability requirement metadata for setup-required or unsupported actions.
- Added import planning/reporting with warnings for unsupported actions and lossy missing-reference handling.
- Added Room-backed export/import repository logic with task ID remapping, variable upsert, profile remapping, and scene element link remapping.
- Documented the v1 JSON bundle format and added unit coverage for sorting, capability metadata, validation, and JSON round trips.

## v0.2.12 — 2026-05-04

Profile template baseline.

- Added an on-device profile template catalog with eight roadmap-backed starter patterns.
- Added slot substitution for template names, context configs, and action arguments.
- Added a Compose template picker and slot form that installs templates as disabled profiles with starter tasks.
- Gated planned calendar, NFC, and external-intent templates so they are visible but cannot create broken profiles yet.
- Added unit coverage for catalog completeness, unsupported-action gating, slot expansion, and planned-template blocking.

## v0.2.11 — 2026-05-04

Public documentation truthfulness pass.

- Corrected README action counts and active runtime-context claims to match the compiled APK.
- Clarified that plugin hosting, Tasker XML import/export, day schedules, and location/geofence runtime support are planned or still being hardened rather than shipped.
- Updated architecture docs to describe the current foreground-service trigger monitors and action capability gates.
- Removed stale audit/checkpoint documents that overclaimed completion against older source snapshots.

## v0.2.10 — 2026-05-04

Regression-test hardening pass.

- Hardened cron step/range parsing so malformed expressions fail closed instead of throwing.
- Added tests for malformed cron steps and valid minute/hour cron matching.
- Added tests for variable scope shadowing and missing-variable expansion.
- Updated README/roadmap metadata for the expanded regression coverage.

## v0.2.9 — 2026-05-04

Run log tracing baseline.

- Added action execution traces with index, label, action type, duration, status, and message.
- Persisted summarized action traces in task run-log messages.
- Expanded run-log cards to show multi-line action trace summaries.
- Added unit coverage for trace summary formatting.

## v0.2.8 — 2026-05-04

Capability gating baseline.

- Added a central action capability registry for supported, setup-required, and unsupported actions.
- Annotated task action rows and action picker cards with setup/unsupported status.
- Disabled unsupported privileged or unimplemented actions in the add-action flow.
- Added warning copy in action configuration dialogs for actions that require setup.
- Added unit coverage for capability gating defaults.

## v0.2.7 — 2026-05-04

Runtime registry and stub-failure hardening pass.

- Registered built-in action implementations and context sources during app startup.
- Aligned runtime action IDs with the action metadata IDs saved by the Compose editor.
- Replaced success-shaped action stubs with real behavior where practical and explicit unsupported failures where Android requires privileged access.
- Implemented notification, intent launch, SMS send, volume, media-key, HTTP POST, and HTTPS download execution paths.
- Removed unused placeholder context source files and stopped silently swallowing application-context polling errors.
- Added unit coverage to ensure every UI action metadata ID has a runtime action implementation.

## v0.2.6 — 2026-05-04

App-open trigger hardening pass.

- Removed the unused plain background `AppOpenService` and its manifest entry.
- Added a foreground-service-owned `AppUsageMonitor` that polls `UsageStatsManager` only when usage access is granted.
- Added opened/closed `AppEvent` dispatch when the foreground package changes.
- Shared usage-access detection between setup UI and the app-open monitor.
- Added focused unit coverage for foreground package selection.
- Updated README/roadmap metadata for app-open monitoring.

## v0.2.5 — 2026-05-04

WiFi trigger hardening pass.

- Replaced the manifest `CONNECTIVITY_CHANGE` receiver with a lifecycle-owned `ConnectivityManager.NetworkCallback`.
- Added WiFi event dispatch from the foreground automation service with duplicate-state suppression.
- Added Android 13 nearby WiFi devices permission metadata and setup checklist coverage.
- Added SSID normalization tests for quoted and unknown platform values.
- Updated README/roadmap metadata for platform-safe WiFi monitoring.

## v0.2.4 — 2026-05-04

Exact alarm hardening pass.

- Removed `USE_EXACT_ALARM` so OpenTasker no longer declares the alarm-clock/calendar-only permission.
- Added an app-owned time tick scheduler that uses exact `AlarmManager` delivery when allowed and inexact `setWindow()` fallback when exact alarms are denied.
- Replaced the manifest `TIME_TICK` dependency with an internal scheduled receiver and exact-alarm permission-change rescheduling.
- Added focused unit coverage for minute-boundary scheduling.
- Updated setup text and README/roadmap metadata for exact-alarm fallback behavior.

## v0.2.3 — 2026-05-04

Permission onboarding pass.

- Added a Setup tab with live status for Android runtime permissions and special access gates.
- Added direct request/open-settings actions for notifications, exact alarms, battery optimization, usage access, notification access, overlay access, foreground/background location, Bluetooth, SMS, and DND access.
- Added Bluetooth scan permission metadata for Android 12+ Bluetooth setup.
- Updated README/version metadata for the setup checklist.

## v0.2.2 — 2026-05-04

Active UI reintegration pass.

- Replaced the launcher-only status screen with a live Compose management UI.
- Added profile creation, editing, enable/disable toggling, deletion, and context attachment backed by Room.
- Added task creation, editing, deletion, and action add/edit/delete flows driven by the action metadata registry.
- Restored run-log browsing inside the active APK.
- Registered built-in action metadata during app startup so dynamic action forms are populated.
- Updated README/version metadata to reflect the active UI state.

## v0.2.1 — 2026-05-04

Production hardening pass.

- Fixed Windows and Linux Gradle bootstrap scripts so builds work from paths containing `--`.
- Aligned app version metadata and README badge to the shipped APK version.
- Re-enabled release minification and resource shrinking while keeping unsigned release builds possible without local secrets.
- Consolidated release CI and added a push/PR build workflow.
- Removed tracked local build artifacts and machine-specific configuration from the repository.
- Replaced broken Hilt runtime entrypoints with the active non-Hilt application singleton wiring.
- Hardened shell, intent, file, network, notification, settings, geofence, receiver, backup, and JSON parsing paths.
- Added Room schema export and focused validation unit tests.
- Improved shared Compose component semantics and light-theme error contrast.

## v0.2.0 — 2026-05-04

Full UI layer with database integration and action editor.

- **Database integration:** Room DAOs with StateFlow live updates for profiles and tasks
- **Profile CRUD:** Create, edit, delete profiles with persistence
- **Task CRUD:** Create, edit, delete tasks with action lists
- **Action editor:** Dynamic form generation for registered action definitions based on metadata registry
- **Context picker:** Multi-select context families with predicate configuration (app, time, day, location, state, event)
- **Action metadata system:** Comprehensive metadata for all built-in actions with field types and validation
- **Task list screen:** Dedicated view to browse and manage all tasks
- **Profile enable/disable toggle:** Toggle profiles on/off with database update
- **Gradle 8.9 toolchain:** Updated from 8.7 for AGP 8.7.2 compatibility
- **Lint baseline:** Suppressed MissingPermission and CoarseFineLocation warnings

## v0.1.0 — 2026-05-03

Initial scaffold.

- Project skeleton: Kotlin 2.0 + Jetpack Compose + Material 3
- Core data model: Profile / Context / Task / Action / Scene / Variable
- AMOLED-black default theme
- Architecture document (`docs/ARCHITECTURE.md`)
- Roadmap (`ROADMAP.md`) tracking parity with Tasker feature surface
- MIT license, shields.io badges
