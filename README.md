# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.82-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-7f52ff.svg)](https://kotlinlang.org)

**OpenTasker** is a fully open-source, on-device, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android.

---

## Features

### Automation engine

- **Encrypted automation database** — SQLCipher encrypts the complete Room file at rest with a random key wrapped by Android Keystore; existing plaintext databases migrate once before Room opens, and wrong-key opens fail closed

- **Profiles, contexts, tasks, actions** — a complete Room-backed automation pipeline with a Compose UI
- **Companion presence triggers** — user-confirmed CompanionDeviceManager associations emit low-power present/absent events without a scanning loop, with setup-time revocation
- **7 context families** — Application, Time, Day, Location, State, Event, and Plugin (Locale/Tasker condition)
- **74 built-in actions** plus engine-handled flow control (`task.run`, `if`/`else`/`end if`, `for each`/`end for`, `try`/`catch`/`end try`, `stop`)
- **USB device contexts** — attach/detach event pulses expose bounded device, vendor, product, and class fields for local input-device automations
- **Template expressions** — bounded `{{ ... }}` expansion with scoped variables, arrays, JSON paths, string/math/date functions, traces, and strict regex policy
- **Side-effect-free preflight reviews** — preview a task or profile with synthetic event variables, expanded inputs, branch decisions, setup gaps, intended effects, and explicit blockers before any action runs
- **Trigger simulation** — from a profile editor or Context Inspector, pin family-specific synthetic events and see each predicate, context expression, cooldown, and admission result without writing a production run-log row or running the task
- **Automation lint** — profile saves, imports, the flow graph, and Context Inspector surface missing reversals, repeated state triggers, conflicting writers, and inter-profile loops with concrete fixes
- **First-class secret variables** — AES-256-GCM Android Keystore storage, deliberate reveal/re-entry UX, and provenance-based redaction for derived action arguments, logs, traces, and failures
- **One redaction boundary for stored arguments** — credential-bearing action arguments (HTTP authorization/headers/query/body, request payloads, script stdin, SMS text) are masked wherever they are displayed, including the task list, flow graph, and previews, so they cannot leak through a screenshot or accessibility semantics; unregistered actions and unknown keys fail closed
- **Coherent execution controls** — per-profile single/restart/queued/parallel re-trigger behavior, followed by a global per-task abort-new/abort-existing/run-both/wait collision policy across profile, manual, nested, widget, notification, and external runs
- **Action-level flow controls** — atomic action reordering plus optional conditions and continue-after-failure behavior, with those semantics preserved through storage and bundle round trips
- **Durable edit history** — task, profile, and scene cards expose five-step undo/redo; scene snapshots include names, dimensions, and elements, and a new edit after undo safely starts a branch
- **Profile groups** — organize profiles into named groups with filter chips
- **Nested context logic** — author backward-compatible ALL/ANY/NOT groups over profile contexts; the Inspector explains the evaluated tree
- **Local projects** — scope tasks, profiles, scenes, and variables behind a shared project boundary with explicit reassignment on deletion

### Triggers (contexts)

- **Offline bundle import** - paste exported JSON or decoded QR text into the existing disabled-by-default review flow with bounded input validation

- Time/day schedules with presets, aliases, and ranges
- Device state (battery, charging, headphones, screen, media playback, airplane, power save, Wi-Fi SSID)
- App foreground detection via UsageStats, with optional exact/glob Activity component matching and explicit unavailable-component reporting
- Wi-Fi and data/internet connectivity via NetworkCallback
- Notification listener with package/title/body filters
- NFC tag scans with normalized ID matching and a one-time NDEF write helper
- Calendar windows with redacted event metadata
- Sunrise/sunset filters with coordinate, offset, and window support
- Shake, Bluetooth connect/disconnect and Android 16 bond-loss/encryption security events, Android 16 Advanced Protection transitions, sanitized SMS/MMS receipt on standard/F-Droid builds, package install/remove/replace
- Bluetooth all-devices-disconnected transition with an editor preset and multi-device tracking
- Android 15+ screen-recording visibility trigger - `event=screen_recording` reacts to whether this app is visible in a recording without capturing screen contents
- SMS/MMS-received trigger - `event=sms_received` exposes sanitized sender/body metadata on standard/F-Droid builds; Android 17 may delay standard OTP SMS delivery for up to three hours outside exempt apps
- Quick Settings tile tap, home-screen widget/shortcut, boot
- Authenticated `event=push` bridge for a de-googled UnifiedPush distributor; delivery IDs are deduplicated, payloads are bounded, and message content is redacted before matching/logging
- Received Share (`ACTION_SEND`/`SEND_MULTIPLE`) trigger for bounded text, URLs, single files, and multiple files, with MIME/text/URI filters and `share_*` task variables
- FOSS platform location/geofence — GPS/network fixes, balanced provider cadence, radius/accuracy/dwell evaluation, persisted dwell state, and API 36 background delivery evidence
- Locale/Tasker condition plugins — polled as first-class context predicates with last-known-state caching
- Home Assistant bridge proof of concept — bounded outbound JSON webhooks with HTTPS-by-default policy, redacted webhook secrets, and transient retry/backoff

