# Research — OpenTasker

## Executive Summary

OpenTasker (v0.2.75, 58 built-in actions + engine flow control) is a local-first, FOSS Android automation engine with a mature foundation: native Kotlin/Compose UI, Room-backed profiles/tasks/scenes, template expressions, encrypted backup, Tasker/Locale interop, Shizuku/Termux bridges, import review, run traces, and API 37 targeting. Since the prior research pass, **the five previously-flagged P0s and the main-thread-execution defect have all shipped and were re-verified this pass**: fail-closed decoding of corrupt payloads (`eebb2ef`), Room-driven running-profile reconciliation (`96428f0`), durable globals across every execution entry point (`74dd240`), revocable-grant authorization of the exported Locale fire receiver (`5218ee3`), a repaired cleartext-LAN transport contract (`5bb4925`), and off-main-thread task execution on `Dispatchers.IO` with debug StrictMode (`1f3213a`). Newer work also landed the structured data reader (JSON/CSV/XML), date-time and text/regex action packs, and external-receiver hardening.

This pass surfaces a new fault line the roadmap did not cover: **the global-variable naming convention is enforced inconsistently across the write paths**, so a legitimately-configured `var.persist` can silently drop its value, the Variables editor mislabels scope, and concurrent runs can clobber each other's global write-back. Beyond that correctness cluster, the highest-value net-new directions are (a) **platform-survival compliance** — Android 16 Advanced Protection Mode actively revokes accessibility from automation-class apps, `ACCESS_LOCAL_NETWORK` will start `EPERM`-ing LAN sockets, and 16 KB page size / predictive-back / Safer-Intents are target-36 obligations — and (b) a small set of **local-first authoring primitives** (temporary/auto-revert state, IME switch, media-active context, clipboard/QR template import) that competitors ship and OpenTasker lacks without adding any cloud surface.

Top opportunities in priority order: (1) unify the global-variable naming convention across `var.persist`/editor/engine, (2) make concurrent global write-back transactional, (3) Advanced Protection Mode awareness + graceful degradation, (4) `ACCESS_LOCAL_NETWORK` runtime request + fallback (code path), (5) PendingIntent immutability / request-code and forwarded-intent sanitization sweep, (6) 16 KB + predictive-back + Safer-Intents target-36 hardening, (7) temporary/auto-revert state action, (8) full-DB SQLCipher encryption, (9) wire the inert scene slider + bound the Termux rate-limit map, (10) media-active context and IME-switch actions.

Confidence convention: product-map, competitor, and source-code claims are **Verified** (file:line or commit checked this pass) unless a bullet is explicitly labeled otherwise.

## Product Map

- Core workflows: create profile contexts; author ordered/branched tasks; build and launch scenes; inspect context/run traces; import, export, back up, restore, and share local automation data.
- Personas: privacy-conscious Android power users, Tasker/MacroDroid migrants, de-Googled/F-Droid users, local smart-home operators, and developers integrating Locale plugins, Termux, or Shizuku.
- Platforms and distribution: Android 8.0+ (`minSdk 26`), target/compile API 37, phone/tablet/foldable Compose UI, GitHub APKs, F-Droid metadata; no server account or cloud dependency.
- Integrations: Locale setting/condition broadcasts, Tasker XML/JSON bundles, Termux `RUN_COMMAND`, Shizuku allowlisted shell, framework sensors/events, HTTP/Ping/Wake-on-LAN, NFC (read + write), calendar/sun, widgets, QS tile, shortcuts, notifications, structured-data/date-time/text action packs.
- Data flow: Room entities feed UI and matchers; context sources activate profiles; `executeAndLogTask` hydrates durable globals, expands variables, and executes bounded actions on `Dispatchers.IO`; run traces return to Room/diagnostics; local bundles and password-encrypted DB backups cross device boundaries.

## Competitive Landscape

- **Tasker (6.5–6.8)** — Beyond the data-transform primitives OpenTasker now matches, recent releases add a Set-Keyboard/IME action + get-keyboard-info, an "Extra Triggers" external-app trigger API with referrer/extras variables, clipboard/text macro import, calendar-changed triggers and calendar query actions, unified Work-Profile/Private-Space control, and notification `ProgressStyle` milestones. Learn its local IPC trigger API and clipboard-import sharing; avoid opaque expert-only config and disguised-APK export.
- **MacroDroid** — Approachable authoring, typed variables, action blocks, and a cloud Template Store. Learn its progressive disclosure; deliver template sharing the local-first way (clipboard/QR/file), not a hosted store.
- **Automate (LlamaLab)** — Visual fibers, persistent atomic variables, companion/NFC blocks. Learn its durable-variable and debugging contracts.
- **PhoneProfilesPlus / Easer** — FOSS offline profile automation. Concrete open demand not yet tracked: temporary/auto-revert state activation, "last Bluetooth device disconnected" state, media-playback-active state, cell-tower "learn location" geofencing, and inverted (outside-geofence) conditions. Learn their conservative local posture; avoid their platform ceilings and manual-refresh failure modes.
- **vFlow / ShiroiKuma fork** — Module registry and bounded root-cause fixes (projects, scoped persistent variables, intent dispatch, source gating). Learn the module boundary and fail-closed fixes.
- **Home Assistant / Node-RED / Activepieces** — Traces, inject/debug, blueprints, immutable versions. Learn simulation and schema evolution; avoid cloud/team machinery.

