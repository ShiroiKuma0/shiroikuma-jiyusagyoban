# OpenTasker Roadmap

**Current app version:** 0.2.71
**Last updated:** 2026-06-16

Items are organized by tier and priority. Completed items are deleted, not checked off. See CHANGELOG.md for shipped work.

---

## Tier Summary

| Tier | Goal | Exit criteria |
|---|---|---|
| Now | Ship a truthful, installable v0.3.0 beta on Android 13+ | Platform compliance verified, docs accurate, performance baselined |
| Next | Reach credible beta parity for core automation workflows | Scene/flow authoring, Shizuku/Termux execution, sharing publish, i18n |
| Later | Expand into power-user and ecosystem features | Encrypted backup, visual editor polish, community marketplace |
| Backlog | Smaller improvements by priority | P2 = medium value, P3 = nice-to-have |
| Under Consideration | Needs more proof, policy clearance, or design | Webhook receiver, Wear OS, NL generation, MQTT |
| Rejected | Conflicts with OpenTasker's philosophy | Cloud dependency, proprietary export, artificial limits |

---

## Now (v0.3.0 beta gate)

### N1 - Target SDK 36 / foreground-service platform readiness

Compile SDK and target SDK are 36 and API-36 smoke evidence exists, but the explicit foreground-service behavior matrix, Play `specialUse` declaration evidence, background-start denial evidence, and large-screen/predictive-back/intent-hardening rehearsal remain incomplete. Needs on-device testing with API 35/36 emulators or physical devices.

- Audit all FGS callsites from `MainActivity` and `BootReceiver`
- Prepare Play `specialUse|location` declaration/demo evidence
- Add regression evidence for boot-start, app-launch start, background-start denial
- Harden `BootReceiver` to match `MainActivity`'s guarded startup
- Rehearse Android 16 target behavior (large-screen, predictive back, intent hardening)
- Update Setup/Inspector to show platform-blocked profile families

**Blocked on:** device testing (API 35/36 emulators or physical devices)

### N2 - Background geofence durability evidence

Single-device API-36 evidence captured (v0.2.46-v0.2.48). No multi-device, multi-provider, or multi-OEM data.

- Test across Samsung One UI, Pixel stock, and a third OEM
- GPS-only, network-only, and combined provider modes
- Doze deep-idle and rare-bucket transitions
- Post-reboot persisted-dwell verification
- OEM battery-killer audit and evidence matrix

**Blocked on:** 3+ physical devices with different OEMs

### N5 - Macrobenchmark + Baseline Profile

Not started. Add an `app:benchmark` module for cold start, service start latency, and Profiles tab time-to-first-frame. Ship a Baseline Profile in the release artifact.

**Blocked on:** connected device for benchmark measurement

### N9 - Documentation truth pass

README and UX polish docs synced for v0.2.71. F-Droid readiness docs refreshed locally. ARCHITECTURE.md and IMPROVEMENT_PLAN.md are current. Remaining: sync any version strings missed in non-tracked docs.

### N10 - Target-SDK-36 adaptive layout and intent hardening rehearsal

App compiles and targets SDK 36 with monochrome launcher icon. No explicit large-screen test matrix, predictive-back run, or intent-redirection audit is tracked yet.

- Large screens and desktop/windowing at `sw600dp`
- Predictive back in gesture and 3-button modes
- Intent-redirection hardening for Tasker/Locale/NFC/external intents
- Fixed-rate scheduling semantics for polling loops

**Blocked on:** phone, foldable/tablet emulator, and desktop/freeform device runs

---

## Next (post-v0.3 beta, v0.3.x -> v0.4)

### X1 - Scene editor finishing pass

Element creation/editing, drag-to-move, scaled previews, and nudge controls shipped. Remaining: resize handles, multi-select layout edits, alignment guides, and overlay launch via `SYSTEM_ALERT_WINDOW`.

### X2 - Visual flow editor authoring

Read-only flow graph with node deep links and lane overview shipped. Remaining: canvas-side authoring (drag from palette, edge routing, branch/subflow markers, pinch-zoom/pan, persist edits).

### X3 - Shizuku elevated backend

Readiness/detection only (v0.2.26). Add opt-in Shizuku integration with permission request, isolated `ShizukuShellRunner`, elevated command allowlist, action-level capability flag, run-log audit, and kill-switch.

### X4 - Termux scripting dispatch

Readiness/detection only (v0.2.27). Add `RUN_COMMAND` request flow, per-script allowlist with hash pinning, stdout/stderr/exit-code capture, output-to-variable mapping, and dispatch frequency cap.

