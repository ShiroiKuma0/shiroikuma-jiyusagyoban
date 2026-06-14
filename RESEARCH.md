# Research — OpenTasker

Updated: 2026-06-14 (exhaustive research pass; supersedes 2026-06-12)

## Executive Summary

OpenTasker is a Kotlin/Compose Android automation engine at source v0.2.60 with a mature
profile → context → task → action pipeline, 48 registered actions plus engine-handled flow
control (`if/else/endif`, `for each`, `task.run`, `stop`), FOSS geofencing, a Locale/Tasker
plugin host, Tasker XML import, JSON bundle transfer, OEM battery-killer guidance, and
F-Droid/Play readiness tooling. Its strongest current shape remains engine *correctness*:
every P0/P1 bug from the 2026-06-12 pass is now fixed in code (verified below), and the
codebase carries zero TODO/FIXME/STUB markers.

The frontier has therefore moved off "make the actions honest" and onto three fronts:
**(1) platform deadlines** — Play requires `targetSdk 36` by **Aug 31, 2026** and the app
still targets 35; Android 17 (beta now) silently breaks background TTS/sound unless gated
behind a while-in-use foreground service, and OpenTasker shipped TTS/sound actions this
cycle. **(2) trust gaps the engine work skipped** — screen-reader accessibility is poor
(~16 icon-only buttons with `contentDescription = null`, no `Role`/`stateDescription`),
the 10 action-implementation classes have *zero* unit tests, and config-change/process-death
state is lost everywhere (0 `rememberSaveable`, 98 `remember`). **(3) the competitive
ground shifting under it** — Tasker, MacroDroid, and the fast-moving FOSS entrant vFlow all
shipped AI natural-language authoring and Shizuku execution in 2026; "powerful + modern +
FOSS + user-friendly" is still an unmet market need OpenTasker is closest to filling.

Top opportunities in priority order:

1. Commit the `targetSdk 35 → 36` jump (Play hard deadline Aug 31, 2026) — N1/N10 already scope the rehearsal; it must now ship, not rehearse.
2. Gate background TTS/sound actions behind while-in-use FGS or fail honestly (Android 17 silent-failure risk on newly-shipped audio actions).
3. Screen-reader/TalkBack accessibility pass (icon-only `contentDescription`, `Role`, `stateDescription`, live regions) — table-stakes trust gap, untouched by the engine work.
4. Unit tests for the 10 action-implementation classes (Network/File/Settings/Media/System/App/etc.) — the most-exercised, least-tested code.
5. Config-change & process-death state retention (`rememberSaveable` for editor drafts, dialogs, filters).
6. Quick parity wins Tasker migrants vote for: Wake-on-LAN action, date/time format template function, passive (battery-friendly) location listener.
7. Network Security Config to scope LAN cleartext opt-in (Android 17 deprecates `usesCleartextTraffic`).
8. Strategic: evaluate optional BYO-key AI profile authoring (already Under Consideration; competitive shift now makes it the single biggest feature gap).

## Product Map

- **Core workflows:** create profile with context trigger(s) → attach enter/exit tasks → configure actions with `{{ }}` template args → monitor via run logs + context inspector → export/import/share JSON or Tasker XML bundles.
- **User personas:** privacy-conscious Android power users, Tasker migrants wanting FOSS, F-Droid users, home-automation hobbyists bridging phone to HA/Node-RED/MQTT.
- **Platforms:** Android 8.0+ (API 26), compileSdk 36, **targetSdk 35** (must reach 36 before Aug 31 2026). Phone-first; large-screen/TV is the top community platform request and is untouched.
- **Distribution:** GitHub release APKs, F-Droid readiness tooling, Play policy gates (SMS omission, manifest-policy check). Standard/F-Droid/Play feature-split mirrors competitors.
- **Key integrations:** Locale/Tasker plugin host (setting + condition), signature-scoped external-intent API, Tasker XML import, NFC read/write, CalendarProvider, platform LocationManager, OEM battery-killer DB.

## Competitive Landscape

### vFlow (ChaoMixian/vFlow) — the modern entrant to watch
- **Does well:** ~1.1k stars, shipping multiple releases/month (v1.5.2 May 2026). Typed "magic variables" data-flow (module outputs are typed and pipe into later steps — structurally more modern than string globals), Module Registry, visual block editor, a Master-Worker core (`vFlowCore.dex` over a local socket) with Shell + Root/Shizuku worker paths, and a **native AI Agent flow** (v1.5.0). 2026 added OCR, crypto/hash, XML parse, clipboard-change + screen + app-switch triggers.
- **Learn:** typed data-flow piping; the privilege-worker separation; AI authoring.
- **Avoid / OpenTasker's opening:** Chinese-first docs, **no F-Droid listing**, no Tasker XML import, no Locale/Tasker plugin host. OpenTasker wins on English-first + F-Droid + migration + plugin interop — ship those before vFlow closes them.

