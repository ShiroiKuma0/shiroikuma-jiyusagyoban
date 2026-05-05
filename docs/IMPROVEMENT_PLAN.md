# OpenTasker Improvement Plan

Last updated: 2026-05-05

This plan tracks the quality, security, UX, architecture, and release-readiness work identified during the May 2026 hardening pass. Status values are:

- Not started: scoped but no code landed.
- Started: initial implementation or cleanup has landed, with more work remaining.
- Blocked: needs a product decision, platform validation, or external setup.
- Done: implemented with build/test/lint verification.

## Current Verification Gate

Every completed implementation slice should pass:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
git diff --check
```

## Priority Plan

| ID | Area | Priority | Status | Goal | Exit criteria |
|---|---|---:|---|---|---|
| P1 | External automation security | Critical | Done | Prevent arbitrary third-party apps from running tasks or toggling profiles just by declaring OpenTasker's normal permission. | External callers require explicit user trust, a stronger permission model, or a signed/allowlisted integration path; tests cover accepted and rejected requests. |
| P2 | UI architecture split | High | Not started | Split the active Compose automation UI into maintainable screen, editor, state, and component modules. | `ActiveAutomationUi.kt` is reduced to routing/composition glue; major screens have focused files and targeted previews/tests where practical. |
| P3 | Stale UI snapshot cleanup | High | Done | Remove unused `.kt.bak` screens from active source packages so the canonical UI surface is unambiguous. | Historical snapshots live outside `app/src/main/java`; the app compiles without stale screen files in source navigation. |
| P4 | Lint debt reduction | High | Started | Reduce warning noise so lint can stay useful and CI can eventually fail on real issues. | Known local warnings are either fixed, narrowed, or intentionally documented; no broad suppressions are added for fixable issues. |
| P5 | Dependency modernization | High | Started | Upgrade AndroidX, Room, WorkManager, DataStore, Coroutines, Hilt, Navigation, Compose, and Gson in controlled groups. | Dependency versions are cataloged centrally; updates land in small batches with compile, unit, assemble, lint, release/F-Droid profile builds, and at least one emulator smoke pass for app startup/navigation. |
| P6 | Emulator and instrumented reliability tests | High | Started | Cover Android platform behavior that JVM tests cannot verify. | Emulator tests cover setup checklist, profile enable/disable, task execution, run logs, permission-denied flows, and service startup. |
| P7 | Release pipeline hardening | High | Not started | Make CI and release outputs production-grade. | Normal CI runs lint; release workflow validates signing, publishes checksums, uploads artifacts, and produces scoped release notes. |
| P8 | Run-log/debug UX | Medium | Started | Make "why did this run or not run?" answerable from the app. | Run logs support filters, source trigger, cooldown/retry decisions, sanitized expanded args, and capability/permission failure reasons. |
| P9 | Room migration tests | Medium | Started | Protect user data as schemas evolve. | Migration tests cover all exported schema versions for both Room databases and backup/restore compatibility. |
| P10 | Hilt vs non-Hilt architecture | Medium | Not started | Resolve dormant DI drift and keep app startup deterministic. | Either Hilt migration is completed and tested, or unused Hilt surface is removed until needed. |
| P11 | Action safety boundaries | Medium | Not started | Apply consistent guardrails to shell, intent, file, SMS, settings, and privileged actions. | Destructive and privileged actions have capability checks, permission explanations, scoped-storage behavior, and clear unsupported failures. |
| P12 | User-facing text centralization | Low | Not started | Improve accessibility, localization readiness, and copy consistency. | Stable labels, errors, empty states, and permission explanations move to string resources where practical. |
| P13 | Template-first creation | High | Started | Compete with MacroDroid and Home Assistant by reducing blank-canvas setup friction through starter automations. | Starter profiles cover WiFi arrival, low battery, app opened, bedtime, work mode, notification action, and SMS safety examples with setup requirements shown before install. |
| P14 | Visual rule summaries | High | Started | Make profiles readable at a glance without opening every editor. | Each profile renders a compact sentence or graph explaining triggers, conditions, actions, inversions, cooldown, and re-trigger behavior. |
| P15 | Guided builder flow | High | Not started | Offer a MacroDroid-style Trigger -> Conditions -> Actions -> Review creation path while keeping advanced editing available. | New users can create a complete disabled-for-review automation without understanding internal profile/task/context vocabulary first. |
| P16 | Execution trace depth | High | Started | Move toward Automate/n8n-style step traces for every run. | Run details show step status, duration, input summary, output summary, expanded variables, skip/retry/cooldown decisions, and failure reason. |
| P17 | Blueprint/share system | Medium | Started | Turn JSON bundles into a polished import/export and sharing experience. | Bundle preview validates required permissions, risky actions, variables, tasks, contexts, and schema compatibility before import. |
| P18 | Tasker/Locale compatibility | Medium | Started | Make migration from Tasker credible without weakening security. | Tasker project import groundwork, Locale host/target docs, and same-signer or user-approved external-intent integration paths are documented and tested. |
| P19 | Platform reliability dashboard | High | Started | Show whether Android platform constraints will let automations run reliably. | Setup displays live service health, exact alarm state, battery optimization, usage access, notification access, location/background location, OEM risk, and blocked automation families. |

## Execution Sequence

1. Finish P3 and the low-risk parts of P4 so the source tree and lint output are easier to trust.
2. Design P1's external automation trust model before changing compatibility-sensitive behavior.
3. Add P9 migration tests before further persistence changes.
4. Split P2 screen-by-screen, starting with run logs and setup because they are high-value debugging surfaces.
5. Add P6 emulator smoke tests around the screens and services touched by P2.
6. Modernize dependencies in P5 only after the test net is stronger.
7. Promote lint into CI under P7 once P4 warning noise is low enough.
8. Build P8/P16 run-log diagnostics before adding large new automation surface area.
9. Expand P13 templates and P15 guided creation after diagnostics can explain failures clearly.
10. Start P14 visual summaries with read-only profile summaries before building a visual flow editor.

## Research-Backed Product Direction

OpenTasker should not compete by feature count alone. The stronger position is private, open-source, on-device Android automation with Tasker-compatible power, MacroDroid-like clarity, Automate-like explainability, and n8n/Home-Assistant-like debugging and templates.

| Reference product | Lesson for OpenTasker | Tracked items |
|---|---|---|
| Tasker | Power users expect profiles, tasks, variables, scenes, plugins, imports, and advanced control flow. | P14, P16, P18 |
| MacroDroid | The trigger/action/constraint creation model lowers setup friction. | P13, P15, P19 |
| Automate | Visual flows and execution traces make complex automations easier to reason about. | P14, P16 |
| IFTTT | Discovery and integrations matter, but OpenTasker should avoid cloud dependency. | P13, P17, P18 |
| Home Assistant | Blueprints reduce blank-canvas friction and make sharing safer. | P13, P17 |
| n8n and Node-RED | Execution history, debug sidebars, and error handling are product features, not developer extras. | P8, P16 |
| Android platform | Exact alarms, foreground services, background restrictions, and permission gates are core product constraints. | P6, P11, P19 |

## Notes

- External automation is the highest-risk product surface because it can trigger user-defined actions. Compatibility with legitimate automation integrations must be preserved deliberately, not assumed.
- External automation is now signature-scoped at the manifest permission level, with a regression test covering the permission and exported receiver contract. Future broader third-party compatibility should use an explicit user trust model rather than reverting to a normal permission.
- The active UI is functional but too concentrated. Refactors should keep the current user workflows recognizable while reducing file size and state coupling.
- Lint dependency warnings should be handled in grouped upgrade branches rather than one large version jump.
- Initial lint cleanup reduced `lintDebug` from 31 warnings to 22 warnings. The remaining warnings are dependency/target-SDK modernization items and obsolete Navigation lint checks that should be handled under P5.
- Instrumented migration-test scaffolding exists for `AppDatabase` 1->2 and `AutomationDatabase` version 1, and the verification gate now compiles androidTest sources. Full completion of P9 still needs emulator execution in CI or a local Android target.
- The first dependency modernization batch updates AndroidX test libraries to support migration-test coverage. Room remains on 2.6.1 until the Kotlin serialization/runtime stack is upgraded together.
- A local `connectedDebugAndroidTest` attempt found device `R5CY34G070L`, but Android rejected debug APK installation with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because an existing `com.opentasker.app` install is signed differently. Do not clear that install without explicit user approval.
- Run-log diagnostics are now more connected: users can filter all/succeeded/failed/skipped entries, narrow history by task, search by task name or message, see structured source/action trace details, and review cooldown or single-mode skip decisions from the app.
- The X6 context inspector baseline now shows registered source health, latest observed context values, setup state, source errors, and per-profile match/blocking explanations. P19 remains Started because broader OEM risk and service-health diagnostics still need expansion.
- The X7 notification listener baseline now emits `event=notification` through the Event source with package allowlists, title/body filters, bounded regex matching, and redacted Android logging. Remaining notification hardening should focus on richer UI presets and privacy review before export/share flows.
- The X8 NFC trigger baseline now emits `event=nfc` through the Event source from tag/tech/NDEF discovery intents, supports normalized tag ID filters, and promotes the nightstand NFC template to setup-required installation. Remaining NFC polish should focus on an optional write-helper flow and device-level smoke coverage.
- The X9 calendar/sun baseline now emits redacted `event=calendar` metadata from local CalendarProvider windows and evaluates `sunrise`/`sunset` Event filters from configured coordinates, offsets, and windows. Remaining calendar/sun work should focus on device-level verification, richer presets, and privacy review before export/share defaults.
- The X10 Tasker XML import baseline now parses common Tasker task/profile/variable XML into OpenTasker bundles with mapped-action and unsupported-action reporting. Remaining migration work should add a file picker, preview UI, and explicit confirmation before writing imported entities to Room.
- The X11 F-Droid readiness baseline now has a property-based `fdroid` build profile, pinned build tools, proprietary dependency-family verification, CI coverage, readiness docs, and draft fdroiddata metadata. Remaining distribution work should use fdroidserver lint/build and reproducible binary comparison against a signed upstream release.
- The X12 dependency modernization baseline now centralizes Gradle plugin/library versions in `gradle/libs.versions.toml` and documents a staged upgrade plan. Remaining modernization work should bump versions only in small related batches with the full debug, release, lint, and F-Droid verification gate.
- The L1 visual flow baseline now has a pure graph builder and optional read-only Flow tab for profile/context/task/action structure. Remaining L1 work should add node deep links and condition/branch visualization before any drag/drop editor.
- The L2 scene baseline now exposes Room-backed scene shells in the active UI, validates scene geometry/task bindings, and reports overlay permission readiness. Remaining scene work should add element editing and Android overlay runtime policy checks before launching overlays.
- The L3 Shizuku readiness baseline now detects Shizuku manager package visibility, exposes optional setup status, and annotates elevated-action candidates while keeping privileged actions blocked. Remaining L3 work should add a reviewed API dependency, explicit user opt-in, permission request handling, backend isolation, and run-log audit entries before any elevated execution.
- The L4 Termux script readiness baseline now detects Termux and Termux:Tasker package visibility, exposes optional setup status, and adds a gated `script.termux.run` action with an explicit runtime failure. Remaining L4 work should add `RUN_COMMAND` permission onboarding, script allowlisting, Termux dispatch, stdout/stderr/exit-code capture, output variable mapping, and audit logging before enabling user scripts.
- The L5 profile sharing baseline now creates offline bundle share manifests with stable slugs, unverified trust state, capability/import safety findings, and GitHub Discussions submission markdown. Remaining L5 work should add share preview UI, screenshot handling, local import review, copy/share helpers, and signed-template metadata after a real review workflow exists.
- The L6 FOSS geofence baseline now centralizes Haversine radius checks, optional accuracy gating, and dwell-time evaluation in `FossGeofenceEvaluator`, wires active Location contexts through it, registers a platform `LocationManager` source with fail-closed setup events, persists inside-since state per profile/context/config before matching, and surfaces dwell state in Context Inspector profile rows. Remaining L6 work should add battery tuning, expanded policy disclosures, stale-key cleanup, and device-level background-location verification before public geofence claims.
- The L7 template expression baseline now has a pure bounded `{{ ... }}` engine wired into action argument and condition expansion after legacy `%var` expansion, with task/event/global scope precedence, arrays, JSON path reads, string/math functions, sanitized expanded-argument summaries, warning counts, persisted per-expression run-log diagnostics, fail-closed condition warnings, and an explicit policy that regex-like functions remain unsupported in templates while legacy `%var(regex:...)` stays bounded separately.