### Actions (74 registered + 10 engine-handled)

| Category | Count | Examples |
|----------|------:|---------|
| Settings | 16 | Wi-Fi, Bluetooth, brightness, volume, airplane, mobile data, screen timeout, DND, Zen rule set/clear, ringer mode, torch, tile state, temporary state, keyboard info, keyboard picker |
| App | 10 | launch intent, launch app, publish shortcut, kill, archive, unarchive, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 8 | HTTP Request, Home Assistant webhook, MQTT publish, legacy GET/POST aliases, ping, download, Wake-on-LAN |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 7 | vibrate, clipboard set, reboot, lock, screen off, wake, log |
| Notification | 4 | notify/toast, progress, cancel, TTS speak |
| Variable | 13 | set variable, clipboard get, contacts lookup, read data (JSON/CSV/XML/HTML), date-time (format/parse/add), text (match/replace/split/join/substring) |
| Flow | 1+10 | wait; engine: task.run, if/else/end if, for each/end for, try/catch/end try, stop |
| Plugin | 2 | Locale setting dispatch, Locale condition query |
| Script | 1 | SHA-256-pinned Termux `RUN_COMMAND` with bounded result capture |
| Import | 1 | unsupported Tasker action placeholder |

Every action carries an explicit capability contract; an action with no reviewed contract resolves to unsupported rather than defaulting to available. Privileged actions (airplane, mobile data, screenshot, reboot, screen off, kill app) are gated to fail honestly. Set brightness and set screen timeout require the **Modify system settings** special access granted from Setup, and Wake-on-LAN requires local network access on Android 17+. SMS is available in standard/F-Droid builds; Play builds omit SMS/phone-state permissions.

New automations use **HTTP Request** for GET, HEAD, POST, PUT, PATCH, DELETE, and OPTIONS. It accepts structured query/header lines, inline or OpenTasker-file request bodies, per-stage timeouts, status/header/body variables, and atomic file output. Redirects default off and can be enabled only for the same origin; TLS verification cannot be disabled, cleartext remains private-LAN-only, and response/request sizes are bounded. Stored `http.get` and `http.post` actions continue to execute through compatibility aliases. Put credentials in Keystore-backed secret variables and reference them from Authorization or header fields so traces remain redacted.

The **Intent Dispatch** action supports bounded activity, explicit broadcast, and explicit service delivery. It accepts allowlisted URI/MIME data, six activity/URI flags, capped string/int/bool extras, and optional ordered-broadcast result-code capture. External activity actions with arbitrary actions require a chosen component; broadcasts and services always require one. `file://` URIs, parcelable/serialization-style extras, unknown flags, ambiguous targets, and non-exported external components fail closed.

The **MQTT Publish** action uses a small in-app MQTT 3.1.1 QoS 0/1 client over platform sockets and TLS, so the F-Droid build adds no MQTT dependency. TLS is enabled by default; cleartext is restricted to private/local hosts and the Android 17 local-network grant. Payloads are capped at 64 KB, QoS 2 and wildcard publish topics are rejected, and username/password fields are redacted.

The **Clipboard** actions read and write text without an extra permission, cap transfers at 64 KiB, and mark clipboard-derived values sensitive. **Contact lookup** supports bounded name/phone/email matching into sensitive variables. Android 17+ defaults to a field-scoped system picker with a timeout and no broad address-book grant; explicit `READ_CONTACTS` permission mode remains available for unattended runs through Setup.

**Quick Settings tiles** provide four app-owned slots. Long-press a tile to bind a task and configure its label, subtitle, icon, and state; `tile.set` can update a configured slot at runtime, and tile-triggered task runs use the same foreground execution and run-log identity as other external entry points.

The **Temporary State** action applies a bounded reversible setting (brightness, volume, ringer mode, or DND) and restores the captured prior value through a unique persisted WorkManager job. Reusing the same revert key replaces the earlier timer, and pending work remains inspectable through WorkManager after process death.

