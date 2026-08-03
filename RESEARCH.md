# Research — OpenTasker

**Date:** 2026-08-02  
**Scope:** Repository, product documentation, tests and history, Android/platform changes, dependencies, security guidance, direct competitors, adjacent automation systems, community signals, and academic/engineering research. This file replaces the previous research synthesis.  
**Confidence:** **Verified** means checked in the current tree or a primary source; **Likely** means corroborated by several sources but still needing device/product validation; **Exploratory** means a candidate for discovery only.

## Executive Summary

OpenTasker is a serious local-first Android automation product, not a toy workflow editor. The current mainline at `1e702ba` has 235 production Kotlin files, 180 JVM test files, 15 instrumentation-test files, Room schema v10, Compose screens for nine major surfaces, explicit execution policies, bounded task evaluation, encrypted secret handling, external protocol v2, diagnostics, import/export, and F-Droid release metadata. The mainline includes global/per-profile execution admission, burst controls, a persisted circuit breaker, feedback-risk analysis, adaptive navigation coverage, and predictive-back handling.

The product’s defensible position is inspectable, offline automation with explicit capabilities, redacted evidence, and bounded execution. Tasker and MacroDroid win on breadth and onboarding; Automate and Node-RED demonstrate reusable flow and failure semantics; Home Assistant and n8n demonstrate run modes, execution history, and retries. None of those findings justify a cloud marketplace, unrestricted screen-driving agent, or arbitrary-code parity goal.

Highest-value next work, in priority order:

1. Finish the execution-safety contract: normalize every producer through one structured command/event ledger, carry one execution identity through admission, service, run log, external status, and Diagnostics, and make loop/storm decisions explainable and redacted.
2. Make release and capability truth machine-checkable across Gradle, manifest, README, CLAUDE, CHANGELOG, and F-Droid metadata. Current F-Droid metadata still pins an older commit, and CLAUDE contains historical version/capability claims.
3. Make Setup and Diagnostics freshness-aware and capability-derived so a user can distinguish Loading, Ready, Stale, and Error for each required subsystem.
4. Add adaptive large-screen/resize evidence and a Macrobenchmark/Baseline Profile decision; the mockup is a desktop-like command center, while current UI verification is mostly source/unit coverage.
5. Preserve the local-only boundary while improving safe authoring: global search, reusable local subflows, lossless imports, actionable failure catch/retry, and privacy-preserving Android APIs.

## Product Map

### Current product boundary (Verified)

- **Runtime:** one Kotlin/Compose Android app, `minSdk = 26`, compile/target API 37. The app manifest declares foreground-service types, boot recovery, notification listener, exact alarms, local network, Bluetooth/location, widgets, Quick Settings, Locale, Termux, and a signature-protected automation target.
- **Domain model:** profiles contain context predicates and tasks; actions include typed forms and engine-handled flow nodes; scenes, variables, run history, edit history, and external execution state persist in Room schema v10.
- **Execution:** `SINGLE`, `RESTART`, `QUEUED`, and `PARALLEL` policies feed a bounded `TaskRunner`. `ExecutionAdmissionController` now limits active work to 8 globally/2 per profile, burst starts to 32 globally/8 per profile in 10 seconds, and trips a 60-second persisted circuit after three strikes.
- **Authoring surfaces:** `ActiveAutomationUi` exposes Profiles, Tasks, Variables, Flow, Scenes, Inspector, Setup, Run Log, and Diagnostics. Primary navigation keeps Profiles/Tasks/Setup/Run Log visible and places secondary tools under More. `rememberSaveable` preserves editor state across common navigation changes.
- **Integrations:** Locale-compatible plugins, v2 external dispatch, Tasker XML staging through OpenTasker JSON, encrypted bundle import/export, widgets/shortcuts/Quick Settings, notifications, local HTTP, Shizuku capability discovery, and Termux command execution. Privileged paths fail closed when capability evidence is missing.
- **Distribution:** local release scripts verify the Gradle distribution/wrapper, dependency metadata, SBOM, and OSV results. F-Droid metadata and the release-truth manifest agree on v0.2.81/code 83 and its immutable artifact commit. There is no `.github/` hosted workflow in the current tree.

### Maturity and evidence (Verified/Likely)

- **Verified:** repository history through `1e702ba` is dominated by hardening, migration safety, diagnostics, locale, release-gate, execution, and adaptive-navigation changes rather than only UI churn.
- **Verified:** exported Room schemas and hand-written migrations cover versions 1–10; JSON payloads make explicit versioning and lossless migration important.
- **Verified:** source-level tests cover contracts, imports, redaction, execution policies, diagnostics, locale, and release truth. No current emulator/OEM run report is checked into the repository, so background behavior remains **Likely**, not verified.
- **Verified:** `design/mockups/opentasker-command-center-menu.png` depicts a polished Catppuccin/AMOLED command center with metrics, active execution, health, Setup, Flow, Scenes, and Inspector. Source screens cover the surfaces, but current Diagnostics does not yet expose every mockup metric (for example CPU/memory/uptime).
- **Verified:** root `README.md` and current metadata are aligned to v0.2.81; `CLAUDE.md` and files under `docs/research/` retain explicitly historical release snapshots.