### X5 - Health Connect polling trigger

Not started. Add read-only polling/sync trigger via WorkManager (15 min - 24 h), firing events for sleep, heart rate, steps, and exercise. Opt-in with background-read permission handling and 30-day history limits. F-Droid track must not ship the dependency by default.

### X6 - Variable expression engine v3

Template engine v2 shipped (bounded `{{ }}`, arrays, JSON paths, functions, traces). Remaining: writeable nested array/JSON paths, Run-Log expression debugger view, and `var.persist` action for explicit global scope.

### X7 - Profile sharing publish channel

Manifest and Discussions submission text shipped. Sharing preview UI shipped. Remaining: curated GitHub-Discussions-backed feed, detached-signature verified templates, and screenshot attachments.

### X8 - Dependency/release governance

Dependency stack is current (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, Room 2.8.4). Hilt/Dagger removed. Gson removed (kotlinx-serialization only). Room 3 and AGP 10 remain monitored future migrations. Compose BOM 2026.05.00 evaluation is pending.

### X9 - i18n/l10n bootstrap

Not started. Move user-facing strings to `strings.xml`, set up `values-<locale>` skeleton, document contributor translation workflow.

### RD4 - Compose BOM 2026.05.00 evaluation

Current BOM is 2026.04.01. Evaluate 2026.05.00 with compile, test, F-Droid, and device smoke gates. Includes testing v2 API migration (StandardTestDispatcher).

### RD7 - Encrypted database backup/restore

`DatabaseBackupManager.kt` exists without encryption. Add encrypted backup/restore using a user-supplied passphrase or device-bound key, `.otbackup` file format, and schema compatibility validation.

---

## Backlog

### P2 - Medium value

- **Screen-reader / TalkBack accessibility pass** — `contentDescription` coverage is complete, but a full TalkBack sweep and accessibility-checks instrumentation gate are needed. *Blocked on device.*
- **Broaden unit tests for action implementations** — HTTP/WoL/URL/wait/ping guards covered. File read cap, download byte bounds, settings/media/app honest-failure paths, and notification-channel guards still need focused coverage.
- **WorkManager retention coverage** — `RunLogPruneWorker` is wired and runs every 6 hours. Remaining: worker-level test coverage and a deliberate service re-arm policy decision.
- **Work-profile and Private Space audit** — app-open contexts and package events are current-profile only; work/private profile apps may be silently missed.

### P3 - Nice-to-have

- **strings.xml centralization** — only ~5 entries; most UI copy is inline Compose text. Extract stable labels, titles, permission copy, and error messages.
- **DesignSystem token adoption** — `DesignSystem.kt` defines spacing/radius/elevation scales but UI hardcodes dp values throughout.
- **Tasker XML export** — import-only today; add round-trip export for the mappable action subset.
- **UnifiedPush `event=push` trigger (RD32)** — FOSS push via ntfy/UnifiedPush connector for remote triggering without Google services or polling.
- **MQTT publish action (RD34)** — outbound-only `mqtt.publish` for Home Assistant/Node-RED integration. Needs library decision spike (Paho vs HiveMQ).
- **Locale/Tasker plugin target bridge (RD37)** — expose OpenTasker as a Locale-compatible plugin target so Tasker/MacroDroid can invoke approved tasks.
- **Mobile hotspot toggle** — privileged action via Shizuku allowlist. *Depends on X3.*

---

## Under Consideration

| Item | Why it might matter | Why it is not tiered yet |
|---|---|---|
| HTTP webhook receiver (RD10) | n8n/Node-RED/HA interop without cloud | Local HTTP server security surface, FGS/battery constraints |
| Natural-language profile creation | Reduces learning curve beyond templates | Needs privacy-preserving on-device architecture |
| Wear OS companion | Wrist triggers and quick-run tiles | Smaller audience; phone reliability comes first |
| Encrypted backup/sync | Smooth device migration without cloud trust | Requires key management design |
| App Factory / standalone APK export | Tasker differentiator for power users | Huge build/signing/security surface |
| Multi-device/collaboration | Family/shared automations | Contradicts on-device simplicity unless tightly bounded |
| IFTTT migration | Subscription backlash creates demand | IFTTT formats/API access may be unstable |
| Usage-duration triggers | App usage time as wellness automation | Sensitive UsageStats access and privacy UX |
| Phone/call control actions | Common automation request | Play policy and Android API restrictions are high friction |
| Matter / Google Home actor | Smart-home actuation without HA dependency | Matter SDK on Android is nascent; commissioning UX is heavy |

---