The **Keyboard** actions report the current/enabled IMEs into bounded variables. `ime.set` validates the requested component or package and opens Android's picker; normal applications cannot silently select another keyboard, so it fails with an explicit user-selection message rather than pretending the switch happened.

The **push trigger spike** chooses a distributor-neutral UnifiedPush boundary instead of adding a polling client or a Google service. Setup creates a per-install token; a distributor adapter forwards an explicit `com.opentasker.action.PUSH_EVENT` broadcast with that token, topic, event ID, title, and message. The receiver authenticates the token, caps the message at 8 KiB, keeps only topic/title/event ID/size in event metadata, and suppresses duplicate topic/event-ID deliveries for 30 seconds. Delivery is at-least-once, so retry belongs to the distributor; no network retry is attempted inside the receiver. ntfy can be adapted to this same envelope later without changing profiles.

The **Received Share trigger** registers OpenTasker in Android's Sharesheet for text, URLs, MIME-typed content, and one or more file/content URIs. It sanitizes and bounds every value before matching, rejects arbitrary Parcelable or oversized extras, and never opens a URI in the receiver. Matching share tasks receive `share_text`, `share_uri`, `share_uris`, `share_mime`, `share_count`, and `share_multiple` as run-scoped variables.

Variable names follow Tasker's scope rule: an all-lowercase name is local to the current task, while any name containing an uppercase letter is global and durable. `var.persist` promotes an all-lowercase target to a global name, and the Variable vault applies the same normalization. Concurrent runs merge changes to different globals; if two stale snapshots change the same global, the first committed value is kept and the later conflict is recorded in the run log.

### Reliability and observability

- OEM battery-killer detection with per-vendor remediation (Samsung, Xiaomi, OnePlus, Oppo, Realme, Vivo, Huawei, etc.)
- Alarm-backed time/day reevaluation through Doze, with a persisted engine heartbeat and periodic WorkManager watchdog that re-arms dropped ticks and foreground-service timeout recovery
- Setup checklist covering notifications, exact alarms, battery optimization, usage access, overlays, location, Bluetooth, SMS, DND, modify system settings, Shizuku, and Termux
- Context inspector with live source health, latest values, per-profile match explanations, and
  Loading/Ready/Stale/Error observation status with age-aware reporting
- Keyset-paged run logs with SQL-backed task/status/date/search filters, complete expandable action traces, redacted JSON/CSV export, per-step diagnostics and variable writes, reviewed retention reductions, held admission rows with safe manual replay, and user-pinned history
- Live view of in-flight automations — task, origin, current step, and elapsed time — with per-run cancellation that unwinds nested sub-tasks and records a terminal `Cancelled` outcome
- In-app diagnostics for service/foreground-type/standby/exact-alarm/matcher/watchdog health, a bounded process log, and captured crash previews; shared reports include the same evidence with credential redaction
- Crash log capture and local diagnostic export

### Interoperability

- **Locale/Tasker plugin host** — setting dispatch, condition queries, configuration parsing, request-query events, bundle validation, and last-known-state fallback
- **Locale/Tasker condition context** — condition plugins as first-class profile predicates polled every 30 seconds
- **External automation target** — signature-scoped intents to run tasks, toggle profiles, query status, and pass variables. Task runs are asynchronous (protocol v2): the receiver validates and enqueues, then returns an execution ID that callers poll with `QUERY_EXECUTION`, because a broadcast cannot stay open for a task that may wait minutes. Callers must send `PROTOCOL_VERSION=2`; see [docs/EXTERNAL_INTENTS.md](docs/EXTERNAL_INTENTS.md)
- **OpenTasker JSON bundles** — schema-versioned export/import with deterministic legacy migrations, project membership preservation, computed action-power manifests, data-to-external-chain warnings, explicit keep/rename/replace review for variable-name conflicts, disabled-by-default installation, explicit first-enable acknowledgement, and secret values omitted by design
- **Tasker XML import/export** — preview with migration/capability warnings, deterministic mapped/unsupported/lossy reporting, and safe batches for notification, variable, media, settings, and flow actions
- **Profile sharing** — offline share manifests with editable local preview, screenshot attachments, safety findings, import-plan review, and GitHub Discussions submission text; unverified shares stay blocked until the existing import review passes

Untrusted imports are preflighted before object/DOM allocation. OpenTasker JSON is capped at 16 Mi characters, 250,000 lexical tokens, and depth 64; Tasker XML is capped at 4 Mi characters, 100,000 nodes, and depth 64. Both formats share decoded limits of 5,000 top-level entities, 20,000 actions, 10,000 contexts, 10,000 scene elements, and 8 MiB of aggregate UTF-8 string data. A named budget violation aborts before the Room transaction.