### User segments and boundaries

- Privacy-conscious Android users who want useful automation without an account, analytics dependency, or cloud control plane.
- Tasker, MacroDroid, and Automate migrants who value familiar contexts/actions but want clearer state, safer defaults, and open distribution.
- Power users combining Termux, HTTP, variables, scenes, plugins, and flow control while expecting secrets and destructive actions to remain bounded.
- Maintainers and packagers who need reproducible builds, truthful metadata, migration evidence, and rollback-friendly releases.
- **Out of scope for the safe core:** unrestricted accessibility/screen-driving agents, hidden APIs, root-only assumptions, arbitrary Java/shell by default, and server-backed template sharing.

## Competitive Landscape

### Tasker — breadth and ecosystem benchmark

Tasker’s profile/context/task/action/scene mental model and extensive plugins remain the breadth benchmark. Its 6.5/6.6 change logs show continued platform integration, permission management, Shizuku, sunrise/sunset, and import improvements. The research signal is to make migration, search, privilege state, and external protocol excellent; it is not to copy arbitrary code or root-equivalent actions.

### MacroDroid — onboarding and local templates

MacroDroid’s trigger/action/constraint vocabulary and template store shorten time-to-first-success. Community comparisons consistently describe it as easier than Tasker. OpenTasker should copy progressive disclosure, concise forms, local examples, and visible active state, while retaining offline sharing and no commercial/account dependency.

### LlamaLab Automate — visual flow and failure identity

Automate documents fibers, variables, fork/join, and Catch Failure blocks. Its strongest lesson is explicit failure identity and recoverable control flow. OpenTasker should keep graph bounds and static cycle detection, then add bounded catch/retry/finally semantics rather than unbounded fork expressiveness.

### Samsung Modes and Routines — platform-native clarity

Samsung exposes conditions/actions, active mode state, recommendations, and searchable entry points. It validates a “what is active now?” surface and contextual permission prompts. It is not a fit for OpenTasker’s cross-device/open distribution goals, but its concise state presentation is a useful UI benchmark.

### PhoneProfilesPlus / Easer / KeyMapper — adjacent open-source lessons

PhoneProfilesPlus shows the durability of explicit profiles and a mature FOSS user base; Easer shows the cost of maintenance drift; KeyMapper demonstrates how accessibility, gestures, and intent macros expand policy and privacy risk. OpenTasker should keep explicit capabilities and fail-closed privilege boundaries rather than treat screen control as a core promise.

### Node-RED / Home Assistant / n8n — reliability patterns, not Android competitors

Node-RED’s subflows, context scopes, Catch/Status nodes, and explicit non-recursion; Home Assistant’s single/restart/queued/parallel automation modes; and n8n’s retry, continue-on-fail, execution history, and error workflows all point toward structured run identity, concurrency policy, and explainable terminal outcomes. These systems support the execution-ledger roadmap item, not a cloud/server rewrite.

### AI screen agents — rejected core direction

Mobile-use, DroidRun, AutoDroid, DroidBot-GPT, and VisionTasker show research momentum in GUI agents. They also introduce ambient data access, nondeterministic actions, accessibility/policy exposure, and difficult replay. They may be useful as an explicitly sandboxed experiment, but they are not a safe default automation primitive for OpenTasker.

## Security, Privacy, and Reliability

### Strengths already present

- **Secrets:** Keystore-backed secret variables, redaction in logs/previews, bounded encrypted bundle transfer, and explicit recovery boundaries align with OWASP MASVS storage guidance.
- **Execution:** admission leases are acquired before hydration, released idempotently, and logged as skips when capacity/circuit policy rejects work. The controller is persisted enough to survive process restarts.
- **Imports:** Tasker/OpenTasker/backup flows stage data for review, use schema/version checks, and protect against secret collisions and pathologically large payloads.
- **Supply chain:** Gradle wrapper bootstrap, dependency verification metadata, SBOM generation, and OSV querying are part of `tools/verify-local-release.ps1`. The wrapper distribution has a SHA-256 pin; the remaining risk is proving all bootstrap artifacts and release metadata are aligned.
- **Android privacy:** Android 17’s Contact Picker offers field-scoped grants, a better path than broad contacts permission for the planned contacts action. Advanced Protection callbacks and new background restrictions should be represented as capability evidence, not guessed state.

### Material residual risks