### Tasker (commercial, joaoapps.com)
- **Does well:** the reference (400+ actions, AutoApps plugin ecosystem, Taskernet sharing). 2026: **Shizuku** integration, BeanShell, native sunrise/sunset, and **AI profile/widget generation** (Gemini + OpenRouter, BYO-key, persistent refine, round-trips its "Exported Descriptions" text format).
- **Learn:** BYO-key AI authoring that parses the app's own export format both ways; Shizuku as the accessibility-revocation escape hatch.
- **Avoid:** UI universally criticized as clunky; 15 years of accreted complexity.

### MacroDroid (commercial, freemium)
- **Does well:** simpler UX, broad coverage, **AI Macro Builder** (2026), and privacy-automation primitives OpenTasker lacks: **Camera-In-Use trigger/constraint** and **Microphone-Recording constraint**.
- **Learn:** camera/mic-in-use as triggers (AppOps active-op watcher — FOSS-friendly, no cloud); these are differentiated privacy automations.
- **Avoid:** 5-macro free cap; proprietary format.

### Automation by Jens (`com.jens.automation2`, GPLv3) — closest live FOSS analog
- **Does well:** actively maintained (v1.8.7, Mar 2026), on F-Droid, broad trigger/action set (location, charging, USB, WiFi/BT, NFC, calls, calendar; HTTP, media, TTS, scripts).
- **Learn:** trigger/action breadth is the FOSS baseline OpenTasker must clearly beat.
- **Avoid:** no modern UI (no Compose/M3), no plugin system, no Tasker import, no community sharing — exactly OpenTasker's edges.

### Automate (LlamaLab, freemium)
- **Does well:** 400+ flowchart blocks, mature visual model; 2026 added Content/Database query blocks and a bigint type.
- **Learn:** flowchart authoring depth (already scoped as X2).
- **Avoid:** ~30-block free cap; flow-only model overwhelms beginners (keep the list editor primary).

### Easer / LibreTasks — dead references
- Easer discontinued; LibreTasks last shipped 2018. Confirm OpenTasker is not benchmarking against abandoned UX.

## Security, Privacy, and Reliability

### Prior bugs — all VERIFIED FIXED in code (2026-06-14 audit)
- **ScreenTimeout unit conversion** — fixed; `SettingsActions.kt:326` writes `ms.toInt()` (millis) directly, logs `ms / 1000`s.
- **Notification ID overflow** — fixed; `BuiltInActions.kt:56` uses an `AtomicInteger(10_000)` counter, not `currentTimeMillis().toInt()`.
- **OpenUrl scheme injection** — fixed; `AppActions.kt:85-103` enforces `ALLOWED_SCHEMES = {https,http,tel,mailto,geo}` before `ACTION_VIEW`.
- **file.read / HTTP / download unbounded** — fixed; `FileActions.kt:25` (1 MB cap), `NetworkActions.kt:44,90,168,186-187` (`MAX_RESPONSE_BYTES` 1 MB, `MAX_DOWNLOAD_BYTES` 50 MB, bounded stream copies).
- **TaskRunner timeouts** — fixed; `TaskRunner.kt:212-218,348-352` wraps every action in `withTimeout` (default 60 s; network 120 s; `flow.wait` 30 min cap also enforced in `BuiltInActions.kt:232`).
- **Deprecated SMS/BT APIs** — migrated; `AppActions.kt:129` and `SettingsActions.kt:68` use `getSystemService(...)` on API 31+.

### Open gaps (this pass)
- **Accessibility / screen reader (Verified):** ~46 `contentDescription` uses but ~16 icon-only `IconButton`s pass `contentDescription = null` (`ActiveAutomationUi.kt:1556,1561,1568,1680,1685,1693,1698,1704` and the add/nav icons), no `Role`/`stateDescription` outside `PremiumComponents.kt`. TalkBack users cannot identify action/context row controls. Material3 IconButton gives implicit 48 dp targets, so touch size is the lesser concern.
- **Zero action-implementation tests (Verified):** no `app/src/test/java/com/opentasker/core/actions/` directory. NetworkActions, FileActions, SettingsActions, MediaActions, SystemActions, AppActions, BuiltInActions, ScriptActions, ImportActions, LocalePluginActions have no unit coverage — the byte-cap, scheme-allowlist, and timeout guards above are themselves untested.
- **State loss on config change / process death (Verified):** 0 `rememberSaveable` vs 98 `remember` across `ui/`. Editor drafts, dialog state, and filter selections are lost on rotation, multi-window resize, or background process death.
- **Android 17 background audio (Needs live validation):** TTS/sound actions (`MediaActions.kt`, `BuiltInActions.kt`) play from `AutomationService` (`foregroundServiceType="specialUse|location"`). Under Android 17, background audio requires a while-in-use FGS capability; the `location` subtype grants WIU only while location is actively in use. Background TTS/sound may fail *silently* when no location context is active. Must verify on Android 17 and gate or fail-honest.
- **Cleartext / LAN HTTP (Needs live validation):** no `usesCleartextTraffic` or `networkSecurityConfig` in the manifest, yet a LAN/private-host HTTP opt-in exists. Android 17 deprecates `usesCleartextTraffic`; a scoped Network Security Config is the forward-compatible way to permit LAN cleartext without opening all hosts.