### UI and theming

- AMOLED-first Catppuccin Mocha (dark) and Latte (light) palettes, high contrast mode
- Refined mobile shell with clearer primary navigation, bottom-bar contrast, and edge-to-edge system bar theming
- Accessible Setup theme selector with explicit selected state plus denser, confidence-building backup controls
- Compact-safe profile, task, and run-log cards with horizontally safe status chips and filtered empty states
- Variable vault, Flow, Scenes, and Inspector surfaces with summary metrics, clear status language, and polished empty states
- Shared installed-app picker across application, notification, action, and Locale plugin editors, with label/package search, app icons, validated manual entry, and a latest-observed Inspector shortcut
- Metadata-driven action forms with stable-value selectors, bounded number validation, task/app/file pickers, and forward-compatible preservation of unfamiliar imported arguments
- Guided profile templates with variable slots and safety notes
- Scene element editor with drag-to-move, resize handles, multi-select, alignment guides, scaled canvas previews, overlay launch, accessible image metadata, validated sliders, and tap/long-press task bindings
- Flow graphs with zoom/pan canvas previews, edge routing, branch/subflow markers, node deep links, and picker-backed add commands
- Profile and task search bars
- One global search across profiles, tasks, actions, variables, and scenes, including named references, with live results and deep links into the matching editor or library surface
- Saveable editor/dialog state across rotation and resize
- Adaptive navigation rail at medium/expanded widths keeps every destination discoverable while compact windows retain the bottom navigation layout

### Distribution

- F-Droid readiness profile with dependency-policy and metadata verification
- Play distribution profile with SMS/phone-state manifest policy gate
- Local release verification scripts for F-Droid metadata, readiness, and APK payload comparison
- Environment-driven release signing
- SQLite database backup/restore with WAL-safe validation and atomic staged restore, reviewed before staging (source, schema version, compatibility, entity counts) and cancellable afterwards; encrypted `.otbackup` v2 exports use bounded-memory, independently authenticated 64 KiB frames while legacy v1 files remain restorable. Secret rows stay ciphertext and the device-bound Keystore key is never copied, so a restore on another device requires secret re-entry
- APK payload comparison harness for reproducibility checks
- SQLCipher native libraries are included in both standard and F-Droid source builds; the release gate audits their 16 KB page alignment and keeps the dependency checksum-pinned

### Power-user backends

- Shizuku manager/service/permission status, a persisted default-on kill switch, and a fail-closed command allowlist; elevated actions remain unsupported until a privileged user-service transport ships
- Termux 0.109+ `RUN_COMMAND` integration with a user-managed SHA-256 allowlist, pre-run hash verification, timeouts, and bounded output variables

To run a Termux script, place it below `~/.termux/tasker/`, enable Termux's external-app access, and grant OpenTasker `RUN_COMMAND` permission from Setup. Add the script path and the expected 64-character SHA-256 under **Approved Termux scripts**; OpenTasker performs a hash preflight and rechecks inside the fixed execution wrapper before the script can run. A capture prefix such as `%script` writes bounded `%script_stdout`, `%script_stderr`, `%script_exit_code`, and original-length variables; captured content is never written to the run log.

---

## Architecture

```
AutomationService (foreground)
  ↓
ProfileMatcher (monitors context streams)
  ↓
ContextSources (app, time, state, event, location, plugin)
  ↓
TaskRunner (executes action list with flow control)
  ↓
ActionRegistry (built-ins + capability gates + Locale plugin dispatch)
  ↓
Room DB (persistent storage + StateFlow live queries)
```

No Hilt — manual dependency wiring via `OpenTaskerApp_NoHilt`. MVVM with Compose, Room, coroutines, DataStore, and WorkManager.

---

## Build & Run

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release build (unsigned without keystore env vars):
```bash
./gradlew :app:assembleRelease
```

F-Droid profile:
```bash
./gradlew -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata
```

Play manifest policy check:
```bash
./gradlew -PopenTaskerDistribution=play :app:verifyPlayManifestPolicy
```

Full local release gate (pinned Gradle bootstrap verification, blocking lint, the 1,040-test JVM floor, JaCoCo coverage floors for scheduling/resilience/receivers/UI utilities, Room schemas, Android-test compilation, resolved dependency/SBOM and OSV policy, configuration-cache reuse, plus Play and F-Droid release builds):

Release-facing version, SDK, capability-count, schema, and required artifact-commit claims are generated and checked from [`tools/release-truth.json`](tools/release-truth.json) by the same gate.