- **Execution feedback:** admission currently explains capacity/circuit decisions, but direct causal-chain metadata and one structured event model are not yet shared by every producer, external response, run log, and Diagnostics projection.
- **Freshness:** `EngineHealthReader` observes service heartbeat, standby bucket, foreground types, exact alarm state, matcher errors, WorkManager state, and pending JobScheduler reasons, but `DiagnosticsScreen` still reduces that to coarse healthy/not-healthy logic without subsystem age and remediation state.
- **Manifest surface:** many high-risk permissions and exported components are intentional but need capability-to-feature evidence, especially notification listener, overlays, exact alarms, SMS/phone placeholders, local network, and Termux.
- **Release truth:** `fdroid/metadata/com.opentasker.app.yml` and `tools/release-truth.json` agree on v0.2.81/code 83 and artifact commit `cd8f68c`; the release gate verifies the pin, version, capability counts, SDK levels, and schema versions together.
- **Documentation drift:** `CLAUDE.md`, archived research, and the roadmap contain historical counts/statuses. A contributor can follow a stale “planned” instruction even when code has shipped.
- **Device/OEM variance:** background restrictions, foreground-service policy, exact alarms, predictive back, large screens, 16 KB page compatibility, and vendor process killing require emulator and physical-device evidence; source tests cannot prove them.

### Security/reliability priorities

1. Keep all execution admission, retries, loop detection, and cancellations bounded and redacted.
2. Use one execution ID and structured terminal reason for manual, context, widget, shortcut, Quick Settings, Locale, Scene, notification, external, and worker producers.
3. Derive Setup requirements from enabled capabilities and show age/state/remediation in Diagnostics.
4. Validate 16 KB compatibility, predictive back, adaptive layouts, and Android 17 behavior under the local release gate.
5. Prefer platform-scoped grants (Contact Picker, Companion Device, App Links) over broad permissions or ambient accessibility.

## Architecture Assessment

### What is coherent

- The UI → engine → Room layering is clear in `docs/ARCHITECTURE.md`, and dependency direction is mostly explicit.
- `AutomationTargetContract.internalRunTaskIntent()` is now the single protocol-v2 builder. `AutomationTargetReceiver` rejects missing/old protocol versions, and Locale/Scene producers use the builder. The previous research claim that those producers bypassed protocol v2 is **fixed** by commit 961d400 and must not be reintroduced.
- Action metadata, typed forms, validation, redaction, execution controls, and diagnostics are moving toward one capability contract. Tests include source guards for protocol usage and migration/serialization behavior.
- Room schema exports and hand-written migrations make storage changes reviewable, while local release scripts provide a practical quality gate without relying on hosted CI.

### Where seams remain

- Producer adapters still converge through several manual paths before `AutomationService`; a structured normalized command/event ledger would make exactly-once, cancellation, retry, and replay properties testable across adapters.
- `RunLogDiagnostics` parses semi-structured Source/Decision/Reason text. This is useful for humans but fragile for machine projections, external status, and future analytics; retain redaction while moving to typed fields.
- `EngineHealthReader` has richer evidence than the UI displays. A subsystem state model should preserve timestamps, stop reasons, and remediation without claiming “healthy” from one boolean.
- The single-module build is productive now, but Room 3.0’s new `androidx.room3` package and KSP-only code generation make migration a deliberate breaking project, not a version bump.
- Compose, Kotlin, KSP, Room, WorkManager, and OkHttp are all moving quickly. The current pinned set is coherent; synchronized upgrade batches with compile/test/schema evidence are safer than piecemeal updates.
- No hosted CI is present. Local gates are strong, but release confidence depends on a repeatable maintainer workflow and artifacts that prove device, reproducibility, and metadata checks.

### Recommended architectural direction

Keep the local-first layered model. Add a typed execution envelope with producer, profile/task, execution ID, admission decision, causal parent, redaction-safe inputs, retry/circuit state, and terminal reason. Project that envelope into Run Log, external status, and Diagnostics. Generate a capability/release snapshot consumed by docs and metadata. Treat device validation and benchmark evidence as first-class release artifacts.

## Rejected Ideas

- **Cloud template marketplace or mandatory account:** conflicts with the strongest differentiator (offline ownership), expands privacy and moderation obligations, and competes poorly with TaskerNet/MacroDroid ecosystems.
- **Unrestricted AI/screen-driving automation:** nondeterministic, difficult to replay, accessibility-sensitive, and too privileged for the default engine. Keep any experiment sandboxed and opt-in.
- **Arbitrary Java/shell/root parity with Tasker:** increases attack surface and OEM breakage faster than it increases reliable user value. Prefer explicit Termux/Shizuku capability contracts.
- **Unbounded parallelism or recursive flow graphs:** adjacent systems support explicit modes and non-recursion for a reason; preserve admission, nesting, step, and duration limits.
- **Room 3 migration as a routine dependency bump:** Room 3.0’s package/codegen changes make this a dedicated migration project with schema and performance evidence.
- **Hosted CI as a prerequisite for every change:** local release gates are the current product constraint. Improve artifact evidence and add CI only when a maintainer chooses a hosting/trust model.
- **Broad contacts/notification/accessibility permissions by default:** use field-scoped or feature-scoped platform grants and show capability state instead.