### Platform policy (now in effect / imminent)
- **targetSdk 36 required for Play by Aug 31, 2026** (Verified). The app is on 35. Forces edge-to-edge defaults and predictive-back (predictive back already shipped). Single highest platform deadline.
- **May 15, 2026 enforcement already live** (Verified): geofencing removed as an approved FGS use case; Contacts must use the system picker. OpenTasker declares `specialUse|location` (not a geofencing-FGS justification) and ships no contacts action, so it is compliant — but Play declarations must avoid the word "geofencing," and `specialUse` still requires a Console justification + demo video.
- **SMS/call-log, USE_EXACT_ALARM, accessibility** unchanged and still restrictive — current gating (Play SMS omission, `SCHEDULE_EXACT_ALARM`, no accessibility dependency) remains correct.

### Recovery / rollback
- DB backup/restore is solid (integrity check, WAL checkpoint, staged restore, rollback). Encryption is still open (roadmap RD7). No undo/version history beyond the recently added edit-snapshot undo.

## Architecture Assessment
- **`ActiveAutomationUi.kt` (~2.9k lines)** still bundles ViewModel, router, screens, dialogs, and import review. Splitting it remains the prerequisite for adding accessibility semantics cleanly and for any new editor surface (roadmap P2 split plan).
- **Action layer is the test desert:** every other package (engine, contexts, plugins, transfer, storage, capabilities) has JVM tests; the action implementations — which hold the security guards — have none.
- **Design system is decorative:** `DesignSystem.kt` defines spacing/radius/elevation scales but screens hardcode dp (tracked in audit follow-ups).
- **Template engine** has an `applyFunction` dispatcher (`TemplateExpressionEngine.kt:373`) but no date/time formatting function — a concrete Tasker-import parity gap (`getDateFormatted` is a top migrant request).

## Rejected Ideas

| Idea | Reason | Source |
|---|---|---|
| Clipboard-change trigger | Android 10+ blocks background clipboard reads; usable only while foreground/IME — too constrained to ship as a reliable trigger | vFlow has it; Android clipboard-access docs |
| MMS / group-message actions | Locked to the default SMS-handler app by Play policy; OpenTasker deliberately omits SMS on Play | Play SMS/Call-Log policy |
| Native AutoInput-style accessibility "interact with any app" | Play AccessibilityService policy + Android 14+/APM auto-revocation make it fragile and policy-hostile as a core feature | Play accessibility policy, community |
| Cloud-required backend / mandatory AI | Violates on-device/no-telemetry positioning; AI must stay opt-in BYO-key | F-Droid policy, project philosophy |
| Google Fit triggers | Fit APIs sunset end-2026; use Health Connect (already X5) | Health Connect migration docs |
| Wear OS / TV as a near-term tier | Real demand (TV is #1 Tasker vote) but phone reliability (N2) comes first; keep Under Consideration | Tasker vote board |

## Sources

### Competitor / FOSS
- https://github.com/ChaoMixian/vFlow
- https://github.com/ChaoMixian/vFlow/releases
- https://f-droid.org/packages/com.jens.automation2/
- https://llamalab.com/automate/
- https://www.macrodroid.com/
- https://tasker.helprace.com/s1-general/ideas/accepted
- https://lemmy.world/post/2616306

### Android platform / policy
- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/blog/posts/the-first-beta-of-android-17
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://support.google.com/googleplay/android-developer/answer/13392821
- https://developer.android.com/jetpack/androidx/releases/glance
- https://developer.android.com/health-and-fitness/health-connect/architecture

### Integration / paradigm
- https://docs.ntfy.sh/integrations/
- https://f-droid.org/en/2022/12/18/unifiedpush.html
- https://www.home-assistant.io/integrations/mcp/
- https://github.com/mobile-next/mobile-mcp
- https://www.pulsemcp.com/servers/dceluis-tasker
- https://github.com/guifelix/awesome-tasker

## Open Questions
1. **Android 17 background audio under `specialUse|location`:** does the `location` subtype's while-in-use grant cover TTS/sound when no location context is active, or must the service add a media/`mediaPlayback` FGS path? Blocks correct gating of the new audio actions. (Needs Android 17 device test.)
2. **`specialUse` Play review longevity:** does the current declaration survive Console review post-geofencing-removal, or does it need reframing + a fresh demo video before the Aug 2026 targetSdk-36 submission?
3. **AI authoring fit:** can an optional BYO-key NL→profile generator that parses OpenTasker's own JSON export format be added without compromising the on-device/no-telemetry promise (all inference user-initiated, key user-supplied, nothing auto-sent)? This decides whether the biggest competitive gap is closable within the project's philosophy.