Performance evidence is local and explicit. The quality gate validates the committed baseline-profile artifact and compiles the API 35+ Macrobenchmark harness; collect device evidence with `./gradlew :app:generateBaselineProfile` and run the release-like benchmark APK with `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. The harness records cold-start (`StartupTimingMetric`) and first-navigation (`FrameTimingMetric`) results. Review repeated clean runs before changing a regression budget; hosted CI is intentionally not required.

Current release claims come from `tools/release-truth.json` and the versioned README/CHANGELOG. The local quality gate reports stale version, schema, and capability claims found in ignored historical research files as warnings; labeled historical snapshots remain non-blocking.

The gate writes the debug JaCoCo XML report to `app/build/reports/jacoco/debugCoverage/debugCoverage.xml` and an HTML drill-down beside it. The test-count floor is ratcheted to the current passing count; the four area floors are explicit in `app/build.gradle.kts` and fail the gate on regression.

```powershell
.\tools\verify-local-release.ps1
```

Before it executes Gradle, the gate checks that `distributionSha256Sum` matches the configured binary distribution and that the checked-in `gradle-wrapper.jar` matches Gradle's published SHA-256. It also independently checks every dependency checksum against its recorded upstream Maven evidence, requires signature verification with an explicit trusted-key set, and rejects Gradle-generated or blanket-trust metadata. Use `.\tools\verify-dependency-verification.ps1 -UpdateOrigins` only after deliberately reviewing a dependency change; the normal verifier must pass without that switch. Use `.\tools\verify-local-release.ps1 -BootstrapOnly` for the fast bootstrap preflight alone. The full gate writes those verified hashes into the machine-readable report under `build/reports/opentasker/`. To prove failure propagation without running the full build, run `.\tools\verify-local-release.ps1 -SeedFailure`; success is a nonzero exit with `Seeded local quality-gate failure`.

Treat a wrapper upgrade as one atomic change: run `gradlew wrapper --gradle-version <version> --distribution-type bin --gradle-distribution-sha256-sum <official-bin-sha256>`, verify the regenerated JAR against Gradle's published wrapper-JAR checksum, update both expected hashes in `tools/verify-local-release.ps1` and `ReleaseTruthContractTest`, then run a clean wrapper bootstrap and the full local release gate. Never update only the distribution URL or only the executable JAR.

---

## Development

| Property | Value |
|----------|-------|
| Kotlin | 2.4.10 |
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| KSP | 2.3.10 |
| Build Tools | 36.0.0 |
| Macrobenchmark | 1.5.0-alpha07 |
| JDK | 17 or 21 |
| Min SDK | 26 (Android 8.0) |
| Compile SDK | 37 |
| Target SDK | 37 |
| Room | 2.8.4 |
| Compose BOM | 2026.06.00 |
| WorkManager | 2.11.2 |

All dependency versions are centralized in `gradle/libs.versions.toml`.

---

## Planned

`data.read` supports bounded HTML extraction with CSS selectors and normalized element text. Parsing is local-only and never fetches linked resources; the pinned jsoup parser is MIT-licensed and included in the F-Droid-compatible dependency set.

See [ROADMAP.md](ROADMAP.md) for the full backlog. Key remaining work:

- Broad device-verified background geofence reliability evidence
- API 37 platform readiness pass (FGS, predictive back, large-screen QA)
- Device-run performance evidence is collected locally through the checked-in Macrobenchmark and Baseline Profile harness

---

## License

MIT — see [LICENSE](LICENSE).

## Contributing

Issues and pull requests welcome. See [ROADMAP.md](ROADMAP.md) for planned features.

### Translations

OpenTasker supports localization. English source copy lives in `app/src/main/res/values/strings.xml`, `action_catalog_strings.xml`, and `dynamic_surface_strings.xml`. The app uses AGP's generated per-app language configuration with `en-US` as the default. An alternate locale is included in a release only after at least 80% of the default `<string>` resources have genuinely translated values; incomplete or empty locale directories are not shipped. Debug builds enable Android's `en-XA` and `ar-XB` pseudolocales for expansion and right-to-left checks. To contribute a translation:

1. Copy the three translatable XML files from `app/src/main/res/values/` to `app/src/main/res/values-<locale>/`
2. Translate only the string values (not the `name` attributes)
3. Omit strings that are identical to English — Android falls back automatically, but the locale must still reach the 80% translated-value threshold
4. Run `./gradlew :app:verifyLocaleResources` and submit the locale directory only when it passes

No incomplete locale directories are kept as release placeholders. The gate reports the exact translated-string count when a locale falls below the threshold.