## Sources

### Android and platform

- https://developer.android.com/about/versions/17/features
- https://developer.android.com/about/versions/17/summary
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/develop/ui/shortcuts
- https://developer.android.com/reference/android/app/NotificationManager#addAutomaticZenRule(android.app.AutomaticZenRule)
- https://developer.android.com/training/app-links/add-applinks
- https://developer.android.com/guide/components/intents-filters
- https://developer.android.com/topic/architecture
- https://developer.android.com/privacy-and-security/advanced-protection-mode
- https://developer.android.com/about/versions/17/features/contact-picker

### AndroidX, Kotlin, and build security

- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/jetpack/androidx/releases/room3
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/develop/ui/compose/bom
- https://kotlinlang.org/docs/whatsnew24.html
- https://github.com/google/ksp/releases/tag/2.3.10
- https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0
- https://github.com/square/okhttp/releases
- https://docs.gradle.org/current/userguide/gradle_wrapper.html
- https://docs.gradle.org/current/userguide/dependency_verification.html
- https://google.github.io/osv.dev/api/

### Security, packaging, and reproducibility

- https://mas.owasp.org/MASTG/knowledge/android/MASVS-STORAGE/MASTG-KNOW-0047/
- https://mas.owasp.org/MASTG/0x05d-Testing-Data-Storage/
- https://mas.owasp.org/checklists/MASVS-STORAGE/
- https://f-droid.org/docs/Build_Metadata_Reference/
- https://f-droid.org/docs/Reproducible_Builds/
- https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/

### Competitors and adjacent systems

- https://tasker.joaoapps.com/userguide/en/index.html
- https://tasker.joaoapps.com/changes/changes6.6.html
- https://www.macrodroid.com/
- https://templates.macrodroid.com/
- https://www.llamalab.com/automate/doc/index.html
- https://llamalab.com/automate/doc/block/failure_catch.html
- https://llamalab.com/automate/doc/block/fork.html
- https://www.samsung.com/my/support/mobile-devices/how-to-use-routines-on-your-samsung-galaxy-device/
- https://github.com/henrichg/PhoneProfilesPlus
- https://github.com/renyuneyun/Easer
- https://github.com/keymapperorg/KeyMapper
- https://nodered.org/docs/developing-flows/flow-structure
- https://nodered.org/docs/user-guide/handling-errors
- https://nodered.org/docs/user-guide/runtime/logging
- https://www.home-assistant.io/docs/automation/modes/
- https://www.home-assistant.io/docs/scripts
- https://n8n-io-n8n.mintlify.app/workflows/error-handling
- https://www.mintlify.com/n8n-io/n8n/api/executions
- https://github.com/UnifiedPush/android-connector
- https://github.com/binwiederhier/ntfy

### Community, lists, and research

- https://github.com/guifelix/awesome-tasker
- https://github.com/dariubs/awesome-workflow-automation
- https://github.com/androiddevnotes/awesome-android-kotlin-apps
- https://www.reddit.com/r/androidapps/comments/1glnrt9
- https://www.reddit.com/r/fossdroid/comments/1h41kxl
- https://github.com/henrichg/PhoneProfilesPlus/issues
- https://arxiv.org/abs/2408.04755
- https://arxiv.org/abs/2308.15272
- https://arxiv.org/abs/2111.11562
- https://arxiv.org/abs/1802.01790

## Open Questions

- **Device evidence:** Which API 35–37 devices and OEM battery policies are release-blocking for foreground services, exact alarms, notification listeners, overlays, and background receivers?
- **Execution contract:** Which producer adapters still need an end-to-end test from source event through admission, service, terminal run log, external response, and Diagnostics?
- **Capability UX:** What is the minimum evidence and remediation copy that makes Setup actionable without asking for permissions that no enabled automation needs?
- **Large screens:** Which pane, navigation, and editor state transitions fail at 600 dp, 840 dp, fold/unfold, rotation, and font-scale extremes?
- **Performance:** Do cold start, first navigation, run-log pagination, and flow editing need a Baseline Profile, and what budget is acceptable on API 35+ devices?
- **Release truth:** Should the generated capability/release snapshot be a checked-in artifact, a build output, or both, and who owns updating F-Droid’s immutable commit pin?
- **Storage:** What is the migration and benchmark plan if Room 3 becomes desirable, given the new package and KSP-only code generation?
- **Community:** Which local bundle/template workflow gives users safe sharing without introducing account, moderation, or remote-secret risks?