## Rejected

| Item | Reason |
|---|---|
| Cloud-required automation backend | Violates on-device/privacy-first positioning |
| Proprietary export format | Creates the lock-in OpenTasker is meant to avoid |
| Artificial macro/block/action limits | Conflicts with FOSS positioning |
| Mandatory Shizuku/root/ADB | Too much setup friction; elevated mode must remain optional |
| GPL plugin SDK requirement | Would reduce plugin adoption; MIT is better for ecosystem |
| Server dependency (HA/openHAB style) | OpenTasker should stay standalone |
| FBP-only editor | Flowcharts are too heavy as the only interface |
| Silent background automation | Unsafe, policy-hostile, and untrustworthy |

---

## Completed (v0.2.60 - v0.2.70)

Items shipped since the last roadmap rotation. Earlier completions (v0.2.0 - v0.2.59) are in CHANGELOG.md.

| Item | Shipped in |
|---|---|
| N6 - Sharing preview UI (bundle review dialog) | v0.2.62 |
| N7 - Locale condition plugin context UX | v0.2.69 |
| N8 - Hilt decision (removed Hilt/Dagger) | v0.2.61 |
| Profile group/folder organization | v0.2.70 |
| Diagnostic report share button + redaction tests | v0.2.69 |
| CI SHA-pinned actions + least-privilege permissions | v0.2.61+ |
| rememberSaveable state retention | v0.2.62 |
| Accessibility contentDescription coverage | v0.2.62 |
| Profile and task search bars | v0.2.62 |
| Date/time format template function | v0.2.61 |
| android:allowBackup=false declared | v0.2.61 |
| Gson removed (kotlinx-serialization only) | v0.2.61 |
| WorkManager periodic run-log pruning | v0.2.61 |
| Crash capture + diagnostic report builder | v0.2.61 |
| targetSdk raised to 36 | v0.2.61 |
| Typed run-log source/sourceLabel columns | v0.2.60 |
| task.run sub-task action (depth-bounded) | v0.2.60 |
| Flow control: if/else/endif, foreach, stop | v0.2.60 |
| Bluetooth connect/disconnect trigger | v0.2.60 |
| OEM battery-killer detection + setup guidance | v0.2.60 |
| Theme toggle (dark/light/high-contrast) | v0.2.61 |
| Wake-on-LAN action | v0.2.61 |
| Passive location provider | v0.2.62 |
| Camera/mic event triggers | v0.2.62 |

---

## Source Index

### Local evidence tags

| Tag | Description |
|---|---|
| L1 | Core repo: MainActivity, OpenTaskerApp_NoHilt, UI snapshots, README/CHANGELOG |
| L6 | Location evidence: tools/collect-location-evidence.ps1, FOSS_GEOFENCING.md, single-device API-36 data |
| L8 | 2026-06-06 manifest/service/build audit through c2412ad |
| L9 | 2026-06-06 UI maintainability audit (ActiveAutomationUi.kt 2891 lines, strings.xml 5 entries) |
| L12 | 2026-06-06 platform/incoming-intent audit (FGS, BootReceiver, Locale, NFC, Tasker XML) |
| L14 | 2026-06-06 dependency/release audit (catalog versions, CI workflows, F-Droid metadata) |

### External sources

| Tag | Source |
|---|---|
| S1 | [Easer](https://github.com/renyuneyun/Easer) |
| S3 | [Termux:Tasker](https://github.com/termux/termux-tasker) |
| S6 | [Shizuku](https://github.com/RikkaApps/Shizuku) |
| S7 | [Locale plugin SDK](https://github.com/twofortyfouram/android-monorepo) |
| S9 | [Node-RED](https://github.com/node-red/node-red) |
| S10 | [n8n](https://github.com/n8n-io/n8n) |
| S12 | [Home Assistant](https://www.home-assistant.io/docs/automation/) |
| S16 | [Tasker](https://tasker.joaoapps.com/userguide/en/) |
| S17 | [MacroDroid](https://www.macrodroid.com/) |
| S18 | [Automate](https://llamalab.com/automate/doc/) |
| S28 | [Don't Kill My App](https://dontkillmyapp.com/) |
| S31 | [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) |
| S45 | [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) |
| S46 | [TileService](https://developer.android.com/reference/android/service/quicksettings/TileService) |
| S47 | [Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/overview) |
| S49 | [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| S52 | [Android 16 target behavior](https://developer.android.com/about/versions/16/behavior-changes-16) |
| S53 | [Android 16 all-app behavior](https://developer.android.com/about/versions/16/behavior-changes-all) |