## Security, Privacy, and Reliability

Prior P0s — re-verified **resolved** this pass (do not re-open): corrupt-payload fail-closed decoding (`TaskDao`/`ProfileDao`/`SceneDao`, commit `eebb2ef`); Room-`Flow` profile reconciliation (`AutomationService.observeProfileRegistry`, `96428f0`); durable globals hydrated/persisted in `TaskExecutionHelper` (`74dd240`); revocable Locale execution grants (`LocaleGrantStore`, `5218ee3`); repaired cleartext-LAN transport (`5bb4925`); off-main execution + debug StrictMode (`1f3213a`).

New this pass (file:line verified):

- **Verified — global-variable naming convention is inconsistent and can silently drop persisted values:** `PersistVariableAction` (`BuiltInActions.kt:197-201`) computes `globalName = args["global_name"] ?: name.replaceFirstChar { it.uppercase() }` then calls `variables.set(globalName, …)`; `VariableStore.set` (`VariableStore.kt:43-44`) only routes uppercase-first names to `globals`, so an explicit **lowercase** `global_name` is written to the transient local scope, never persisted, yet the action returns Success. Separately, `ActiveAutomationViewModel.updateVariable/deleteVariable` (`ActiveAutomationViewModel.kt:570-580`) hard-code `isGlobal = true` for any name, so a lowercase-named "global" hydrates at runtime but any task write to it never persists back. Root-cause: three call sites disagree on the uppercase=global rule.
- **Verified — concurrent global write-back is not transactional:** each `executeAndLogTask` builds its own `VariableStore`, hydrates a baseline, and writes back only its own diff (`TaskExecutionHelper.kt:31-45`); under `AutomationMode.PARALLEL` or manual-plus-profile overlap two runs read the same baseline and independently write diffs with no merge or row lock, so a global changed mid-flight by a sibling run is invisible and can be clobbered. The "last-write-wins" comment is not backed by a read→run→write transaction.
- **Verified — notification-button PendingIntent request codes collide:** `NotifyAction` (`BuiltInActions.kt:74-83`) derives the request code from `notifId.hashCode()*31+i`; `notifId` is an `Int` (its own hashCode), the id is user-controllable, and with `FLAG_UPDATE_CURRENT` a colliding code lets a newer notification silently overwrite an older notification's button intent, firing the wrong task.
- **Verified — scene SLIDER control is inert:** `SceneOverlayService` (`SceneOverlayService.kt:186-193`) builds a `SeekBar` with no `OnSeekBarChangeListener`, so a scene slider drives no task and surfaces no value, unlike BUTTON tap/long-press wiring.
- **Verified — Termux rate-limit map grows unbounded:** `ScriptActions.kt:78-90` keeps a process-lifetime `ConcurrentHashMap` keyed by the template-expanded (user-controlled) executable string and never prunes it, leaking memory across varying script paths.
- **Likely — FileActions symlink TOCTOU:** `safeUserFile` (`FileActions.kt:183-190`) canonicalizes and containment-checks the requested path, but `file.write`/`file.append` then `mkdirs()` and stream through path components that can be swapped for symlinks between check and I/O; open with `NOFOLLOW_LINKS` and re-verify containment at I/O time.
- **Likely — event-pulse continuity breaks across reconcile:** `ProfileMatcherImpl` (`:41-59,137-147`) seeds `pulseSequence` at 0 via `scan`; every `reloadProfiles` rebuilds the matcher and resets both the sequence and the compared baseline, so an event-context pulse in flight during an edit/reconcile can be dropped or a buffered `SharedFlow` replay double-fired.
- **Needs live validation — Advanced Protection Mode revokes automation capabilities:** Android 16 `AdvancedProtectionManager` (`QUERY_ADVANCED_PROTECTION_MODE`) revokes Accessibility from apps not declaring the "Accessibility Tool" use, directly degrading automation-class apps; OpenTasker has no detection or graceful-degradation path.
- **Needs live validation — `ACCESS_LOCAL_NETWORK` enforcement:** once enforced for target-37 LAN traffic, HTTP-to-private-IP, Ping, WoL, and any future MQTT/HA bridge `EPERM` without a granted permission; the runtime request + graceful-denial code path is not implemented (device evidence is separately tracked as blocked).
- **Verified — dependency advisory posture is clean** for the pinned Room/kotlinx-serialization/androidx set as of this pass; when OkHttp or any native-`.so` dependency (SQLCipher, MQTT) lands, re-audit for CVEs and 16 KB page-size alignment. Point-in-time; keep scanning.

