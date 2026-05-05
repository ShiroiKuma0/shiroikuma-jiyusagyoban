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
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
git diff --check
```

## Priority Plan

| ID | Area | Priority | Status | Goal | Exit criteria |
|---|---|---:|---|---|---|
| P1 | External automation security | Critical | Not started | Prevent arbitrary third-party apps from running tasks or toggling profiles just by declaring OpenTasker's normal permission. | External callers require explicit user trust, a stronger permission model, or a signed/allowlisted integration path; tests cover accepted and rejected requests. |
| P2 | UI architecture split | High | Not started | Split the active Compose automation UI into maintainable screen, editor, state, and component modules. | `ActiveAutomationUi.kt` is reduced to routing/composition glue; major screens have focused files and targeted previews/tests where practical. |
| P3 | Stale UI snapshot cleanup | High | Done | Remove unused `.kt.bak` screens from active source packages so the canonical UI surface is unambiguous. | Historical snapshots live outside `app/src/main/java`; the app compiles without stale screen files in source navigation. |
| P4 | Lint debt reduction | High | Started | Reduce warning noise so lint can stay useful and CI can eventually fail on real issues. | Known local warnings are either fixed, narrowed, or intentionally documented; no broad suppressions are added for fixable issues. |
| P5 | Dependency modernization | High | Not started | Upgrade AndroidX, Room, WorkManager, DataStore, Coroutines, Hilt, Navigation, Compose, and Gson in controlled groups. | Dependency updates land in small batches with compile, unit, assemble, lint, and at least one emulator smoke pass for app startup/navigation. |
| P6 | Emulator and instrumented reliability tests | High | Not started | Cover Android platform behavior that JVM tests cannot verify. | Emulator tests cover setup checklist, profile enable/disable, task execution, run logs, permission-denied flows, and service startup. |
| P7 | Release pipeline hardening | High | Not started | Make CI and release outputs production-grade. | Normal CI runs lint; release workflow validates signing, publishes checksums, uploads artifacts, and produces scoped release notes. |
| P8 | Run-log/debug UX | Medium | Not started | Make "why did this run or not run?" answerable from the app. | Run logs support filters, source trigger, cooldown/retry decisions, sanitized expanded args, and capability/permission failure reasons. |
| P9 | Room migration tests | Medium | Not started | Protect user data as schemas evolve. | Migration tests cover all exported schema versions for both Room databases and backup/restore compatibility. |
| P10 | Hilt vs non-Hilt architecture | Medium | Not started | Resolve dormant DI drift and keep app startup deterministic. | Either Hilt migration is completed and tested, or unused Hilt surface is removed until needed. |
| P11 | Action safety boundaries | Medium | Not started | Apply consistent guardrails to shell, intent, file, SMS, settings, and privileged actions. | Destructive and privileged actions have capability checks, permission explanations, scoped-storage behavior, and clear unsupported failures. |
| P12 | User-facing text centralization | Low | Not started | Improve accessibility, localization readiness, and copy consistency. | Stable labels, errors, empty states, and permission explanations move to string resources where practical. |

## Execution Sequence

1. Finish P3 and the low-risk parts of P4 so the source tree and lint output are easier to trust.
2. Design P1's external automation trust model before changing compatibility-sensitive behavior.
3. Add P9 migration tests before further persistence changes.
4. Split P2 screen-by-screen, starting with run logs and setup because they are high-value debugging surfaces.
5. Add P6 emulator smoke tests around the screens and services touched by P2.
6. Modernize dependencies in P5 only after the test net is stronger.
7. Promote lint into CI under P7 once P4 warning noise is low enough.

## Notes

- External automation is the highest-risk product surface because it can trigger user-defined actions. Compatibility with legitimate automation integrations must be preserved deliberately, not assumed.
- The active UI is functional but too concentrated. Refactors should keep the current user workflows recognizable while reducing file size and state coupling.
- Lint dependency warnings should be handled in grouped upgrade branches rather than one large version jump.
- Initial lint cleanup reduced `lintDebug` from 31 warnings to 22 warnings. The remaining warnings are dependency/target-SDK modernization items and obsolete Navigation lint checks that should be handled under P5.