## Architecture Assessment

- Centralize the uppercase=global variable rule in one normalizer used by `PersistVariableAction`, the Variables editor, and `VariableStore`; today three sites encode it differently and disagree.
- Make global hydrate→run→persist transactional (or serialize per-variable write-back) so PARALLEL and manual/profile overlap cannot clobber globals.
- Reference-count context-source monitors against the reconciled enabled-profile set; the accelerometer (`ShakeDetector`, `SENSOR_DELAY_UI`), Wi-Fi/connectivity callbacks, and AppOps camera/mic watchers currently start in `onCreate` for the whole service lifetime regardless of whether any enabled profile uses them (overlaps the tracked "start monitors only when needed" item; the accelerometer is the costliest offender).
- Give exported/forwarded intents a single sanitization boundary: enforce `FLAG_IMMUTABLE` + explicit components on every PendingIntent, adopt `intentMatchingFlags="enforceIntentFilter"` on exported receivers, and add `detectUnsafeIntentLaunch()` to the existing debug StrictMode before generalizing intent dispatch.
- Sub-task input variables are set into the parent scope before the child scope is pushed (`TaskRunner.kt:250-255`), so lowercase inputs persist into the parent task's later actions; push the child scope first.
- Stream `BackupEncryption` (already tracked) and, separately, evaluate SQLCipher for whole-DB encryption at rest — distinct from the tracked per-value secret variables.
- Target-36/37 platform obligations not yet addressed: 16 KB page-size alignment audit of any native `.so`, predictive-back (`OnBackInvokedCallback`) migration for scene/dialog dismissal, and health-permission split only if health triggers are ever added.

## Rejected Ideas

- **Cell-tower "learn location" geofencing (Easer #379)** — battery-friendly and requested, but `READ_PHONE_STATE`/cell-identity access is a privacy and permission cost that conflicts with the app's minimal-permission posture; Under Consideration, not Now.
- **Work Profile / Private Space toggle, mobile-hotspot toggle** — require device-policy/privileged access; keep behind the Shizuku track, not a core action.
- **Generic UWB/BLE Ranging proximity trigger** — genuinely new (Android 16 `RangingManager`) but needs UWB/channel-sounding hardware to build and verify; routed to blocked, not Now.
- **Health Connect triggers / health-permission migration** — no health feature exists; adding one drags in a dependency + privacy-policy activity for marginal fit.
- **Cloud template store / hosted sharing** — contradicts local-first; clipboard/QR/file import is the FOSS-aligned answer.
- **On-device OCR/ML, arbitrary accessibility scripting, LLM-generated automations, disguised-APK export** — unchanged from prior pass: F-Droid/size, policy, trust, and abuse-amplifier reasons stand.
- **Kotlin 2.4.0 / Navigation3 / Glance migration now** — evidence-led wait; no defect justifies the churn (tracked as evaluations).

## Sources

### Project, FOSS competitors, and community
- https://github.com/SysAdminDoc/OpenTasker
- https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban
- https://github.com/renyuneyun/Easer/issues
- https://github.com/henrichg/PhoneProfilesPlus/discussions/62
- https://github.com/ChaoMixian/vFlow

### Commercial and adjacent products
- https://tasker.joaoapps.com/changes/changes6.5.html
- https://tasker.joaoapps.com/changes/changes6.6.html
- https://tasker.joaoapps.com/userguide/en/help/ah_http_request.html
- https://www.macrodroid.com/
- https://templates.macrodroid.com/
- https://www.llamalab.com/automate/

### Android platform, reliability, and security
- https://developer.android.com/about/versions/15/features
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/privacy-and-security/advanced-protection-mode
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/privacy-and-security/risks/intent-redirection
- https://mas.owasp.org/MASTG/tests/android/MASVS-PLATFORM/MASTG-TEST-0030/
- https://mas.owasp.org/MASVS/06-MASVS-CRYPTO/
- https://developer.android.com/reference/android/os/StrictMode

### Distribution
- https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html
- https://android-developers.googleblog.com/2026/03/android-developer-verification.html

## Open Questions

- Should the uppercase=global rule be relaxed (allow explicit scope selection) or strictly enforced/validated in the editor and `var.persist` — i.e., is the convention or the UI the thing to change?
- Is SQLCipher acceptable on the F-Droid reproducible-build track (native `.so`, 16 KB alignment), or must whole-DB encryption stay off the F-Droid variant?
- For Advanced Protection Mode, which features degrade to a documented manual fallback vs. hide entirely when AAPM is on?
- Which signing identity/account owns `com.opentasker` for Android Developer Verification, and what is the in-app messaging when sideload/verification friction hits users in first-rollout regions?
